package okaychatedge

import okay.effects.json.Json
import okay.http.{Request, Response}
import okay.scala3.*
import okay.serverless.cloud.*
import okay.serverless.host.{AiServer, Host, HostConfig}

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.{ExecutionContext, Future}

/**
 * THE CHAT ON A SERVER OF ONE'S OWN (okay-serverless' host): the fallback beside Cloudflare, the same app and objects
 * as the Worker's, every effect on this process and one directory. What entry.js does for the Worker is done here: the
 * data read (okay-chat's intents, the probe, the boards; the static table from its file, not KV), a model's failure
 * made an answer (`{error}`), the page served from worker/public, the cron of wrangler.toml.
 *
 * Its settings, from the environment: `OKAY_CHAT_DIR` (the state; `host-data`), `PORT` (8787), `HOST` (127.0.0.1);
 * a model server of OpenAI's API — `AI_URL`, `AI_KEY`, `AI_CHAT_MODEL`, `AI_EMBED_MODEL` — or none: no lane, the
 * subjects by trigrams; the Worker's own variables by their names (TELEGRAM_TOKEN, JOBS_WHERE, …). `TEST_SCHEDULED`
 * runs a cron by a request, as `wrangler dev --test-scheduled` does; `COUNTRY_HEADER`, `COUNTRY` — `Located`.
 */
object Serve:
  private val names = Vector("EMBED", "JOB_SOURCES", "JOBS_API", "JOBS_WHERE", "LANE_DAILY", "LANE", "OPEN_API",
    "TELEGRAM_API", "TELEGRAM_SECRET", "TELEGRAM_TOKEN")
  /** the Worker's model lane (wrangler.toml's LANE) */
  private val lane = "@cf/google/gemma-4-26b-a4b-it"

  def main(args: Array[String]): Unit =
    given ExecutionContext = ExecutionContext.global
    val env = sys.env
    val data = Paths.get(env.getOrElse("OKAY_CHAT_DATA", "data"))
    def text(name: String): String = Files.readString(data.resolve(name))
    Edge.init(text("intents.json.txt"), text("probe.json.txt"),
      Option.when(Files.exists(data.resolve("job-sources.json.txt")))(text("job-sources.json.txt")))
    Edge.warm()
    Edge.load(Safetensors(Files.readAllBytes(data.resolve("static.safetensors"))))

    val ai = env.get("AI_URL").filter(_.nonEmpty).map(url => AiServer(url, env.get("AI_KEY").filter(_.nonEmpty),
      (env.get("AI_CHAT_MODEL").map(m => env.getOrElse("LANE", lane) -> m) ++
        env.get("AI_EMBED_MODEL").map(Embed.bgeM3 -> _)).toMap))
    // with no model server, the Worker's own defaults without Workers AI: subjects by trigrams, no lane
    val defaults = if ai.isDefined then Map("LANE" -> lane, "EMBED" -> "ai") else Map("LANE" -> "", "EMBED" -> "")
    val vars = defaults ++ names.flatMap(n => env.get(n).map(n -> _))

    val located = Located(Chat.Edges, env.get("COUNTRY_HEADER").filter(_.nonEmpty),
      env.get("COUNTRY").filter(_.nonEmpty))
    val host = Host.start(located, Chat.actors, HostConfig(
      dir = Paths.get(env.getOrElse("OKAY_CHAT_DIR", "host-data")),
      port = env.get("PORT").map(_.toInt).getOrElse(8787),
      host = env.getOrElse("HOST", "127.0.0.1"),
      static = Some(Paths.get("worker", "public")).filter(Files.isDirectory(_)),
      vars = vars, crons = List("*/2 * * * *"), ai = ai,
      adjust = p => p.copy(ai = guarded(p.ai)),
      testScheduled = env.get("TEST_SCHEDULED").exists(_.nonEmpty)))
    System.err.println(s"okay-chat-edge on http://${env.getOrElse("HOST", "127.0.0.1")}:${host.port}")
    Thread.currentThread().join()

  /** a model's failure is an answer, as on the Worker: `{error}`, read as no answer */
  private def guarded(ai: Port[Ai])(using ExecutionContext): Port[Ai] = new Port[Ai]:
    def apply[X](op: Ai[X]): Future[X] = op match
      case r: Ai.Run => ai(r).recover { case e => Json.obj("error" -> Json.Str(e.toString)) }

  /**
   * WHERE THE PERSON IS, as entry.js tells it from `request.cf.country`: here from a proxy's header one trusts
   * (`COUNTRY_HEADER`, `cf-ipcountry` behind Cloudflare's), else the server's own country (`COUNTRY`) — a two-letter
   * code, in English as the job boards name it. The header a request carries of its own is not believed.
   */
  private final class Located(app: App, header: Option[String], default: Option[String]) extends App:
    private def named(code: String): Option[String] =
      Option.when(code.matches("[A-Z]{2}") && code != "XX" && code != "T1")(
        java.util.Locale.of("", code).getDisplayCountry(java.util.Locale.ENGLISH)).filter(n => n.nonEmpty && n != code)
    override def admit(r: Request): Either[Response, Request] ! Cloud =
      val code = header.flatMap(r.header).map(_.trim.toUpperCase).orElse(default.map(_.toUpperCase))
      val kept = r.headers.filterNot((k, _) => k.equalsIgnoreCase("x-okay-country"))
      app.admit(r.copy(headers = kept ++ code.flatMap(named).map(n => "x-okay-country" -> List(n))))
    override def objectFor(r: Request): Option[(String, String)] = app.objectFor(r)
    override def fetch(r: Request): Response ! Cloud = app.fetch(r)
    override def scheduled(s: Scheduled): Unit ! Cloud = app.scheduled(s)
