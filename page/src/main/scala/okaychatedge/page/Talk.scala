package okaychatedge.page

import okay.business.model.Events
import okay.business.scala3.*
import okay.scala3.std.Async
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.concurrent.ExecutionContext.Implicits.global

/** a turn of the conversation, as the page shows it: what the person wrote, and what was said to them */
final case class Turn(wrote: Option[String], said: Option[String])

/** what changed in the conversation: turn `n` came; or turns may have been missed — read it again */
enum Change:
  case Came(n: Int, turn: Turn)
  case Again

/**
 * THE CONVERSATION, AS THE PAGE ASKS IT — the effect; where it is (this Worker's /c/<id>, a test's list) is the
 * handler's. `Read`: how many turns there have been, and the turns kept. `Changes`: the turns as they come — a reply,
 * and what another party tells this one (a deal asked, an offer for a waiting need). `Live`: whether they come by
 * themselves just now; when not, the page reads.
 */
enum Talk[X]:
  case Say(words: String) extends Talk[Either[String, Unit]]
  case Read() extends Talk[Either[String, (Int, Vector[Turn])]]
  case Changes() extends Talk[Events[Change]]
  case Live() extends Talk[Boolean]

object Talk:
  def say[R[_]](words: String)(using In[Talk, R]): Either[String, Unit] ! R =
    performAt[Talk, R, Either[String, Unit]](Say(words))
  def read[R[_]](using In[Talk, R]): Either[String, (Int, Vector[Turn])] ! R =
    performAt[Talk, R, Either[String, (Int, Vector[Turn])]](Read())
  def changes[R[_]](using In[Talk, R]): Events[Change] ! R = performAt[Talk, R, Events[Change]](Changes())
  def live[R[_]](using In[Talk, R]): Boolean ! R = performAt[Talk, R, Boolean](Live())

  /** this browser's conversation: `web:` and 32 hex digits, made once and kept in localStorage — unguessable, so it is
   * the only key to it */
  def mine(): String =
    val key = "okay-chat-edge.id"
    Option(dom.window.localStorage.getItem(key)).getOrElse {
      val made = "web:" + js.Dynamic.global.crypto.randomUUID().asInstanceOf[String].replace("-", "")
      dom.window.localStorage.setItem(key, made)
      made
    }

  private def turn(t: js.Dynamic): Turn = Turn(
    t.text.asInstanceOf[js.UndefOr[String]].toOption,
    t.reply.asInstanceOf[js.UndefOr[String]].toOption.orElse(t.told.asInstanceOf[js.UndefOr[String]].toOption))

  /**
   * THE CONVERSATION'S SOCKET: one WebSocket to the conversation's object, hibernating there — the turns pushed as
   * they are kept, no request a turn. What comes while the program is not listening (sending, a screen being drawn)
   * waits here for the next listener. Lost, it is opened again (1 s, doubling to 30 s; at once when the page comes
   * back into view), and `Again` said: what came meanwhile is read.
   */
  private final class Socket(url: String):
    private var ws: Option[dom.WebSocket] = None
    private var open = false
    private var waitMs = 1000
    private var waiting = Vector.empty[Change]
    private var listeners = Vector.empty[Change => Unit]
    private var opened = false

    def live: Boolean = open

    private def tell(c: Change): Unit =
      if listeners.isEmpty then waiting :+= c else listeners.foreach(_(c))

    val events: Events[Change] = Events { k =>
      listeners :+= k
      val held = waiting
      waiting = Vector.empty
      held.foreach(k)
      () => listeners = listeners.filterNot(_ eq k)
    }

    def connect(): Unit = if ws.isEmpty then
      val s = new dom.WebSocket(url)
      ws = Some(s)
      s.onopen = _ =>
        open = true
        waitMs = 1000
        // a socket opened again: what came while it was closed is read
        if opened then tell(Change.Again)
        opened = true
      s.onmessage = e =>
        val j = js.JSON.parse(e.data.asInstanceOf[String])
        tell(Change.Came(j.n.asInstanceOf[Int], turn(j.turn)))
      s.onclose = _ =>
        open = false
        ws = None
        val again = waitMs
        waitMs = math.min(waitMs * 2, 30000)
        dom.window.setTimeout(() => connect(), again)

    def start(): Unit =
      connect()
      dom.document.addEventListener("visibilitychange", (_: dom.Event) =>
        if dom.document.visibilityState == "visible" && ws.isEmpty then connect())

  /** THE WORKER'S: a message POSTed to /c/<id>, the conversation read at /c/<id>/page, its turns by the socket at
   * /c/<id>/socket */
  def overHttp[R[_], Rest[_], A](id: String)(p: A ! R)(using Split[Talk, R, Rest], In[Async, Rest]): A ! Rest =
    val url = s"/c/$id"
    val loc = dom.window.location
    val socket = Socket(s"${if loc.protocol == "https:" then "wss" else "ws"}://${loc.host}$url/socket")
    var started = false
    def asked[X](f: => scala.concurrent.Future[X]): Either[String, X] ! Rest =
      Async.fromFuture[Either[String, X], Rest](f.map(Right(_)).recover { case e => Left(e.getMessage) })
    handle(p, Handler.General[Talk, Rest, A, A](a => pure(a), [X] => (op: Talk[X], k: X => A ! Rest) => op match
      case Say(words) =>
        val init = new dom.RequestInit {}
        init.method = dom.HttpMethod.POST
        init.body = words
        asked(dom.fetch(url, init).toFuture.map(r => if r.ok then () else throw js.JavaScriptException(r.statusText)))
          .flatMap(r => k(r))
      case Read() =>
        asked(dom.fetch(s"$url/page").toFuture.flatMap(_.json().toFuture).map { j =>
          val d = j.asInstanceOf[js.Dynamic]
          (d.count.asInstanceOf[Int], d.turns.asInstanceOf[js.Array[js.Dynamic]].toVector.map(turn))
        }).flatMap(r => k(r))
      case Changes() =>
        if !started then { started = true; socket.start() }
        k(socket.events)
      case Live() => k(socket.live)))
