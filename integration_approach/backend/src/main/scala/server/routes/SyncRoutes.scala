package server.routes

import remarkable.auth.{AuthService, DeviceToken}
import remarkable.api.RemarkableClient
import remarkable.notebook.NotebookDownloader
import server.model.*
import server.services.UserStore
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.http.*
import zio.json.*

import java.nio.file.{Files, Paths}

/** POST /api/sync
  *
  * Body: { "notebookIds": ["uuid1", "uuid2", ...] }
  *
  * Downloads and renders the selected notebooks.  For now this triggers the
  * existing NotebookDownloader pipeline and returns when complete.
  * (Notion upload integration can be layered on top once the Rust OCR pipeline
  * is ported — for now it stores PDFs to OUTPUT_DIR.)
  */
object SyncRoutes:

  val routes: Routes[Any, Nothing] = Routes(
    Method.POST / "api" / "sync" ->
      handler { (req: Request) =>
        MeRoutes.withSession(req) { (googleId, _) =>
          (for
            body    <- req.body.asString
            parsed  <- ZIO.fromEither(body.fromJson[SyncRequest])
                         .mapError(e => new RuntimeException(s"Bad request: $e"))
            profile <- UserStore.getOrFail(googleId)
            deviceTokenStr <- ZIO
                               .fromOption(profile.remarkableDeviceToken)
                               .orElseFail(new RuntimeException(
                                 "reMarkable not paired. Please pair your device first."
                               ))
            _ <- ZIO
                   .fromOption(profile.notionIntegrationKey)
                   .orElseFail(new RuntimeException(
                     "Notion integration key not configured."
                   ))
            outputDir = Paths.get(
                          sys.env.getOrElse("OUTPUT_DIR", "output"),
                          googleId,
                        )
            _ <- ZIO.attemptBlocking(Files.createDirectories(outputDir))
            results <- HttpClientZioBackend.scoped().flatMap { backend =>
                         for
                           userToken <- AuthService.refreshUserToken(
                                          backend, DeviceToken(deviceTokenStr)
                                        )
                           allDocs   <- RemarkableClient.listAll(backend, userToken)
                           // Filter to only the requested notebook IDs; empty list means "sync all"
                           selected   = if parsed.notebookIds.isEmpty then allDocs
                                        else allDocs.filter(d =>
                                          parsed.notebookIds.contains(d.id)
                                        )
                           results   <- NotebookDownloader.downloadSelected(
                                          backend, userToken, allDocs, selected, outputDir
                                        )
                         yield results
                       }.provideSomeLayer(Scope.default)
          yield Response.json(
            OkResponse(s"Sync complete. ${results.length} notebooks downloaded.").toJson
          ))
            .catchAll(e =>
              ZIO.succeed(
                Response
                  .json(ErrorResponse(e.getMessage).toJson)
                  .status(Status.InternalServerError)
              )
            )
        }
      },
  )
