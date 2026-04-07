package remarkable.api

import zio.json.*

// ---------------------------------------------------------------------------
// Auth request bodies (used by AuthService)
// ---------------------------------------------------------------------------

case class DeviceRegistrationRequest(
    code: String,
    deviceDesc: String,
    deviceID: String,
)
object DeviceRegistrationRequest:
  given JsonEncoder[DeviceRegistrationRequest] = DeriveJsonEncoder.gen

// ---------------------------------------------------------------------------
// Sync v3 — root endpoint response
// ---------------------------------------------------------------------------

/** Response from GET /sync/v3/root */
case class SyncRootResponse(
    generation: Long,
    hash: String,
)
object SyncRootResponse:
  given JsonDecoder[SyncRootResponse] = DeriveJsonDecoder.gen

// ---------------------------------------------------------------------------
// Sync v3 — index file entries
//
// Index files are plain text with the following line format (colon-delimited):
//
//   <sha256hex>:<type>:<entryName>:<subfiles>:<sizeBytes>
//
// Root index:    type = "80000000"  (document or collection)
// Doc sub-index: type = "0"         (individual file blob)
//
// Both root and document indexes begin with a schema version line ("3" or "4").
// Schema v4 also has a summary line after the version (total count + size).
// We handle both.
// ---------------------------------------------------------------------------

/** A single parsed line from a sync v3 index file. */
case class IndexEntry(
    hash: String,       // SHA-256 of the blob
    entryType: String,  // "80000000" = doc/folder, "0" = file blob
    name: String,       // document UUID (root index) or filename (doc index)
    subfiles: Int,      // number of sub-files (doc entries only)
    size: Long,         // size in bytes
):
  def isDocument: Boolean = entryType == "80000000"
  def isFile: Boolean     = entryType == "0"

// ---------------------------------------------------------------------------
// Resolved document info (built after walking the tree)
// ---------------------------------------------------------------------------

/** Entry type for folders vs documents. */
enum EntryKind:
  case Document
  case Collection

/** Metadata read from a <docID>.metadata blob.
  *
  * Many fields are optional in the actual API responses — older documents may
  * omit `deleted`, `parent`, etc.  We use Option with sensible defaults rather
  * than failing hard on missing fields.
  */
case class DocumentMetadata(
    visibleName: Option[String],
    `type`: Option[String],
    parent: Option[String],
    deleted: Option[Boolean],
)
object DocumentMetadata:
  given JsonDecoder[DocumentMetadata] = DeriveJsonDecoder.gen

  def entryKind(m: DocumentMetadata): EntryKind =
    if m.`type`.contains("CollectionType") then EntryKind.Collection
    else EntryKind.Document

/** A document with metadata resolved from the sync v3 tree. */
case class CloudDocument(
    id: String,               // UUID
    name: String,             // human-readable name from metadata
    parent: String,           // parent UUID (empty = root)
    kind: EntryKind,
    deleted: Boolean,         // true if the document has been trashed
    docHash: String,          // hash of the document index blob
    files: List[IndexEntry],  // entries from the document sub-index
)

/** A document entry enriched with its resolved folder path. */
case class ResolvedDocument(
    doc: CloudDocument,
    folderPath: String,   // e.g. "Research/Papers"
)
