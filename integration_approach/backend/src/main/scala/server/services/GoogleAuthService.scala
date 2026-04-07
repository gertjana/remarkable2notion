package server.services

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import zio.*

import java.net.URL
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/** Verifies a Google ID token (JWT) obtained from the Google Identity Services
  * "Sign In With Google" button (credential field in the POST body).
  *
  * Verification steps:
  *   1. Fetch Google's JWKS from https://www.googleapis.com/oauth2/v3/certs
  *   2. Verify the RS256 signature
  *   3. Check aud == GOOGLE_CLIENT_ID
  *   4. Check iss is accounts.google.com or https://accounts.google.com
  *   5. Check exp has not passed
  *
  * Returns (googleId, email) on success.
  */
object GoogleAuthService:

  private val JwksUrl = "https://www.googleapis.com/oauth2/v3/certs"

  // JWK provider with a 10-minute cache so we don't hammer Google's JWKS endpoint.
  private lazy val jwkProvider =
    new JwkProviderBuilder(new URL(JwksUrl))
      .cached(10, 10, TimeUnit.MINUTES)
      .build()

  /** Verifies the credential token and returns (googleId, email). */
  def verify(idToken: String, clientId: String): Task[(String, String)] =
    ZIO.attemptBlocking {
      val decoded = JWT.decode(idToken)
      val kid     = decoded.getKeyId
      val jwk     = jwkProvider.get(kid)
      val pubKey  = jwk.getPublicKey.asInstanceOf[RSAPublicKey]
      val algo    = Algorithm.RSA256(pubKey, null)

      val verifier = JWT
        .require(algo)
        .withAudience(clientId)
        .withIssuer("accounts.google.com", "https://accounts.google.com")
        .build()

      val verified = verifier.verify(idToken)
      val sub      = verified.getSubject
      val email    = verified.getClaim("email").asString()

      (sub, email)
    }
