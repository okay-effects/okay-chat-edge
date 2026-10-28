package okaychatedge

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.*

/**
 * THE WORKER: `POST /route` with a message, its intent back as JSON. The table, the rules and the probe's weights come
 * from the Worker's own modules (`entry.js` imports them), read once per isolate.
 */
@JSExportTopLevel("worker")
object Worker:
  private var router: Router = null

  /** the router: the static table's bytes, okay-chat's built intents, the probe's weights (JSON, from `fit`) */
  @JSExport
  def init(static: ArrayBuffer, built: String, probe: String): Unit =
    router = Router(new StaticTable(new Safetensors(static)), built, Probe.fromJson(js.JSON.parse(probe)))

  @JSExport
  def ready: Boolean = router != null

  /** every rule compiled before the first request: V8 compiles a regex on its first use, once for one-byte (Latin)
   * text and once for two-byte (Cyrillic) — 20 and 75 ms, which a request's 10 ms of CPU cannot pay. At the
   * isolate's start they are paid once */
  @JSExport
  def warm(): Unit =
    router.route("zzz qqq")
    router.route("ъъъ ыыы")

  @JSExport
  def route(text: String): js.Dynamic =
    val r = router.route(text)
    js.Dynamic.literal(intent = r.intent, by = r.by, probability = r.probability, margin = r.margin)

  @JSExport
  def fetch(request: js.Dynamic, env: js.Dynamic, ctx: js.Dynamic): js.Promise[js.Dynamic] =
    val url = js.Dynamic.newInstance(js.Dynamic.global.URL)(request.url)
    if request.method.asInstanceOf[String] == "POST" && url.pathname.asInstanceOf[String] == "/route" then
      request.text().asInstanceOf[js.Promise[String]].`then`[js.Dynamic]((text: String) => json(route(text)))
    else js.Promise.resolve[js.Dynamic](json(js.Dynamic.literal(error = "POST /route with a message")))

  private def json(o: js.Dynamic): js.Dynamic =
    js.Dynamic.newInstance(js.Dynamic.global.Response)(js.JSON.stringify(o),
      js.Dynamic.literal(headers = js.Dynamic.literal("content-type" -> "application/json; charset=utf-8")))
