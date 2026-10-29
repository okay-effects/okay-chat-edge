package okaychatedge

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.field

/**
 * WHAT A LISTING IS ABOUT, AS A VECTOR — for matching an offer to a need, which the static table cannot do: it was
 * distilled from okay-chat's INTENT corpus and has no trades in it («сантехник» is a word it never saw). Two spaces,
 * chosen by the Worker's variable `EMBED`, and a listing keeps the name of its space so two are never compared:
 *
 *  - `ai`: Workers AI's `@cf/baai/bge-m3`, multilingual (a need in Russian meets an offer in Polish) — an encoder, not
 *    a generator: no model supplies data, as in okay-chat, which runs its own encoder in its process;
 *  - `trigrams` (the default, and what `wrangler dev` has: Workers AI is remote only): no model at all — the
 *    character trigrams of the words, hashed into 512 buckets. «сантехник» and «сантехнику» share most of theirs;
 *    across languages they share none.
 */
object Embed:
  val bgeM3 = "@cf/baai/bge-m3"

  /** the space the Worker's variable names */
  def space: String ! Cloud = Env.variable[Cloud]("EMBED").map(_.filter(_ == "ai").fold("trigrams")(_ => bgeM3))

  /** a text's vector in `space`, normalised */
  def apply(space: String, text: String): Option[Array[Float]] ! Cloud =
    if space == bgeM3 then
      Ai.run[Cloud](bgeM3, Json.obj("text" -> Json.Arr(Vector(Json.Str(text))))).map { j =>
        j.field("data").collect { case Json.Arr(rows) => rows }.flatMap(_.headOption).collect {
          case Json.Arr(xs) =>
            val ns = xs.collect { case Json.Num(n) => n.toFloat }
            val v = new Array[Float](ns.length)
            ns.indices.foreach(i => v(i) = ns(i))
            StaticTable.normalised(v)
        }
      }
    else pure(trigrams(text))

  /** the hashed character trigrams of a text's words, normalised; none for a text with no letters */
  def trigrams(text: String, dim: Int = 512): Option[Array[Float]] =
    val v = new Array[Float](dim)
    var any = false
    for w <- StaticTable.tokens(text) do
      val padded = s"#$w#"
      for i <- 0 to padded.length - 3 do
        val g = padded.substring(i, i + 3)
        // FNV-1a, 32 bits: the bucket
        var h = 0x811c9dc5
        for c <- g do h = (h ^ c) * 0x01000193
        val b = java.lang.Integer.remainderUnsigned(h, dim)
        v(b) = v(b) + 1
        any = true
    if any then Some(StaticTable.normalised(v)) else None

  private val email = """[\w.+-]+@[\w-]+(\.[\w-]+)+""".r

  /** the listing's subject: okay-chat's `what` slot (its rule's capture: what follows «ремонтирую», «нужен»), its
   * address taken out, its first clause — a city or a detail follows a comma */
  def what(slot: Option[String], text: String): String =
    val s = email.replaceAllIn(slot.getOrElse(text), "").trim
    s.split("[,;.!?]").iterator.map(_.trim).find(_.nonEmpty).getOrElse(s)
