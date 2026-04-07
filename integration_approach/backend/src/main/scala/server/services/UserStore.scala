package server.services

import server.model.UserProfile
import zio.*
import zio.json.*

import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets

/** Persists UserProfile as JSON files under ~/.config/remarkable2notion/users/.
  *
  * Thread-safety: each write replaces the file atomically (write-to-temp then
  * rename), so concurrent writes for different users are safe.  Same-user
  * concurrent writes are serialised via ZIO STM if needed in future; for now
  * we accept last-write-wins since concurrent same-user requests are unlikely.
  */
object UserStore:

  private val usersDir: Path =
    Paths
      .get(sys.env.getOrElse("HOME", "/tmp"))
      .resolve(".config/remarkable2notion/users")

  private def profilePath(googleId: String): Path =
    usersDir.resolve(s"$googleId.json")

  /** Reads the profile for a given Google user ID. Returns None if not found. */
  def get(googleId: String): Task[Option[UserProfile]] =
    ZIO.attemptBlocking {
      val path = profilePath(googleId)
      if Files.exists(path) then
        val json = Files.readString(path, StandardCharsets.UTF_8)
        json.fromJson[UserProfile] match
          case Right(p)  => Some(p)
          case Left(err) => throw new RuntimeException(s"Corrupt profile for $googleId: $err")
      else None
    }

  /** Writes (creates or replaces) the profile for a user. */
  def put(profile: UserProfile): Task[Unit] =
    ZIO.attemptBlocking {
      Files.createDirectories(usersDir)
      val path = profilePath(profile.googleId)
      val tmp  = path.resolveSibling(s"${profile.googleId}.tmp")
      Files.writeString(tmp, profile.toJson, StandardCharsets.UTF_8)
      Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

  /** Returns the profile or fails with a clear message. */
  def getOrFail(googleId: String): Task[UserProfile] =
    get(googleId).flatMap {
      case Some(p) => ZIO.succeed(p)
      case None    => ZIO.fail(new RuntimeException(s"User $googleId not found"))
    }

  /** Upserts a profile by applying a transformation to the current value
    * (or a default if not yet stored).
    */
  def update(googleId: String, email: String)(f: UserProfile => UserProfile): Task[UserProfile] =
    for
      existing <- get(googleId)
      base      = existing.getOrElse(UserProfile(googleId, email))
      updated   = f(base)
      _        <- put(updated)
    yield updated
