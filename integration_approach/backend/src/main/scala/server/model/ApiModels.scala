package server.model

import zio.json.*

// ---------------------------------------------------------------------------
// Request / response bodies shared by all routes
// ---------------------------------------------------------------------------

case class PairRequest(code: String)
object PairRequest:
  given JsonCodec[PairRequest] = DeriveJsonCodec.gen

case class NotionKeyRequest(integrationKey: String)
object NotionKeyRequest:
  given JsonCodec[NotionKeyRequest] = DeriveJsonCodec.gen

case class SyncRequest(notebookIds: List[String])
object SyncRequest:
  given JsonCodec[SyncRequest] = DeriveJsonCodec.gen

case class NotebookItem(id: String, name: String, folderPath: String)
object NotebookItem:
  given JsonCodec[NotebookItem] = DeriveJsonCodec.gen

case class MeResponse(
    email: String,
    remarkablePaired: Boolean,
    notionConfigured: Boolean,
)
object MeResponse:
  given JsonCodec[MeResponse] = DeriveJsonCodec.gen

case class ErrorResponse(error: String)
object ErrorResponse:
  given JsonCodec[ErrorResponse] = DeriveJsonCodec.gen

case class OkResponse(message: String)
object OkResponse:
  given JsonCodec[OkResponse] = DeriveJsonCodec.gen

case class GoogleCallbackRequest(credential: String)
object GoogleCallbackRequest:
  given JsonCodec[GoogleCallbackRequest] = DeriveJsonCodec.gen
