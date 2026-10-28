#!/bin/sh
# end to end on wrangler dev (workerd): two people, a deal asked, a contact given, the deal answered, both told.
# `scripts/e2e.sh` after `sbt worker`, `scripts/data.sh` and `scripts/kv.sh --local`
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
port=${PORT:-8799}
cd "$here/worker"
rm -rf .wrangler/state/v3/do
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

check "a need routed by rule" "$(say ann 'нужен сантехник, течёт кран')" '"intent":"need"'
check "an address noticed" "$(say ann 'мой адрес ann@example.com')" '"turn":2'
check "an offer by rule" "$(say bob 'ремонтирую сантехнику, Вроцлав, bob@example.com')" '"intent":"offer"'
deal=$(curl -s -X POST --data '{"seeker":"ann","provider":"bob","what":"течёт кран"}' "$u/deals")
check "a deal asked" "$deal" '"deal":1'
check "the provider told how to answer" "$(curl -s "$u/c/bob")" 'берусь 1'
check "the seeker cannot accept her own ask" "$(say ann 'берусь 1')" 'нельзя'
check "the provider accepts: the seeker's address back" "$(say bob 'берусь 1')" 'контакт — ann@example.com'
check "the seeker told: accepted, and the provider's address" "$(curl -s "$u/c/ann")" 'контакт — bob@example.com'
check "the deal accepted" "$(curl -s "$u/deals/1")" '"state":"accepted"'
check "an accepted deal is not declined after" "$(say bob 'отказываюсь 1')" 'нельзя'
deal2=$(curl -s -X POST --data '{"seeker":"ann","provider":"bob","what":"ещё одна работа"}' "$u/deals")
check "the next deal's number" "$deal2" '"deal":2'
check "the provider cannot withdraw it" "$(curl -s -X POST --data '{"by":"bob"}' "$u/deals/2/withdraw")" 'нельзя'
check "the seeker withdraws it" "$(curl -s -X POST --data '{"by":"ann"}' "$u/deals/2/withdraw")" 'withdrawn'
check "the provider told" "$(curl -s "$u/c/bob")" 'отбой по заказу: ещё одна работа'
# two answers to one deal at once: the object takes them one at a time — exactly one wins
curl -s -X POST --data '{"seeker":"ann","provider":"bob","what":"спорная работа"}' "$u/deals" > /dev/null
say bob 'берусь 3' > /tmp/okay-chat-edge-a & a=$!
say bob 'отказываюсь 3' > /tmp/okay-chat-edge-b & b=$!
wait $a $b
wins=$(cat /tmp/okay-chat-edge-a /tmp/okay-chat-edge-b | grep -c 'сделка 3: \(accepted\|declined\)' || true)
check "two answers at once: exactly one wins" "wins=$wins" "wins=1"
exit $fail
