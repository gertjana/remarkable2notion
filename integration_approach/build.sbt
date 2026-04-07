import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

ThisBuild / scalaVersion := "3.4.2"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "remarkable2notion"

val zioVersion        = "2.1.9"
val zioJsonVersion    = "0.7.3"
val zioHttpVersion    = "3.0.1"
val sttpVersion       = "3.9.8"
val pdfboxVersion     = "3.0.3"
val laminarVersion    = "17.0.0"
val scalajsDomVersion = "2.8.0"

// ---------------------------------------------------------------------------
// core  —  shared domain logic, runs on the JVM
// ---------------------------------------------------------------------------
lazy val core = (project in file("core"))
  .settings(
    name := "remarkable-core",
    libraryDependencies ++= Seq(
      // ZIO
      "dev.zio"                       %% "zio"          % zioVersion,
      "dev.zio"                       %% "zio-streams"  % zioVersion,
      "dev.zio"                       %% "zio-json"     % zioJsonVersion,

      // HTTP client (sttp with ZIO backend)
      "com.softwaremill.sttp.client3" %% "zio"          % sttpVersion,
      "com.softwaremill.sttp.client3" %% "zio-json"     % sttpVersion,

      // PDF generation
      "org.apache.pdfbox"              % "pdfbox"       % pdfboxVersion,

      // Testing
      "dev.zio"                       %% "zio-test"     % zioVersion % Test,
      "dev.zio"                       %% "zio-test-sbt" % zioVersion % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
  )

// ---------------------------------------------------------------------------
// backend  —  ZIO HTTP server, JVM only
// ---------------------------------------------------------------------------
lazy val backend = (project in file("backend"))
  .dependsOn(core)
  .settings(
    name := "remarkable-backend",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-http" % zioHttpVersion,
      "dev.zio" %% "zio-json" % zioJsonVersion,
      "dev.zio" %% "zio"      % zioVersion,

      // JWT / OIDC — for verifying Google ID tokens
      "com.auth0" % "java-jwt"    % "4.4.0",
      "com.auth0" % "jwks-rsa"    % "0.22.1",
    ),

    // Fat JAR — includes the bundled frontend JS at classpath root /webapp/
    assembly / mainClass := Some("server.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", _*)             => MergeStrategy.discard
      case PathList("module-info.class")         => MergeStrategy.discard
      case x if x.endsWith(".class")             => MergeStrategy.last
      case _                                     => MergeStrategy.first
    },

    // Forward relevant env vars when running via sbt
    run / fork := true,
    run / envVars ++= {
      val vars = Seq(
        "GOOGLE_CLIENT_ID", "SESSION_SECRET",
        "REMARKABLE_DEVICE_TOKEN", "PORT",
      )
      vars.flatMap(k => sys.env.get(k).map(k -> _)).toMap
    },

    // Copy compiled frontend bundle into backend resources before compile/run.
    // Uses fastOptJS output for development; swap to fullOptJS for production builds.
    Compile / resourceGenerators += Def.task {
      val jsFile   = (frontend / Compile / fastOptJS).value.data
      val mapFile  = new File(jsFile.getPath + ".map")
      val targetDir = (Compile / resourceManaged).value / "webapp"
      IO.createDirectory(targetDir)

      val destJs  = targetDir / jsFile.getName
      val destMap = targetDir / mapFile.getName
      IO.copyFile(jsFile, destJs)
      if (mapFile.exists()) IO.copyFile(mapFile, destMap)

      Seq(destJs, destMap).filter(_.exists())
    }.taskValue,
  )

// ---------------------------------------------------------------------------
// frontend  —  Scala.js + Laminar SPA
// ---------------------------------------------------------------------------
lazy val frontend = (project in file("frontend"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "remarkable-frontend",
    scalaJSUseMainModuleInitializer := true,

    libraryDependencies ++= Seq(
      "com.raquo"     %%% "laminar"    % laminarVersion,
      "org.scala-js"  %%% "scalajs-dom" % scalajsDomVersion,
      "dev.zio"       %%% "zio-json"   % zioJsonVersion,
    ),
  )
