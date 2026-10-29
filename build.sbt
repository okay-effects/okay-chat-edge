// okay-chat-edge: okay-chat's chat on Cloudflare Workers' free plan — Scala 3 on Scala.js, no model on the path; the
// same chat on the JVM, on a server of one's own, when Cloudflare is not there (edge/jvm, `sbt edgeJVM/run`)
import org.scalajs.linker.interface.{ESVersion, ModuleKind}

ThisBuild / scalaVersion := "3.3.8"
ThisBuild / resolvers ++= Seq(
  "okay-serverless" at "https://okay-effects.github.io/okay-serverless/maven",
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-faces" at "https://okay-effects.github.io/okay-faces/maven",
  "okay-store" at "https://okay-effects.github.io/okay-store/maven")

/**
 * THE CHAT, ONCE, on two platforms (specs: the host): JavaScript for Cloudflare — the main home, a Worker and its
 * Durable Objects — and the JVM for a server of one's own, the fallback (okay-serverless' host: one process, one
 * directory). Its logic is shared; each platform gives its regexes, JSON, text and the static table's bytes.
 */
lazy val edge = crossProject(JSPlatform, JVMPlatform).crossType(CrossType.Full).in(file("edge"))
  .settings(
    name := "okay-chat-edge",
    scalacOptions ++= Seq("-deprecation", "-feature"),
  )
  .jsSettings(
    // the cloud as effects: a Worker, its Durable Objects, durable state without replay (okay-serverless)
    libraryDependencies += "io.github.okay-effects" %%% "okay-serverless-cloudflare" % serverless,
    // an ES module for wrangler; ES2018 for the regexes' \p{L} and lookbehind (the rules are Unicode)
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)
      .withESFeatures(_.withESVersion(ESVersion.ES2018))),
  )
  .jvmSettings(
    // the same effects on one's own server: SQLite, the JDK's HTTP, a model server of OpenAI's API
    libraryDependencies += "io.github.okay-effects" %% "okay-serverless-host" % serverless,
    Compile / mainClass := Some("okaychatedge.Serve"),
    run / fork := true,
    run / baseDirectory := (ThisBuild / baseDirectory).value,
  )

val serverless = "0.1.0"

/** what runs at build time — the probe's fit, the measures — linked into bench/tools.js, never into the Worker */
lazy val tools = (project in file("tools"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(edge.js)
  .settings(
    name := "okay-chat-edge-tools",
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)
      .withESFeatures(_.withESVersion(ESVersion.ES2018))),
  )

val toolsJs = taskKey[File]("the build-time tools, linked")
toolsJs := {
  val out = (tools / Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
  val _ = (tools / Compile / fastLinkJS).value
  val to = baseDirectory.value / "bench" / "tools.js"
  IO.copyFile(out / "main.js", to)
  to
}

/** the Worker's code, linked (full optimisation) into worker/okay.js */
val worker = taskKey[File]("the Worker's code, linked")
worker := {
  val out = (edge.js / Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
  val _ = (edge.js / Compile / fullLinkJS).value
  val to = baseDirectory.value / "worker" / "okay.js"
  IO.copyFile(out / "main.js", to)
  to
}

/** the chat's web page: an okay-ui program (page/) — its screens on okay-faces' DOM face, the conversation reached by
 * its effect `Talk` — linked into worker/public/page.js: the Worker's static assets, served free and outside the 100 000
 * requests a day */
lazy val page = (project in file("page"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "okay-chat-edge-page",
    resolvers += "okay-ui" at "https://okay-effects.github.io/okay-ui/maven",
    libraryDependencies += "io.github.okay-effects" %%% "okay-ui" % "0.1.0",
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
  )

val pageJs = taskKey[File]("the web page's code, linked")
pageJs := {
  val out = (page / Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
  val _ = (page / Compile / fullLinkJS).value
  val to = baseDirectory.value / "worker" / "public" / "page.js"
  IO.copyFile(out / "main.js", to)
  to
}
