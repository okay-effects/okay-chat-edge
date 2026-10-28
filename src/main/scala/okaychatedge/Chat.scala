package okaychatedge

import okay.effects.json.Json
import okay.http.{Request, Response}
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.cloudflare.Worker
import okay.serverless.{Durable, field}
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * THE CHAT on okay-serverless: a stateless Worker routes each message (the router in its isolate), and the
 * conversation it belongs to — a Durable Object, one per conversation, SQLite under it (the free plan's kind) — keeps
 * its turns: each turn one step of durable state, one transaction.
 *
 *   POST /route            a message → its intent (no conversation)
 *   POST /c/<id>           a message in conversation <id> → its turn: the intent, and the turn's number
 *   GET  /c/<id>           the conversation's turns
 */
object Chat:
  /** the turns a conversation keeps: the last ones */
  val kept = 200

  def turnJson(text: String, r: Route, at: Long): Json = Json.obj(
    "text" -> Json.Str(text), "intent" -> Json.Str(r.intent), "by" -> Json.Str(r.by),
    "probability" -> Json.Num(BigDecimal(r.probability)), "at" -> Json.Num(BigDecimal(at)))

  def routeJson(r: Route): Json = Json.obj("intent" -> Json.Str(r.intent), "by" -> Json.Str(r.by),
    "probability" -> Json.Num(BigDecimal(r.probability)), "margin" -> Json.Num(BigDecimal(r.margin)))

  /** A CONVERSATION: its turns, durable — a turn appended in one step */
  object Conversations extends Actor:
    def fetch(name: String, request: Request): Response ! InActor =
      if request.method == "POST" then
        request.json match
          case Left(e) => pure(Response.text(s"a turn, as JSON: $e", 400))
          case Right(turn) =>
            Durable.step[ActorStep, InActor, Int](name)(Durable.get[List[Json], ActorStep]("turns").flatMap { ts =>
              val all = (ts.getOrElse(Nil) :+ turn).takeRight(kept)
              Durable.get[Int, ActorStep]("count").flatMap { n =>
                val count = n.getOrElse(0) + 1
                Durable.put[List[Json], ActorStep]("turns", all)
                  .andThen(Durable.put[Int, ActorStep]("count", count)).as(count)
              }
            }).map(n => Response.json(Json.obj("turn" -> Json.Num(BigDecimal(n)))))
      else
        Durable.step[ActorStep, InActor, List[Json]](name)(Durable.get[List[Json], ActorStep]("turns")
          .map(_.getOrElse(Nil))).map(ts => Response.json(Json.Arr(ts.toVector)))

  /** THE WORKER: a message routed here, its turn kept by its conversation */
  object Edges extends App:
    override def fetch(r: Request): Response ! Cloud = (r.method, r.path.split('/').toList) match
      case ("POST", List("", "route")) => pure(Response.json(routeJson(Edge.route(r.body))))
      case ("POST", List("", "c", id)) =>
        val route = Edge.route(r.body)
        Clock.now[Cloud].flatMap { at =>
          Objects.call[Cloud]("CONVERSATIONS", id, Request.json("POST", "/", turnJson(r.body, route, at)))
            .map(kept => Response.json(Json.obj("route" -> routeJson(route),
              "turn" -> okay.effects.json.Descent.parse(kept.body).toOption.flatMap(_.field("turn"))
                .getOrElse(Json.Null))))
        }
      case ("GET", List("", "c", id)) => Objects.call[Cloud]("CONVERSATIONS", id, Request("GET", "/"))
      case _ => pure(Response.text("POST /route, POST /c/<id>, GET /c/<id>", 404))

  @JSExportTopLevel("worker")
  val worker: Worker = Worker(Edges, Map("CONVERSATIONS" -> Conversations))
