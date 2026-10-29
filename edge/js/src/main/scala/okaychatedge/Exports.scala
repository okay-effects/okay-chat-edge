package okaychatedge

import okay.serverless.cloudflare.Worker

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.ArrayBuffer

/** THE ROUTER, as entry.js and the benches see it (`edge`): `Edge`, its table's bytes an ArrayBuffer */
@JSExportTopLevel("edge")
object EdgeJs:
  @JSExport
  def init(built: String, probe: String, sources: js.UndefOr[String] = js.undefined): Unit =
    Edge.init(built, probe, sources.toOption)
  @JSExport
  def warm(): Unit = Edge.warm()
  @JSExport
  def hasTable: Boolean = Edge.hasTable
  @JSExport
  def load(bytes: ArrayBuffer): Unit = Edge.load(new Safetensors(bytes))
  @JSExport
  def route(text: String): js.Dynamic =
    val r = Edge.route(text)
    js.Dynamic.literal(intent = r.intent, by = r.by, probability = r.probability, margin = r.margin)

/** THE WORKER (Cloudflare): the chat's app and its objects, by okay-serverless's `Worker` */
object Exports:
  @JSExportTopLevel("worker")
  val worker: Worker = Worker(Chat.Edges, Chat.actors)
