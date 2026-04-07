package remarkable.auth

import remarkable.api.DeviceRegistrationRequest
import sttp.client3.*
import sttp.client3.httpclient.zio.*
import sttp.client3.ziojson.*
import zio.*
import zio.json.*

/** Tokens used in API calls. */
case class DeviceToken(value: String) extends AnyVal
case class UserToken(value: String)   extends AnyVal

/** Result of the pairing flow — printed for the user to save. */
case class PairingResult(deviceToken: DeviceToken)

/** Type alias for the sttp backend used throughout the app. */
type RmBackend = SttpBackend[Task, Any]

object AuthService:

  // The old auth host (my.remarkable.com) now serves the web frontend (Vercel) and
  // returns 405 for POST requests. The backend API moved to webapp.cloud.remarkable.com.
  private val AuthHost = "https://webapp.cloud.remarkable.com"

  // -------------------------------------------------------------------------
  // Pairing flow
  // -------------------------------------------------------------------------

  /** Full interactive pairing flow.
    *
    *  1. Prints the URL the user must open to generate a one-time code.
    *  2. Reads the code from stdin.
    *  3. Registers this client as a new device with the reMarkable API.
    *  4. Returns the device token for the caller to display.
    */
  def pair(backend: RmBackend): ZIO[Any, Throwable, PairingResult] =
    for
      _    <- Console.printLine(
                """
                  |=== reMarkable Device Pairing ===
                  |
                  |1. Open this URL in your browser:
                  |   https://my.remarkable.com/device/desktop/connect
                  |
                  |2. Sign in and copy the one-time pairing code shown on screen.
                  |
                  |""".stripMargin
              )
      code  <- Console.readLine("Enter pairing code: ").map(_.trim)
      _     <- ZIO.when(code.isEmpty)(ZIO.fail(new RuntimeException("Pairing code cannot be empty")))
      token <- registerDevice(backend, code)
      _     <- Console.printLine(
                 s"""
                    |Pairing successful!
                    |
                    |Your device token (save this — it will not be shown again):
                    |
                    |  ${token.value}
                    |
                    |Export it before running sync:
                    |  export REMARKABLE_DEVICE_TOKEN="${token.value}"
                    |""".stripMargin
               )
    yield PairingResult(token)

  // -------------------------------------------------------------------------
  // Device registration
  // -------------------------------------------------------------------------

  /** Registers a new device with the reMarkable API using a one-time code.
    * Returns the long-lived device token (JWT).
    */
  def registerDevice(backend: RmBackend, code: String): ZIO[Any, Throwable, DeviceToken] =
    val deviceId   = java.util.UUID.randomUUID().toString
    val deviceDesc = "desktop-macos"
    val body       = DeviceRegistrationRequest(code, deviceDesc, deviceId)

    val request = basicRequest
      .post(uri"$AuthHost/token/json/2/device/new")
      .contentType("application/json")
      .body(body.toJson)
      .response(asStringAlways)

    for
      response <- backend.send(request)
      token    <- response.code.code match
                    case 200 =>
                      val raw = response.body.trim
                      if raw.nonEmpty then ZIO.succeed(DeviceToken(raw))
                      else ZIO.fail(new RuntimeException("Empty token response from registration endpoint"))
                    case 400 =>
                      ZIO.fail(new RuntimeException(s"Invalid pairing code (HTTP 400): ${response.body}"))
                    case c =>
                      ZIO.fail(new RuntimeException(s"Device registration failed (HTTP $c): ${response.body}"))
    yield token

  // -------------------------------------------------------------------------
  // User token refresh
  // -------------------------------------------------------------------------

  /** Exchanges the long-lived device token for a short-lived user token.
    * Must be called before each API session; the user token is in-memory only.
    */
  def refreshUserToken(backend: RmBackend, deviceToken: DeviceToken): ZIO[Any, Throwable, UserToken] =
    val request = basicRequest
      .post(uri"$AuthHost/token/json/2/user/new")
      .auth.bearer(deviceToken.value)
      .response(asStringAlways)

    for
      response <- backend.send(request)
      token    <- response.code.code match
                    case 200 =>
                      val raw = response.body.trim
                      if raw.nonEmpty then ZIO.succeed(UserToken(raw))
                      else ZIO.fail(new RuntimeException("Empty user token response"))
                    case 401 =>
                      ZIO.fail(new RuntimeException(
                        "Device token rejected (HTTP 401). Re-run the pair command to get a new token."
                      ))
                    case c =>
                      ZIO.fail(new RuntimeException(s"User token refresh failed (HTTP $c): ${response.body}"))
    yield token
