package server.model

import zio.json.*

/** Persisted per-user state, stored as JSON on disk.
  *
  * File location: ~/.config/remarkable2notion/users/<googleId>.json
  */
case class UserProfile(
    googleId: String,
    email: String,
    remarkableDeviceToken: Option[String] = None,
    notionIntegrationKey: Option[String]  = None,
)

object UserProfile:
  given JsonCodec[UserProfile] = DeriveJsonCodec.gen
