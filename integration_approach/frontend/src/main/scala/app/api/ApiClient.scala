package app.api

import org.scalajs.dom
import org.scalajs.dom.{Fetch, HttpMethod, RequestInit, RequestMode}
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSON

/** Minimal fetch-based API client.  All methods return Future so Laminar
  * EventStream / Signal can consume them via EventStream.fromFuture.
  */
object ApiClient:
  given ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  private def fetchJson(method: String, path: String, body: Option[String] = None): Future[String] =
    val init = js.Dynamic.literal(
      method      = method,
      credentials = "same-origin",
      headers     = js.Dynamic.literal("Content-Type" -> "application/json"),
    )
    body.foreach(b => init.updateDynamic("body")(b))
    Fetch
      .fetch(path, init.asInstanceOf[RequestInit])
      .toFuture
      .flatMap(_.text().toFuture)

  // ---------------------------------------------------------------------------
  // Auth
  // ---------------------------------------------------------------------------

  /** Sends the Google ID token credential to the backend for verification. */
  def googleLogin(credential: String): Future[String] =
    fetchJson("POST", "/api/auth/google", Some(s"""{"credential":"$credential"}"""))

  def logout(): Future[String] =
    fetchJson("POST", "/api/auth/logout")

  // ---------------------------------------------------------------------------
  // Me
  // ---------------------------------------------------------------------------

  case class MeResponse(email: String, remarkablePaired: Boolean, notionConfigured: Boolean)

  def me(): Future[Either[String, MeResponse]] =
    fetchJson("GET", "/api/me").map { body =>
      val d = JSON.parse(body).asInstanceOf[js.Dynamic]
      if js.isUndefined(d.error) then
        Right(MeResponse(
          d.email.toString,
          d.remarkablePaired.asInstanceOf[Boolean],
          d.notionConfigured.asInstanceOf[Boolean],
        ))
      else Left(d.error.toString)
    }

  // ---------------------------------------------------------------------------
  // reMarkable pairing
  // ---------------------------------------------------------------------------

  def pairRemarkable(code: String): Future[Either[String, String]] =
    fetchJson("POST", "/api/remarkable/pair", Some(s"""{"code":"$code"}""")).map(parseMessageOrError)

  // ---------------------------------------------------------------------------
  // Notebooks
  // ---------------------------------------------------------------------------

  case class Notebook(id: String, name: String, folderPath: String)

  def listNotebooks(): Future[Either[String, List[Notebook]]] =
    fetchJson("GET", "/api/remarkable/notebooks").map { body =>
      val d = JSON.parse(body).asInstanceOf[js.Dynamic]
      if !js.isUndefined(d.error) then Left(d.error.toString)
      else
        val arr = d.asInstanceOf[js.Array[js.Dynamic]]
        Right(arr.toList.map(n => Notebook(n.id.toString, n.name.toString, n.folderPath.toString)))
    }

  // ---------------------------------------------------------------------------
  // Notion key
  // ---------------------------------------------------------------------------

  def saveNotionKey(key: String): Future[Either[String, String]] =
    fetchJson("POST", "/api/notion/key", Some(s"""{"integrationKey":"$key"}""")).map(parseMessageOrError)

  // ---------------------------------------------------------------------------
  // Sync
  // ---------------------------------------------------------------------------

  def sync(ids: List[String]): Future[Either[String, String]] =
    val idsJson = ids.map(id => s""""$id"""").mkString("[", ",", "]")
    fetchJson("POST", "/api/sync", Some(s"""{"notebookIds":$idsJson}""")).map(parseMessageOrError)

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def parseMessageOrError(body: String): Either[String, String] =
    val d = JSON.parse(body).asInstanceOf[js.Dynamic]
    if !js.isUndefined(d.error) then Left(d.error.toString)
    else Right(d.message.toString)
