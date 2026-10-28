// okay-chat-edge: okay-chat's chat on Cloudflare Workers' free plan — Scala 3 on Scala.js, no model on the path
import org.scalajs.linker.interface.{ESVersion, ModuleKind}

ThisBuild / scalaVersion := "3.3.8"
ThisBuild / resolvers ++= Seq(
  "okay-serverless" at "https://okay-effects.github.io/okay-serverless/maven",
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-simple" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-faces" at "https://okay-effects.github.io/okay-faces/maven",
  "okay-store" at "https://okay-effects.github.io/okay-store/maven")

lazy val edge = (project in file("."))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "okay-chat-edge",
    // the cloud as effects: a Worker, its Durable Objects, durable state without replay (okay-serverless)
    libraryDependencies += "io.github.okay-effects" %%% "okay-serverless-cloudflare" % "0.1.0",
    scalacOptions ++= Seq("-deprecation", "-feature"),
    // an ES module for wrangler; ES2018 for the regexes' \p{L} and lookbehind (the rules are Unicode)
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)
      .withESFeatures(_.withESVersion(ESVersion.ES2018))),
  )

/** what runs at build time — the probe's fit, the measures — linked into bench/tools.js, never into the Worker */
lazy val tools = (project in file("tools"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(edge)
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
  val out = (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
  val _ = (Compile / fullLinkJS).value
  val to = baseDirectory.value / "worker" / "okay.js"
  IO.copyFile(out / "main.js", to)
  to
}
