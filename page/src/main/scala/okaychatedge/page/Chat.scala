package okaychatedge.page

import okay.business.model.*
import okay.business.scala3.*
import okay.scala3.std.Async

/**
 * THE CHAT AS SCREENS — one screen, shown again on every change: the conversation as blocks (`me`, `bot`), a line to
 * write in, a button to send. Sent, the message stays on the screen as `sending` until its turn comes back by the
 * conversation's socket with its reply; turns come by number — the next one is added, a gap read again (`Again`), one
 * already here left. The words of the page are in the browser's language (`Words`); what the chat says is the chat's.
 */
object Chat:
  type Page[X] = (Async + Ui + Talk)[X]

  enum Msg:
    case Send(words: String)
    case Changed(c: Change)

  final case class State(count: Int = 0, turns: Vector[Turn] = Vector.empty, sending: Option[String] = None,
                         problem: Option[String] = None, sent: Int = 0)

  def main(w: Words): Unit ! Page =
    for
      changes <- Talk.changes[Page]
      s <- reread(w, State())
      _ <- loop(w, s, changes)
    yield ()

  def loop(w: Words, s: State, changes: Events[Change]): Unit ! Page =
    show[Msg, Page](render(w, s).until(changes.map(Msg.Changed(_)))).flatMap {
      case Msg.Send(words) if words.trim.nonEmpty =>
        val sending = s.copy(sending = Some(words.trim), problem = None, sent = s.sent + 1)
        working[Page]("Okay! Chat")(conversation(w, sending)*).flatMap(_ =>
          Talk.say[Page](words.trim).flatMap {
            case Left(e) => pure[State, Page](sending.copy(sending = None, problem = Some(w.offline(e))))
            // the turn comes by the socket; with no socket just now, it is read
            case Right(()) => Talk.live[Page].flatMap(live => if live then pure(sending) else reread(w, sending))
          }).flatMap(loop(w, _, changes))
      case Msg.Send(_) => loop(w, s, changes)
      case Msg.Changed(Change.Came(n, t)) if n == s.count + 1 =>
        val came = s.copy(count = n, turns = s.turns :+ t, problem = None)
        loop(w, if t.wrote.exists(s.sending.contains) then came.copy(sending = None) else came, changes)
      case Msg.Changed(Change.Came(n, _)) if n <= s.count => loop(w, s, changes)
      case Msg.Changed(_) => reread(w, s).flatMap(loop(w, _, changes))
    }

  private def reread(w: Words, s: State): State ! Page =
    Talk.read[Page].map {
      case Right((n, ts)) =>
        s.copy(count = n, turns = ts, problem = None,
          sending = s.sending.filterNot(x => ts.exists(_.wrote.contains(x))))
      case Left(e) => s.copy(problem = Some(w.offline(e)))
    }

  private val url = """https?://[^\s<>«»"]+[^\s<>«»".,;:!?)\]]""".r

  /** a link as shown: its host and the start of its path — a vacancy's address is a line and a half */
  def label(u: String): String =
    val bare = u.replaceFirst("^https?://", "").replaceFirst("^www\\.", "")
    if bare.length <= 40 then bare else bare.take(38) + "…"

  /** what a message says, its addresses links: text, link, text… (each a part, shown in one flow) */
  def withLinks(x: String): List[Part] =
    val (parts, rest) = url.findAllMatchIn(x).foldLeft((List.empty[Part], 0)) { case ((ps, at), m) =>
      (ps ++ Option.when(m.start > at)(Part.text(x.substring(at, m.start))).toList :+ Part.link(label(m.matched),
        m.matched), m.end)
    }
    parts ++ Option.when(rest < x.length)(Part.text(x.substring(rest))).toList

  private def conversation(w: Words, s: State): List[Part] =
    val said =
      if s.turns.isEmpty && s.sending.isEmpty then List(Part.block("bot")(Part.text(w.greeting)))
      else s.turns.toList.flatMap(t =>
        t.wrote.map(x => Part.block("me")(withLinks(x)*)).toList ++ t.said.map(x => Part.block("bot")(withLinks(x)*)))
    val pending = s.sending.toList.flatMap(x =>
      List(Part.block("me-sending")(Part.text(x)), Part.block("bot-typing")(Part.text("…"))))
    List(Part.block("head")(Part.block("avatar")(Part.text("O")),
      Part.block("who")(Part.block("name")(Part.text("Okay! Chat")), Part.block("sub")(Part.text(w.market)))),
      Part.block("log")((said ++ pending)*)) ++ s.problem.map(Part.problem).toList

  def render(w: Words, s: State): Screen[Msg] =
    // a fresh field after each message sent: the line empties; a text that grows, as a message does
    val line = Form.one(Field.longText(w.message).keyed(s"message-${s.sent}").hinted(w.example))
    Screen[Msg]("Okay! Chat", conversation(w, s) :+ line, List(Button.submit(w.send, line)(Msg.Send(_))))
