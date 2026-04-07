package remarkable.api

import remarkable.auth.{RmBackend, UserToken}
import sttp.client3.*
import sttp.client3.httpclient.zio.*
import sttp.client3.ziojson.*
import zio.*
import zio.json.*

/** HTTP client for the reMarkable Cloud Sync v3 API.
  *
  * Sync v3 uses a content-addressed blob store.  All content is accessed via
  * hash IDs.  The protocol is:
  *
  *   1. GET /sync/v3/root  → {generation, hash}
  *      The hash points to the root index blob.
  *
  *   2. GET /sync/v3/files/<rootHash>  → root index (text, colon-delimited)
  *      Each line is:  <sha256>:<type>:<docUUID>:<subfiles>:<size>
  *      type "80000000" = document/folder entry
  *
  *   3. For each document entry, GET /sync/v3/files/<docHash>  → doc sub-index
  *      Each line is: <sha256>:0:<filename>:0:<size>
  *      e.g. "abc.metadata", "abc.content", "abc/<pageUUID>.rm", "abc.pdf"
  *
  *   4. Individual blobs (metadata JSON, .rm pages, embedded PDFs) are fetched
  *      from GET /sync/v3/files/<blobHash> as raw bytes.
  *
  * All methods require a valid [[UserToken]] (short-lived; refresh before use).
  */
