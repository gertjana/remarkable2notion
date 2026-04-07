package remarkable.notebook

import remarkable.api.{CloudDocument, EntryKind, IndexEntry, RemarkableClient, ResolvedDocument}
import remarkable.auth.{RmBackend, UserToken}
import remarkable.render.{PdfRenderer, RmParser}
import zio.*

import java.io.{ByteArrayInputStream, FileOutputStream}
import java.nio.file.{Files, Path, Paths}
import java.util.zip.ZipInputStream

/** Downloads all notebooks from the reMarkable cloud and renders them as PDFs.
  *
  * In sync v3, each document is a tree of individual content-addressed blobs
  * rather than a single ZIP file.  We walk the document sub-index to find the
  * relevant blobs and either:
  *   - extract an embedded PDF (if the doc has a <docID>.pdf blob), or
  *   - collect and render the per-page .rm stroke blobs via PDFBox.
  *
  * Output layout mirrors the reMarkable folder hierarchy:
  *   output/<folder-path>/<notebook-name>.pdf
  */
object NotebookDownloader:

  /** Summary of a single downloaded notebook. */
  case class DownloadResult(
      name: String,
      folderPath: String,
      outputPath: Path,
  )

  /** Lists all notebooks and renders them into `outputDir`. */
  def downloadAll(
      backend: RmBackend,
      userToken: UserToken,
      outputDir: Path,
  ): ZIO[Any, Throwable, List[DownloadResult]] =
    for
      _       <- Console.printLine("Listing documents from reMarkable cloud...")
      allDocs <- RemarkableClient.listAll(backend, userToken)
      _       <- Console.printLine(s"Found ${allDocs.length} entries (documents + folders)")

      resolved = RemarkableClient.resolveFolderPaths(allDocs)
      _       <- Console.printLine(s"Resolved ${resolved.length} documents with folder paths")

      results <- ZIO.foreach(resolved) { doc =>
                   downloadOne(backend, userToken, doc, outputDir)
                     .tapError(e =>
                       Console.printLine(s"[WARN] Failed to download '${doc.doc.name}': ${e.getMessage}")
                     )
                     .option
                 }
    yield results.flatten

  // -------------------------------------------------------------------------
  // Single notebook download + render
  // -------------------------------------------------------------------------

  private def downloadOne(
      backend: RmBackend,
      userToken: UserToken,
      resolved: ResolvedDocument,
      outputDir: Path,
  ): ZIO[Any, Throwable, DownloadResult] =
    val doc    = resolved.doc
    val name   = sanitizeFilename(doc.name)
    val subDir = if resolved.folderPath.isEmpty then outputDir
                 else outputDir.resolve(resolved.folderPath.replace("/", java.io.File.separator))

    for
      _       <- ZIO.attempt(Files.createDirectories(subDir))
      _       <- Console.printLine(s"  Downloading: ${resolved.folderPath}/${doc.name}")
      outPath <- renderDocument(backend, userToken, doc, name, subDir)
    yield DownloadResult(doc.name, resolved.folderPath, outPath)

  // -------------------------------------------------------------------------
  // Document rendering — embedded PDF vs stroke render
  // -------------------------------------------------------------------------

  private def renderDocument(
      backend: RmBackend,
      userToken: UserToken,
      doc: CloudDocument,
      name: String,
      outDir: Path,
  ): ZIO[Any, Throwable, Path] =
    // Check whether the sub-index contains an embedded PDF blob
    val embeddedPdfEntry: Option[IndexEntry] =
      doc.files.find { e =>
        e.name.endsWith(".pdf") && !e.name.contains("thumbnails")
      }

    embeddedPdfEntry match
      case Some(pdfEntry) =>
        for
          pdfBytes <- RemarkableClient.getBlob(backend, userToken, pdfEntry.hash)
          outPath   = outDir.resolve(s"$name.pdf")
          _        <- ZIO.attempt(Files.write(outPath, pdfBytes))
        yield outPath

      case None =>
        renderFromStrokes(backend, userToken, doc, name, outDir)

  // -------------------------------------------------------------------------
  // Stroke rendering
  // -------------------------------------------------------------------------

  private def renderFromStrokes(
      backend: RmBackend,
      userToken: UserToken,
      doc: CloudDocument,
      name: String,
      outDir: Path,
  ): ZIO[Any, Throwable, Path] =
    // Find all .rm page blob entries, grouped by page UUID
    // Entry names are: "<docID>/<pageUUID>.rm"
    val rmEntries: List[IndexEntry] =
      doc.files
        .filter(e => e.name.endsWith(".rm"))
        .sortBy(_.name)   // sort by filename for stable page order

    // Determine page order from .content blob (if present)
    for
      pageOrder  <- readPageOrder(backend, userToken, doc)
      // Download each .rm blob in page order (or sorted order if no .content)
      orderedEntries = orderPages(rmEntries, pageOrder, doc.id)
      pages     <- ZIO.foreach(orderedEntries) { entry =>
                     RemarkableClient.getBlob(backend, userToken, entry.hash)
                   }
      outPath    = outDir.resolve(s"$name.pdf")
      _         <- ZIO.attempt(PdfRenderer.render(pages, outPath))
    yield outPath

  /** Reads the page order from the document's .content JSON blob, if present. */
  private def readPageOrder(
      backend: RmBackend,
      userToken: UserToken,
      doc: CloudDocument,
  ): ZIO[Any, Throwable, List[String]] =
    doc.files.find(e => e.name.endsWith(".content")) match
      case None        => ZIO.succeed(List.empty)
      case Some(entry) =>
        RemarkableClient.getBlob(backend, userToken, entry.hash)
          .map(bytes => parsePageOrder(new String(bytes, "UTF-8")))
          .catchAll(_ => ZIO.succeed(List.empty))

  /** Sorts .rm entries according to the page order from .content, or falls back
    * to alphabetic order of page UUIDs.
    */
  private def orderPages(
      rmEntries: List[IndexEntry],
      pageOrder: List[String],
      docId: String,
  ): List[IndexEntry] =
    if pageOrder.isEmpty then
      rmEntries
    else
      // Build a map from pageUUID -> entry
      val byPageId: Map[String, IndexEntry] = rmEntries.map { e =>
        // Entry name is either "<docID>/<pageUUID>.rm" or "<pageUUID>.rm"
        val pageId = e.name
          .stripPrefix(s"$docId/")
          .stripSuffix(".rm")
        pageId -> e
      }.toMap
      pageOrder.flatMap(byPageId.get)

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /** Parses the "pages" array from a reMarkable .content JSON file.
    *
    * Handles two firmware variants:
    *   - Older: `"pages": ["uuid1", "uuid2", ...]`            — flat UUID array
    *   - Newer: `"cPages": { "pages": [{"id": "uuid1"}, ...] }` — object array
    *
    * We try the newer cPages format first, then fall back to the flat format.
    */
  private def parsePageOrder(json: String): List[String] =
    val UuidRe = """"([0-9a-f\-]{36})"""".r

    // Newer firmware: cPages.pages array — extract all UUIDs from the "id" fields
    val CPagesRe = """"cPages"\s*:\s*\{[^}]*"pages"\s*:\s*\[([^\]]*)\]""".r
    CPagesRe.findFirstMatchIn(json) match
      case Some(m) =>
        val inner = m.group(1)
        UuidRe.findAllMatchIn(inner).map(_.group(1)).toList
      case None =>
        // Older firmware: flat "pages" array
        val FlatPagesRe = """"pages"\s*:\s*\[([^\]]*)\]""".r
        FlatPagesRe.findFirstMatchIn(json) match
          case None    => List.empty
          case Some(m) => UuidRe.findAllMatchIn(m.group(1)).map(_.group(1)).toList

  private def sanitizeFilename(name: String): String =
    name.replaceAll("""[/\\:*?"<>|]""", "_").trim
