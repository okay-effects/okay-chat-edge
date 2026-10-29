package okaychatedge

import okay.effects.json.Json
import okay.http.Request
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.field
import scala.scalajs.js

/** a vacancy as the chat lists it: its title, the employer, where, how, the employer's own page */
final case class Vacancy(title: String, company: String, place: String, workplace: String, url: String)

/** what a person asked for: the words to search by, where, remote only */
final case class JobAsk(query: String, location: Option[String], remote: Boolean)

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
  private val jobWords = new js.RegExp("(?<!\\p{L})(вакан\\p{L}*|работ[уаы]|роботу|робота|роботи|prac[ayę]|" +
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
    "для", "и", "или", "у", "з", "із", "w", "we", "na", "dla", "i", "lub", "in", "at", "or", "and", "as", "как",
    "разработчиком", "специалистом", "developer", "engineer")
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

  private val split = new js.RegExp("[^\\p{L}\\p{N}+#.]+", "u")
  private def latin(w: String): Boolean = w.exists(c => c >= 'a' && c <= 'z')

  /** the text read: the place, remote, and the words left — Latin ones first */
  def read(text: String): (Vector[String], Option[String], Boolean) =
    import js.JSStringOps.*
    val ws = text.toLowerCase.jsSplit(split).toVector.map(_.stripSuffix(".")).filter(_.nonEmpty)
    val place = ws.flatMap(w => places.collectFirst { case (stems, p) if stems.exists(w.startsWith) => (w, p) })
    val remote = ws.exists(remoteWords)
    val left = ws.filterNot(w => asking(w) || remoteWords(w) || place.exists(_._1 == w))
    (left, place.headOption.map(_._2), remote)

  /** the words to search by: the Latin ones as written; with none, the rest put into English by the model */
  def ask(text: String, at: Long): Option[JobAsk] ! Cloud =
    val (left, location, remote) = read(text)
    val latinOnes = left.filter(latin)
    if left.isEmpty then pure(None)
    else if latinOnes.nonEmpty then pure(Some(JobAsk(latinOnes.mkString(" "), location, remote)))
    else Lane.model.flatMap {
      case None => pure(Some(JobAsk(left.mkString(" "), location, remote)))
      case Some(m) => Budget.take(at).flatMap(granted =>
        if !granted then pure(Some(JobAsk(left.mkString(" "), location, remote)))
        else Lane.english(m, left.mkString(" ")).map(e =>
          Some(JobAsk(e.getOrElse(left.mkString(" ")), location, remote))))
    }

  private def encoded(s: String): String = js.URIUtils.encodeURIComponent(s)

  /** the first vacancies Workable has for the ask, and how many it has in all — or what went wrong */
  def search(a: JobAsk): Either[String, (Int, Vector[Vacancy])] ! Cloud =
    Env.variable[Cloud]("JOBS_API").flatMap { api =>
      val q = Vector(Some(s"query=${encoded(a.query)}"), a.location.map(l => s"location=${encoded(l)}"),
        Option.when(a.remote)("workplace=remote")).flatten.mkString("&")
      Fetch[Cloud](api.getOrElse("https://jobs.workable.com"), Request("GET", s"/api/v1/jobs?$q")
        .copy(headers = Map("user-agent" -> List("okay-chat-edge (+https://okay-chat-edge.okay-effects.workers.dev)"),
          "accept" -> List("application/json")))).map { r =>
        if r.status != 200 then Left(s"Workable: ${r.status}")
        else
          val d = js.JSON.parse(r.body)
          val jobs = d.jobs.asInstanceOf[js.UndefOr[js.Array[js.Dynamic]]].getOrElse(js.Array())
          val str = (x: js.Dynamic) => x.asInstanceOf[js.UndefOr[String]].toOption.filter(_.nonEmpty)
          Right((d.totalSize.asInstanceOf[js.UndefOr[Int]].getOrElse(jobs.length), jobs.toVector.take(5).map { j =>
            val loc = j.location.asInstanceOf[js.UndefOr[js.Dynamic]].toOption
            Vacancy(str(j.title).getOrElse("?"), j.company.asInstanceOf[js.UndefOr[js.Dynamic]].toOption
              .flatMap(c => str(c.title)).getOrElse(""),
              loc.flatMap(l => str(l.city).orElse(str(l.countryName))).getOrElse(""), str(j.workplace).getOrElse(""),
              str(j.url).getOrElse(""))
          }))
      }
    }

  /** a message asking for vacancies, answered */
  def answer(text: String, l: Lang, at: Long): String ! Cloud =
    ask(text, at).flatMap {
      case None => pure(Phrases.whichJob(l))
      case Some(a) => search(a).map {
        case Left(e) => Phrases.jobsUnreachable(l, e)
        case Right((_, Vector())) => Phrases.noJobs(l, a.query, a.location, a.remote)
        case Right((total, vs)) => Phrases.jobs(l, a.query, a.location, a.remote, total, vs.zipWithIndex.map((v, i) =>
          s"${i + 1}. ${v.title}" + Vector(v.company, v.place, v.workplace).filter(_.nonEmpty)
            .mkString(" — ", ", ", "") + s"\n${v.url}"))
      }
    }
