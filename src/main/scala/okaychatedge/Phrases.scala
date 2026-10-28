package okaychatedge

import okaychatedge.Lang.*

/**
 * WHAT THE CHAT SAYS, in four languages — okay-chat's `Phrases`, the ones the edge says so far, word for word (its
 * comments carry the operator's reasons: one promise everywhere, to ASK; deal numbers are not the seeker's to know).
 */
object Phrases:

  def storedOffer(l: Lang, what: String): String = l match
    case Ru => s"""записал предложение: "$what""""
    case Uk => s"""записав пропозицію: "$what""""
    case Pl => s"""zapisałem ofertę: "$what""""
    case En => s"""stored offer: "$what""""

  def nobodyYet(l: Lang): String = l match
    case Ru => "пока никого не нашёл — запомнил ваш запрос и спрошу, писать ли ему, когда кто-то появится"
    case Uk => "поки нікого не знайшов — запам'ятав ваш запит і запитаю, чи писати йому, коли хтось з'явиться"
    case Pl => "na razie nikogo nie znalazłem — zapamiętałem prośbę i zapytam, czy napisać, gdy ktoś się pojawi"
    case En => "nobody yet — I remembered your request and will ask whether to write when someone shows up"

  def appeared(l: Lang, what: String, request: String): String = l match
    case Ru => s"""по вашему запросу "$request" появилось предложение: $what — скажите "спроси 1", если интересно"""
    case Uk => s"""за вашим запитом "$request" з'явилася пропозиція: $what — скажіть "спитай 1", якщо цікаво"""
    case Pl => s"""na twoje zgłoszenie "$request" pojawiła się oferta: $what — powiedz "zapytaj 1", jeśli chcesz"""
    case En => s"""someone turned up for your request "$request": $what — say "ask 1" if you want them"""

  def howToAsk(l: Lang, n: Int): String =
    if n <= 1 then l match
      case Ru => "скажите: спроси 1"
      case Uk => "скажіть: спитай 1"
      case Pl => "powiedz: zapytaj 1"
      case En => "say: ask 1"
    else l match
      case Ru => s"скажите: спроси 1 2 (или: спроси всех)"
      case Uk => s"скажіть: спитай 1 2 (або: спитай усіх)"
      case Pl => s"powiedz: zapytaj 1 2 (albo: zapytaj wszystkich)"
      case En => s"say: ask 1 2 (or: ask all)"

  def found(l: Lang, n: Int, lines: String): String = (l match
    case Ru => s"нашёл:\n$lines\n"
    case Uk => s"знайшов:\n$lines\n"
    case Pl => s"znalazłem:\n$lines\n"
    case En => s"found:\n$lines\n") + howToAsk(l, n)

  def searchFirst(l: Lang): String = l match
    case Ru => "сначала скажите, что вам нужно — я найду кандидатов"
    case Uk => "спочатку скажіть, що вам потрібно — я знайду кандидатів"
    case Pl => "najpierw powiedz, czego potrzebujesz — znajdę kandydatów"
    case En => "tell me what you need first — I will find candidates"

  def whichOnes(l: Lang): String = l match
    case Ru => "кого спросить? Назовите номера или скажите: спроси всех"
    case Uk => "кого спитати? Назвіть номери або скажіть: спитай усіх"
    case Pl => "kogo zapytać? Podaj numery albo powiedz: zapytaj wszystkich"
    case En => "who should I ask? Name the numbers, or say: ask all"

  def asked(l: Lang, n: Int, reached: Int): String =
    val all = reached >= n
    l match
      case Ru => if all then s"спросил $n — сообщу, кто возьмётся"
        else s"спросил $n, дошло до $reached — остальным сообщить не смог; отвечу, кто возьмётся"
      case Uk => if all then s"спитав $n — повідомлю, хто візьметься"
        else s"спитав $n, дійшло до $reached — решті повідомити не зміг; скажу, хто візьметься"
      case Pl => if all then s"zapytałem $n — dam znać, kto się podejmie"
        else s"zapytałem $n, dotarło do $reached — reszty nie udało się powiadomić; dam znać, kto się podejmie"
      case En => if all then s"asked $n — I will tell you who takes it"
        else s"asked $n, reached $reached — could not notify the rest; I will tell you who takes it"

  def youWereAsked(l: Lang, deal: Long, what: String): String = l match
    case Ru => s"Вас спрашивают: «$what» (сделка $deal). Ответьте «берусь $deal» — " +
      s"и я передам ваш контакт, — или «отказываюсь $deal»."
    case Uk => s"Вас питають: «$what» (угода $deal). Відповідайте «берусь $deal» — " +
      s"і я передам ваш контакт, — або «відмовляюся $deal»."
    case Pl => s"Ktoś pyta o: „$what” (transakcja $deal). Odpowiedz „biorę $deal” — " +
      s"przekażę twój kontakt — albo „odmawiam $deal”."
    case En => s"Someone is asking about: \"$what\" (deal $deal). Reply \"take $deal\" — " +
      s"and I will pass on your contact — or \"decline $deal\"."

  def notYourDeal(l: Lang): String = l match
    case Ru => "эта сделка не ваша или уже закрыта"
    case Uk => "ця угода не ваша або вже закрита"
    case Pl => "to nie jest twoja transakcja albo jest już zamknięta"
    case En => "not your deal, or already closed"

  def dealBound(l: Lang, n: Long, what: String, contact: String): String =
    val who = if contact.isEmpty then "" else (l match
      case Ru => s" Контакт: $contact."
      case Uk => s" Контакт: $contact."
      case Pl => s" Kontakt: $contact."
      case En => s" Contact: $contact.")
    (l match
      case Ru => s"По сделке $n («$what») согласились взяться."
      case Uk => s"За угодою $n («$what») погодилися взятися."
      case Pl => s"W transakcji $n („$what”) ktoś się podjął."
      case En => s"Deal $n (\"$what\") has been accepted.") + who

  def accepted(l: Lang, n: Long, contact: String): String =
    val extra = if contact.isEmpty then "" else (l match
      case Ru => s"; его контакт: $contact"
      case Uk => s"; його контакт: $contact"
      case Pl => s"; jego kontakt: $contact"
      case En => s"; their contact: $contact")
    (l match
      case Ru => s"передал согласие по сделке $n — вторая сторона получила ваш контакт"
      case Uk => s"передав згоду за угодою $n — інша сторона отримала ваш контакт"
      case Pl => s"przekazałem zgodę w transakcji $n — druga strona dostała twój kontakt"
      case En => s"accepted deal $n — the other side got your contact") + extra

  def declined(l: Lang, n: Long): String = l match
    case Ru => s"передал отказ по сделке $n"
    case Uk => s"передав відмову за угодою $n"
    case Pl => s"przekazałem odmowę w transakcji $n"
    case En => s"declined deal $n"

  def howCanIHelp(l: Lang): String = l match
    case Ru => "Чем я могу вам помочь?"
    case Uk => "Чим я можу вам допомогти?"
    case Pl => "W czym mogę pomóc?"
    case En => "How can I help?"

  /** the first thing anyone sees, before a word of theirs is read: all four languages at once */
  def greeting: String =
    Vector(
      "Hi! How can I help?",
      "Cześć! W czym mogę pomóc?",
      "Вітаю! Чим можу допомогти?",
      "Привет! Чем могу помочь?"
    ).mkString("\n")

  def smallTalk(l: Lang): String = l match
    case Ru => "это рынок спроса и предложения: здесь предлагают работу, услуги, товары, аренду — и ищут их. "
    case Uk => "це ринок попиту і пропозиції: тут пропонують роботу, послуги, товари, оренду — і шукають їх. "
    case Pl => "to rynek popytu i podaży: tu oferuje się pracę, usługi, towary, wynajem — i ich szuka. "
    case En => "this is a market of supply and demand: people offer work, services, goods, rentals — and look for them. "

  /** okay-chat's help: built from the intent table's rows (here: the intents the edge acts on) */
  def help(l: Lang, rows: Vector[String]): String =
    val head = l match
      case Ru => "Вот всё, что я умею."
      case Uk => "Ось усе, що я вмію."
      case Pl => "Oto wszystko, co potrafię."
      case En => "Here is everything I can do."
    val tail = l match
      case Ru => "Пишите обычными словами — команды знать не обязательно, примеры выше просто короче."
      case Uk => "Пишіть звичайними словами — команди знати не обов'язково, приклади вище просто коротші."
      case Pl => "Pisz zwykłymi słowami — komend znać nie trzeba, powyższe przykłady są po prostu krótsze."
      case En => "Write in ordinary words: you do not need the commands, the examples above are just shorter."
    s"$head\n\n${rows.mkString("\n")}\n\n$tail"

  /** what a person offered (okay-chat's, without its «удали предложение 1»: the edge cannot take one down yet) */
  def offers(l: Lang, rows: Vector[String]): String =
    (l match
      case Ru => "ваши предложения:"
      case Uk => "ваші пропозиції:"
      case Pl => "twoje oferty:"
      case En => "your offers:") + rows.zipWithIndex.map((w, i) => s"\n${i + 1}) $w").mkString

  def noOffers(l: Lang): String = l match
    case Ru => "У вас пока нет предложений. Расскажите, что предлагаете или что объявляете — " +
      "и я запишу."
    case Uk => "У вас поки немає пропозицій. Розкажіть, що пропонуєте чи що оголошуєте — " +
      "і я запишу."
    case Pl => "Nie masz jeszcze ofert. Powiedz, co oferujesz albo co ogłaszasz — zapiszę."
    case En => "You have no offers yet. Tell me what you offer or what you are announcing " +
      "and I will record it."

  def requests(l: Lang, rows: Vector[String]): String =
    (l match
      case Ru => "ваши заявки:"
      case Uk => "ваші заявки:"
      case Pl => "twoje zgłoszenia:"
      case En => "your requests:") + rows.zipWithIndex.map((w, i) => s"\n${i + 1}) $w").mkString

  /** the edge keeps every need it is told (no «запомни» needed, as okay-chat's wording asks) */
  def noRequests(l: Lang): String = l match
    case Ru => "У вас пока нет заявок. Скажите, что ищете — я поищу и сообщу о новых."
    case Uk => "У вас поки немає заявок. Скажіть, що шукаєте — я пошукаю і повідомлю про нові."
    case Pl => "Nie masz jeszcze zgłoszeń. Powiedz, czego szukasz — poszukam i dam znać o nowych."
    case En => "You have no requests yet. Tell me what you are looking for — I will search and tell you about new ones."
