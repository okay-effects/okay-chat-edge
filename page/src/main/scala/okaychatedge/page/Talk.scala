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

/**
 * THE CONVERSATION, AS THE PAGE ASKS IT — the effect; where it is (this Worker's /c/<id>, a test's list) is the
 * handler's. `Changes`: when to read the conversation again — what another party tells this one (a deal asked, an
 * offer for a waiting need) arrives as a turn the page did not ask for.
 */
enum Talk[X]:
  case Say(words: String) extends Talk[Either[String, Unit]]
  case Read() extends Talk[Either[String, Vector[Turn]]]
  case Changes() extends Talk[Events[Unit]]

object Talk:
  def say[R[_]](words: String)(using In[Talk, R]): Either[String, Unit] ! R = performAt[Talk, R, Either[String, Unit]](
    Say(words))
  def read[R[_]](using In[Talk, R]): Either[String, Vector[Turn]] ! R =
    performAt[Talk, R, Either[String, Vector[Turn]]](Read())
  def changes[R[_]](using In[Talk, R]): Events[Unit] ! R = performAt[Talk, R, Events[Unit]](Changes())

  /** this browser's conversation: `web:` and 32 hex digits, made once and kept in localStorage — unguessable, so it is
   * the only key to it */
  def mine(): String =
    val key = "okay-chat-edge.id"
    Option(dom.window.localStorage.getItem(key)).getOrElse {
      val made = "web:" + js.Dynamic.global.crypto.randomUUID().asInstanceOf[String].replace("-", "")
      dom.window.localStorage.setItem(key, made)
      made
    }

  private def turns(j: js.Any): Vector[Turn] =
    j.asInstanceOf[js.Array[js.Dynamic]].toVector.map(t => Turn(
      t.text.asInstanceOf[js.UndefOr[String]].toOption,
      t.reply.asInstanceOf[js.UndefOr[String]].toOption.orElse(t.told.asInstanceOf[js.UndefOr[String]].toOption)))

  /**
   * THE WORKER'S: a message POSTed to /c/<id>, the conversation read by GET; changes every `every` ms while the page
   * is in view, and when it comes back into view — each read is one of the free plan's requests a day.
   */
  def overHttp[R[_], Rest[_], A](id: String, every: Int = 20000)(p: A ! R)(
    using Split[Talk, R, Rest], In[Async, Rest]): A ! Rest =
    val url = s"/c/$id"
    def asked[X](f: => scala.concurrent.Future[X]): Either[String, X] ! Rest =
      Async.fromFuture[Either[String, X], Rest](f.map(Right(_)).recover { case e => Left(e.getMessage) })
    handle(p, Handler.General[Talk, Rest, A, A](a => pure(a), [X] => (op: Talk[X], k: X => A ! Rest) => op match
      case Say(words) =>
        val init = new dom.RequestInit {}
        init.method = dom.HttpMethod.POST
        init.body = words
        asked(dom.fetch(url, init).toFuture.map(r => if r.ok then () else throw js.JavaScriptException(r.statusText)))
          .flatMap(r => k(r))
      case Read() => asked(dom.fetch(url).toFuture.flatMap(_.json().toFuture).map(turns)).flatMap(r => k(r))
      case Changes() => k(Events[Unit] { fire =>
        val visible = () => dom.document.visibilityState == "visible"
        val timer = dom.window.setInterval(() => if visible() then fire(()), every)
        val back: js.Function1[dom.Event, Unit] = _ => if visible() then fire(())
        dom.document.addEventListener("visibilitychange", back)
        () => { dom.window.clearInterval(timer); dom.document.removeEventListener("visibilitychange", back) }
      })))
