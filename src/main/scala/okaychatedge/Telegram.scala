package okaychatedge

import okay.effects.json.{Descent, Json}
import okay.http.{Request, Response}
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.field

/**
 * THE BOT, a door to the same conversations (okay-chat's specs/channels.md: a channel adds a door, never a
 * behaviour) — by webhook here, where okay-chat polls: a Worker has an address and no loop to poll from.
 *
 * Telegram POSTs each update to `/telegram` with the secret given at `setWebhook` in a header; anything without it is
 * refused. A private chat's message is a turn of conversation `tg:<user id>`, and the reply rides back IN the webhook's
 * answer (`{"method": "sendMessage", …}`) — no request of ours for it. What another party is told (a deal asked, an
 * offer for a waiting need) is sent with the bot's token, `sendMessage`.
 *
 * The account is the address, as in okay-chat: a Telegram id is proven by Telegram, so `@username` (or the id's link)
 * is this person's contact until they write an e-mail.
 *
 *   TELEGRAM_TOKEN   the bot's token (a secret)
 *   TELEGRAM_SECRET  the webhook's secret token (a secret)
 *   TELEGRAM_API     the Bot API's address, https://api.telegram.org unless set (a local stand-in for tests)
 */
object Telegram:
  val prefix = "tg:"

  private def text(j: Json, k: String): Option[String] = j.field(k).collect { case Json.Str(s) => s }
  private def id(j: Json, k: String): Option[String] = j.field(k).collect { case Json.Num(n) => n.toBigInt.toString }

  private val ok = Response.json(Json.obj("ok" -> Json.Bool(true)))

  def update(r: Request): Response ! Cloud =
    Env.variable[Cloud]("TELEGRAM_SECRET").flatMap {
      case Some(secret) if r.header("x-telegram-bot-api-secret-token").contains(secret) =>
        Descent.parse(r.body).toOption.flatMap(message) match
          case None => pure(ok)
          case Some((chat, user, contact, said)) =>
            val reply: String ! Cloud =
              if said.startsWith("/start") then pure(Phrases.greeting)
              else Clock.now[Cloud].flatMap(at => Chat.Edges.turn(prefix + user, said, Some(contact), at))
                .map((_, s, _, l) => s.fold(Phrases.howCanIHelp(l))(_.reply))
            reply.map(t => Response.json(Json.obj("method" -> Json.Str("sendMessage"), "chat_id" -> Json.Str(chat),
              "text" -> Json.Str(t))))
      case _ => pure(Response.text("", 403))
    }

  /** a private chat's text: its chat, its sender, the sender's address, the text */
  private def message(u: Json): Option[(String, String, String, String)] =
    for
      m <- u.field("message")
      chat <- m.field("chat")
      if text(chat, "type").contains("private")
      chatId <- id(chat, "id")
      from <- m.field("from")
      user <- id(from, "id")
      said <- text(m, "text").map(_.trim).filter(_.nonEmpty)
    yield (chatId, user, text(from, "username").fold(s"tg://user?id=$user")("@" + _), said)

  /** a party told: if it is a Telegram conversation, by the bot (its private chat's id is its user's) */
  def push(party: String, said: String): Unit ! Cloud =
    if !party.startsWith(prefix) then pure(())
    else Env.variable[Cloud]("TELEGRAM_TOKEN").flatMap {
      case None => pure(())
      case Some(token) => Env.variable[Cloud]("TELEGRAM_API").flatMap(api =>
        Fetch[Cloud](api.getOrElse("https://api.telegram.org"), Request.json("POST", s"/bot$token/sendMessage",
          Json.obj("chat_id" -> Json.Str(party.drop(prefix.length)), "text" -> Json.Str(said)))).as(()))
    }
