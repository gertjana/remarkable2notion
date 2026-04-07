package remarkable.auth

import zio.*

/** Reads the reMarkable device token from the environment.
  *
  * The device token is a long-lived JWT obtained once during the `pair` command.
  * For hosted / CI usage it is supplied via the REMARKABLE_DEVICE_TOKEN environment
  * variable so that nothing is written to disk.
  *
  * Usage:
  *   - Run `sbt run pair` once to register the device and print the token.
  *   - Export the printed token: export REMARKABLE_DEVICE_TOKEN=<token>
  *   - Subsequent `sbt run sync` calls will pick it up automatically.
  */
object TokenStore:

  val EnvVar = "REMARKABLE_DEVICE_TOKEN"

  /** Returns the device token or fails with a descriptive error. */
  val deviceToken: ZIO[Any, Throwable, String] =
    System.env(EnvVar).flatMap {
      case Some(token) if token.nonEmpty => ZIO.succeed(token)
      case _ =>
        ZIO.fail(new RuntimeException(
          s"""No reMarkable device token found.
             |
             |Run the pairing command first:
             |  sbt "run pair"
             |
             |Then export the printed token:
             |  export $EnvVar=<token>
             |""".stripMargin
        ))
    }
