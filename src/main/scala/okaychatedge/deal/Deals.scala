package okaychatedge.deal

import okay.effects.json.{Descent, Json}
import okay.http.{Request, Response}
import okay.scala3.*
import okay.serverless.cloud.*
import okay.serverless.{Durable, field}

/**
 * A DEAL IS AN OBJECT: a Durable Object under its number, its flow its durable state — a transition one step, so two
 * answers to one deal cannot both win. Numbers come from `Numbers` (one object, a counter), as okay-chat's deal ids
 * are global ("берусь 3" names a deal, not a list's line).
 *
 *   POST /create   {scenario, parties: {role: party}, what}   → the flow
 *   POST /advance  {transition, by, at}                       → {flow, transition} or {refused}
 *   GET  /         the flow
 */
object Deals extends Actor:
  def fetch(name: String, request: Request): Response ! InActor = (request.method, request.path) match
    case ("POST", "/create") => json(request) { j =>
        val flow = Flow(name.toLong, text(j, "scenario").getOrElse("deal"),
          j.field("parties").collect { case o: Json.Obj => o.fields.collect { case (k, Json.Str(v)) => k -> v }.toMap }
            .getOrElse(Map.empty), text(j, "what").getOrElse(""), "", Vector.empty)
        ScenarioDef.builtIn.get(flow.scenario) match
          case None => pure(Response.text(s"no scenario ${flow.scenario}", 400))
          case Some(d) =>
            val made = flow.copy(state = d.initial)
            Durable.step[ActorStep, InActor, Flow](name)(Durable.get[Json, ActorStep]("flow").flatMap {
              case Some(was) => pure(read(was))
              case None => Durable.put[Json, ActorStep]("flow", write(made)).as(made)
            }).map(f => Response.json(write(f)))
      }
    case ("POST", "/advance") => json(request) { j =>
        val transition = text(j, "transition").getOrElse("")
        val by = text(j, "by").getOrElse("")
        val at = j.field("at").collect { case Json.Num(n) => n.toLong }.getOrElse(0L)
        Durable.step[ActorStep, InActor, Json](name)(Durable.get[Json, ActorStep]("flow").flatMap {
          case None => pure(Json.obj("refused" -> Json.Str(s"no deal $name")))
          case Some(stored) =>
            val f = read(stored)
            val d = ScenarioDef.builtIn(f.scenario)
            Flow.advance(d, f, transition, by, at) match
              case Left(NoAdvance(why)) => pure(Json.obj("refused" -> Json.Str(why)))
              case Right((next, t)) => Durable.put[Json, ActorStep]("flow", write(next)).as(Json.obj(
                "flow" -> write(next),
                "unlocks" -> Json.Arr(t.unlocks.map((r, a) => Json.Arr(Vector(Json.Str(r), Json.Str(a))))),
                "notifies" -> Json.Arr(t.notifies.map((r, tpl) =>
                  Json.Arr(Vector(Json.Str(r), Json.Str(Flow.fill(tpl, d, next, by))))))))
        }).map(Response.json(_))
      }
    case _ => Durable.step[ActorStep, InActor, Option[Json]](name)(Durable.get[Json, ActorStep]("flow"))
        .map(f => f.map(Response.json(_)).getOrElse(Response.text(s"no deal $name", 404)))

  private def json(r: Request)(f: Json => Response ! InActor): Response ! InActor =
    Descent.parse(r.body) match
      case Left(e) => pure(Response.text(s"JSON: ${e.message}", 400))
      case Right(j) => f(j)

  def text(j: Json, k: String): Option[String] = j.field(k).collect { case Json.Str(s) => s }

  def write(f: Flow): Json = Json.obj(
    "id" -> Json.Num(BigDecimal(f.id)), "scenario" -> Json.Str(f.scenario),
    "parties" -> Json.Obj(f.parties.toVector.map((k, v) => k -> Json.Str(v))),
    "what" -> Json.Str(f.what), "state" -> Json.Str(f.state),
    "history" -> Json.Arr(f.history.map((t, by, at) =>
      Json.Arr(Vector(Json.Str(t), Json.Str(by), Json.Num(BigDecimal(at)))))))

  def read(j: Json): Flow = Flow(
    j.field("id").collect { case Json.Num(n) => n.toLong }.getOrElse(0L),
    text(j, "scenario").getOrElse("deal"),
    j.field("parties").collect { case o: Json.Obj => o.fields.collect { case (k, Json.Str(v)) => k -> v }.toMap }
      .getOrElse(Map.empty),
    text(j, "what").getOrElse(""), text(j, "state").getOrElse(""),
    j.field("history").collect { case Json.Arr(xs) => xs.collect {
      case Json.Arr(Vector(Json.Str(t), Json.Str(by), Json.Num(at))) => (t, by, at.toLong)
    } }.getOrElse(Vector.empty))

/** THE NUMBERS: one object, a counter — the next deal's number, each one step */
object Numbers extends Actor:
  def fetch(name: String, request: Request): Response ! InActor =
    Durable.step[ActorStep, InActor, Long](name)(Durable.get[Long, ActorStep]("next").flatMap { n =>
      val mine = n.getOrElse(1L)
      Durable.put[Long, ActorStep]("next", mine + 1).as(mine)
    }).map(n => Response.text(n.toString))
