package okaychatedge

import okay.effects.json.{Descent, Json}
import okay.http.{Request, Response}
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.cloudflare.Worker
import okay.serverless.{Durable, field}
import okaychatedge.deal.{Deals, Numbers}
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * THE CHAT on okay-serverless: a stateless Worker routes each message (the router in its isolate); the conversation
 * it belongs to — a Durable Object, one per conversation, SQLite under it (the free plan's kind) — keeps its turns,
 * each one step of durable state; a deal is an object of its own (`deal.Deals`), and what a deal tells a party is
 * written into that party's conversation. A party is a conversation until the edge has profiles.
 *
 *   POST /route                          a message → its intent (no conversation)
 *   POST /c/<id>                         a message in conversation <id> → its intent, the turn's number, a reply
 *   GET  /c/<id>                         the conversation's turns
 *   POST /deals {seeker, provider, what} a deal asked (until the edge finds candidates itself) → its number
 *   GET  /deals/<n>                      the deal
 */
object Chat:
  /** the turns a conversation keeps: the last ones */
  val kept = 200

  private val email = """[\w.+-]+@[\w-]+(\.[\w-]+)+""".r
  private val number = """\d+""".r

  def routeJson(r: Route): Json = Json.obj("intent" -> Json.Str(r.intent), "by" -> Json.Str(r.by),
    "probability" -> Json.Num(BigDecimal(r.probability)), "margin" -> Json.Num(BigDecimal(r.margin)))

  /** A CONVERSATION: its turns, durable — a turn appended in one step; the address its person gave, kept */
  object Conversations extends Actor:
    private def append(name: String, turn: Json): Int ! InActor =
      Durable.step[ActorStep, InActor, Int](name)(Durable.get[List[Json], ActorStep]("turns").flatMap { ts =>
        Durable.get[Int, ActorStep]("count").flatMap { n =>
          val count = n.getOrElse(0) + 1
          val contact = turn.field("contact").collect { case Json.Str(c) => c }
          Durable.put[List[Json], ActorStep]("turns", (ts.getOrElse(Nil) :+ turn).takeRight(kept))
            .andThen(Durable.put[Int, ActorStep]("count", count))
            .andThen(contact.fold(pure[Unit, ActorStep](()))(c => Durable.put[String, ActorStep]("contact", c)))
            .as(count)
        }
      })

    def fetch(name: String, request: Request): Response ! InActor = (request.method, request.path) match
      case ("POST", "/") | ("POST", "/tell") => Descent.parse(request.body) match
          case Left(e) => pure(Response.text(s"a turn, as JSON: ${e.message}", 400))
          case Right(turn) => append(name, turn).map(n => Response.json(Json.obj("turn" -> Json.Num(BigDecimal(n)))))
      case ("GET", "/contact") =>
        Durable.step[ActorStep, InActor, Option[String]](name)(Durable.get[String, ActorStep]("contact"))
          .map(c => c.map(Response.text(_)).getOrElse(Response.text("", 404)))
      case _ =>
        Durable.step[ActorStep, InActor, List[Json]](name)(Durable.get[List[Json], ActorStep]("turns")
          .map(_.getOrElse(Nil))).map(ts => Response.json(Json.Arr(ts.toVector)))

  // ---- what the Worker asks the objects

  private def tell(party: String, text: String, at: Long): Unit ! Cloud =
    Objects.call[Cloud]("CONVERSATIONS", party, Request.json("POST", "/tell",
      Json.obj("told" -> Json.Str(text), "at" -> Json.Num(BigDecimal(at))))).as(())

  private def contactOf(party: String): Option[String] ! Cloud =
    Objects.call[Cloud]("CONVERSATIONS", party, Request("GET", "/contact"))
      .map(r => Option.when(r.status == 200 && r.body.nonEmpty)(r.body))

  private def parsed(r: Response): Json = Descent.parse(r.body).toOption.getOrElse(Json.Null)

  /**
   * A DEAL ANSWERED from a conversation — "берусь 3", "отказываюсь 3", "отбой 3": the transition asked of deal 3 by
   * this party; each party told what the scenario tells it; each unlock the other party's address. The reply to the
   * one who answered
   */
  private def answer(me: String, transition: String, text: String, at: Long): String ! Cloud =
    number.findFirstIn(text) match
      case None => pure("назовите номер сделки")
      case Some(n) =>
        Objects.call[Cloud]("DEALS", n, Request.json("POST", "/advance", Json.obj("transition" -> Json.Str(transition),
          "by" -> Json.Str(me), "at" -> Json.Num(BigDecimal(at))))).map(parsed).flatMap { j =>
          j.field("refused").collect { case Json.Str(why) => why } match
            case Some(why) => pure(s"сделка $n: нельзя — $why")
            case None =>
              val flow = j.field("flow").map(Deals.read).get
              val pairs = (k: String) => j.field(k).collect { case Json.Arr(xs) => xs.collect {
                case Json.Arr(Vector(Json.Str(a), Json.Str(b))) => (a, b) } }.getOrElse(Vector.empty)
              val told = pairs("notifies").map((role, said) =>
                flow.parties.get(role).filter(_ != me).fold(pure[Unit, Cloud](()))(p => tell(p, s"сделка $n: $said", at)))
              // an unlock of `contact` for a viewer: the OTHER party's address, to the viewer
              val unlocked = pairs("unlocks").collect { case (viewer, "contact") => viewer }.flatMap { viewer =>
                flow.parties.get(viewer).map(v => (v, flow.parties.collect { case (r, p) if r != viewer => p }.toVector))
              }
              val mine = unlocked.collect { case (v, others) if v == me => others }.flatten
              val theirs = unlocked.collect { case (v, others) if v != me => (v, others) }
              val toThem = theirs.map((v, others) => others.foldLeft(pure[Unit, Cloud](()))((p, o) =>
                p.andThen(contactOf(o).flatMap(c => tell(v, s"сделка $n: контакт — ${c.getOrElse("не указан")}", at)))))
              (told ++ toThem).foldLeft(pure[Unit, Cloud](()))(_.andThen(_)).andThen(
                mine.foldLeft(pure[Vector[String], Cloud](Vector.empty))((acc, o) =>
                  acc.flatMap(cs => contactOf(o).map(c => cs :+ c.getOrElse("не указан")))))
                .map(cs => s"сделка $n: ${flow.state}" + (if cs.isEmpty then "" else s"; контакт — ${cs.mkString(", ")}"))
        }

  /** a deal asked: its number from `Numbers`, the deal made, the provider told how to answer, the seeker told */
  private def ask(seeker: String, provider: String, what: String, at: Long): Long ! Cloud =
    Objects.call[Cloud]("NUMBERS", "deals", Request("POST", "/")).map(_.body.trim.toLong).flatMap { n =>
      Objects.call[Cloud]("DEALS", n.toString, Request.json("POST", "/create", Json.obj("scenario" -> Json.Str("deal"),
        "parties" -> Json.obj("seeker" -> Json.Str(seeker), "provider" -> Json.Str(provider)),
        "what" -> Json.Str(what))))
        .andThen(tell(provider, s"вас спросили, сделка $n: $what — ответьте «берусь $n» или «отказываюсь $n»", at))
        .andThen(tell(seeker, s"спросил, сделка $n: $what — сообщу, когда ответят", at)).as(n)
    }

  /** THE WORKER: a message routed here, its turn kept by its conversation, a deal's answer carried */
  object Edges extends App:
    /** the deal answers okay-chat takes in a conversation ("берусь 3", "отказываюсь 3"); its `withdraw` intent is a
     * listing taken down, not a deal — a seeker's withdrawal is `POST /deals/<n>/withdraw` */
    private val answers = Set("accept", "decline")

    override def fetch(r: Request): Response ! Cloud = (r.method, r.path.split('/').toList) match
      case ("POST", List("", "route")) => pure(Response.json(routeJson(Edge.route(r.body))))
      case ("POST", List("", "c", id)) =>
        val route = Edge.route(r.body)
        Clock.now[Cloud].flatMap { at =>
          val reply: Option[String] ! Cloud =
            if answers(route.intent) then answer(id, route.intent, r.body, at).map(Some(_)) else pure(None)
          reply.flatMap { said =>
            val turn = Json.Obj(Vector("text" -> Json.Str(r.body), "route" -> routeJson(route),
              "at" -> Json.Num(BigDecimal(at))) ++ said.map("reply" -> Json.Str(_)) ++
              email.findFirstIn(r.body).map("contact" -> Json.Str(_)))
            Objects.call[Cloud]("CONVERSATIONS", id, Request.json("POST", "/", turn)).map(kept =>
              Response.json(Json.Obj(Vector("route" -> routeJson(route),
                "turn" -> parsed(kept).field("turn").getOrElse(Json.Null)) ++ said.map("reply" -> Json.Str(_)))))
          }
        }
      case ("GET", List("", "c", id)) => Objects.call[Cloud]("CONVERSATIONS", id, Request("GET", "/"))
      case ("POST", List("", "deals")) => Descent.parse(r.body).toOption match
          case None => pure(Response.text("{seeker, provider, what}", 400))
          case Some(j) =>
            (Deals.text(j, "seeker"), Deals.text(j, "provider"), Deals.text(j, "what")) match
              case (Some(s), Some(p), Some(w)) => Clock.now[Cloud].flatMap(at => ask(s, p, w, at))
                  .map(n => Response.json(Json.obj("deal" -> Json.Num(BigDecimal(n)))))
              case _ => pure(Response.text("{seeker, provider, what}", 400))
      case ("GET", List("", "deals", n)) => Objects.call[Cloud]("DEALS", n, Request("GET", "/"))
      case ("POST", List("", "deals", n, transition)) => Descent.parse(r.body).toOption.flatMap(Deals.text(_, "by")) match
          case None => pure(Response.text("{by}", 400))
          case Some(by) => Clock.now[Cloud].flatMap(at => answer(by, transition, n, at)).map(Response.text(_))
      case _ => pure(Response.text("POST /route, POST /c/<id>, GET /c/<id>, POST /deals, GET /deals/<n>", 404))

  @JSExportTopLevel("worker")
  val worker: Worker = Worker(Edges, Map("CONVERSATIONS" -> Conversations, "DEALS" -> Deals, "NUMBERS" -> Numbers))
