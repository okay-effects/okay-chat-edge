package okaychatedge

import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/** where a message went, and why */
final case class Route(intent: String, by: String, probability: Double = 0.0, margin: Double = 0.0)

/**
 * ONE MESSAGE IN, ONE INTENT OUT, as okay-chat routes it: the RULES first (okay-chat's `intents.json`, built by its
 * catalogue), then — for the intents read by meaning — the static table's embedding and okay's PROBE over it.
 */
final class Router(table: StaticTable, rules: Vector[(String, js.RegExp)], probe: Probe):

  def route(text: String): Route =
    rules.find((_, p) => p.test(text)) match
      case Some((intent, _)) => Route(intent, "rule")
      case None => table.encode(text).map(byMeaning).getOrElse(Route("?", "unknown words"))

  def byMeaning(v: Float32Array): Route =
    val r = probe.ranked(v)
    Route(r.head._1, "probe", r.head._2, r.head._2 - r.lift(1).map(_._2).getOrElse(0.0))

object Router:
  /** the router: the static table, okay-chat's built `intents.json` (its rules), the probe's weights (`fit`) */
  def apply(table: StaticTable, built: String, probe: Probe): Router =
    val rules = for
      i <- intents(built)
      r <- i.rules.asInstanceOf[js.UndefOr[js.Array[String]]].map(_.toVector).getOrElse(Vector.empty)
    yield (i.name.asInstanceOf[String], Rules.compile(r))
    new Router(table, rules, probe)

  private def intents(built: String): Vector[js.Dynamic] =
    js.JSON.parse(built).intents.asInstanceOf[js.Array[js.Dynamic]].toVector

  /** the intents read by meaning: `semantic` not false */
  def semantic(built: String): Set[String] =
    intents(built).filter(i => js.isUndefined(i.semantic) || i.semantic.asInstanceOf[Boolean])
      .map(_.name.asInstanceOf[String]).toSet

  /** the authored examples of the corpus: (text, intent) */
  def examples(corpus: String): Vector[(String, String)] =
    for
      i <- js.JSON.parse(corpus).intents.asInstanceOf[js.Array[js.Dynamic]].toVector
      t <- texts(i.examples)
    yield (t, i.name.asInstanceOf[String])

  private def texts(ex: js.Dynamic): Vector[String] =
    if js.isUndefined(ex) then Vector.empty
    else if js.Array.isArray(ex) then ex.asInstanceOf[js.Array[String]].toVector
    else ex.asInstanceOf[js.Dictionary[js.Array[String]]].values.flatMap(_.toVector).toVector

  /** the probe fitted on the semantic intents' examples */
  def fit(table: StaticTable, built: String, corpus: String): Probe =
    val sem = semantic(built)
    Probe.train(examples(corpus).filter((_, i) => sem(i)).flatMap((t, i) => table.encode(t).map(_ -> i)))
