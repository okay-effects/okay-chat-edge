#!/bin/sh
# end to end on wrangler dev (workerd): two people, a deal asked, a contact given, the deal answered, both told.
# `scripts/e2e.sh` after `sbt worker`, `scripts/data.sh` and `scripts/kv.sh --local`
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
port=${PORT:-8799}
cd "$here/worker"
rm -rf .wrangler/state/v3/do .wrangler/state/v3/d1
tg=$((port + 1))
node "$here/scripts/fake-telegram.mjs" "$tg" /tmp/okay-chat-edge-tg.jsonl & tgpid=$!
npx wrangler dev --port "$port" --var EMBED:trigrams --var LANE_DAILY:0 --var OPEN_API:yes --var TELEGRAM_TOKEN:test-token --var TELEGRAM_SECRET:test-secret \
  --var "TELEGRAM_API:http://127.0.0.1:$tg" --var "JOBS_API:http://127.0.0.1:$tg" > /tmp/okay-chat-edge-e2e.log 2>&1 &
pid=$!
trap 'kill $pid $tgpid 2>/dev/null' EXIT
for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$port/" && break; sleep 1; done
u="http://127.0.0.1:$port"
fail=0
check() { # what, got, expected substring
  case "$2" in *"$3"*) echo "ok   $1" ;; *) echo "FAIL $1: expected $3 in: $2"; fail=1 ;; esac
}
say() { curl -s -X POST --data "$2" "$u/c/$1"; }

check "an offer by rule" "$(say bob 'ремонтирую сантехнику, Вроцлав, bob@example.com')" '"intent":"offer"'
check "another offer" "$(say cara 'могу преподавать английский онлайн, cara@example.com')" 'записал предложение'
check "no candidates yet asked for" "$(say ann 'спроси 1')" 'сначала скажите, что вам нужно'
need=$(say ann 'нужен сантехник, течёт кран')
check "a need routed by rule" "$need" '"intent":"need"'
check "a need answered with the closest offer first" "$need" '1. ремонтирую сантехнику'
check "the unrelated offer is not a candidate" "$need" 'нашёл'
case "$need" in *английский*) echo "FAIL the English teacher offered for a plumber: $need"; fail=1 ;; *) echo "ok   not the teacher" ;; esac
check "an address noticed" "$(say ann 'мой адрес ann@example.com')" '"turn"'
check "a number out of the list" "$(say ann 'спроси 5')" 'кого спросить'
check "the shown candidate asked, no deal number to the seeker" "$(say ann 'спроси 1')" 'спросил 1 — сообщу'
check "the provider told how to answer" "$(curl -s "$u/c/bob")" 'Вас спрашивают: «нужен сантехник, течёт кран» (сделка 1)'
check "the seeker cannot accept her own ask" "$(say ann 'берусь 1')" 'эта сделка не ваша'
check "the provider accepts: the seeker's address back" "$(say bob 'берусь 1')" \
  'передал согласие по сделке 1 — вторая сторона получила ваш контакт; его контакт: ann@example.com'
check "the seeker told: accepted, and the provider's address" "$(curl -s "$u/c/ann")" \
  'По сделке 1 («нужен сантехник, течёт кран») согласились взяться. Контакт: bob@example.com.'
check "the deal accepted" "$(curl -s "$u/deals/1")" '"state":"accepted"'
check "an accepted deal is not declined after" "$(say bob 'отказываюсь 1')" 'эта сделка не ваша'
deal2=$(curl -s -X POST --data '{"seeker":"ann","provider":"bob","what":"ещё одна работа"}' "$u/deals")
check "a deal asked directly" "$deal2" '"deal":2'
check "the provider cannot withdraw it" "$(curl -s -X POST --data '{"by":"bob"}' "$u/deals/2/withdraw")" 'эта сделка не ваша'
check "the seeker withdraws it" "$(curl -s -X POST --data '{"by":"ann"}' "$u/deals/2/withdraw")" '2: withdrawn'
check "the provider told" "$(curl -s "$u/c/bob")" '2: отбой по заказу: ещё одна работа'
# a need kept, and told when an offer for it comes
check "a need with nobody yet" "$(say dan 'нужен электрик, Краков')" 'пока никого не нашёл'
check "the offer that answers it: the waiting told" "$(say eve 'занимаюсь электромонтажом, eve@example.com')" 'записал предложение'
check "the need told of the offer" "$(curl -s "$u/c/dan")" 'появилось предложение: занимаюсь электромонтажом'
check "and its «спроси 1» asks it" "$(say dan 'спроси 1')" 'спросил 1'
check "the new provider told" "$(curl -s "$u/c/eve")" 'Вас спрашивают: «нужен электрик, Краков» (сделка 3)'

# each answered in its own language; a turn with no evidence of one («берусь 9») in what the person wrote last
check "Ukrainian" "$(say fay 'потрібен репетитор з математики, дуже треба')" 'знайшов'
check "Ukrainian, kept" "$(say fay 'берусь 9')" 'ця угода не ваша'
check "Polish" "$(say gus 'szukam hydraulika, potrzebuję szybko')" 'znalaz'
check "Polish, kept" "$(say gus 'biorę 9')" 'to nie jest twoja transakcja'
check "English" "$(say hal 'I need a plumber for my kitchen')" 'nobody yet'
check "English, kept" "$(say hal 'take 9')" 'not your deal'
check "Cyrillic after Polish: not Polish" "$(say gus 'мои предложения')" 'У вас пока нет предложений'
check "a price is not a language" "$(say hal 'take 9, 40 zł')" 'not your deal'

