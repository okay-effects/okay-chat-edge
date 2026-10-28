package okaychatedge

import scala.scalajs.js
import scala.scalajs.js.JSStringOps.*

/**
 * WHICH LANGUAGE TO ANSWER IN — okay-chat's `Lang` over okay's `Language.Cues`, ported: the message's script first
 * (any Cyrillic letter makes it Cyrillic: Latin rides inside as an address or a brand), then within it the letters one
 * language owns alone and okay-chat's everyday words; an amount with its currency counts for none («вход 40 zł» is not
 * Polish). The unique best with any evidence wins; else `None`, and the conversation answers in what this person
 * wrote last — a three-word turn («берусь 1») is never guessed.
 */
enum Lang:
  case Ru, Uk, Pl, En

object Lang:
  val default: Lang = Ru

  def code(l: Lang): String = l.toString.toLowerCase
  def parse(s: String): Option[Lang] = values.find(code(_) == s.trim.toLowerCase)

  private val marks: Map[Lang, Set[Char]] = Map(
    Ru -> Set('ы', 'э', 'ъ', 'ё'), Uk -> Set('і', 'ї', 'є', 'ґ'),
    Pl -> Set('ą', 'ć', 'ę', 'ł', 'ń', 'ś', 'ź', 'ż'), En -> Set.empty)

  // okay-chat's words (its Lang.scala), as they are
  private val words: Map[Lang, Set[String]] = Map(
    Uk -> Set("не", "що", "як", "мені", "мене", "потрібен", "потрібно", "треба", "шукаю", "хто", "де", "чи", "дуже",
      "будь", "ласка", "так", "ні", "куди", "сьогодні", "ввечері", "зараз", "якщо", "чому", "коли", "скільки", "хочу",
      "можу", "маю", "буде", "було", "їхати", "піти", "знайти", "вхід", "квиток", "привіт", "вітаю", "добрий",
      "доброго", "дякую", "дякуємо", "бувай", "прощавай"),
    Ru -> Set("что", "как", "мне", "меня", "нужен", "нужно", "ищу", "кто", "где", "очень", "пожалуйста", "да", "нет",
      "это", "куда", "сегодня", "вечером", "сейчас", "если", "почему", "когда", "сколько", "хочу", "могу", "имею",
      "будет", "было", "ехать", "сходить", "найти", "вход", "билет", "привет", "здравствуйте", "здравствуй", "добрый",
      "доброе", "спасибо", "пока"),
    Pl -> Set("nie", "tak", "jest", "jestem", "czy", "gdzie", "kto", "potrzebuję", "szukam",
      "proszę", "dziękuję", "mam", "chcę", "dla", "cześć", "dzień", "witam", "dobry", "dzięki", "pa"),
    En -> Set("the", "is", "are", "need", "want", "can", "please", "thanks", "you", "my", "me", "for", "who", "what",
      "how", "hi", "hello", "hey", "morning", "evening", "bye", "cheers"))

  // okay-chat's amount (`(?iU)`, `\w` a letter in any script), as a JS RegExp: a price is not a language
  private val w = "[\\p{L}\\p{N}_]"
  private val amount = new js.RegExp(
    s"\\d[\\d\\s.,]*\\s*(?:zł|zl|pln|złot$w*|злот$w*|зл|€|eur|евро|євро|\\$$|usd|доллар$w*|долар$w*|dolar$w*|" +
      s"₴|uah|грн|гривн?$w*|₽|rub|руб$w*|рубл$w*|kč|czk|£|gbp|фунт$w*)|" +
      "(?:zł|zl|pln|€|eur|\\$|usd|₴|uah|грн|₽|rub|kč|czk|£|gbp)\\s*\\d[\\d\\s.,]*", "giu")
  private val nonLetters = new js.RegExp("[^\\p{L}]+", "u")

  private def cyrillic(c: Char): Boolean = c >= 'Ѐ' && c <= 'ԯ'
  private def latin(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'À' && c <= 'ɏ')

  private def isCyrillic(l: Lang): Boolean = l == Ru || l == Uk

  /**
   * the language to answer in: the message's own, else the one this person wrote last — if it is in the message's
   * alphabet. «мои предложения» after a Polish turn is not Polish (measured live 2026-10-29): Cyrillic with no evidence
   * is Russian, Latin with none English, unless the last language shares the alphabet.
   */
  def answer(text: String, last: Option[Lang]): Lang =
    of(text).getOrElse {
      val t = text.toLowerCase
      if t.exists(cyrillic) then last.filter(isCyrillic).getOrElse(Ru)
      else if t.exists(latin) then last.filterNot(isCyrillic).getOrElse(En)
      else last.getOrElse(default)
    }

  def of(text: String): Option[Lang] =
    val t = text.toLowerCase.jsReplace(amount, " ")
    val ws = t.jsSplit(nonLetters).toVector.filter(_.nonEmpty)
    val candidates =
      if t.exists(cyrillic) then Vector(Ru, Uk)
      else if t.exists(latin) then Vector(Pl, En)
      else values.toVector
    val scored = candidates.map(l => l -> (t.count(marks(l)) + ws.count(words(l)))).sortBy(-_._2)
    scored match
      case (best, n) +: rest if n > 0 && rest.headOption.forall(_._2 < n) => Some(best)
      case _ => None
