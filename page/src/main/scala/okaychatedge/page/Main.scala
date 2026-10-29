package okaychatedge.page

import okay.business.dom.Dom
import okay.business.faces.Skin
import okay.scala3.std.Async
import org.scalajs.dom
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

/** the words of the page itself, in the browser's language — the chat answers in the language of what is written */
final case class Words(market: String, message: String, example: String, send: String, offline: String => String,
                       inTelegram: String, telegram: Option[String] = None):
  /** okay-chat's greeting: all four languages, before a word of theirs is read */
  val greeting: String = Vector("Hi! How can I help?", "Cześć! W czym mogę pomóc?", "Вітаю! Чим можу допомогти?",
    "Привет! Чем могу помочь?").mkString("\n")

object Words:
  def of(lang: String, telegram: Option[String]): Words = (lang.take(2).toLowerCase match
    case "uk" => Words("ринок попиту і пропозиції", "Повідомлення", "наприклад: потрібен електрик, Вроцлав",
      "Надіслати", e => s"немає зв'язку — спробуйте ще раз ($e)", "Telegram ↗")
    case "pl" => Words("rynek popytu i podaży", "Wiadomość", "na przykład: potrzebny elektryk, Wrocław", "Wyślij",
      e => s"brak połączenia — spróbuj jeszcze raz ($e)", "Telegram ↗")
    case "en" => Words("a market of supply and demand", "Message", "for example: need an electrician, Wrocław", "Send",
      e => s"no connection — try again ($e)", "Telegram ↗")
    case _ => Words("рынок спроса и предложения", "Сообщение", "например: нужен электрик, Вроцлав", "Отправить",
      e => s"нет связи — попробуйте ещё раз ($e)", "Telegram ↗")).copy(telegram = telegram)

/** THE PAGE, one line: the chat's screens on okay-faces' DOM face, its conversation this Worker's */
object Main:
  def main(args: Array[String]): Unit =
    val root = Option(dom.document.getElementById("app")).getOrElse(dom.document.body)
    Typing.install()
    val _ = Async.toFuture(Dom.run(root, Look.skin, follow = true)(
      Talk.overHttp(Talk.mine())(Chat.main(Words.of(dom.window.navigator.language,
        Option(root.getAttribute("data-telegram")).map(_.trim.stripPrefix("@")).filter(_.nonEmpty))))))

/** okay-ui's calm skin in the chat's violet, and the conversation's marks: bubbles, mine to the right (`css` first:
 * an object's vals are made in order, and `skin` reads it) */
