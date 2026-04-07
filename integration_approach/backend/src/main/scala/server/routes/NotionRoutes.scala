package server.routes

import server.model.*
import server.services.UserStore
import zio.*
import zio.http.*
import zio.json.*

/** POST /api/notion/key
  *
  * Body: { "integrationKey": "secret_..." }
  *
  * Stores the Notion integration key for the authenticated user.
  */
object NotionRoutes:

  val routes: Routes[Any, Nothing] = Routes(
    Method.POST / "api" / "notion" / "key" ->
      handler { (req: Request) =>
        MeRoutes.withSession(req) { (googleId, email) =>
          (for
            body   <- req.body.asString
            parsed <- ZIO.fromEither(body.fromJson[NotionKeyRequest])
                        .mapError(e => new RuntimeException(s"Bad request: $e"))
            _      <- ZIO.when(parsed.integrationKey.trim.isEmpty)(
                        ZIO.fail(new RuntimeException("Integration key cannot be empty"))
                      )
            _      <- UserStore.update(googleId, email)(
                        _.copy(notionIntegrationKey = Some(parsed.integrationKey.trim))
                      )
          yield Response.json(OkResponse("Notion integration key saved").toJson))
            .catchAll(e =>
              ZIO.succeed(
                Response
                  .json(ErrorResponse(e.getMessage).toJson)
                  .status(Status.BadRequest)
              )
            )
        }
      },
  )
