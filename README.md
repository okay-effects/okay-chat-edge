# Okay! Chat on the edge

**A marketplace you talk to, on Cloudflare Workers' free plan.** Say in ordinary words what you offer or what you
need; the chat finds the other side, asks them for you and passes on the contacts once they agree. Ask it for
vacancies by a keyword and a city, and it reads employers' own public job boards. Russian, Ukrainian, Polish and
English; in the browser and in Telegram.

- **Live:** [okay-chat-edge.okay-effects.workers.dev](https://okay-chat-edge.okay-effects.workers.dev/)
- **Telegram:** [@OkayChatTestBot](https://t.me/OkayChatTestBot)
- **On the site:** [okay-effects.github.io — Okay! Chat](https://okay-effects.github.io/#okay-chat)

Scala 3 compiled to JavaScript, on [okay-serverless](https://github.com/okay-effects/okay-serverless) (the cloud as
effects, Cloudflare's handlers) and [okay-ui](https://github.com/okay-effects/okay-ui) (the page's screens, on
[okay-faces](https://github.com/okay-effects/okay-faces)). The chat's behaviour is okay-chat's — its rules, its
phrases, its deal scenario — carried to a platform with no JVM, no GPU and 10 ms of CPU a request.

Cloudflare is its home, not its master: the same code runs on the JVM, on a server of one's own, through
okay-serverless' host — the fallback, every effect on one process and one directory (see *Without Cloudflare*).

## What it does

| You write | It does |
|---|---|
| «ремонтирую стиральные машины, Вроцлав» | keeps it as an offer, and tells those already waiting for one |
| «нужен сантехник, течёт кран» | answers with the closest offers, matched by meaning; nobody yet — it remembers and tells you when someone comes |
| «спроси 1 2», «спроси всех» | writes to the people you chose: «Вас спрашивают… (сделка 6)» |
| «берусь 6» / «отказываюсь 6» | answers the deal; on yes each side gets the other's contact |
| «вакансии scala, Вроцлав», «jobs java remote», «szukam pracy python Kraków» | vacancies from Workable's search and 206 employers' own boards, each with the employer's page |
| «ещё», «запомни», «хватит вакансий» | the next five; new vacancies told as they appear; stop |
| «мои предложения», «мои заявки», «помощь» | your listings; what it can do |
| anything else | a language model answers in role, within a daily budget, and may promise nothing |

The language of the answer is the message's own (its script, the letters only one language has, everyday words), or
the one you wrote in last. A vacancy search's place is the one you name, else the last one you named, else — on the
page — your country as Cloudflare reads it; «везде» for the whole world.

## How it is built

```
            ┌──────────────── Worker (stateless): routes each message ────────────────┐
 page ──────┤  rules (188 regexes) → static table (embedding by lookup) → probe       │
 Telegram ──┤  Jobs: Workable search + the boards in D1     Lane: Workers AI (gemma)   │
 API ───────┤                                                                          │
            └─────┬──────────────────────┬────────────────────────┬──────────────────┘
                  │                      │                        │
        Conversation (DO)            Deal (DO)              D1: market, vacancies (FTS5), asks
        turns, language, place,      a scenario's state      KV: the static table (2.9 MB)
        a page's WebSocket           machine, one step a     Cron: a board a tick, a search re-asked
        (hibernating)                transition
```

- **No model on a message's path.** A message is routed by okay-chat's rules, translated to native JavaScript
  regexes, then by a static embedding table (5363 units, F16) and a logistic probe fitted at build time: 8–26 µs.
- **A conversation is a Durable Object** (SQLite storage, the free plan's kind): its turns, its language, the place
  last named, the candidates last shown, the search last made. Each turn one durable step.
- **A deal is a Durable Object:** okay-match's scenario engine, a transition one step, so two answers to one deal
  cannot both win. Contacts are unlocked by the scenario, not by the code that asks.
- **The market is in D1:** each listing's subject (okay-chat's `what` slot) as a vector — Workers AI's bge-m3 (live)
  or character trigrams (tests) — ranked in the Worker.
- **Vacancies:** Workable's public search, asked per message; 206 employers' boards (Greenhouse, Ashby, Workable)
  imported by a cron trigger, one board a tick, the ones in Poland, Ukraine and Europe kept, with a full-text index.
  Only open, legally clean sources: boards that forbid collection (pracuj.pl, OLX, LinkedIn) are not read.
- **Three doors to one conversation:** the page (an okay-ui program; the conversation is its effect `Talk`, answered
  over HTTP and a hibernating WebSocket), Telegram (a webhook; the reply rides in its answer) and the API.
- **The model lane** (`Lane`): what the rules do not act on goes to Workers AI's gemma-4-26b with the help and the
  market in its prompt; a reply that claims an act, promises or links is dropped; 2000 replies a day at most.

## The free plan, measured

| | measured | the limit |
|---|---|---|
| a message, warm (rules, table, probe) | 8–26 µs | 10 ms CPU |
| the isolate's start | ~140 ms | 1 s |
| the Worker | 306 KB gzipped (the table in KV) | 3 MB |
| the page | 307 KB gzipped, static assets | free, no requests |
| a model reply (gemma, thinking off) | ~4 neurons, ~1.2 s | 10 000 neurons a day |
| a bge-m3 embedding | ~0.02 neurons | |
| the boards' first filling | ~11 000 rows written | 100 000 a day |
| a board's parse (1.4 MB) | 1.6 ms | |

The full story, every step with its measurements and what it took: [docs/history.md](docs/history.md).

## Run it

Needs sbt, Node 22+.

```sh
sbt worker pageJs                      # the Worker (worker/okay.js) and the page (worker/public/page.js)
scripts/kv.sh --local                  # the static table into the local KV
cd worker && npx wrangler dev          # http://127.0.0.1:8787/
scripts/e2e.sh                         # 76 checks on wrangler dev, Telegram and Workable stood in
```

`data/` holds what the chat carries from okay-chat: its built rules (`intents.json`), its authored examples
(`corpus-intents.json`), the static table (`static.safetensors`), the employers' boards (`job-sources.json.txt`) and
the probe fitted on them (`probe.json`). `scripts/data.sh` refreshes them from an okay-chat checkout beside this one.

## Deploy it

```sh
npx wrangler login
TELEGRAM_TOKEN=<a bot's token from @BotFather> scripts/deploy.sh
```

`deploy.sh` makes the KV namespace and the D1 database once (their ids written into `worker/wrangler.toml` — a fork
replaces the ids there with the placeholders `okay-chat-edge-table` and `00000000-0000-0000-0000-000000000000`),
puts the table into KV, deploys the Worker, sets the bot's token and a webhook secret, and points the bot's webhook
at the Worker. Variables (`worker/wrangler.toml`): `EMBED` (`ai` or `trigrams`), `LANE` (the model, or none),
`LANE_DAILY`, `JOBS_WHERE`. A webhook stops a bot's polling: use a bot of its own.

## Without Cloudflare

The chat is written once, against okay-serverless' effects: Cloudflare is one set of their handlers, okay-serverless'
host another — a JVM process with SQLite for the Durable Objects, D1 and KV, the JDK for HTTP and WebSockets, a
model server of OpenAI's API for Workers AI. Only the platform's own speed lives apart (`edge/js`: JavaScript's
regexes and `JSON.parse`; `edge/jvm`: `java.util.regex`, okay's JSON).

```sh
sbt edgeJVM/run                       # http://127.0.0.1:8787 — the page, the API, /telegram, the cron
TARGET=host scripts/e2e.sh            # the end-to-end checks, on the host instead of wrangler
```

Settings come from the environment: `OKAY_CHAT_DIR` (the state, `host-data/`), `PORT`, `HOST`; a model server —
`AI_URL`, `AI_KEY`, `AI_CHAT_MODEL`, `AI_EMBED_MODEL` (none: no lane, subjects by trigrams); the Worker's variables
by their names (`TELEGRAM_TOKEN`, `TELEGRAM_SECRET`, `JOBS_WHERE`, …); where people are — `COUNTRY_HEADER` (a
proxy's header one trusts, `cf-ipcountry`) or `COUNTRY` (a two-letter code).

## Layout

| | |
|---|---|
| `edge/shared/` | the chat, both platforms: `Router`, `Rules`, `StaticTable`, `Probe`, `Chat` (the app and the conversation), `deal/` (the scenario engine, deals), `Market`, `Embed`, `Jobs`, `Boards`, `Vacancies`, `Asks`, `Lane`, `Budget`, `Telegram`, `Lang`, `Phrases` |
| `edge/js/`, `edge/jvm/` | each platform's regexes, JSON, text, the table's bytes; the Worker's exports; `Serve`, the host's main |
| `page/` | the page: an okay-ui program (`Chat`), its effect `Talk` and handler, its look |
| `worker/` | `entry.js` (what JavaScript must say), `wrangler.toml`, `public/` (the page's files) |
| `tools/`, `bench/` | build-time tools (the probe's fit) and measures — never in the Worker |
| `scripts/` | data, KV, deploy, the end-to-end checks and their stand-ins |
| `specs/plan.md` | what is done, what is next |

Apache License 2.0. Author: [Sergiy Shcherbyna](https://www.linkedin.com/in/sergiy-shcherbyna-b904751).
