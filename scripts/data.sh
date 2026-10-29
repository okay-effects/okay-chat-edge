#!/bin/sh
# the data the Worker carries, from okay-chat beside this repository (OKAY_CHAT to say where): its static table, its
# built intents (rules), its authored corpus (examples) — then the probe fitted on them
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
chat=${OKAY_CHAT:-$here/../okay-chat}
mkdir -p "$here/data"
cp "$chat/src/main/resources/static.safetensors" "$chat/src/main/resources/intents.json" "$here/data/"
cp "$chat/corpus/intents.json" "$here/data/corpus-intents.json"
cp "$here/data/intents.json" "$here/data/intents.json.txt"
# okay-chat's employers' boards (Greenhouse, Ashby, Workable accounts) — the vacancies the cron imports
cp "$chat/src/main/resources/job-sources.json" "$here/data/job-sources.json.txt"
cd "$here" && sbt -batch worker toolsJs >/dev/null && node bench/fit.mjs && cp data/probe.json data/probe.json.txt
