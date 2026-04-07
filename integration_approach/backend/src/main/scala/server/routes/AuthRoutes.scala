package server.routes

import server.model.*
import server.services.{GoogleAuthService, SessionService, UserStore}
import zio.*
import zio.http.*
import zio.json.*

/** POST /api/auth/google
  *
  * Body: { "credential": "<Google ID token>" }
  *
  * Verifies the token, upserts the user profile, and sets a signed session
  * cookie.  The frontend (Google Identity Services) posts the credential here
  * after the user clicks the Sign-In button.
  */
object AuthRoutes:

  val routes: Routes[Any, Nothing] = Routes(
    Method.POST / "api" / "auth" / "google" ->
      handler { (req: Request) =>
        (for
          clientId <- ZIO
                        .fromOption(sys.env.get("GOOGLE_CLIENT_ID"))
                        .orElseFail(new RuntimeException("GOOGLE_CLIENT_ID not set"))
          secret   <- ZIO
                        .fromOption(sys.env.get("SESSION_SECRET"))
                        .orElseFail(new RuntimeException("SESSION_SECRET not set"))
          body     <- req.body.asString
          parsed   <- ZIO.fromEither(body.fromJson[GoogleCallbackRequest])
                        .mapError(e => new RuntimeException(s"Bad request body: $e"))
          (googleId, email) <- GoogleAuthService.verify(parsed.credential, clientId)
          _        <- UserStore.update(googleId, email)(identity)
          cookie    = SessionService.encode(googleId, email, secret)
          response  = Response.json(OkResponse("authenticated").toJson)
                        .addCookie(
                          Cookie.Response(
                            name        = "session",
                            content     = cookie,
                            path        = Some(Path.root),
                            isHttpOnly  = true,
                            sameSite    = Some(Cookie.SameSite.Strict),
                            // maxAge 30 days
                            maxAge      = Some(30.days),
                          )
                        )
        yield response)
          .catchAll(e =>
            ZIO.succeed(
              Response
                .json(ErrorResponse(e.getMessage).toJson)
                .status(Status.Unauthorized)
            )
          )
      },

    Method.POST / "api" / "auth" / "logout" ->
      handler { (_: Request) =>
        val cleared = Response
          .json(OkResponse("logged out").toJson)
          .addCookie(
            Cookie.Response(
              name    = "session",
              content = "",
              path    = Some(Path.root),
              maxAge  = Some(Duration.Zero),
            )
          )
        ZIO.succeed(cleared)
      },
  )
