package server.routes

import remarkable.auth.{AuthService, DeviceToken}
import remarkable.api.RemarkableClient
import server.model.*
import server.services.UserStore
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*
import zio.http.*
import zio.json.*

/** Routes for reMarkable device pairing and notebook listing. */
object RemarkableRoutes:

  val routes: Routes[Any, Nothing] = Routes(

    // POST /api/remarkable/pair
    // Body: { "code": "XXXXXXXX" }
    // Pairs the device using the one-time code and stores the device token.
    Method.POST / "api" / "remarkable" / "pair" ->
      handler { (req: Request) =>
        MeRoutes.withSession(req) { (googleId, email) =>
          (for
            body    <- req.body.asString
            parsed  <- ZIO.fromEither(body.fromJson[PairRequest])
                         .mapError(e => new RuntimeException(s"Bad request: $e"))
            _       <- ZIO.when(parsed.code.trim.isEmpty)(
                         ZIO.fail(new RuntimeException("Pairing code cannot be empty"))
                       )
            token   <- HttpClientZioBackend.scoped().flatMap { backend =>
                         AuthService.registerDevice(backend, parsed.code.trim)
                       }.provideSomeLayer(Scope.default)
            _       <- UserStore.update(googleId, email)(
                         _.copy(remarkableDeviceToken = Some(token.value))
                       )
          yield Response.json(OkResponse("reMarkable paired successfully").toJson))
            .catchAll(e =>
              ZIO.succeed(
                Response
                  .json(ErrorResponse(e.getMessage).toJson)
                  .status(Status.BadRequest)
              )
            )
        }
      },

    // GET /api/remarkable/notebooks
    // Returns the list of notebooks for the authenticated user.
    Method.GET / "api" / "remarkable" / "notebooks" ->
      handler { (req: Request) =>
        MeRoutes.withSession(req) { (googleId, _) =>
          (for
            profile      <- UserStore.getOrFail(googleId)
            deviceTokenStr <- ZIO
                               .fromOption(profile.remarkableDeviceToken)
                               .orElseFail(new RuntimeException(
                                 "reMarkable not paired. Please pair your device first."
                               ))
            notebooks    <- HttpClientZioBackend.scoped().flatMap { backend =>
                               for
                                 userToken <- AuthService.refreshUserToken(
                                                backend, DeviceToken(deviceTokenStr)
                                              )
                                 allDocs   <- RemarkableClient.listAll(backend, userToken)
                                 resolved   = RemarkableClient.resolveFolderPaths(allDocs)
                               yield resolved.map(r =>
                                 NotebookItem(r.doc.id, r.doc.name, r.folderPath)
                               )
                             }.provideSomeLayer(Scope.default)
          yield Response.json(notebooks.toJson))
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
