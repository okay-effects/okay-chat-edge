package okaychatedge

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.*

/**
 * THE ROUTER IN AN ISOLATE: its rules and probe from the Worker's own modules at the isolate's start, warmed there; the
 * static table from KV on the first request (an isolate's start may not read KV), kept while the isolate lives.
 */
@JSExportTopLevel("edge")
object Edge:
  private var router: Router = null
  private var table: StaticTable = null

  /** at the isolate's start: okay-chat's built intents (the rules) and the probe's weights (JSON, from `fit`) */
  /** okay-chat's employers' boards (its job-sources.json), imported by the cron (`Boards`) */
  var boards: Vector[Board] = Vector.empty

  @JSExport
  def init(built: String, probe: String, sources: js.UndefOr[String] = js.undefined): Unit =
    router = Router(built, Probe.fromJson(js.JSON.parse(probe)))
    boards = sources.toOption.map(Boards.parse).getOrElse(Vector.empty)

  /** every rule compiled before the first request: V8 compiles a regex on its first use, once for one-byte (Latin)
   * text and once for two-byte (Cyrillic) — 20 and 75 ms, which a request's 10 ms of CPU cannot pay */
  @JSExport
  def warm(): Unit =
    router.byRule("zzz qqq")
    router.byRule("ъъъ ыыы")

  @JSExport
  def hasTable: Boolean = table != null

  /** the static table's bytes, read once per isolate (from KV, or carried in the Worker) */
  @JSExport
  def load(bytes: ArrayBuffer): Unit = table = new StaticTable(new Safetensors(bytes))

  def route(text: String): Route = router.route(table, text)

  /** an intent's slot, by okay-chat's pattern */
  def slot(intent: String, name: String, text: String): Option[String] = router.slot(intent, name, text)

  /** the help rows for the intents the edge acts on (`Chat.acted`) */
  def help(l: Lang): Vector[String] = router.help(Lang.code(l), Chat.acted).flatMap {
    case ("requests", _) => Vector(Phrases.requestsHelp(l))
    // vacancies from the internet, the edge's own, beside the search among people
    case ("need", row) => Vector(row, Phrases.jobsHelp(l))
    case (_, row) => Vector(row)
  }

  /** a message's vector by the static table */
  def encode(text: String): Option[Float32Array] = table.encode(text)

  @JSExport("route")
  def routeJs(text: String): js.Dynamic =
    val r = route(text)
    js.Dynamic.literal(intent = r.intent, by = r.by, probability = r.probability, margin = r.margin)
