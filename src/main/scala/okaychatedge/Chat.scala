package okaychatedge

import okay.effects.json.{Descent, Json}
import okay.http.{Request, Response}
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.cloud.Sockets
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
  /** the intents the edge acts on itself; the rest go to the model lane (`Lane`) */
  val acted = Set("offer", "need", "ask_candidates", "accept", "decline", "listings", "requests", "help")

  /** a need with no trade in it — «ищу работу»: which work is the question, not a search */
  private val noTrade = Set("работу", "работа", "роботу", "робота", "pracy", "pracę", "praca", "job", "a job", "work")

  private val where = Set("в", "во", "у", "на", "w", "we", "na", "in", "at", "near")
  /** «работу», «работу во Вроцлаве», «pracy w Krakowie» — no trade; «работу программистом» names one */
  private def tradeless(what: String): Boolean =
    what.toLowerCase.split("[^\\p{L}]+").filter(_.nonEmpty).toList match
      case "a" :: w :: rest => tradeless((w :: rest).mkString(" "))
      case w :: rest => noTrade(w) && rest.headOption.forall(where)
      case Nil => false

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
          // an e-mail written is the address; the door's (a Telegram account) only until there is one
          val written = turn.field("contact").collect { case Json.Str(c) => c }
          val door = turn.field("door").collect { case Json.Str(c) => c }
          val shown = turn.field("shown")
          val keep = (k: String, v: Option[Json]) =>
            v.fold(pure[Unit, ActorStep](()))(j => Durable.put[Json, ActorStep](k, j))
          Durable.put[List[Json], ActorStep]("turns", (ts.getOrElse(Nil) :+ turn).takeRight(kept))
            .andThen(Durable.put[Int, ActorStep]("count", count))
            .andThen(written.fold(door.fold(pure[Unit, ActorStep](()))(d =>
              Durable.get[String, ActorStep]("contact").flatMap(c =>
                if c.isDefined then pure(()) else Durable.put[String, ActorStep]("contact", d))))(c =>
              Durable.put[String, ActorStep]("contact", c)))
            .andThen(keep("shown", shown))
            .andThen(keep("lang", turn.field("lang")))
            .andThen(keep("jobs", turn.field("jobs")))
            .andThen(keep("place", turn.field("place")))
            .andThen(keep("need", turn.field("need").orElse(shown.flatMap(_ => turn.field("text")))))
            .as(count)
        }
      })

    /** the conversation's last exchanges, as a model reads them: [role, words], the newest last */
    private def recent(ts: List[Json]): Vector[Json] =
      val said = (k: String, j: Json) => j.field(k).collect { case Json.Str(s) => s.take(Lane.turnChars) }
      ts.takeRight(Lane.turns).toVector.flatMap(t =>
        said("text", t).map("user" -> _).toVector ++ said("reply", t).orElse(said("told", t)).map("assistant" -> _))
        .takeRight(Lane.turns).map((r, s) => Json.Arr(Vector(Json.Str(r), Json.Str(s))))

    /** a turn as a page shows it: what was written, what was said — nothing of the routing, no address */
    private def shownTurn(t: Json): Json =
      Json.Obj(Vector("text", "reply", "told").flatMap(k => t.field(k).collect { case s: Json.Str => k -> s }))

    /** A PAGE'S SOCKET (a WebSocket the object holds — hibernating: an idle page costs nothing): each turn appended is
     * sent to the pages open on this conversation as it is kept, `{n, turn}` — `n` its number, by which a page
     * knows it missed one and reads the conversation again */
    private def told(n: Int, turn: Json): Int ! InActor =
      Sockets.broadcast[InActor](Json.print(Json.obj("n" -> Json.Num(BigDecimal(n)), "turn" -> shownTurn(turn))),
        Some("page")).as(n)

    def fetch(name: String, request: Request): Response ! InActor = (request.method, request.path) match
      case ("POST", "/") | ("POST", "/tell") => Descent.parse(request.body) match
          case Left(e) => pure(Response.text(s"a turn, as JSON: ${e.message}", 400))
          case Right(turn) => append(name, turn).flatMap(told(_, turn))
              .map(n => Response.json(Json.obj("turn" -> Json.Num(BigDecimal(n)))))
      case ("GET", "/socket") => Sockets.accept[InActor](request, "page")
      case ("GET", "/page") =>
        Durable.step[ActorStep, InActor, (Int, List[Json])](name)(Durable.get[Int, ActorStep]("count").flatMap(n =>
          Durable.get[List[Json], ActorStep]("turns").map(ts => (n.getOrElse(0), ts.getOrElse(Nil)))))
          .map((n, ts) => Response.json(Json.obj("count" -> Json.Num(BigDecimal(n)),
            "turns" -> Json.Arr(ts.toVector.map(shownTurn)))))
      case ("GET", "/state") =>
        Durable.step[ActorStep, InActor, Vector[(String, Option[Json])]](name)(
          Vector("shown", "need", "lang", "jobs", "place")
            .foldLeft(pure[Vector[(String, Option[Json])], ActorStep](Vector.empty))(
            (acc, k) => acc.flatMap(kv => Durable.get[Json, ActorStep](k).map(v => kv :+ (k -> v))))
            .flatMap(kv => Durable.get[List[Json], ActorStep]("turns").map(ts =>
              kv :+ ("recent" -> Some(Json.Arr(recent(ts.getOrElse(Nil))))))))
          .map(kv => Response.json(Json.Obj(kv.collect { case (k, Some(v)) => k -> v })))
      case ("GET", "/contact") =>
        Durable.step[ActorStep, InActor, Option[String]](name)(Durable.get[String, ActorStep]("contact"))
          .map(c => c.map(Response.text(_)).getOrElse(Response.text("", 404)))
      case _ =>
        Durable.step[ActorStep, InActor, List[Json]](name)(Durable.get[List[Json], ActorStep]("turns")
          .map(_.getOrElse(Nil))).map(ts => Response.json(Json.Arr(ts.toVector)))

  // ---- what the Worker asks the objects

  private[okaychatedge] def tell(party: String, text: String, at: Long): Unit ! Cloud =
    Objects.call[Cloud]("CONVERSATIONS", party, Request.json("POST", "/tell",
      Json.obj("told" -> Json.Str(text), "at" -> Json.Num(BigDecimal(at))))).andThen(Telegram.push(party, text))

  private def contactOf(party: String): Option[String] ! Cloud =
    Objects.call[Cloud]("CONVERSATIONS", party, Request("GET", "/contact"))
      .map(r => Option.when(r.status == 200 && r.body.nonEmpty)(r.body))

  private def parsed(r: Response): Json = Descent.parse(r.body).toOption.getOrElse(Json.Null)

  /** what a conversation remembers between turns: the candidates it last showed, for which need, in which language */
  final case class State(shown: Vector[String], need: String, lang: Option[Lang],
                         recent: Vector[(String, String)] = Vector.empty, jobs: Option[(JobAsk, Int)] = None,
                         place: Option[String] = None)

  private def stateOf(party: String): State ! Cloud =
    Objects.call[Cloud]("CONVERSATIONS", party, Request("GET", "/state")).map(parsed).map { j =>
      State(j.field("shown").collect { case Json.Arr(xs) => xs.collect { case Json.Str(p) => p } }
          .getOrElse(Vector.empty),
        j.field("need").collect { case Json.Str(t) => t }.getOrElse(""),
        j.field("lang").collect { case Json.Str(c) => c }.flatMap(Lang.parse),
        j.field("recent").collect { case Json.Arr(xs) => xs.collect {
          case Json.Arr(Vector(Json.Str(r), Json.Str(s))) => (r, s) } }.getOrElse(Vector.empty),
        j.field("jobs").flatMap(Jobs.fromJson),
        j.field("place").collect { case Json.Str(p) => p })
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
  final case class Said(reply: String, shown: Option[Vector[String]] = None, jobs: Option[Json] = None)

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
                  "at" -> Json.Num(BigDecimal(at)))))
                  .andThen(Telegram.push(w.party, Phrases.appeared(lw, text, w.text)))))))
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

    /**
     * ONE MESSAGE IN A CONVERSATION — whichever door it came through (the API, Telegram): routed, answered in its
     * language, kept as a turn. `contact` is an address the door itself proves (a Telegram account); an e-mail in the
     * text is one too. The route, the reply if any, the turn's number
     */
    def turn(id: String, text: String, contact: Option[String], at: Long, near: Option[String] = None)
      : (Route, Option[Said], Json, Lang) ! Cloud =
      val route = Edge.route(text)
      stateOf(id).flatMap { state =>
        // okay-chat's: the message's own language if it shows one, else what this person wrote last
        val l = Lang.answer(text, state.lang)
        val reply: Option[Said] ! Cloud =
          // vacancies: «хватит вакансий»; a search; «ещё» and «запомни» after one
          if Jobs.stops(text) then Asks.forget(id).map(n => Some(Said(Phrases.jobsStopped(l, n))))
          // a search's place, not said: the last one this person named, or where they are (a page's country)
          else if Jobs.asked(text, route.intent) then Jobs.answer(text, l, at, state.place.orElse(near)).map((t, a) =>
            Some(Said(t, jobs = a.map(Jobs.json(_, 0)))))
          else if state.jobs.isDefined && Jobs.more(text, route.intent) then
            val (a, from) = state.jobs.get
            Jobs.shown(a, l, from + Jobs.page).map(t => Some(Said(t, jobs = Some(Jobs.json(a, from + Jobs.page)))))
          else if state.jobs.isDefined && route.intent == "remember" then
            val (a, _) = state.jobs.get
            Asks.remember(id, a, l, at).map(ok =>
              Some(Said(if ok then Phrases.jobsRemembered(l, Phrases.query(l, a)) else Phrases.jobsTooMany(l))))
          else route.intent match
          case i if answers(i) => answer(id, i, text, at, l).map(t => Some(Said(t)))
          case "need" if tradeless(Embed.what(Edge.slot("need", "what", text), text)) =>
            unclear(text, l, state.recent, at).map(Some(_))
          case "offer" | "need" => listed(id, route.intent, text, at, l).map(Some(_))
          case "help" => pure(Some(Said(Phrases.help(l, Edge.help(l)))))
          case "listings" => Market.mine(id, "offer").map(ms =>
            Some(Said(if ms.isEmpty then Phrases.noOffers(l) else Phrases.offers(l, ms))))
          case "requests" => Market.mine(id, "need").map(ms =>
            Some(Said(if ms.isEmpty then Phrases.noRequests(l) else Phrases.requests(l, ms))))
          case "ask_candidates" => askShown(id, state, text, at, l).map(Some(_))
          case _ => unclear(text, l, state.recent, at).map(Some(_))
        reply.flatMap { said =>
          val kept = Json.Obj(Vector("text" -> Json.Str(text), "route" -> routeJson(route),
            "lang" -> Json.Str(Lang.code(l)), "at" -> Json.Num(BigDecimal(at))) ++
            said.map(s => "reply" -> Json.Str(s.reply)) ++
            said.flatMap(_.shown).map(sh => "shown" -> Json.Arr(sh.map(Json.Str(_)))) ++
            said.flatMap(_.jobs).map("jobs" -> _) ++
            Jobs.placeIn(text).map(p => "place" -> Json.Str(p)) ++
            email.findFirstIn(text).map("contact" -> Json.Str(_)) ++ contact.map("door" -> Json.Str(_)))
          Objects.call[Cloud]("CONVERSATIONS", id, Request.json("POST", "/", kept)).map(k =>
            (route, said, parsed(k).field("turn").getOrElse(Json.Null), l))
        }
      }

    /** what the edge does not act on: the model lane, told the help, the market and the conversation so far — within
     * the day's budget; else okay-chat's canned line */
    private def unclear(text: String, l: Lang, recent: Vector[(String, String)], at: Long): Said ! Cloud =
      val help = Edge.help(l)
      val canned = Said(Phrases.smallTalk(l) + Phrases.help(l, help))
      Lane.model.flatMap {
        case None => pure(canned)
        case Some(m) => Budget.take(at).flatMap(granted =>
          if !granted then pure(canned)
          else Market.newest(5).flatMap(market => Lane.reply(m, text, l, help, market, recent))
            .map(_.fold(canned)(Said(_))))
      }

    /** a conversation the open API may speak as: a page's (`web:` and 32 hex digits, unguessable) — never a
     * Telegram one (its id is a number anyone can try); any other only where `OPEN_API` is set (tests, local) */
    private val web = "^web:[0-9a-f]{32}$".r
    private def open: Boolean ! Cloud = Env.variable[Cloud]("OPEN_API").map(_.exists(_.trim.nonEmpty))
    private def speaksAs(id: String): Boolean ! Cloud =
      if id.startsWith(Telegram.prefix) then pure(false)
      else if web.matches(id) then pure(true)
      else open
    private val refused = Response.text("not this conversation", 403)

    /** the cron's tick: the boards imported one a tick, a remembered search asked again */
    override def scheduled(event: Scheduled): Unit ! Cloud = Boards.tick(event.time)

    override def fetch(r: Request): Response ! Cloud = (r.method, r.path.split('/').toList) match
      case (_, "" :: "c" :: id :: _) => speaksAs(id).flatMap(ok => if ok then conversation(r, id) else pure(refused))
      case (_, "" :: "deals" :: _) => open.flatMap(ok => if ok then deals(r) else pure(refused))
      case ("POST", List("", "route")) => pure(Response.json(routeJson(Edge.route(r.body))))
      case ("POST", List("", "telegram")) => Telegram.update(r)
      // how many vacancies are kept, from how many boards
      case ("GET", List("", "vacancies")) => Vacancies.count.map((n, b) =>
        Response.json(Json.obj("vacancies" -> Json.Num(BigDecimal(n)), "boards" -> Json.Num(BigDecimal(b)))))
      case _ => pure(Response.text("POST /c/<id>, GET /c/<id>, POST /route", 404))

    private def conversation(r: Request, id: String): Response ! Cloud = (r.method, r.path.split('/').toList) match
      case ("POST", List("", "c", _)) =>
        // where the person is: the country Cloudflare reads off the request (entry.js puts it as `x-okay-country`)
        Clock.now[Cloud].flatMap(at => turn(id, r.body, None, at, r.header("x-okay-country").filter(_.nonEmpty)))
          .map((route, said, n, _) =>
          Response.json(Json.Obj(Vector("route" -> routeJson(route), "turn" -> n) ++
            said.map(s => "reply" -> Json.Str(s.reply)))))
      case ("GET", List("", "c", _)) => Objects.call[Cloud]("CONVERSATIONS", id, Request("GET", "/"))
      // the page's reading: the turns as shown, and how many there have been
      case ("GET", List("", "c", _, "page")) => Objects.call[Cloud]("CONVERSATIONS", id, Request("GET", "/page"))
      case _ => pure(Response.text("POST /c/<id>, GET /c/<id>", 404))

    private def deals(r: Request): Response ! Cloud = (r.method, r.path.split('/').toList) match
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
      case _ => pure(Response.text("POST /deals, GET /deals/<n>, POST /deals/<n>/<transition>", 404))

  @JSExportTopLevel("worker")
  val worker: Worker = Worker(Edges, Map("CONVERSATIONS" -> Conversations, "DEALS" -> Deals, "NUMBERS" -> Numbers,
    "BUDGET" -> Budget.Days))
