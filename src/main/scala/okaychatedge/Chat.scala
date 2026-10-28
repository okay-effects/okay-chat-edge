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
          val shown = turn.field("shown")
          val keep = (k: String, v: Option[Json]) =>
            v.fold(pure[Unit, ActorStep](()))(j => Durable.put[Json, ActorStep](k, j))
          Durable.put[List[Json], ActorStep]("turns", (ts.getOrElse(Nil) :+ turn).takeRight(kept))
            .andThen(Durable.put[Int, ActorStep]("count", count))
            .andThen(contact.fold(pure[Unit, ActorStep](()))(c => Durable.put[String, ActorStep]("contact", c)))
            .andThen(keep("shown", shown))
            .andThen(keep("lang", turn.field("lang")))
            .andThen(keep("need", turn.field("need").orElse(shown.flatMap(_ => turn.field("text")))))
            .as(count)
        }
      })

    def fetch(name: String, request: Request): Response ! InActor = (request.method, request.path) match
      case ("POST", "/") | ("POST", "/tell") => Descent.parse(request.body) match
          case Left(e) => pure(Response.text(s"a turn, as JSON: ${e.message}", 400))
          case Right(turn) => append(name, turn).map(n => Response.json(Json.obj("turn" -> Json.Num(BigDecimal(n)))))
      case ("GET", "/state") =>
        Durable.step[ActorStep, InActor, Vector[(String, Option[Json])]](name)(
          Vector("shown", "need", "lang").foldLeft(pure[Vector[(String, Option[Json])], ActorStep](Vector.empty))(
            (acc, k) => acc.flatMap(kv => Durable.get[Json, ActorStep](k).map(v => kv :+ (k -> v)))))
          .map(kv => Response.json(Json.Obj(kv.collect { case (k, Some(v)) => k -> v })))
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

  /** what a conversation remembers between turns: the candidates it last showed, for which need, in which language */
  final case class State(shown: Vector[String], need: String, lang: Option[Lang])

  private def stateOf(party: String): State ! Cloud =
    Objects.call[Cloud]("CONVERSATIONS", party, Request("GET", "/state")).map(parsed).map { j =>
      State(j.field("shown").collect { case Json.Arr(xs) => xs.collect { case Json.Str(p) => p } }
          .getOrElse(Vector.empty),
        j.field("need").collect { case Json.Str(t) => t }.getOrElse(""),
        j.field("lang").collect { case Json.Str(c) => c }.flatMap(Lang.parse))
    }

  /** the language a party is told in: the one it wrote last */
  private def langOf(party: String): Lang ! Cloud = stateOf(party).map(_.lang.getOrElse(Lang.default))

  /**
   * A DEAL ANSWERED from a conversation — "берусь 3", "отказываюсь 3": the transition asked of deal 3 by this party,
   * as okay-chat answers it: on acceptance each side gets the other's address (the scenario's unlocks) and the other
   * side is told the deal bound, in its own language; a refusal is told to nobody (okay-chat's). The reply to the one
   * who answered. A transition the chat does not take (`withdraw`, through the API) is told as the scenario words it.
   */
  private def answer(me: String, transition: String, text: String, at: Long, l: Lang): String ! Cloud =
    number.findFirstIn(text) match
      case None => pure(Phrases.notYourDeal(l))
      case Some(n) =>
        Objects.call[Cloud]("DEALS", n, Request.json("POST", "/advance", Json.obj("transition" -> Json.Str(transition),
          "by" -> Json.Str(me), "at" -> Json.Num(BigDecimal(at))))).map(parsed).flatMap { j =>
          j.field("flow").map(Deals.read) match
            case None => pure(Phrases.notYourDeal(l))
            case Some(flow) =>
              val pairs = (k: String) => j.field(k).collect { case Json.Arr(xs) => xs.collect {
                case Json.Arr(Vector(Json.Str(a), Json.Str(b))) => (a, b) } }.getOrElse(Vector.empty)
              val others = flow.parties.values.filter(_ != me).toVector.distinct
              // an unlock of `contact` for a viewer: the OTHER parties' addresses, to the viewer
              val sees = pairs("unlocks").collect { case (viewer, "contact") => flow.parties.get(viewer) }.flatten.toSet
              val contacts = (ps: Vector[String]) => ps.foldLeft(pure[Vector[String], Cloud](Vector.empty))((acc, o) =>
                acc.flatMap(cs => contactOf(o).map(cs ++ _)))
              val id = n.toLong
              transition match
                case "accept" =>
                  others.foldLeft(pure[Unit, Cloud](()))((p, o) => p.andThen(
                    langOf(o).flatMap(lo => (if sees(o) then contacts(Vector(me)) else pure(Vector.empty))
                      .flatMap(mine => tell(o, Phrases.dealBound(lo, id, flow.what, mine.mkString(", ")), at)))))
                    .andThen(if sees(me) then contacts(others) else pure(Vector.empty))
                    .map(cs => Phrases.accepted(l, id, cs.mkString(", ")))
                case "decline" => pure(Phrases.declined(l, id))
                case _ =>
                  pairs("notifies").foldLeft(pure[Unit, Cloud](()))((p, rs) => p.andThen(
                    flow.parties.get(rs._1).filter(_ != me)
                      .fold(pure[Unit, Cloud](()))(o => tell(o, s"$n: ${rs._2}", at))))
                    .as(s"$n: ${flow.state}")
        }

  /** what a turn says besides its reply: the candidates it showed, kept by the conversation for «спроси 1 2» */
  final case class Said(reply: String, shown: Option[Vector[String]] = None)

  /** an offer kept in the market, and the needs already waiting for it told; a need kept, and answered with the
   * closest offers, numbered — each in okay-chat's words, in the language of the one it is said to */
  private def listed(me: String, side: String, text: String, at: Long, l: Lang): Said ! Cloud =
    val what = Embed.what(Edge.slot(side, "what", text), text)
    Embed.space.flatMap(space => Embed(space, what).flatMap {
      case None => pure(Said(Phrases.searchFirst(l)))
      case Some(v) =>
        Market.add(me, side, text, what, space, v, at).andThen(
          if side == "offer" then
            // the needs already waiting for it: each told, its «спроси 1» this offer
            Market.waiting(me, space, v).flatMap(ws => ws.foldLeft(pure[Unit, Cloud](()))((p, w) => p.andThen(
              langOf(w.party).flatMap(lw => Objects.call[Cloud]("CONVERSATIONS", w.party, Request.json("POST", "/tell",
                Json.obj("told" -> Json.Str(Phrases.appeared(lw, text, w.text)),
                  "shown" -> Json.Arr(Vector(Json.Str(me))), "need" -> Json.Str(w.text),
                  "at" -> Json.Num(BigDecimal(at))))).as(())))))
              .as(Said(Phrases.storedOffer(l, what)))
          else Market.candidates(me, space, v).map { cs =>
            if cs.isEmpty then Said(Phrases.nobodyYet(l), Some(Vector.empty))
            else Said(Phrases.found(l, cs.size, cs.zipWithIndex.map((c, i) => s"${i + 1}. ${c.text}").mkString("\n")),
              Some(cs.map(_.party).toVector))
          })
    })

  private val all = """(?iu)(?:^|\s)(всех|все|усіх|всіх|wszystkich|all)(?:\s|$)""".r

  /** «спроси 1 2», «спроси всех»: the conversation's last shown candidates asked — a deal each, for its last need */
  private def askShown(me: String, state: State, text: String, at: Long, l: Lang): Said ! Cloud =
    val picked =
      if all.findFirstIn(text).isDefined then state.shown
      else number.findAllIn(text).map(_.toInt).toVector.distinct.flatMap(i => state.shown.lift(i - 1))
    if state.shown.isEmpty then pure(Said(Phrases.searchFirst(l)))
    else if picked.isEmpty then pure(Said(Phrases.whichOnes(l)))
    else picked.foldLeft(pure[Vector[Long], Cloud](Vector.empty))((acc, p) =>
      acc.flatMap(ns => ask(me, p, state.need, at).map(ns :+ _)))
      .map(ns => Said(Phrases.asked(l, ns.size, ns.size)))

  /** a deal asked: its number from `Numbers`, the deal made, the provider told how to answer — in its language (the
   * seeker is answered by the caller: deal numbers are not the seeker's to know) */
  private def ask(seeker: String, provider: String, what: String, at: Long): Long ! Cloud =
    Objects.call[Cloud]("NUMBERS", "deals", Request("POST", "/")).map(_.body.trim.toLong).flatMap { n =>
      Objects.call[Cloud]("DEALS", n.toString, Request.json("POST", "/create", Json.obj("scenario" -> Json.Str("deal"),
        "parties" -> Json.obj("seeker" -> Json.Str(seeker), "provider" -> Json.Str(provider)),
        "what" -> Json.Str(what))))
        .andThen(langOf(provider).flatMap(lp => tell(provider, Phrases.youWereAsked(lp, n, what), at))).as(n)
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
        Clock.now[Cloud].flatMap(at => stateOf(id).flatMap { state =>
          // okay-chat's: the message's own language if it shows one, else what this person wrote last
          val l = Lang.of(r.body).orElse(state.lang).getOrElse(Lang.default)
          val reply: Option[Said] ! Cloud = route.intent match
            case i if answers(i) => answer(id, i, r.body, at, l).map(t => Some(Said(t)))
            case "offer" | "need" => listed(id, route.intent, r.body, at, l).map(Some(_))
            case "ask_candidates" => askShown(id, state, r.body, at, l).map(Some(_))
            case _ => pure(None)
          reply.flatMap { said =>
            val turn = Json.Obj(Vector("text" -> Json.Str(r.body), "route" -> routeJson(route),
              "lang" -> Json.Str(Lang.code(l)), "at" -> Json.Num(BigDecimal(at))) ++
              said.map(s => "reply" -> Json.Str(s.reply)) ++
              said.flatMap(_.shown).map(sh => "shown" -> Json.Arr(sh.map(Json.Str(_)))) ++
              email.findFirstIn(r.body).map("contact" -> Json.Str(_)))
            Objects.call[Cloud]("CONVERSATIONS", id, Request.json("POST", "/", turn)).map(kept =>
              Response.json(Json.Obj(Vector("route" -> routeJson(route),
                "turn" -> parsed(kept).field("turn").getOrElse(Json.Null)) ++
                said.map(s => "reply" -> Json.Str(s.reply)))))
          }
        })
      case ("GET", List("", "c", id)) => Objects.call[Cloud]("CONVERSATIONS", id, Request("GET", "/"))
      case ("POST", List("", "deals")) => Descent.parse(r.body).toOption match
          case None => pure(Response.text("{seeker, provider, what}", 400))
          case Some(j) =>
            (Deals.text(j, "seeker"), Deals.text(j, "provider"), Deals.text(j, "what")) match
              case (Some(s), Some(p), Some(w)) => Clock.now[Cloud].flatMap(at => ask(s, p, w, at)
                  .flatMap(n => langOf(s).flatMap(ls => tell(s, Phrases.asked(ls, 1, 1), at)).as(n)))
                  .map(n => Response.json(Json.obj("deal" -> Json.Num(BigDecimal(n)))))
              case _ => pure(Response.text("{seeker, provider, what}", 400))
      case ("GET", List("", "deals", n)) => Objects.call[Cloud]("DEALS", n, Request("GET", "/"))
      case ("POST", List("", "deals", n, transition)) =>
        Descent.parse(r.body).toOption.flatMap(Deals.text(_, "by")) match
          case None => pure(Response.text("{by}", 400))
          case Some(by) => Clock.now[Cloud].flatMap(at => langOf(by).flatMap(l => answer(by, transition, n, at, l)))
            .map(Response.text(_))
      case _ => pure(Response.text("POST /route, POST /c/<id>, GET /c/<id>, POST /deals, GET /deals/<n>", 404))

  @JSExportTopLevel("worker")
  val worker: Worker = Worker(Edges, Map("CONVERSATIONS" -> Conversations, "DEALS" -> Deals, "NUMBERS" -> Numbers))
