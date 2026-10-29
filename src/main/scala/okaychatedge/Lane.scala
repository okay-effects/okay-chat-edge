package okaychatedge

import okay.effects.json.Json
import scala.scalajs.js
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.field

/**
 * THE MODEL LANE — okay-chat's, made small: what the rules and the probe do not act on (small talk, «что ты
 * можешь?», «что есть?», «ищу работу» with no trade in it) is answered by a model on Workers AI, in role. It acts on
 * nothing: it is told what the service can do and what is on the market now, and may say only that — no people, no
 * offers, no prices, no links of its own, no promises. A reply with a link or a commitment in it is dropped; no
 * model, or no answer
 * (the free plan's 10 000 neurons spent, an error), and okay-chat's canned answer is said instead.
 *
 * `LANE`: the model — gemma-4-26b-a4b-it, measured 2026-10-29: ~1.2 s, ~4 neurons a reply (~2300 a day free), its
 * thinking off; qwen3-30b and gemma with thinking on spent the whole budget thinking and said nothing.
 */
object Lane:
  /** how much of the conversation the model reads: the last exchanges, each cut */
  val turns = 6
  val turnChars = 400

  def model: Option[String] ! Cloud = Env.variable[Cloud]("LANE").map(_.map(_.trim).filter(_.nonEmpty))

  private def language(l: Lang): String = l match
    case Lang.Ru => "Russian"
    case Lang.Uk => "Ukrainian"
    case Lang.Pl => "Polish"
    case Lang.En => "English"

  def system(l: Lang, help: Vector[String], market: Vector[String]): String =
    s"""You are the Telegram bot Okay! Chat: a marketplace of supply and demand. People offer work, services, goods,
       |rentals — and look for them; the bot brings together those who look and those who offer.
       |What the bot can do (only this; the examples are what a person writes):
       |${help.mkString("\n")}
       |On the market now (the newest offers; there are no others):
       |${if market.isEmpty then "(nothing yet)" else market.zipWithIndex.map((m, i) => s"${i + 1}. $m").mkString("\n")}
       |Rules: answer in ${language(l)}, briefly, 1–3 sentences, plain text. Never invent people, offers, events,
       |prices or links — only what is above. Promise nothing beyond the list. If the person looks for something or
       |offers something but did not say what and where, ask them to write it in one sentence, like the examples.
       |You yourself record nothing, search nothing and send nothing: the bot does that only when the person writes
       |such a sentence. Never say you recorded, saved, found or will offer anything. If the person says who they
       |are or what they do but not whether they offer it or look for it, ask which — with one example sentence
       |for each side.
       |To reach someone from the market they must write their need as a sentence first; the bot then lists
       |matches.""".stripMargin

  /**
   * okay-chat's second guard (`ModelLane.commits`), and the edge's own: a reply that commits the service (a promise
   * about how it will behave), confesses on its behalf, or claims an act the lane cannot do — it records nothing, so
   * «записал», «сохранил», «zapisałem» are untrue by construction (seen live 2026-10-29: «Записал: вы предлагаете
   * услуги электрика» for a sentence the router had not taken as an offer). Such a reply is not said.
   */
  private val commitments = new js.RegExp("(?<!\\p{L})(" +
    // okay-chat's promises
    "обещаю|обіцяю|obiecuję|я исправлю|я виправлю|поправлю|я (?:буду|стану) (?:показыв|искать|шукати|делать|" +
    "робити|предлаг|пропон)\\p{L}*|больше не (?:буду|будет)|більше не (?:буду|буде)|в следующий раз|" +
    "наступного разу|następnym razem|next time|я (?:учту|запомню это|исправлюсь)|я врахую|" +
    "i (?:will|'ll) (?:fix|improve|make sure|only show)|постараюсь|намагатимусь|postaram się|" +
    // okay-chat's confessions
    "я ошибся|я помилився|pomyliłem się|i was wrong|my mistake|" +
    // the acts the lane cannot do
    "записал\\p{L}*|запишу|зафиксир\\p{L}*|сохранил\\p{L}*|сохраню|запомнил\\p{L}*|записав|запишу|" +
    "зберіг|збережу|запам'ятав|zapisałem|zapiszę|zapamiętałem|zanotowałem|i (?:have )?(?:recorded|saved|noted)|" +
    "i(?:'ll| will) (?:record|save|note)|я (?:нашёл|нашел|знайшов)|znalazłem|i found" +
    ")(?!\\p{L})", "iu")

  def commits(reply: String): Boolean = commitments.test(reply)

  private def said(j: Json): Option[String] =
    j.field("response").collect { case Json.Str(s) => s }.orElse(
      j.field("choices").collect { case Json.Arr(cs) => cs }.flatMap(_.headOption)
        .flatMap(_.field("message")).flatMap(_.field("content")).collect { case Json.Str(s) => s })

  private def message(role: String, content: String): Json =
    Json.obj("role" -> Json.Str(role), "content" -> Json.Str(content))

  /** user and assistant by turns, the user first — as chat templates (Gemma's) demand: a notice told between two
   * replies joins the reply before it, and what the bot said before anyone wrote is dropped */
  def alternating(ms: Vector[(String, String)]): Vector[(String, String)] =
    ms.dropWhile(_._1 != "user").foldLeft(Vector.empty[(String, String)]) {
      case (acc :+ ((r, c)), (r2, c2)) if r == r2 => acc :+ (r -> s"$c\n$c2")
      case (acc, m) => acc :+ m
    }

  /** the model's answer to `text`, the conversation's last exchanges before it */
  def reply(m: String, text: String, l: Lang, help: Vector[String], market: Vector[String],
            recent: Vector[(String, String)]): Option[String] ! Cloud =
    Ai.run[Cloud](m, Json.obj(
      "messages" -> Json.Arr(message("system", system(l, help, market)) +:
        alternating(recent :+ ("user" -> text)).map(message)),
      "max_tokens" -> Json.Num(300),
      "chat_template_kwargs" -> Json.obj("enable_thinking" -> Json.Bool(false))))
      .map(j => said(j).map(_.trim)
        .filter(r => r.nonEmpty && r.length < 1500 && !r.contains("http") && !commits(r)))

  /** a job search's words in English, as a job board searches (Workable): 1–3 words, or none */
  def english(m: String, words: String): Option[String] ! Cloud =
    Ai.run[Cloud](m, Json.obj(
      "messages" -> Json.Arr(Vector(
        message("system", "Translate the job search words to English as a job board would be searched: the role " +
          "or skill only, 1-3 words, lowercase, nothing else. Example: \"бухгалтером\" -> \"accountant\"; " +
          "\"программистом джава\" -> \"java developer\"."),
        message("user", words))),
      "max_tokens" -> Json.Num(20),
      "chat_template_kwargs" -> Json.obj("enable_thinking" -> Json.Bool(false))))
      .map(j => said(j).map(_.trim.toLowerCase.replaceAll("[^a-z0-9+#. -]", "").trim).filter(e =>
        e.nonEmpty && e.length <= 40))