object Look:
  val css: String =
    """
      |body .okay-ui { max-width: 760px; margin: 0 auto; }
      |body .okay-ui h1.okay-heading-1 { display: none; }
      |body .okay-mark-head { display: flex; gap: 12px; align-items: center; padding: 4px 0 12px;
      |  border-bottom: 1px solid color-mix(in srgb, currentColor 12%, transparent); }
      |body .okay-mark-head > .okay-group, body .okay-mark-who > .okay-group { display: contents; }
      |body .okay-mark-avatar { width: 40px; height: 40px; border-radius: 50%; display: grid; place-items: center;
      |  font-weight: 700; color: #fff; background: linear-gradient(135deg, #ff8a5c, #ff4d6d); flex: none; }
      |body .okay-mark-who { display: flex; flex-direction: column; }
      |body .okay-mark-head .okay-text { margin: 0; }
      |body .okay-mark-telegram { margin-left: auto; }
      |body .okay-mark-telegram .okay-link { display: inline-block; padding: 6px 12px; border-radius: 16px;
      |  background: #229ed9; color: #fff; text-decoration: none; font-weight: 600; font-size: 14px; }
      |body .okay-mark-name .okay-text { font-weight: 600; }
      |body .okay-mark-sub .okay-text { font-size: 13px; opacity: .65; }
      |body .okay-mark-log { display: block; padding: 12px 0; }
      |body .okay-mark-log > .okay-group { display: flex; flex-direction: column; gap: 8px; }
      |body .okay-mark-me, body .okay-mark-bot, body .okay-mark-me-sending, body .okay-mark-bot-typing {
      |  display: block; max-width: 82%; padding: 8px 12px; border-radius: 16px; }
      |body .okay-mark-log .okay-text { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
      |body .okay-mark-log > .okay-group > .okay-mark > .okay-group { display: block; }
      |body .okay-mark-log .okay-mark .okay-text { display: inline; }
      |body .okay-mark-log .okay-link { color: var(--okay-accent, #7b5cff); text-decoration: underline;
      |  text-underline-offset: 2px; overflow-wrap: anywhere; }
      |body .okay-mark-log :is(.okay-mark-me, .okay-mark-me-sending) .okay-link { color: #fff; }
      |body .okay-mark-me, body .okay-mark-me-sending { align-self: flex-end; background: #7b5cff; color: #fff;
      |  border-bottom-right-radius: 4px; }
      |body .okay-mark-me-sending, body .okay-mark-bot-typing { opacity: .6; }
      |body .okay-mark-bot, body .okay-mark-bot-typing { align-self: flex-start; border-bottom-left-radius: 4px;
      |  background: color-mix(in srgb, currentColor 8%, transparent); }
      |body .okay-ui nav.okay-actions { position: sticky; bottom: 0; background: var(--okay-bg, Canvas);
      |  padding: 8px 0 calc(8px + env(safe-area-inset-bottom)); }
      |body .okay-ui nav.okay-actions form.okay-form { display: flex !important; flex-direction: row !important;
      |  gap: 8px; align-items: center; padding: 8px; }
      |body .okay-ui nav.okay-actions form.okay-form fieldset { flex: 1; border: 0; margin: 0; padding: 0;
      |  min-width: 0; }
      |body .okay-ui nav.okay-actions .okay-field { margin: 0; }
      |body .okay-ui nav.okay-actions form.okay-form { box-sizing: border-box; max-width: 100%; }
      |body .okay-ui nav.okay-actions form.okay-form { align-items: flex-end !important; }
      |body .okay-ui nav.okay-actions form.okay-form > fieldset { flex: 1 1 0; min-width: 0; display: block; }
      |body .okay-ui nav.okay-actions .okay-field { width: 100%; min-width: 0; }
      |body .okay-ui nav.okay-actions form.okay-form > button { flex: none; width: auto; min-width: 0; margin: 0;
      |  align-self: flex-end;
      |  min-height: 44px; padding-inline: 16px; }
      |body .okay-ui nav.okay-actions .okay-label, body .okay-ui nav.okay-actions .okay-doc {
      |  position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
      |body .okay-ui nav.okay-actions :is(input, textarea) { display: block; width: 100%; box-sizing: border-box;
      |  border-radius: 20px; padding: 10px 14px; font: inherit; line-height: 1.35; resize: none;
      |  min-height: 44px; max-height: 40vh; overflow-y: auto; field-sizing: content; }
      |""".stripMargin

  val skin: Skin =
    val calm = Skin.calm(accent = "#7b5cff", deep = "#2a1f66")
    Skin(calm.look, calm.css + css)

/**
 * WRITING A MESSAGE, as in a messenger — what the page's face leaves to the page: the line grows with what is
 * written (where the browser has no `field-sizing`), and on a computer Enter sends, Shift+Enter is a new line; on a
 * phone the keyboard's return is a new line and the button sends.
 */
object Typing:
  /** whether the page should stay at its end: the person was there, or has just sent — a screen drawn anew (after
   * the «…» of sending) starts at the top, and is brought back to the end */
  private var atEnd = true
  private def end(): Double = dom.document.documentElement.scrollHeight.toDouble
  private def nearEnd(): Boolean = dom.window.innerHeight + dom.window.scrollY >= end() - 120

  def install(): Unit =
    dom.window.addEventListener("scroll", (_: dom.Event) => atEnd = nearEnd())
    dom.document.addEventListener("submit", (_: dom.Event) => atEnd = true, true)
    new dom.MutationObserver((_, _) => if atEnd && !nearEnd() then dom.window.scrollTo(0, end().toInt))
      .observe(dom.document.body, new dom.MutationObserverInit { childList = true; subtree = true })
    val fine = dom.window.matchMedia("(pointer: fine)").matches
    val grows = js.Dynamic.global.CSS.supports("field-sizing", "content").asInstanceOf[Boolean]
    def line(t: dom.EventTarget): Option[dom.HTMLTextAreaElement] = t match
      case a: dom.HTMLTextAreaElement if a.closest("nav.okay-actions") != null => Some(a)
      case _ => None
    dom.document.addEventListener("input", (e: dom.Event) => if !grows then line(e.target).foreach { a =>
      a.style.height = "auto"
      a.style.height = s"${a.scrollHeight}px"
    })
    dom.document.addEventListener("keydown", (e: dom.KeyboardEvent) =>
      if fine && e.key == "Enter" && !e.shiftKey && !e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[Boolean] then
        line(e.target).foreach { a =>
          e.preventDefault()
          a.form.asInstanceOf[js.Dynamic].requestSubmit()
        })
