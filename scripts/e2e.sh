#!/bin/sh
# end to end on wrangler dev (workerd): two people, a deal asked, a contact given, the deal answered, both told.
# `scripts/e2e.sh` after `sbt worker`, `scripts/data.sh` and `scripts/kv.sh --local`
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
port=${PORT:-8799}
cd "$here/worker"
rm -rf .wrangler/state/v3/do .wrangler/state/v3/d1
npx wrangler dev --port "$port" > /tmp/okay-chat-edge-e2e.log 2>&1 &
pid=$!
trap 'kill $pid 2>/dev/null' EXIT
for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$port/" && break; sleep 1; done
u="http://127.0.0.1:$port"
fail=0
check() { # what, got, expected substring
  case "$2" in *"$3"*) echo "ok   $1" ;; *) echo "FAIL $1: expected $3 in: $2"; fail=1 ;; esac
}
say() { curl -s -X POST --data "$2" "$u/c/$1"; }

check "an offer by rule" "$(say bob 'ремонтирую сантехнику, Вроцлав, bob@example.com')" '"intent":"offer"'
check "another offer" "$(say cara 'могу преподавать английский онлайн, cara@example.com')" 'записал'
check "no candidates yet asked for" "$(say ann 'спроси 1')" 'сначала скажите'
need=$(say ann 'нужен сантехник, течёт кран')
check "a need routed by rule" "$need" '"intent":"need"'
check "a need answered with the closest offer first" "$need" '1. ремонтирую сантехнику'
check "the unrelated offer is not a candidate" "$need" 'нашёл'
case "$need" in *английский*) echo "FAIL the English teacher offered for a plumber: $need"; fail=1 ;; *) echo "ok   not the teacher" ;; esac
check "an address noticed" "$(say ann 'мой адрес ann@example.com')" '"turn"'
check "a number out of the list" "$(say ann 'спроси 5')" 'кого спросить'
check "the shown candidate asked: a deal" "$(say ann 'спроси 1')" 'спросил: сделка 1'
check "the provider told how to answer" "$(curl -s "$u/c/bob")" 'вас спросили, сделка 1: нужен сантехник, течёт кран'
check "the seeker cannot accept her own ask" "$(say ann 'берусь 1')" 'нельзя'
check "the provider accepts: the seeker's address back" "$(say bob 'берусь 1')" 'контакт — ann@example.com'
check "the seeker told: accepted, and the provider's address" "$(curl -s "$u/c/ann")" 'контакт — bob@example.com'
check "the deal accepted" "$(curl -s "$u/deals/1")" '"state":"accepted"'
check "an accepted deal is not declined after" "$(say bob 'отказываюсь 1')" 'нельзя'
deal2=$(curl -s -X POST --data '{"seeker":"ann","provider":"bob","what":"ещё одна работа"}' "$u/deals")
check "a deal asked directly" "$deal2" '"deal":2'
check "the provider cannot withdraw it" "$(curl -s -X POST --data '{"by":"bob"}' "$u/deals/2/withdraw")" 'нельзя'
check "the seeker withdraws it" "$(curl -s -X POST --data '{"by":"ann"}' "$u/deals/2/withdraw")" 'withdrawn'
check "the provider told" "$(curl -s "$u/c/bob")" 'отбой по заказу: ещё одна работа'
# a need kept, and told when an offer for it comes
check "a need with nobody yet" "$(say dan 'нужен электрик, Краков')" 'пока никого'
check "the offer that answers it: the waiting told" "$(say eve 'занимаюсь электромонтажом, eve@example.com')" 'сообщил: 1'
check "the need told of the offer" "$(curl -s "$u/c/dan")" 'появился: 1. занимаюсь электромонтажом'
check "and its «спроси 1» asks it" "$(say dan 'спроси 1')" 'спросил: сделка 3'
check "the new provider told" "$(curl -s "$u/c/eve")" 'вас спросили, сделка 3: нужен электрик, Краков'

# two answers to one deal at once: the object takes them one at a time — exactly one wins
curl -s -X POST --data '{"seeker":"ann","provider":"bob","what":"спорная работа"}' "$u/deals" > /dev/null
say bob 'берусь 4' > /tmp/okay-chat-edge-a & a=$!
say bob 'отказываюсь 4' > /tmp/okay-chat-edge-b & b=$!
wait $a $b
wins=$(cat /tmp/okay-chat-edge-a /tmp/okay-chat-edge-b | grep -c 'сделка 4: \(accepted\|declined\)' || true)
check "two answers at once: exactly one wins" "wins=$wins" "wins=1"
exit $fail
