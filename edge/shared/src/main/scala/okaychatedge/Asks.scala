package okaychatedge

import okay.scala3.*
import okay.serverless.cloud.*

/**
 * SEARCHES REMEMBERED — «запомни» after a vacancy search: the search kept (D1, at most five a person), and each new
 * vacancy that answers it told to the person, in their language, by whichever door they came (Telegram's bot, the
 * page's socket): from the boards as they are imported, and from Workable, each remembered search asked again in turn
 * (one a tick) for what was posted since it was last asked. «хватит вакансий» forgets them all.
 */
object Asks:
  val db = "MARKET"
  val most = 5
  /** at most this many new vacancies in one message: the rest wait for the person's own search */
  val told = 3

  private var made = false
  private def schema: Unit ! Cloud =
    if made then pure(())
    else Sql.run[Cloud](db, """create table if not exists job_asks (party text not null, query text not null,
      location text not null, remote integer not null, lang text not null, checked integer not null,
      primary key (party, query, location, remote))""").as { made = true }

  final case class Remembered(party: String, ask: JobAsk, lang: Lang, checked: Long)

  private def rows(rs: List[Row]): Vector[Remembered] = rs.toVector.flatMap { r =>
    def t(k: String) = r.get(k).collect { case SqlValue.Text(s) => s }
    def n(k: String) = r.get(k).collect { case SqlValue.Int(v) => v }
    for p <- t("party"); q <- t("query"); loc <- t("location"); l <- t("lang").flatMap(Lang.parse); c <- n("checked")
    yield Remembered(p, JobAsk(q, Option(loc).filter(_.nonEmpty), n("remote").contains(1L)), l, c)
  }

  /** a search kept for a person — false: they have the most already */
  def remember(party: String, a: JobAsk, l: Lang, at: Long): Boolean ! Cloud =
    schema.andThen(Sql.query[Cloud](db, "select count(*) as n from job_asks where party = ?", party)).flatMap { rs =>
      val n = rs.headOption.flatMap(_.get("n")).collect { case SqlValue.Int(v) => v }.getOrElse(0L)
      if n >= most then pure(false)
      else Sql.run[Cloud](db, "insert or replace into job_asks (party, query, location, remote, lang, checked) " +
        "values (?, ?, ?, ?, ?, ?)", party, a.query, a.location.getOrElse(""), if a.remote then 1 else 0,
        Lang.code(l), at).as(true)
    }

  /** a person's searches forgotten: how many there were */
  def forget(party: String): Int ! Cloud =
    schema.andThen(Sql.run[Cloud](db, "delete from job_asks where party = ?", party)).map(_.changes.toInt)

  private def folded(s: String): String = Text.fold(s)

  /** whether a vacancy answers a search: each of its words starts a word of the title, its place is in the place */
  def answers(a: JobAsk, k: Kept): Boolean =
    val title = folded(k.title).split("[^\\p{L}\\p{N}]+").filter(_.nonEmpty)
    a.alternatives.exists(q => folded(q).split("[^\\p{L}\\p{N}]+").filter(_.nonEmpty)
      .forall(w => title.exists(Vacancies.matches(w, _)))) &&
      a.location.forall(l => folded(k.place).contains(folded(l))) && (!a.remote || k.remote)

  private def line(v: Vacancy): String =
    v.title + Vector(v.company, v.place, v.workplace).filter(_.nonEmpty).mkString(" — ", ", ", "") + s"\n${v.url}"

  private def tell(r: Remembered, vs: Vector[Vacancy], at: Long): Unit ! Cloud =
    if vs.isEmpty then pure(())
    else Chat.tell(r.party, Phrases.newJobs(r.lang, Phrases.query(r.lang, r.ask), vs.take(told).map(line)), at)

  /** the boards' new vacancies told to the searches they answer */
  def tellNew(fresh: Vector[Kept], at: Long): Unit ! Cloud =
    schema.andThen(Sql.query[Cloud](db, "select * from job_asks limit 1000")).flatMap(rs =>
      rows(rs).foldLeft(pure[Unit, Cloud](()))((p, r) => p.andThen(tell(r, fresh.filter(answers(r.ask, _)).map(k =>
        Vacancy(k.title, k.company, k.place, if k.remote then "remote" else "", k.url)), at))))

  /** the search asked longest ago, asked of Workable again: what was posted since told */
  def recheck(at: Long): Unit ! Cloud =
    schema.andThen(Sql.query[Cloud](db, "select * from job_asks order by checked limit 1")).flatMap(rs =>
      rows(rs).headOption.fold(pure[Unit, Cloud](()))(r =>
        Jobs.search(r.ask).flatMap {
          case Right((_, vs)) => tell(r, vs.filter(_.created > r.checked), at)
          case Left(_) => pure(())
        }.andThen(Sql.run[Cloud](db, "update job_asks set checked = ? where party = ? and query = ? and " +
          "location = ? and remote = ?", at, r.party, r.ask.query, r.ask.location.getOrElse(""),
          if r.ask.remote then 1 else 0).as(()))))
