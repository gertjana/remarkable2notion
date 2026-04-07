package remarkable

import remarkable.auth.{AuthService, DeviceToken, TokenStore}
import remarkable.notebook.NotebookDownloader
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.*

import java.nio.file.{Files, Paths}

/** Entry point for the reMarkable cloud integration.
  *
  * Commands:
  *   pair  — Interactive device registration. Prints a device token to stdout.
  *           Run once, then export: REMARKABLE_DEVICE_TOKEN=<token>
  *
  *   sync  — Downloads all notebooks from the cloud and renders them as PDFs.
  *           Requires REMARKABLE_DEVICE_TOKEN to be set.
  *           Output is written to ./output/ (or the path given by OUTPUT_DIR).
  */
object Main extends ZIOAppDefault:

  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    (for
      zioArgs <- ZIOAppArgs.getArgs
      // ZIOAppArgs is populated when invoked as: sbt "run <command>"
      // Fall back to REMARKABLE_COMMAND env var for convenience in hosted envs.
      cmd     <- zioArgs.headOption match
                   case Some(c) => ZIO.succeed(Some(c))
                   case None    => System.env("REMARKABLE_COMMAND")
      _       <- cmd match
                   case Some("pair")  => pairCommand
                   case Some("sync")  => syncCommand
                   case Some("debug") => debugCommand
                   case Some(other)   =>
                     Console.printLine(s"Unknown command: $other") *>
                     Console.printLine(usage) *>
                     ZIO.fail(new RuntimeException(s"Unknown command: $other"))
                   case None =>
                     Console.printLine(usage) *>
                     Console.printLine(
                       """Note: pass the command as part of a single quoted argument to sbt:
                         |  sbt "run pair"
                         |  sbt "run sync"
                         |Or set REMARKABLE_COMMAND=pair|sync in the environment.
                         |""".stripMargin
                     ) *>
                     ZIO.fail(new RuntimeException("No command given"))
    yield ())
      .catchAll { e =>
        // Print the error message cleanly and exit with code 1, without a stack trace dump.
        Console.printLineError(s"Error: ${e.getMessage}") *>
        ZIO.succeed(ExitCode.failure)
      }

  // -------------------------------------------------------------------------
  // pair command
  // -------------------------------------------------------------------------

  // -------------------------------------------------------------------------
  // debug command — prints the sub-index blob names for every document
  // -------------------------------------------------------------------------

  private val debugCommand: ZIO[Scope, Throwable, Unit] =
    for
      deviceTokenStr <- TokenStore.deviceToken
      _ <- HttpClientZioBackend.scoped().flatMap { backend =>
             for
               userToken <- AuthService.refreshUserToken(backend, DeviceToken(deviceTokenStr))
               allDocs   <- remarkable.api.RemarkableClient.listAll(backend, userToken)
               // Pick the first document that has .rm files for deep inspection
               firstWithRm = allDocs.find(d => d.files.exists(_.name.endsWith(".rm")))
               _ <- firstWithRm match
                      case None => Console.printLine("No documents with .rm files found!")
                      case Some(doc) =>
                        val rmEntries = doc.files.filter(_.name.endsWith(".rm"))
                        for
                          _ <- Console.printLine(s"\nInspecting: ${doc.name} (id=${doc.id})")
                          _ <- Console.printLine(s"All files in sub-index:")
                          _ <- ZIO.foreach(doc.files.sortBy(_.name)) { e =>
                                 Console.printLine(s"  ${e.name}  (${e.size}b, hash=${e.hash.take(16)}...)")
                               }
                          // Download the first .rm blob and show its header
                          firstRm = rmEntries.head
                          _ <- Console.printLine(s"\nDownloading first .rm: ${firstRm.name} (${firstRm.size}b)")
                          bytes <- remarkable.api.RemarkableClient.getBlob(backend, userToken, firstRm.hash)
                          _ <- Console.printLine(s"Downloaded ${bytes.length} bytes")
                          headerStr = new String(bytes.take(43), "ASCII")
                          _ <- Console.printLine(s"Header (first 43 bytes as ASCII): '$headerStr'")
                          hexDump = bytes.take(80).map(b => f"$b%02x").grouped(16).map(_.mkString(" ")).mkString("\n  ")
                          _ <- Console.printLine(s"Hex dump (first 80 bytes):\n  $hexDump")
                          // Also dump the .content blob
                          contentEntry = doc.files.find(_.name.endsWith(".content"))
                          _ <- ZIO.foreach(contentEntry) { ce =>
                                 for
                                   cb <- remarkable.api.RemarkableClient.getBlob(backend, userToken, ce.hash)
                                   _  <- Console.printLine(s"\n.content JSON (first 500 chars):\n${new String(cb, "UTF-8").take(500)}")
                                 yield ()
                               }
                        yield ()
             yield ()
           }
    yield ()

  private val pairCommand: ZIO[Scope, Throwable, Unit] =
    HttpClientZioBackend.scoped().flatMap { backend =>
      AuthService.pair(backend).unit
    }

  // -------------------------------------------------------------------------
  // sync command
  // -------------------------------------------------------------------------

  private val syncCommand: ZIO[Scope, Throwable, Unit] =
    for
      deviceTokenStr <- TokenStore.deviceToken
      outputDir      <- System.env("OUTPUT_DIR").map {
                          case Some(dir) if dir.nonEmpty => Paths.get(dir)
                          case _                         => Paths.get("output")
                        }
      _              <- ZIO.attempt(Files.createDirectories(outputDir))
      _              <- Console.printLine(s"Output directory: ${outputDir.toAbsolutePath}")

      _ <- HttpClientZioBackend.scoped().flatMap { backend =>
             for
               userToken <- AuthService.refreshUserToken(backend, DeviceToken(deviceTokenStr))
               _         <- Console.printLine("Authenticated successfully.")
               results   <- NotebookDownloader.downloadAll(backend, userToken, outputDir)
               _         <- Console.printLine(s"\nSync complete. ${results.length} notebooks downloaded.")
               _         <- ZIO.foreach(results) { r =>
                              Console.printLine(s"  ${r.outputPath}")
                            }
             yield ()
           }
    yield ()

  // -------------------------------------------------------------------------
  // Usage
  // -------------------------------------------------------------------------

  private val usage: String =
    """reMarkable Cloud Integration
      |
      |Usage (note: the command must be inside the quotes with 'run'):
      |  sbt "run pair"   — Register this client with reMarkable (one-time setup)
      |  sbt "run sync"   — Download all notebooks as PDFs to ./output/
      |
      |Environment variables:
      |  REMARKABLE_DEVICE_TOKEN  Device token obtained from the pair command (required for sync)
      |  REMARKABLE_COMMAND       Alternative to passing command as arg: pair | sync
      |  OUTPUT_DIR               Output directory for PDFs (default: ./output)
      |""".stripMargin
