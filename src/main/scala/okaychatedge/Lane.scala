package okaychatedge

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.field

/**
 * THE MODEL LANE — okay-chat's, made small: what the rules and the probe do not act on (small talk, «что ты
 * можешь?», «что есть?», «ищу работу» with no trade in it) is answered by a model on Workers AI, in role. It acts on
 * nothing: it is told what the service can do and what is on the market now, and may say only that — no people, no
 * offers, no prices, no links of its own, no promises. A reply with a link in it is dropped; no model, or no answer
 * (the free plan's 10 000 neurons spent, an error), and okay-chat's canned answer is said instead.
 *
 * `LANE`: the model — gemma-4-26b-a4b-it, measured 2026-10-29: ~1.2 s, ~4 neurons a reply (~2300 a day free), its
 * thinking off; qwen3-30b and gemma with thinking on spent the whole budget thinking and said nothing.
 */
object Lane:
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
       |To reach someone from the market they must write their need as a sentence first; the bot then lists matches.""".stripMargin

  private def said(j: Json): Option[String] =
    j.field("response").collect { case Json.Str(s) => s }.orElse(
      j.field("choices").collect { case Json.Arr(cs) => cs }.flatMap(_.headOption)
        .flatMap(_.field("message")).flatMap(_.field("content")).collect { case Json.Str(s) => s })

  def reply(text: String, l: Lang, help: Vector[String], market: Vector[String]): Option[String] ! Cloud =
    model.flatMap {
      case None => pure(None)
      case Some(m) =>
        Ai.run[Cloud](m, Json.obj(
          "messages" -> Json.Arr(Vector(
            Json.obj("role" -> Json.Str("system"), "content" -> Json.Str(system(l, help, market))),
            Json.obj("role" -> Json.Str("user"), "content" -> Json.Str(text)))),
          "max_tokens" -> Json.Num(300),
          "chat_template_kwargs" -> Json.obj("enable_thinking" -> Json.Bool(false))))
          .map(j => said(j).map(_.trim).filter(r => r.nonEmpty && r.length < 1500 && !r.contains("http")))
    }
