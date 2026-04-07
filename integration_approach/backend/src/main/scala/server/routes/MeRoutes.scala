package server.routes

import server.model.*
import server.services.{SessionService, UserStore}
import zio.*
import zio.http.*
import zio.json.*

/** GET /api/me
  *
  * Returns the current user's setup status (is reMarkable paired, is Notion
  * configured).  Requires a valid session cookie.
  */
object MeRoutes:

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "api" / "me" ->
      handler { (req: Request) =>
        withSession(req) { (googleId, _) =>
          for
            profile <- UserStore.getOrFail(googleId)
            resp     = MeResponse(
                         email            = profile.email,
                         remarkablePaired = profile.remarkableDeviceToken.isDefined,
                         notionConfigured = profile.notionIntegrationKey.isDefined,
                       )
          yield Response.json(resp.toJson)
        }
      },
  )

  // -------------------------------------------------------------------------
  // Session helper — shared by all routes that require authentication
  // -------------------------------------------------------------------------

  // When DEV_MODE=true, skip all auth and use a fixed local user.
  private val DevGoogleId = "dev-user"
  private val DevEmail    = "dev@localhost"
  private val devMode     = sys.env.get("DEV_MODE").contains("true")

  /** Extracts and verifies the session cookie, then runs `f` with the
    * (googleId, email).  Returns 401 if the session is missing or invalid.
    *
    * In DEV_MODE the cookie check is bypassed entirely.
    */
  def withSession(req: Request)(
      f: (String, String) => Task[Response]
  ): UIO[Response] =
    if devMode then
      (for
        // Ensure the dev user profile exists on disk
        _ <- UserStore.update(DevGoogleId, DevEmail)(identity)
        r <- f(DevGoogleId, DevEmail)
      yield r).catchAll(e =>
        ZIO.succeed(
          Response
            .json(ErrorResponse(e.getMessage).toJson)
            .status(Status.InternalServerError)
        )
      )
    else
      val result =
        for
          secret   <- ZIO
                        .fromOption(sys.env.get("SESSION_SECRET"))
                        .orElseFail(new RuntimeException("SESSION_SECRET not set"))
          cookie   <- ZIO
                        .fromOption(req.cookie("session"))
                        .orElseFail(new RuntimeException("No session cookie"))
          (googleId, email) <-
            ZIO
              .fromOption(SessionService.decode(cookie.content, secret))
              .orElseFail(new RuntimeException("Invalid session"))
          resp     <- f(googleId, email)
        yield resp

      result.catchAll(e =>
        ZIO.succeed(
          Response
            .json(ErrorResponse(e.getMessage).toJson)
            .status(Status.Unauthorized)
        )
      )