object RemarkableClient:

  private val SyncHost = "https://eu.tectonic.remarkable.com"

  // -------------------------------------------------------------------------
  // Root
  // -------------------------------------------------------------------------

  /** Fetches the current sync root: generation counter and root index hash. */
  def getRoot(backend: RmBackend, userToken: UserToken): ZIO[Any, Throwable, SyncRootResponse] =
    val request = basicRequest
      .get(uri"$SyncHost/sync/v3/root")
      .auth.bearer(userToken.value)
      .response(asJson[SyncRootResponse])

    for
      response <- backend.send(request)
      root     <- response.body match
                    case Right(r)  => ZIO.succeed(r)
                    case Left(err) => ZIO.fail(new RuntimeException(s"Failed to get sync root: $err"))
    yield root

  // -------------------------------------------------------------------------
  // Index parsing
  // -------------------------------------------------------------------------

  /** Fetches and parses a sync v3 index file (root or document sub-index). */
  def getIndex(backend: RmBackend, userToken: UserToken, hash: String): ZIO[Any, Throwable, List[IndexEntry]] =
    for
      bytes   <- getBlob(backend, userToken, hash)
      text     = new String(bytes, "UTF-8")
      entries <- ZIO.fromEither(parseIndex(text))
                   .mapError(msg => new RuntimeException(s"Failed to parse index for hash $hash: $msg"))
    yield entries

  /** Parses the colon-delimited sync v3 index text format.
    *
    * Format:
    *   Line 1:  schema version ("3" or "4")
    *   Line 2:  (v4 only) summary:  0:.<colon>.<colon><count>:<totalSize>
    *   Lines 3+: <sha256>:<type>:<name>:<subfiles>:<size>
    */
  private def parseIndex(text: String): Either[String, List[IndexEntry]] =
    val lines = text.split('\n').map(_.trim).filter(_.nonEmpty).toList

    lines match
      case Nil => Right(List.empty)
      case schema :: rest =>
        schema match
          case "3" =>
            parseEntryLines(rest)
          case "4" =>
            // v4 has an extra summary line after the version
            rest match
              case _ :: entryLines => parseEntryLines(entryLines)
              case Nil             => Right(List.empty)
          case other =>
            Left(s"Unknown index schema version: '$other'")

  private def parseEntryLines(lines: List[String]): Either[String, List[IndexEntry]] =
    val results = lines.map(parseLine)
    val errors  = results.collect { case Left(e) => e }
    if errors.nonEmpty then Left(errors.mkString("; "))
    else Right(results.collect { case Right(e) => e })

  private def parseLine(line: String): Either[String, IndexEntry] =
    val parts = line.split(':')
    if parts.length != 5 then
      Left(s"Expected 5 colon-separated fields, got ${parts.length} in: '$line'")
    else
      for
        subfiles <- parts(3).toIntOption.toRight(s"Invalid subfiles '${parts(3)}' in: '$line'")
        size     <- parts(4).toLongOption.toRight(s"Invalid size '${parts(4)}' in: '$line'")
      yield IndexEntry(
        hash      = parts(0),
        entryType = parts(1),
        name      = parts(2),
        subfiles  = subfiles,
        size      = size,
      )

  // -------------------------------------------------------------------------
  // Blob download
  // -------------------------------------------------------------------------

  /** Downloads a raw blob by hash. */
  def getBlob(backend: RmBackend, userToken: UserToken, hash: String): ZIO[Any, Throwable, Array[Byte]] =
    val request = basicRequest
      .get(uri"$SyncHost/sync/v3/files/$hash")
      .auth.bearer(userToken.value)
      .response(asByteArrayAlways)

    for
      response <- backend.send(request)
      bytes    <- response.code.code match
                    case 200 => ZIO.succeed(response.body)
                    case 404 => ZIO.fail(new RuntimeException(s"Blob not found: $hash"))
                    case code =>
                      ZIO.fail(new RuntimeException(s"Blob download failed (HTTP $code) for hash: $hash"))
    yield bytes

  // -------------------------------------------------------------------------
  // Document tree — list all documents
  // -------------------------------------------------------------------------

  /** Lists all documents and folders by walking the sync v3 blob tree.
    *
    * Steps:
    *   1. Fetch root → root index hash
    *   2. Parse root index → list of document index entries (one per doc/folder)
    *   3. For each entry, fetch the document sub-index and read its .metadata blob
    *   4. Return all CloudDocuments (including folders, so paths can be resolved)
    */
  def listAll(backend: RmBackend, userToken: UserToken): ZIO[Any, Throwable, List[CloudDocument]] =
    for
      root        <- getRoot(backend, userToken)
      _           <- Console.printLine(s"  Root: generation=${root.generation}, hash=${root.hash.take(16)}...")
      rootEntries <- getIndex(backend, userToken, root.hash)
      _           <- Console.printLine(s"  Root index: ${rootEntries.length} top-level entries")
      docs        <- ZIO.foreach(rootEntries.filter(_.isDocument)) { entry =>
                       loadDocument(backend, userToken, entry)
                         .tapError(e =>
                           Console.printLine(s"  [WARN] Skipping entry ${entry.name}: ${e.getMessage}")
                         )
                         .option
                     }
    yield docs.flatten

  /** Loads a single document from its root-index entry by fetching and parsing
    * its sub-index and metadata blob.
    */
  private def loadDocument(
      backend: RmBackend,
      userToken: UserToken,
      entry: IndexEntry,
  ): ZIO[Any, Throwable, CloudDocument] =
    for
      subEntries <- getIndex(backend, userToken, entry.hash)

      // Find the .metadata file entry inside the document sub-index
      metadataEntry <- ZIO.fromOption(
                         subEntries.find(e => e.name.endsWith(".metadata"))
                       ).orElseFail(new RuntimeException(s"No .metadata blob in sub-index for doc ${entry.name}"))

      metaBytes <- getBlob(backend, userToken, metadataEntry.hash)
      metaJson   = new String(metaBytes, "UTF-8")
      metadata  <- ZIO.fromEither(metaJson.fromJson[DocumentMetadata])
                     .mapError(e => new RuntimeException(s"Bad metadata JSON for doc ${entry.name}: $e"))

    yield CloudDocument(
      id       = entry.name,
      name     = metadata.visibleName.getOrElse(""),
      parent   = metadata.parent.getOrElse(""),
      kind     = DocumentMetadata.entryKind(metadata),
      deleted  = metadata.deleted.getOrElse(false),
      docHash  = entry.hash,
      files    = subEntries,
    )

  // -------------------------------------------------------------------------
  // Folder path resolution
  // -------------------------------------------------------------------------

  /** Resolves full folder paths for each document from the flat list of all
    * cloud documents (including folders).
    */
  def resolveFolderPaths(docs: List[CloudDocument]): List[ResolvedDocument] =
    val folderIndex: Map[String, String] =
      docs
        .collect { case d if d.kind == EntryKind.Collection => d.id -> d.name }
        .toMap

    def buildPath(parentId: String): String =
      if parentId.isEmpty || parentId == "trash" then ""
      else
        folderIndex.get(parentId) match
          case None       => ""
          case Some(name) =>
            val grandParent = docs.find(_.id == parentId).map(_.parent).getOrElse("")
            val prefix      = buildPath(grandParent)
            if prefix.isEmpty then name else s"$prefix/$name"

    docs
      .filter(_.kind == EntryKind.Document)
      .filterNot(_.deleted)
      .filterNot(_.name.isEmpty)
      .map { doc =>
        val path = buildPath(doc.parent)
        ResolvedDocument(doc, path)
      }
