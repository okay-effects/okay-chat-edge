#!/bin/sh
# THE EDGE ON AN ACCOUNT, and its Telegram bot: `TELEGRAM_TOKEN=<from @BotFather> scripts/deploy.sh`
# after `npx wrangler login`, `sbt worker` and `scripts/data.sh`. Idempotent: what exists is kept.
#   1. the KV namespace and the D1 database, made once — their ids written into worker/wrangler.toml
#   2. the static table into KV
#   3. the Worker deployed (workers.dev)
#   4. the bot's token and a webhook secret as the Worker's secrets; the webhook set to <worker>/telegram
# A bot of its own: setting a webhook stops getUpdates, so okay-chat's bot (which polls) must not be the one.
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
cd "$here/worker"
: "${TELEGRAM_TOKEN:?a bot token from @BotFather, for a bot of its own}"
npx wrangler whoami | grep -q "associated with" || { echo "npx wrangler login first"; exit 1; }

if grep -q 'id = "okay-chat-edge-table"' wrangler.toml; then
  kv=$(npx wrangler kv namespace create okay-chat-edge-table | grep -oE '[0-9a-f]{32}' | head -1)
  sed -i.bak "s/id = \"okay-chat-edge-table\"/id = \"$kv\"/" wrangler.toml && rm wrangler.toml.bak
fi
if grep -q 'database_id = "00000000-0000-0000-0000-000000000000"' wrangler.toml; then
  d1=$(npx wrangler d1 create okay-chat-edge-market | grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' \
    | head -1)
  sed -i.bak "s/00000000-0000-0000-0000-000000000000/$d1/" wrangler.toml && rm wrangler.toml.bak
fi

"$here/scripts/kv.sh" --remote
url=$(npx wrangler deploy | grep -oE 'https://[^ ]+\.workers\.dev' | head -1)
[ -n "$url" ] || { echo "no workers.dev address in the deploy's output"; exit 1; }

secret=$(openssl rand -hex 24)
printf %s "$TELEGRAM_TOKEN" | npx wrangler secret put TELEGRAM_TOKEN
printf %s "$secret" | npx wrangler secret put TELEGRAM_SECRET
curl -s "https://api.telegram.org/bot$TELEGRAM_TOKEN/setWebhook" \
  --data-urlencode "url=$url/telegram" --data-urlencode "secret_token=$secret" \
  --data-urlencode 'allowed_updates=["message"]'
echo
echo "the bot: $(curl -s "https://api.telegram.org/bot$TELEGRAM_TOKEN/getMe" | grep -oE '"username":"[^"]+"')"
echo "the Worker: $url"
