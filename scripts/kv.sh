#!/bin/sh
# the static table into KV, under `static`: `scripts/kv.sh --local` for wrangler dev, `scripts/kv.sh --remote` for
# the account's namespace (its id in worker/wrangler.toml)
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
cd "$here/worker"
npx wrangler kv key put --binding TABLE static --path ../data/static.safetensors "$@"
