package okaychatedge.tools

import okaychatedge.*
import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.*

/**
 * WHAT RUNS AT BUILD TIME, not in the Worker: the probe's fit, and the measures (`bench/`). Its own module, so the
 * Worker carries none of it — Java's regexes above all, linked only to check the rules' translation against them.
 */
@JSExportTopLevel("tools")
object Tools:

  /** THE BUILD: the probe fitted on the semantic intents' examples, as JSON for the Worker */
  @JSExport
  def fit(static: ArrayBuffer, built: String, corpus: String): String =
    js.JSON.stringify(Router.fit(new StaticTable(new Safetensors(static)), built, corpus).toJson)

  /**
   * THE VECTOR LAYER MEASURED as okay-chat's TestStaticTier measures it: fitted on `pool`, scored on the held-out
   * `test` — okay's probe, and two simpler readings beside it (the nearest centroid, the nearest example)
   */
  @JSExport
  def evaluate(static: ArrayBuffer, pool: js.Array[js.Array[String]], test: js.Array[js.Array[String]]): js.Dynamic =
    val t = new StaticTable(new Safetensors(static))
    val p = pool.toVector.flatMap(a => t.encode(a(0)).map(_ -> a(1)))
    val probe = Probe.train(p)
    val centroids = p.groupBy(_._2).toVector.map { (c, rows) =>
      val acc = new Float32Array(t.dim)
      for (v, _) <- rows do { var i = 0; while i < acc.length do { acc(i) = acc(i) + v(i); i += 1 } }
      c -> StaticTable.normalised(acc)
    }
    var byProbe = 0; var byCentroid = 0; var byNearest = 0
    for a <- test do
      t.encode(a(0)).foreach { v =>
        if probe.ranked(v).head._1 == a(1) then byProbe += 1
        if centroids.maxBy((_, c) => StaticTable.dot(v, c))._1 == a(1) then byCentroid += 1
        if p.maxBy((u, _) => StaticTable.dot(u, v))._2 == a(1) then byNearest += 1
      }
    def pc(n: Int) = n * 100.0 / test.length
    js.Dynamic.literal(test = test.length, probe = pc(byProbe), centroid = pc(byCentroid), nearest = pc(byNearest))

  /** two texts' cosine by the static table, and by the market's quantised form */
  @JSExport
  def similarity(static: ArrayBuffer, a: String, b: String): js.Dynamic =
    val t = new StaticTable(new Safetensors(static))
    (t.encode(a), t.encode(b)) match
      case (Some(x), Some(y)) =>
        val q = Market.quantised(y).toArray
        var s = 0.0; var n = 0.0; var i = 0
        while i < x.length do { s += x(i) * q(i); n += q(i).toDouble * q(i); i += 1 }
        js.Dynamic.literal(cosine = StaticTable.dot(x, y), quantised = s / math.sqrt(n))
      case _ => js.Dynamic.literal(cosine = Double.NaN)

  /** two subjects' cosine by the trigrams */
  @JSExport
  def trigramSimilarity(a: String, b: String): Double =
    (Embed.trigrams(a), Embed.trigrams(b)) match
      case (Some(x), Some(y)) => StaticTable.dot(x, y)
      case _ => Double.NaN

  @JSExport
  def compileAll(rules: js.Array[String]): Int = rules.toVector.map(Rules.compile).size

  /** the translation checked: each rule on each message, by Java's semantics (Scala.js's java.util.regex) and by the
   * native regex — the pairs that disagree */
  @JSExport
  def disagreements(rules: js.Array[String], messages: js.Array[String]): js.Array[String] =
    val out = js.Array[String]()
    for r <- rules do
      val viaJava = java.util.regex.Pattern.compile(r)
      val native = Rules.compile(r)
      for m <- messages do
        if viaJava.matcher(m).find() != native.test(m) then out.push(s"$r ⟂ $m")
    out
