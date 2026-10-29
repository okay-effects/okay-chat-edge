package okaychatedge

import okay.effects.json.Json
import okay.http.Request
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.field

/** a vacancy as the chat lists it: its title, the employer, where, how, the employer's own page */
final case class Vacancy(title: String, company: String, place: String, workplace: String, url: String,
                         created: Long = 0L)

/** what a person asked for: the words to search by (alternatives, «scala или java»), where, remote only — and
 * whether the place was not said but taken (from the conversation, or where the person is) */
final case class JobAsk(query: String, location: Option[String], remote: Boolean, assumed: Boolean = false):
  def alternatives: Vector[String] = query.split(" \\| ").toVector.map(_.trim).filter(_.nonEmpty)

/**
 * VACANCIES FROM THE INTERNET, asked in the chat — «вакансии scala, Вроцлав», «ищу работу java разработчиком
 * удалённо», «szukam pracy python Kraków». From Workable's public search across every employer on it
 * (jobs.workable.com/api/v1/jobs): okay-chat's road for jobs (its specs/jobs.md — only open, legally clean sources:
 * its robots.txt says `search=yes, ai-input=yes`, `/api/` not disallowed; the sites that forbid collection —
 * pracuj.pl, OLX, LinkedIn — are not read). One request a search, ~150 KB, read by the platform's own JSON.parse.
 *
 * The words: what the text holds besides the asking words and the place; Workable searches in English, so a search
 * with no Latin word in it is put into English by the model lane (within its day's budget) — else searched as said.
 *
 *   JOBS_API  Workable's address, https://jobs.workable.com unless set (a local stand-in for tests)
 */
