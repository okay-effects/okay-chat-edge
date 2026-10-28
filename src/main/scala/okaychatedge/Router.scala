package okaychatedge

import scala.scalajs.js
import scala.scalajs.js.typedarray.*

/** where a message went, and why */
final case class Route(intent: String, by: String, probability: Double = 0.0, margin: Double = 0.0)

/**
 * ONE MESSAGE IN, ONE INTENT OUT, as okay-chat routes it: the RULES first (okay-chat's `intents.json`, built by its
 * catalogue), then — for the intents read by meaning — the static table's embedding and okay's PROBE over it.
 */
final class Router(rules: Vector[(String, js.RegExp)], probe: Probe, slots: Map[(String, String), js.RegExp]):

  /** an intent's slot, as okay-chat's slot pattern captures it (its first group) */
  def slot(intent: String, name: String, text: String): Option[String] =
    slots.get((intent, name)).flatMap { p =>
      val m = p.exec(text)
      if m == null || m.length < 2 then None else m(1).toOption.map(_.trim).filter(_.nonEmpty)
    }

  /** the intent whose rule matches first */
  def byRule(text: String): Option[String] = rules.find((_, p) => p.test(text)).map(_._1)

  def route(table: StaticTable, text: String): Route =
    byRule(text) match
      case Some(intent) => Route(intent, "rule")
      case None => table.encode(text).map(byMeaning).getOrElse(Route("?", "unknown words"))

  def byMeaning(v: Float32Array): Route =
    val r = probe.ranked(v)
    Route(r.head._1, "probe", r.head._2, r.head._2 - r.lift(1).map(_._2).getOrElse(0.0))

object Router:
  /** the router: okay-chat's built `intents.json` (its rules), the probe's weights (`fit`) */
  def apply(built: String, probe: Probe): Router =
    // okay-chat's `rank`: a total order, the lower first, 50 where an intent names none (its Catalog's default) — so
    // «преподаю английский, cara@x.pl» is an offer (10) before it is a contact (50)
    val ranked = intents(built).sortBy(i => i.rank.asInstanceOf[js.UndefOr[Int]].getOrElse(50))
    val rules = for
      i <- ranked
      r <- i.rules.asInstanceOf[js.UndefOr[js.Array[String]]].map(_.toVector).getOrElse(Vector.empty)
    yield (i.name.asInstanceOf[String], Rules.compile(r))
    val slots = (for
      i <- ranked
      sl <- i.slots.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]].map(_.toVector).getOrElse(Vector.empty)
      if !js.isUndefined(sl.pattern)
    yield (i.name.asInstanceOf[String], sl.name.asInstanceOf[String]) -> Rules.compile(sl.pattern.asInstanceOf[String]))
      .toMap
    new Router(rules, probe, slots)

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
