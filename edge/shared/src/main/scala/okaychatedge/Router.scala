package okaychatedge


/** where a message went, and why */
final case class Route(intent: String, by: String, probability: Double = 0.0, margin: Double = 0.0)

/**
 * ONE MESSAGE IN, ONE INTENT OUT, as okay-chat routes it: the RULES first (okay-chat's `intents.json`, built by its
 * catalogue), then — for the intents read by meaning — the static table's embedding and okay's PROBE over it.
 */
final class Router(rules: Vector[(String, Re)], probe: Probe, slots: Map[(String, String), Re],
                   helps: Vector[(String, J)]):

  /** okay-chat's help rows — «say» — does — for `intents`, in rank order, in a language (Russian where it has none) */
  def help(code: String, intents: Set[String]): Vector[(String, String)] =
    helps.filter((i, _) => intents(i)).flatMap { (i, h) =>
      h.get(code).orElse(h.get("ru")).map(x => i -> s"• «${x("say").str.getOrElse("")}» — ${x("does").str.getOrElse("")}")
    }

  /** an intent's slot, as okay-chat's slot pattern captures it (its first group) */
  def slot(intent: String, name: String, text: String): Option[String] =
    slots.get((intent, name)).flatMap(_.group(text)).map(_.trim).filter(_.nonEmpty)

  /** the intent whose rule matches first */
  def byRule(text: String): Option[String] = rules.find((_, p) => p.test(text)).map(_._1)

  def route(table: StaticTable, text: String): Route =
    byRule(text) match
      case Some(intent) => Route(intent, "rule")
      case None => table.encode(text).map(byMeaning).getOrElse(Route("?", "unknown words"))

  def byMeaning(v: Array[Float]): Route =
    val r = probe.ranked(v)
    Route(r.head._1, "probe", r.head._2, r.head._2 - r.lift(1).map(_._2).getOrElse(0.0))

object Router:
  /** the router: okay-chat's built `intents.json` (its rules), the probe's weights (`fit`) */
  def apply(built: String, probe: Probe): Router =
    // okay-chat's `rank`: a total order, the lower first, 50 where an intent names none (its Catalog's default) — so
    // «преподаю английский, cara@x.pl» is an offer (10) before it is a contact (50)
    val ranked = intents(built).sortBy(i => i("rank").num.map(_.toInt).getOrElse(50))
    val rules = for
      i <- ranked
      r <- i("rules").arr.flatMap(_.str)
    yield (name(i), Rules.compile(r))
    val slots = (for
      i <- ranked
      sl <- i("slots").arr
      pattern <- sl("pattern").str
    yield (name(i), sl("name").str.getOrElse("")) -> Rules.compile(pattern)).toMap
    val helps = ranked.flatMap(i => i.get("help").map(name(i) -> _))
    new Router(rules, probe, slots, helps)

  private def name(i: J): String = i("name").str.getOrElse("")

  private def intents(built: String): Vector[J] = J.parse(built)("intents").arr

  /** the intents read by meaning: `semantic` not false */
  def semantic(built: String): Set[String] =
    intents(built).filter(i => !i("semantic").bool.contains(false)).map(name).toSet

  /** the authored examples of the corpus: (text, intent) */
  def examples(corpus: String): Vector[(String, String)] =
    for
      i <- J.parse(corpus)("intents").arr
      t <- texts(i("examples"))
    yield (t, name(i))

  private def texts(ex: J): Vector[String] =
    val list = ex.arr
    if list.nonEmpty then list.flatMap(_.str) else ex.fields.flatMap(_._2.arr.flatMap(_.str))

  /** the probe fitted on the semantic intents' examples */
  def fit(table: StaticTable, built: String, corpus: String): Probe =
    val sem = semantic(built)
    Probe.train(examples(corpus).filter((_, i) => sem(i)).flatMap((t, i) => table.encode(t).map(_ -> i)))
