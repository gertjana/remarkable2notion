package server.services

import zio.*

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.nio.charset.StandardCharsets

/** Minimal signed session cookie.
  *
  * Format (URL-safe base64):
  *   base64(googleId + "|" + email) + "." + base64(HMAC-SHA256(payload, secret))
  *
  * The secret is read from the SESSION_SECRET environment variable (required).
  * If SESSION_SECRET is not set the server will fail to start.
  */
object SessionService:

  private val Algo = "HmacSHA256"

  private def hmac(payload: String, secret: String): String =
    val key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), Algo)
    val mac = Mac.getInstance(Algo)
    mac.init(key)
    val sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))
    Base64.getUrlEncoder.withoutPadding.encodeToString(sig)

  /** Produces a signed cookie value from the user's google ID and email. */
  def encode(googleId: String, email: String, secret: String): String =
    val payload = Base64.getUrlEncoder.withoutPadding
      .encodeToString(s"$googleId|$email".getBytes(StandardCharsets.UTF_8))
    val sig = hmac(payload, secret)
    s"$payload.$sig"

  /** Decodes and verifies a cookie value.  Returns (googleId, email) or None. */
  def decode(cookie: String, secret: String): Option[(String, String)] =
    cookie.split('.') match
      case Array(payload, sig) =>
        val expected = hmac(payload, secret)
        // Constant-time comparison to prevent timing attacks
        if constantTimeEquals(sig, expected) then
          val decoded = new String(
            Base64.getUrlDecoder.decode(payload),
            StandardCharsets.UTF_8,
          )
          decoded.split('|') match
            case Array(googleId, email) => Some((googleId, email))
            case _                      => None
        else None
      case _ => None

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then false
    else a.zip(b).foldLeft(0)((acc, pair) => acc | (pair._1 ^ pair._2)) == 0
