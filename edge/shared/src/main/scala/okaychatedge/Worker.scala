package okaychatedge

/**
 * THE ROUTER, ONE A PROCESS: its rules and probe at the start, warmed there; the static table loaded once. In a Worker
 * the rules and probe are the Worker's own modules and the table comes from KV on the first request (an isolate's
 * start may not read KV) — `EdgeJs` exports it to entry.js; on the host they are files read at the start.
 */
object Edge:
  private var router: Router = null
  private var table: StaticTable = null

  /** okay-chat's employers' boards (its job-sources.json), imported by the cron (`Boards`) */
  var boards: Vector[Board] = Vector.empty

  /** at the start: okay-chat's built intents (the rules), the probe's weights (JSON, from `fit`), the boards */
  def init(built: String, probe: String, sources: Option[String] = None): Unit =
    router = Router(built, Probe.fromJson(J.parse(probe)))
    boards = sources.map(Boards.parse).getOrElse(Vector.empty)

  /** every rule compiled before the first request: V8 compiles a regex on its first use, once for one-byte (Latin)
   * text and once for two-byte (Cyrillic) — 20 and 75 ms, which a request's 10 ms of CPU cannot pay */
  def warm(): Unit =
    router.byRule("zzz qqq")
    router.byRule("ъъъ ыыы")

  def hasTable: Boolean = table != null

  /** the static table, read once */
  def load(file: Safetensors): Unit = table = new StaticTable(file)

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
  def encode(text: String): Option[Array[Float]] = table.encode(text)