# Telegram: a webhook's update is a turn of `tg:<id>`, the reply in the webhook's answer; the other side by the bot
tgsay() { # user id, username, text
  curl -s -X POST -H 'x-telegram-bot-api-secret-token: test-secret' -H 'content-type: application/json' \
    --data "{\"update_id\":1,\"message\":{\"message_id\":1,\"chat\":{\"id\":$1,\"type\":\"private\"},
      \"from\":{\"id\":$1,\"username\":\"$2\"},\"text\":\"$3\"}}" "$u/telegram"
}
check "a Telegram conversation is not the API's to read" \
  "$(curl -s -o /dev/null -w '%{http_code}' "$u/c/tg:101")" '403'
check "…nor to speak in" "$(curl -s -o /dev/null -w '%{http_code}' -X POST --data 'берусь 1' "$u/c/tg:101")" '403'
# vacancies from Workable's search (the stand-in): the words, the place, remote read from the message
jobs=$(say jobs1 'ищу работу scala разработчиком во Вроцлаве')
check "vacancies: the keyword and the city" "$jobs" 'вакансии «scala», Wrocław — всего 2, первые 2:'
check "…each with the employer and its page" "$jobs" '1. Scala Engineer — Acme, Wrocław, hybrid\nhttps://jobs.workable.com/view/1'
check "…the city asked as Workable reads it" "$(grep '"jobs"' /tmp/okay-chat-edge-tg.jsonl | tail -1)" \
  '"location":"Wrocław","query":"scala"'
say jobs2 'szukam pracy scala zdalnie' > /dev/null
check "Polish, remote" "$(grep '"jobs"' /tmp/okay-chat-edge-tg.jsonl | tail -1)" '"workplace":"remote"'
check "none found: another word suggested" "$(say jobs3 'вакансии cobol, Kraków')" 'по «cobol», Kraków вакансий не нашёл'
check "an offer of work is not a job search" "$(say jobs5 'могу работать сантехником, Вроцлав')" 'записал предложение'
pageid="web:$(openssl rand -hex 16)"
check "a page's socket: its turn pushed as it is kept" "$(node "$here/scripts/socket-check.mjs" "$u" "$pageid" 2>&1)" \
  '{"n":1,"turn":{"text":"помощь","reply":"Вот всё, что я умею.'
check "…and read as the page reads it" "$(curl -s "$u/c/$pageid/page")" '"count":1'
check "no socket to a conversation not a page's" \
  "$(curl -s -o /dev/null -w '%{http_code}' -H 'upgrade: websocket' "$u/c/tg:101/socket")" '403'
check "the page is served" "$(curl -s "$u/")" '<script type="module" src="/page.js">'
check "an update without the secret refused" \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST --data '{}' "$u/telegram")" '403'
check "/start: the greeting, in four languages" "$(tgsay 101 ivan '/start')" 'Привет! Чем могу помочь?'
check "a Telegram offer, answered in the webhook" "$(tgsay 101 ivan 'умею класть плитку в ванной, Вроцлав')" \
  '"method":"sendMessage","chat_id":"101","text":"записал предложение'
check "not understood, no model: what this is, and the help (Latin, no evidence: English)" \
  "$(tgsay 102 jane 'hmm')" 'this is a market of supply and demand'
check "help: okay-chat's rows, only what the edge does" "$(say help1 'помощь')" '«берусь 3»'
check "help names the vacancies" "$(say help1 'помощь')" '«вакансии scala, Вроцлав»'
case "$(say help1 'помощь')" in *резюме*) echo "FAIL help lists what the edge cannot do"; fail=1 ;; *) echo "ok   help: no résumé" ;; esac
check "my offers" "$(say bob 'мои предложения')" 'ваши предложения:\n1) ремонтирую сантехнику'
check "my requests, none" "$(say bob 'мои заявки')" 'У вас пока нет заявок'
check "«ищу работу»: which work is asked, nothing searched" "$(say job1 'Ищу работу')" 'Какую работу ищете?'
case "$(curl -s "$u/c/job1")" in *shown*) echo "FAIL «ищу работу» searched"; fail=1 ;; *) echo "ok   not searched" ;; esac
check "a Telegram need finds the Telegram offer" "$(tgsay 102 jane 'нужно положить плитку в ванной')" \
  'умею класть плитку'
tgsay 102 jane 'спроси 1' > /dev/null
check "the provider told by the bot" "$(cat /tmp/okay-chat-edge-tg.jsonl)" '"chat_id":"101","text":"Вас спрашивают'
deal=$(grep -o 'берусь [0-9]*' /tmp/okay-chat-edge-tg.jsonl | head -1)
check "the provider accepts in Telegram: the seeker's account" "$(tgsay 101 ivan "$deal")" 'его контакт: @jane'
check "the seeker told by the bot, with the provider's account" "$(cat /tmp/okay-chat-edge-tg.jsonl)" \
  '"chat_id":"102","text":"По сделке'
check "  …and the provider's account in it" "$(cat /tmp/okay-chat-edge-tg.jsonl)" 'Контакт: @ivan.'

# two answers to one deal at once: the object takes them one at a time — exactly one wins
n=$(curl -s -X POST --data '{"seeker":"ann","provider":"bob","what":"спорная работа"}' "$u/deals" | tr -dc 0-9)
say bob "берусь $n" > /tmp/okay-chat-edge-a & a=$!
say bob "отказываюсь $n" > /tmp/okay-chat-edge-b & b=$!
wait $a $b
wins=$(cat /tmp/okay-chat-edge-a /tmp/okay-chat-edge-b | grep -c "по сделке $n" || true)
check "two answers at once: exactly one wins" "wins=$wins" "wins=1"
exit $fail
