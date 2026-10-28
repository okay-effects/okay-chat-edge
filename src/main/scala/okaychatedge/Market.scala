package okaychatedge

import okay.http.Bytes
import okay.scala3.*
import okay.serverless.cloud.*
import scala.scalajs.js.typedarray.*

/** a listing found for a need: whose, what it says, how close */
final case class Candidate(party: String, text: String, score: Double)

/**
 * THE MARKET in D1 (the free plan: 5 GB, millions of rows read a day): every offer and need a listing — whose, which
 * side, its words, its subject (okay-chat's `what` slot), and its subject's vector (`Embed`, in the space it names),
 * kept as signed bytes (the normalised vector times 127).
 * A need's candidates are the others' offers, ranked HERE by the same vectors (Vectorize is not on the free plan): a
 * thousand offers are a quarter of a million multiply-adds, well under a millisecond.
 */
object Market:
  val db = "MARKET"
  /** how many offers a need is ranked against: the newest */
  val window = 1000
  /** below this a listing is no candidate (the trigrams' measure: unrelated subjects sit under 0.1) */
  val floor = 0.3

  private var made = false
  private def schema: Unit ! Cloud =
    if made then pure(())
    else Sql.run[Cloud](db, """create table if not exists listings (id integer primary key, party text not null,
      side text not null, text text not null, what text not null, space text not null, vec blob not null,
      at integer not null)""").as { made = true }

  def quantised(v: Float32Array): Bytes =
    Bytes(Array.tabulate(v.length)(i => math.max(-127, math.min(127, math.round(v(i) * 127)).toInt).toByte))

  private def cosine(v: Float32Array, b: Array[Byte]): Double =
    var s = 0.0; var nb = 0.0; var i = 0
    while i < v.length && i < b.length do
      s += v(i) * b(i); nb += b(i).toDouble * b(i); i += 1
    if nb == 0 then 0.0 else s / math.sqrt(nb)

  /** a listing kept: its words, its subject, its subject's vector and the space it is in */
  def add(party: String, side: String, text: String, what: String, space: String, v: Float32Array, at: Long)
    : Unit ! Cloud =
    schema.andThen(Sql.run[Cloud](db,
      "insert into listings (party, side, text, what, space, vec, at) values (?, ?, ?, ?, ?, ?, ?)",
      party, side, text, what, space, quantised(v), at)).as(())

  /** the others' offers closest to a need, the best of each party, best first */
  def candidates(me: String, space: String, need: Float32Array, k: Int = 3): List[Candidate] ! Cloud =
    schema.andThen(Sql.query[Cloud](db,
      "select party, text, vec from listings where side = 'offer' and space = ? and party <> ? order by id desc limit ?",
      space, me, window)).map { rows =>
      rows.flatMap { r =>
        (r.get("party"), r.get("text"), r.get("vec")) match
          case (Some(SqlValue.Text(p)), Some(SqlValue.Text(t)), Some(SqlValue.Blob(b))) =>
            Some(Candidate(p, t, cosine(need, b.toArray)))
          case _ => None
      }.filter(_.score >= floor).groupBy(_.party).values.map(_.maxBy(_.score)).toList.sortBy(-_.score).take(k)
    }
