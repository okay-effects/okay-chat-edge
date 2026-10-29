package okaychatedge

import okay.scala3.*
import okay.serverless.cloud.*

/**
 * THE BOARDS' VACANCIES IN D1, with a full-text index (FTS5: a search reads the matches, not the table — the free
 * plan's 5 million rows read a day): `vacancies` the rows, `vacancies_fts` their titles and places, accents folded
 * («wroclaw» finds «Wrocław»).
 */
object Vacancies:
  val db = "MARKET"

  private var made = false
  def schema: Unit ! Cloud =
    if made then pure(())
    else Sql.batch[Cloud](db,
      Statement.of("""create table if not exists vacancies (id text primary key, board text not null,
        title text not null, company text not null, place text not null, remote integer not null, url text not null,
        at integer not null)"""),
      Statement.of("create index if not exists vacancies_board on vacancies (board)"),
      Statement.of("""create virtual table if not exists vacancies_fts using fts5(title, place, vid unindexed,
        tokenize = 'unicode61 remove_diacritics 2')""")).as { made = true }

  /** a board's vacancies kept: the new inserted, the gone deleted — whether the board was kept before, and the new */
  def keep(board: String, now: Vector[Kept], most: Int = Int.MaxValue): (Boolean, Vector[Kept]) ! Cloud =
    schema.andThen(Sql.query[Cloud](db, "select id from vacancies where board = ?", board)).flatMap { rows =>
      val had = rows.flatMap(_.get("id").collect { case SqlValue.Text(s) => s }).toSet
      val ids = now.map(_.id).toSet
      val fresh = now.filterNot(k => had(k.id)).distinctBy(_.id).take(most)
      val gone = had.diff(ids).toVector
      val at = System.currentTimeMillis()
      val inserts = fresh.flatMap(k => Vector(
        Statement.of("insert or ignore into vacancies (id, board, title, company, place, remote, url, at) " +
          "values (?, ?, ?, ?, ?, ?, ?, ?)", k.id, board, k.title, k.company, k.place, if k.remote then 1 else 0,
          k.url, at),
        Statement.of("insert into vacancies_fts (title, place, vid) values (?, ?, ?)", k.title, k.place, k.id)))
      val deletes = gone.flatMap(id => Vector(Statement.of("delete from vacancies where id = ?", id),
        Statement.of("delete from vacancies_fts where vid = ?", id)))
      val all = inserts ++ deletes
      // D1 takes a batch whole; a hundred statements a batch
      all.grouped(100).foldLeft(pure[Unit, Cloud](()))((p, g) => p.andThen(Sql.batch[Cloud](db, g*).as(())))
        .as((had.nonEmpty, fresh))
    }

  private def terms(s: String): Vector[String] =
    s.toLowerCase.split("[^\\p{L}\\p{N}]+").toVector.filter(_.nonEmpty)

  /** the boards' vacancies for an ask: every word of it in the title, the place in the place, the newest first */
  /** how many of the boards' a search reads */
  val most = 50

  def search(a: JobAsk, limit: Int = most): Vector[Vacancy] ! Cloud =
    val words = terms(a.query)
    if words.isEmpty then pure(Vector.empty)
    else
      val m = (words.map(w => s"title:$w*") ++ a.location.toVector.flatMap(terms).map(w => s"place:$w*"))
        .mkString(" AND ")
      schema.andThen(Sql.query[Cloud](db,
        "select v.title, v.company, v.place, v.remote, v.url from vacancies_fts f join vacancies v on v.id = f.vid " +
          s"where vacancies_fts match ?${if a.remote then " and v.remote = 1" else ""} order by v.at desc limit ?",
        m, limit)).map(_.toVector.flatMap { r =>
        def t(k: String) = r.get(k).collect { case SqlValue.Text(s) => s }.getOrElse("")
        Option.when(t("url").nonEmpty)(Vacancy(t("title"), t("company"), t("place"),
          if r.get("remote").contains(SqlValue.Int(1)) then "remote" else "", t("url")))
      })

  /** how many vacancies are kept, from how many boards */
  def count: (Int, Int) ! Cloud =
    schema.andThen(Sql.query[Cloud](db, "select count(*) as n, count(distinct board) as b from vacancies")).map(rs =>
      rs.headOption.map(r => (r.get("n"), r.get("b")) match
        case (Some(SqlValue.Int(n)), Some(SqlValue.Int(b))) => (n.toInt, b.toInt)
        case _ => (0, 0)).getOrElse((0, 0)))