object Jobs:
  /** a message asking for vacancies: its words name work, and it is not an offer of one's own */
  private val jobWords = Re("(?<!\\p{L})(вакан\\p{L}*|работ[уаы]|роботу|робота|роботи|prac[ayę]|" +
    "oferty|ofert[ay]? pracy|jobs?|vacanc\\p{L}*|hiring)(?!\\p{L})", "iu")
  def asked(text: String, intent: String): Boolean =
    intent != "offer" && intent != "accept" && intent != "decline" && intent != "ask_candidates" &&
      jobWords.test(text)

  /** the words of asking, of any of the four languages — not what is searched */
  private val asking = Set("ищу", "найди", "найти", "найдите", "покажи", "покажите", "дай", "мне", "нужна", "нужен",
    "нужно", "хочу", "есть", "какие", "работу", "работа", "работы", "вакансии", "вакансию", "вакансия", "вакансий",
    "шукаю", "знайди", "покажи", "потрібна", "роботу", "робота", "вакансії", "вакансію", "вакансія", "szukam",
    "znajdź", "pokaż", "pracy", "praca", "pracę", "oferty", "oferta", "ofert", "find", "show", "me", "a", "an", "the",
    "job", "jobs", "vacancy", "vacancies", "work", "looking", "for", "need", "want", "i", "im", "в", "во", "на", "по",
    "для", "и", "или", "у", "з", "із", "w", "we", "na", "dla", "i", "lub", "in", "at", "or", "and", "as", "как")
  /** a role's general word: dropped beside a more telling one («scala разработчиком» → scala), kept alone
   * («вакансии engineer») */
  private val general = Set("разработчиком", "разработчик", "специалистом", "специалист", "розробником",
    "developer", "engineer", "programista", "programistą", "inżynier")
  private val remoteWords = Set("удалённо", "удаленно", "удалёнка", "удаленка", "удалённую", "удаленную",
    "дистанционно",
    "віддалено", "дистанційно", "zdalnie", "zdalna", "zdalną", "remote", "remotely")

  /** where, by the word's start: the place as Workable reads it */
  private val places: Vector[(Vector[String], String)] = Vector(
    Vector("польш", "польщ", "polsk", "polsc", "polan") -> "Poland",
    Vector("украин", "україн", "ukrai") -> "Ukraine",
    Vector("герман", "німеч", "niemc", "germa") -> "Germany",
    Vector("вроцл", "wrocł", "wrocl") -> "Wrocław",
    Vector("краков", "краків", "krakó", "kraków", "krakow", "krako") -> "Kraków",
    Vector("варша", "warsz", "warsa") -> "Warsaw",
    Vector("гданьс", "gdańs", "gdans") -> "Gdańsk",
    Vector("познан", "poznań", "pozna") -> "Poznań",
    Vector("лодз", "łódź", "lodz") -> "Łódź",
    Vector("киев", "київ", "kyiv", "kiev") -> "Kyiv",
    Vector("львов", "львів", "lviv", "lwów") -> "Lviv",
    Vector("харьк", "харків", "khark") -> "Kharkiv",
    Vector("одесс", "одес", "odes") -> "Odesa",
    Vector("днепр", "дніпр", "dnipr") -> "Dnipro",
    Vector("берлин", "берлін", "berli") -> "Berlin")

  private val split = Re("[^\\p{L}\\p{N}+#.]+", "u")
  private def latin(w: String): Boolean = w.exists(c => c >= 'a' && c <= 'z')

  /** the text read: the place, remote, and the words left — Latin ones first */
  /** «или», «or»: alternatives, each searched */
  private val or = Set("или", "або", "чи", "lub", "albo", "czy", "or", "/")
  /** «везде»: no place, the whole board */
  private val anywhere = Set("везде", "везде.", "всюду", "скрізь", "wszędzie", "anywhere", "worldwide", "global")
  /** the short words that are skills, not fragments («de», «la» are not) */
  private val shortSkills = Set("go", "c", "r", "qa", "ui", "ux", "ai", "ml", "bi", "hr", "pm", "js", "ts", "c#", "c++",
    "f#", "3d", "ios", "sql", "php", "net", ".net", "sap", "erp", "crm", "seo", "smm")

  /** what a message says of a search: its alternatives (each its words), the place, remote, «везде» */
  final case class Read(alternatives: Vector[Vector[String]], place: Option[String], remote: Boolean,
                        anywhere: Boolean)

  /** a place a message names, by the stems of its words — any message: «ремонтирую сантехнику, Вроцлав» too */
  def placeIn(text: String): Option[String] =
    split.split(text.toLowerCase).flatMap(w => places.collectFirst {
      case (stems, p) if stems.exists(w.startsWith) => p }).headOption

  private val words = Re("[^\\p{L}\\p{N}+#./]+", "u")

  def read(text: String): Read =
    val ws = words.split(text.toLowerCase)
      .map(_.stripSuffix(".")).filter(_.nonEmpty)
    val place = ws.flatMap(w => places.collectFirst { case (stems, p) if stems.exists(w.startsWith) => (w, p) })
    val remote = ws.exists(remoteWords)
    val groups = ws.foldLeft(Vector(Vector.empty[String]))((gs, w) =>
      if or(w) then gs :+ Vector.empty else gs.init :+ (gs.last :+ w))
    val alternatives = groups.map { g =>
      val left = g.filterNot(w => asking(w) || remoteWords(w) || anywhere(w) || place.exists(_._1 == w) ||
        (latin(w) && w.length < 3 && !shortSkills(w)))
      val telling = left.filterNot(general)
      if telling.nonEmpty then telling else left
    }.filter(_.nonEmpty)
    Read(alternatives, place.headOption.map(_._2), remote, ws.exists(anywhere))

  /** an alternative's words to search by: the Latin ones as written; with none, put into English by the model */
  private def english(words: Vector[String], at: Long): String ! Cloud =
    val latinOnes = words.filter(latin)
    if latinOnes.nonEmpty then pure(latinOnes.mkString(" "))
    else Lane.model.flatMap {
      case None => pure(words.mkString(" "))
      case Some(m) => Budget.take(at).flatMap(granted =>
        if !granted then pure(words.mkString(" "))
        else Lane.english(m, words.mkString(" ")).map(_.getOrElse(words.mkString(" "))))
    }

  /** the search a message asks: its words, and the place — said, or else `near` (the conversation's place, or where
   * the person is), unless «везде» */
  def ask(text: String, at: Long, near: Option[String] = None): Option[JobAsk] ! Cloud =
    val r = read(text)
    if r.alternatives.isEmpty then pure(None)
    else r.alternatives.foldLeft(pure[Vector[String], Cloud](Vector.empty))((acc, words) =>
      acc.flatMap(es => english(words, at).map(es :+ _))).map { es =>
      val location = r.place.orElse(if r.anywhere then None else near)
      Some(JobAsk(es.distinct.mkString(" | "), location, r.remote, assumed = r.place.isEmpty && location.isDefined))
    }

  private def encoded(s: String): String = okay.http.Query.encode(s)

  /** the first vacancies Workable has for the ask — each alternative's, by turns — and how many in all */
  def search(a: JobAsk): Either[String, (Int, Vector[Vacancy])] ! Cloud =
    a.alternatives.foldLeft(pure[Vector[Either[String, (Int, Vector[Vacancy])]], Cloud](Vector.empty))((acc, q) =>
      acc.flatMap(rs => searchOne(a.copy(query = q)).map(rs :+ _))).map { rs =>
      val ok = rs.collect { case Right(r) => r }
      if ok.isEmpty then rs.collectFirst { case Left(e) => Left(e) }.getOrElse(Right((0, Vector.empty)))
      else
        val longest = ok.map(_._2.size).max
        Right((ok.map(_._1).sum, (0 until longest).toVector.flatMap(i => ok.flatMap(_._2.lift(i)))))
    }

  private def searchOne(a: JobAsk): Either[String, (Int, Vector[Vacancy])] ! Cloud =
    Env.variable[Cloud]("JOBS_API").flatMap { api =>
      val q = Vector(Some(s"query=${encoded(a.query)}"), a.location.map(l => s"location=${encoded(l)}"),
        Option.when(a.remote)("workplace=remote")).flatten.mkString("&")
      Fetch[Cloud](api.getOrElse("https://jobs.workable.com"), Request("GET", s"/api/v1/jobs?$q")
        .copy(headers = Map("user-agent" -> List("okay-chat-edge (+https://okay-chat-edge.okay-effects.workers.dev)"),
          "accept" -> List("application/json")))).map { r =>
        if r.status != 200 then Left(s"Workable: ${r.status}")
        else
          val d = J.parse(r.body)
          val jobs = d("jobs").arr
          val str = (x: J) => x.str.filter(_.nonEmpty)
          Right((d("totalSize").num.map(_.toInt).getOrElse(jobs.length), jobs.map { j =>
            val loc = j("location")
            Vacancy(str(j("title")).getOrElse("?"), str(j("company")("title")).getOrElse(""),
              str(loc("city")).orElse(str(loc("countryName"))).getOrElse(""), str(j("workplace")).getOrElse(""),
              str(j("url")).getOrElse(""), str(j("created")).flatMap(Text.isoMillis).getOrElse(0L))
          }))
      }
    }

  /** how many a message shows */
  val page = 5

  /** the boards' vacancies first (okay-chat's employers, PL/UA), then Workable's search; one of each title and
   * employer; how many in all */
  def results(a: JobAsk): Either[String, (String, Vector[Vacancy])] ! Cloud =
    Vacancies.search(a, Vacancies.most).flatMap(boards => search(a).map { found =>
      // the boards' are read up to `most`: as many as that, and there may be more
      def total(n: Int) = if boards.size >= Vacancies.most then s"$n+" else n.toString
      found match
        case Left(e) if boards.isEmpty => Left(e)
        case Left(_) => Right((total(boards.size), boards))
        case Right((all, ws)) =>
          val both = (boards ++ ws).distinctBy(v => (v.title.toLowerCase, v.company.toLowerCase))
          Right((total(all + both.size - ws.size), both))
    })

  private def lines(vs: Vector[Vacancy], from: Int): Vector[String] = vs.zipWithIndex.map((v, i) =>
    s"${from + i + 1}. ${v.title}" + Vector(v.company, v.place, v.workplace).filter(_.nonEmpty)
      .mkString(" — ", ", ", "") + s"\n${v.url}")

  /** a page of vacancies for an ask, from `from` — the reply, and whether there are more after it */
  def shown(a: JobAsk, l: Lang, from: Int): String ! Cloud =
    results(a).map {
      case Left(e) => Phrases.jobsUnreachable(l, e)
      case Right((_, Vector())) => Phrases.noJobs(l, a)
      case Right((_, vs)) if from >= vs.size => Phrases.noMoreJobs(l)
      case Right((total, vs)) =>
        val those = vs.slice(from, from + page)
        Phrases.jobs(l, a, total, from, lines(those, from), from + page < vs.size)
    }

  /** a message asking for vacancies, answered — and the ask, for «ещё» and «запомни» */
  def answer(text: String, l: Lang, at: Long, near: Option[String]): (String, Option[JobAsk]) ! Cloud =
    ask(text, at, near).flatMap {
      case None => pure((Phrases.whichJob(l), None))
      case Some(a) => shown(a, l, 0).map(_ -> Some(a))
    }

  /** «хватит вакансий», «не присылай вакансии», «stop jobs» */
  private val stop = Re("(?<!\\p{L})(хватит|не присылай|не надо|отпиш|перестань|досить|не надсилай|" +
    "відпиш|przestań|nie wysyłaj|wypisz|stop|unsubscribe)(?!\\p{L})", "iu")
  def stops(text: String): Boolean = stop.test(text) && (jobWords.test(text) || text.trim.split("\\s+").length <= 2)

  /** «ещё» in the languages the router's `next` does not know */
  private val moreWords = Re("^\\s*(więcej|następne|dalej|следующие|наступні|ще)\\s*[.!]?\\s*$", "iu")
  def more(text: String, intent: String): Boolean = intent == "next" || moreWords.test(text)

  def json(a: JobAsk, from: Int): okay.effects.json.Json =
    import okay.effects.json.Json
    Json.obj("query" -> Json.Str(a.query), "location" -> Json.Str(a.location.getOrElse("")),
      "remote" -> Json.Bool(a.remote), "assumed" -> Json.Bool(a.assumed), "from" -> Json.Num(BigDecimal(from)))

  def fromJson(j: okay.effects.json.Json): Option[(JobAsk, Int)] =
    import okay.effects.json.Json
    for
      q <- j.field("query").collect { case Json.Str(s) => s }
      loc = j.field("location").collect { case Json.Str(s) => s }.filter(_.nonEmpty)
      r = j.field("remote").contains(Json.Bool(true))
      f = j.field("from").collect { case Json.Num(n) => n.toInt }.getOrElse(0)
      assumed = j.field("assumed").contains(Json.Bool(true))
    yield (JobAsk(q, loc, r, assumed), f)
