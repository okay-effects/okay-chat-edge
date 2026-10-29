package okaychatedge.page

import okay.business.model.*
import okay.business.scala3.*
import okay.scala3.std.Async

/**
 * THE CHAT AS SCREENS — one screen, shown again on every change: the conversation as blocks (`me`, `bot`), a line to
 * write in, a button to send. Sent, the message stays on the screen as `sending` until the conversation, read again,
 * holds it with its reply. The words of the page are in the browser's language (`Words`); what the chat says is the
 * chat's.
 */
object Chat:
  type Page[X] = (Async + Ui + Talk)[X]

  enum Msg:
    case Send(words: String)
    case Changed

  final case class State(turns: Vector[Turn] = Vector.empty, sending: Option[String] = None,
                         problem: Option[String] = None, sent: Int = 0)

  def main(w: Words): Unit ! Page =
    for
      changes <- Talk.changes[Page]
      turns <- Talk.read[Page]
      _ <- loop(w, State(turns = turns.getOrElse(Vector.empty), problem = turns.left.toOption.map(w.offline)), changes)
    yield ()

  def loop(w: Words, s: State, changes: Events[Unit]): Unit ! Page =
    show[Msg, Page](render(w, s).until(changes.map(_ => Msg.Changed))).flatMap {
      case Msg.Send(words) if words.trim.nonEmpty =>
        val sending = s.copy(sending = Some(words.trim), problem = None, sent = s.sent + 1)
        working[Page]("Okay! Chat")(conversation(w, sending)*).flatMap(_ =>
          Talk.say[Page](words.trim).flatMap {
            case Left(e) => pure[State, Page](sending.copy(sending = None, problem = Some(w.offline(e))))
            case Right(()) => reread(w, sending)
          }).flatMap(loop(w, _, changes))
      case Msg.Send(_) => loop(w, s, changes)
      case Msg.Changed => reread(w, s).flatMap(loop(w, _, changes))
    }

  private def reread(w: Words, s: State): State ! Page =
    Talk.read[Page].map {
      case Right(ts) => s.copy(turns = ts, sending = None, problem = None)
      case Left(e) => s.copy(problem = Some(w.offline(e)))
    }

  private def conversation(w: Words, s: State): List[Part] =
    val said =
      if s.turns.isEmpty && s.sending.isEmpty then List(Part.block("bot")(Part.text(w.greeting)))
      else s.turns.toList.flatMap(t =>
        t.wrote.map(x => Part.block("me")(Part.text(x))).toList ++ t.said.map(x => Part.block("bot")(Part.text(x))))
    val pending = s.sending.toList.flatMap(x =>
      List(Part.block("me-sending")(Part.text(x)), Part.block("bot-typing")(Part.text("…"))))
    List(Part.block("head")(Part.block("avatar")(Part.text("O")),
      Part.block("who")(Part.block("name")(Part.text("Okay! Chat")), Part.block("sub")(Part.text(w.market)))),
      Part.block("log")((said ++ pending)*)) ++ s.problem.map(Part.problem).toList

  def render(w: Words, s: State): Screen[Msg] =
    // a fresh field after each message sent: the line empties
    val line = Form.one(Field.text(w.message).keyed(s"message-${s.sent}").hinted(w.example))
    Screen[Msg]("Okay! Chat", conversation(w, s) :+ line, List(Button.submit(w.send, line)(Msg.Send(_))))
