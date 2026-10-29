package okaychatedge

import okay.http.Request
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*

/** a vacancy as kept: its id (`<board>:<the board's id>`), the board, what the chat shows, remote or not, when kept */
final case class Kept(id: String, board: String, title: String, company: String, place: String, remote: Boolean,
                      url: String)

/** an employer's board — okay-chat's job-sources.json row: its name, its kind (`greenhouse`, `ashby`, `workable`) */
final case class Board(name: String, kind: String, url: String)

/**
 * THE EMPLOYERS' OWN BOARDS, imported — okay-chat's 208 (Greenhouse, Ashby, Workable accounts: their public posting
 * APIs, specs/jobs.md), one board a cron tick (every 2 minutes; the free plan's 10 ms of CPU: the largest board,
 * 1.4 MB,
 * parses in 1.6 ms), all of them in ~7 hours. A board's vacancies are compared with the ones kept: the new inserted,
 * the gone deleted — D1's writes are the changes only. A board seen before: its new vacancies told to the searches
 * remembered that they answer (`Asks`).
 *
 * Only the vacancies where this market is (`JOBS_WHERE`, a regex on the place: Poland, Ukraine, their cities, Europe
 * and EMEA unless set): measured 2026-10-29 the 206 boards hold 24 498 vacancies, 1 540 of them here — the rest is
 * mostly remote in the US, which Workable's search finds anyway. D1's free 100 000 rows written a day: the first
 * filling ~11 000 with the index, then the changes; at most 300 new a tick, the rest on the board's next turn.
 */
object Boards:
  val every = 120000L

  /** okay-chat's rows (its job-sources.json, `{sources: [...]}`), the kinds read here */
  def parse(json: String): Vector[Board] =
    val d = J.parse(json)
    val rows = d.get("sources").getOrElse(d).arr
    rows.map(r => Board(r("name").str.getOrElse(""), r("kind").str.getOrElse(""), r("url").str.getOrElse("")))
      .filter(b => Set("greenhouse", "ashby", "workable")(b.kind))

  private def str(x: J): Option[String] =
    x.str.map(_.trim).filter(_.nonEmpty).orElse(x.num.map(n => BigDecimal(n).toBigInt.toString))
  private val remoteWord = Re("remote|zdaln|віддален|удал[её]н", "i")

  /** a board's answer read: its vacancies, as kept */
  def read(b: Board, body: String): Vector[Kept] =
    val d = J.parse(body)
    val jobs = d("jobs").arr
    val company = (if b.kind == "workable" then str(d("name")) else None).getOrElse(b.name)
    jobs.flatMap { j =>
      b.kind match
        case "greenhouse" =>
          val place = str(j("location")("name")).getOrElse("")
          for id <- str(j("id")); t <- str(j("title")); u <- str(j("absolute_url"))
          yield Kept(s"${b.name}:$id", b.name, t, str(j("company_name")).getOrElse(company), place,
            remoteWord.test(place), u)
        case "ashby" =>
          val country = str(j("address")("postalAddress")("addressCountry"))
          val place = (str(j("location")).toVector ++ country.filterNot(c => str(j("location")).exists(_.contains(c))))
            .mkString(", ")
          for id <- str(j("id")); t <- str(j("title")); u <- str(j("jobUrl"))
          yield Kept(s"${b.name}:$id", b.name, t, company, place,
            j("isRemote").bool.getOrElse(false) ||
              str(j("workplaceType")).exists(_.equalsIgnoreCase("remote")), u)
        case _ =>
          val place = Vector(str(j("city")), str(j("country"))).flatten.mkString(", ")
          for id <- str(j("shortcode")); t <- str(j("title")); u <- str(j("url"))
          yield Kept(s"${b.name}:$id", b.name, t, company, place,
            j("telecommuting").bool.getOrElse(false), u)
    }

  val defaultWhere = "poland|polska|ukrain|україн|wroc|warsz|warsaw|krak|gdań|gdan|gdyn|pozna|łódź|lodz|" +
    "katow|lublin|szczec|kyiv|kiev|київ|lviv|львів|kharkiv|odes|dnipr|emea|europe"
  val perTick = 300

  /** the boards: the Worker's (okay-chat's rows), or `JOB_SOURCES` (a test's) */
  def boards: Vector[Board] ! Cloud =
    Env.variable[Cloud]("JOB_SOURCES").map(_.filter(_.trim.nonEmpty).map(parse).getOrElse(Edge.boards))

  /** THE TICK: the board whose turn it is, read and kept; its new vacancies told; then one remembered search asked
   * of Workable again (`Asks.recheck`) */
  def tick(time: Long): Unit ! Cloud =
    boards.flatMap { bs =>
      if bs.isEmpty then pure(())
      else
        val b = bs(((time / every) % bs.size).toInt)
        Fetch.get[Cloud](b.url).flatMap { r =>
          if r.status != 200 then pure(())
          else Env.variable[Cloud]("JOBS_WHERE").flatMap { w =>
            val here = Re(w.filter(_.trim.nonEmpty).getOrElse(defaultWhere), "iu")
            Vacancies.keep(b.name, read(b, r.body).filter(k => here.test(k.place)), perTick)
          }.flatMap { (seenBefore, fresh) =>
            if seenBefore && fresh.nonEmpty then Asks.tellNew(fresh, time) else pure(())
          }
        }
    }.andThen(Asks.recheck(time))
