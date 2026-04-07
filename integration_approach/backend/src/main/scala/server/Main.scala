package server

import server.routes.*
import zio.*
import zio.http.*

/** HTTP server entry point.
  *
  * Required (production) environment variables:
  *   GOOGLE_CLIENT_ID   — OAuth 2.0 client ID from Google Cloud Console
  *   SESSION_SECRET     — Random secret for signing session cookies (min 32 chars)
  *
  * Development shortcut:
  *   DEV_MODE=true      — Skips all authentication. Uses a fixed local user.
  *                        Never use this in production.
  *
  * Optional:
  *   PORT               — HTTP port to listen on (default: 8080)
  *   OUTPUT_DIR         — Directory for downloaded notebooks (default: ./output)
  */
object Main extends ZIOAppDefault:

  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    for
      _    <- validateConfig
      port <- ZIO.succeed(sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(8080))
      _    <- ZIO.logInfo(s"Starting remarkable2notion server on port $port")
      _    <- Server
                .serve(allRoutes)
                .provide(Server.defaultWithPort(port))
    yield ()

  private val allRoutes: Routes[Any, Nothing] =
    AuthRoutes.routes       ++
    MeRoutes.routes         ++
    RemarkableRoutes.routes ++
    NotionRoutes.routes     ++
    SyncRoutes.routes       ++
    StaticRoutes.routes

  private val validateConfig: Task[Unit] =
    if sys.env.get("DEV_MODE").contains("true") then
      ZIO.logWarning("*** DEV_MODE=true — authentication is disabled. Do not use in production. ***")
    else
      for
        clientId <- ZIO.fromOption(sys.env.get("GOOGLE_CLIENT_ID"))
                      .orElseFail(new RuntimeException(
                        "GOOGLE_CLIENT_ID is required (or set DEV_MODE=true to skip auth)"
                      ))
        secret   <- ZIO.fromOption(sys.env.get("SESSION_SECRET"))
                      .orElseFail(new RuntimeException(
                        "SESSION_SECRET is required (or set DEV_MODE=true to skip auth)"
                      ))
        _        <- ZIO.when(secret.length < 32)(
                      ZIO.fail(new RuntimeException(
                        "SESSION_SECRET must be at least 32 characters long."
                      ))
                    )
        _        <- ZIO.logInfo(s"Google client ID: ${clientId.take(8)}...")
      yield ()
