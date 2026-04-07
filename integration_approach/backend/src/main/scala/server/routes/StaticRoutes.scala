package server.routes

import zio.*
import zio.http.*
import zio.json.*
import server.model.ErrorResponse

import java.io.InputStream
import scala.io.Source

/** Serves the compiled Scala.js frontend from the JAR classpath.
  *
  * All static assets are bundled into /webapp/ on the classpath by the sbt
  * resource generator in build.sbt.  Any path not matched by the API routes
  * falls through to this handler, which serves index.html for all non-asset
  * paths (enabling client-side routing in Laminar).
  */
object StaticRoutes:

  private def classpathResource(path: String): Option[Array[Byte]] =
    Option(getClass.getResourceAsStream(path)).map(_.readAllBytes())

  val routes: Routes[Any, Nothing] = Routes(

    // Serve JS bundle and source maps by exact name
    Method.GET / "webapp" / string("filename") ->
      handler { (filename: String, _: Request) =>
        classpathResource(s"/webapp/$filename") match
          case Some(bytes) =>
            val contentType =
              if filename.endsWith(".js") || filename.endsWith(".js.map") then
                Header.ContentType(MediaType.application.json) // close enough for source maps
              else
                Header.ContentType(MediaType.application.`octet-stream`)
            val ct: Header.ContentType =
              if filename.endsWith(".js") then Header.ContentType(MediaType.application.javascript)
              else contentType
            ZIO.succeed(
              Response(
                body    = Body.fromArray(bytes),
                headers = Headers(ct),
              )
            )
          case None =>
            ZIO.succeed(Response.status(Status.NotFound))
      },

    // Serve index.html for everything else (SPA fallback)
    Method.GET / trailing ->
      handler { (_: Path, _: Request) =>
        val clientId = sys.env.getOrElse("GOOGLE_CLIENT_ID", "")
        val devMode  = sys.env.get("DEV_MODE").contains("true").toString
        classpathResource("/webapp/index.html") match
          case Some(bytes) =>
            val html = new String(bytes, "UTF-8")
              .replace("__GOOGLE_CLIENT_ID__", clientId)
              .replace("__DEV_MODE__", devMode)
            ZIO.succeed(
              Response(
                body    = Body.fromString(html),
                headers = Headers(Header.ContentType(MediaType.text.html)),
              )
            )
          case None =>
            // Fallback: no bundled frontend yet — return a basic placeholder
            ZIO.succeed(
              Response(
                body    = Body.fromString(
                  s"""<!DOCTYPE html><html><head><title>remarkable2notion</title></head>
                     |<body><h1>remarkable2notion</h1><p>Frontend not bundled yet. Run <code>sbt frontend/fullOptJS</code> first.</p></body></html>""".stripMargin
                ),
                headers = Headers(Header.ContentType(MediaType.text.html)),
              )
            )
      },
  )
