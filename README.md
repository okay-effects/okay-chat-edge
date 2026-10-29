# okay-chat-edge

okay-chat's chat on **Cloudflare Workers' free plan**: Scala 3 compiled to JavaScript, and no language model on a
message's path — okay-chat's own design (a deterministic router, a static table distilled from an encoder), carried
to a platform that has no JVM, no GPU and 10 ms of CPU a request.

## Step 1 (2026-10-28): the router, measured

A message's intent, as okay-chat finds it: its RULES first (okay-chat's built `intents.json`: 188 Java regexes,
Unicode), then — for the four intents read by meaning (offer, need, whatson, other) — the STATIC TABLE's embedding
(okay's `Static`: 5363 units, words and their pairs and triples, 256 dimensions, half precision, distilled from
paraphrase-multilingual-MiniLM-L12-v2) and okay's PROBE over it (logistic regression, fitted at build time).

| | measured | the free plan's limit |
|---|---|---|
| a message (rules, table, probe), warm | 8–21 µs (Node's V8, 517 authored messages) | 10 ms of CPU |
| the isolate's start: module, table, rules, probe | 19 ms, + ~100 ms warming the regexes | 1 s |
| the Worker | 2.63 MB gzipped (the table 2.9 MB raw, the code 0.6 MB) | 3 MB gzipped |
| the vector layer, okay-chat's held-out half | **97.2%** (okay-chat: 96.1% on this table, 97.7% on the encoder) | |

What it took:

- The rules as NATIVE regexes (`Rules`): Scala.js's `java.util.regex` builds each Unicode `\w` into a class of all
  of Unicode — 188 rules took 420 ms; with Unicode property classes, still 209 ms (V8 builds `\p{Alphabetic}` for each
  of ~2000 classes); with explicit ranges of the scripts okay-chat speaks, 3 ms. Checked: every rule on 619 messages
  agrees with Java's semantics.
- The table read where it lies: half precision, only a message's own units decoded (a 65 536-entry table of halves).
- The probe FITTED AT BUILD TIME (`node bench/fit.mjs`): 300 epochs are ~90 million multiply-adds; the Worker
  carries 4 × 256 weights.
- The regexes WARMED at the isolate's start: V8 compiles a regex on first use, once for Latin and once for Cyrillic
  text (20 and 75 ms) — paid once at the start, never in a request.

## Step 2 (2026-10-28): conversations, the table in KV

On okay-serverless: the stateless Worker routes a message, and its CONVERSATION — a Durable Object, one per
conversation, SQLite under it (the free plan's kind) — keeps its turns, each turn one step of durable state (one
transaction). The static table moved to KV: an isolate's start may not read KV, so it is read on the isolate's first
request and kept; the rules and the probe stay modules, warmed at the start.

    POST /route        a message → its intent
    POST /c/<id>       a message in conversation <id> → its intent and the turn's number
    GET  /c/<id>       the conversation's turns

| | measured | the free plan's limit |
|---|---|---|
| the Worker | **296 KB gzipped** (the code 1.7 MB raw: okay-serverless, okay-scala, okay-http) | 3 MB gzipped |
| the isolate's start: the module, the rules and the probe, the rules warmed | 40 + 4 + 95 ms | 1 s |
| the isolate's first request: the table from KV's bytes, the first message | 2.6 + 3.1 ms (KV's wait is not CPU) | 10 ms of CPU |
| a message, warm | 10–26 µs | |

Checked in `wrangler dev` (workerd): two conversations apart, each turn numbered, the turns read back.

## Step 3 (2026-10-28): the deal

okay-match's scenarios as data, ported as they are (`deal/Scenario.scala`): the deal is its built-in definition —
asked, then accepted or declined by the provider, or withdrawn by the seeker; accepting unlocks both sides' contacts
and tells the seeker. Each deal is a Durable Object under its number (`deal/Deals`): a transition is one step of its
durable state, so two answers to one deal cannot both win. Numbers are global, as okay-chat's are (`Numbers`, one
object, a counter). In a conversation okay-chat's own words answer a deal — «берусь 3», «отказываюсь 3», recognised by
its rules; what a deal tells a party is written into that party's conversation, and an unlock is the other party's
address, which a conversation keeps from any message that gives one.

    POST /deals {seeker, provider, what}   a deal asked (until the edge finds candidates) → its number
    GET  /deals/<n>                        the deal
    POST /deals/<n>/<transition> {by}      a transition asked by a party (`withdraw`: the seeker's)

`scripts/e2e.sh` (wrangler dev): 15 checks — two people, an address noticed, a deal asked and the provider told how to
answer, the seeker refused her own ask, the provider accepting and each side given the other's address, an accepted
deal not declined after, a withdrawal by its seeker only, and two answers at once of which exactly one wins. The Worker
306 KB gzipped.

## Step 4 (2026-10-28): candidates

The market is D1 (`Market`): every offer and need a listing — whose, which side, its words, its SUBJECT (okay-chat's
`what` slot: its own rule's capture, «ремонтирую **сантехнику**», the address and what follows a comma taken out) and
the subject's vector. A need is answered with the closest others' offers, numbered; «спроси 1», «спроси всех»
(okay-chat's `ask_candidates` rules) ask them — a deal each, for that need.

The static table cannot match a need to an offer: it was distilled from okay-chat's INTENT corpus and has no trades
in it («сантехник», «фото», «свадеб» are not among its 5363 units). So a subject's vector is `Embed`'s, in the space
the Worker's variable `EMBED` names — and a listing keeps its space's name, so two spaces are never compared:

- `trigrams` (the default; what `wrangler dev` has): no model — the words' character trigrams hashed into 512
  buckets. Measured (`node bench/similar.mjs`): related subjects 0.34–0.84 («сантехник» / «сантехнику» 0.84,
  «электрик» / «электромонтаж» 0.49), unrelated 0–0.12; the floor is 0.3. One language only.
- `ai`: Workers AI's `@cf/baai/bge-m3`, multilingual — an encoder, not a generator (okay-chat runs its own encoder
  in its process). Remote only: it needs a Cloudflare account (`wrangler login`), and is NOT yet run.

Ranking is in the Worker (Vectorize is not on the free plan): a need reads the newest 1000 offers of its space.
A need waits: an offer that comes later and is close to it tells its party — «по вашему запросу … появилось предложение: …»,
and that conversation's «спроси 1» is the new offer.

`scripts/e2e.sh`: 26 checks — the deal's, and an offer kept, a need answered with the plumber and not the English
teacher, «спроси 5» refused, «спроси 1» asking the plumber, a need with nobody told when the electrician comes.

## Step 5 (2026-10-28): four languages, okay-chat's words

The chat says what okay-chat says, word for word, in Russian, Ukrainian, Polish and English (`Phrases`: the offer
stored, nobody yet, found and how to ask, which ones, asked N — no deal numbers to the seeker —, you were asked, not
your deal, the deal bound, accepted, declined). A refusal is told to nobody but the one refusing, as in okay-chat.

The language is okay-chat's `Lang` over okay's `Language.Cues` (`Lang`): the script first, then the letters one
language owns alone and okay-chat's everyday words; an amount with its currency counts for none. No evidence — «берусь
9» — and the reply is in what this person wrote last (a conversation keeps `lang`); a party told of a deal is told in
its own. Every pattern a JS RegExp, as the rules are.

`scripts/e2e.sh`: 33 checks — the earlier ones in okay-chat's words, and a Ukrainian, a Polish and an English person
answered in their languages, kept for a turn with no evidence, a price not taken for Polish. The Worker 298 KB gzipped.

## Step 6 (2026-10-29): Telegram

The bot is a door to the same conversations (`Telegram`): Telegram POSTs each update to `/telegram` (a webhook —
a Worker has an address and no loop to poll from; okay-chat polls), the secret given at `setWebhook` checked in its
header. A private chat's message is a turn of conversation `tg:<user id>`; the reply rides back in the webhook's
answer (`{"method": "sendMessage", …}`, no request of ours), and what another party is told — a deal asked, a deal
bound, an offer for a waiting need — the bot sends with its token. The account is the address, as in okay-chat:
`@username` until the person writes an e-mail. `/start` is okay-chat's greeting in four languages; a message the
edge does not act on yet gets «Чем я могу вам помочь?» in its language.

`scripts/e2e.sh`: 42 checks — the earlier ones, and Telegram against a stand-in for the Bot API
(`scripts/fake-telegram.mjs`): an update without the secret refused, /start, an offer, a need finding it, «спроси 1»
reaching the provider by the bot, «берусь N» in Telegram giving each side the other's account.

Live: `npx wrangler login`, `sbt worker pageJs`, then `TELEGRAM_TOKEN=<from @BotFather> scripts/deploy.sh` — a bot of its own (a webhook
stops getUpdates, so not okay-chat's): the KV namespace and the D1 database made once, the table into KV, the Worker
deployed, the token and a webhook secret as its secrets, the webhook set.

## Step 7 (2026-10-29): live, bge-m3

Deployed: https://okay-chat-edge.okay-effects.workers.dev, the bot @OkayChatTestBot on its webhook
(okay-chat's test bot, its token in okay-chat's `.env`; okay-chat run locally with it would poll — a webhook stops
that, `deleteWebhook` gives it back). `EMBED = "ai"`: Workers AI's bge-m3, on the free plan — 121 tokens
cost 0.13 neurons (~1075 a million), a message ~0.02 of the 10 000 a day. `scripts/e2e.sh` stays on trigrams.

bge-m3 on subjects (the `what` slot), measured on the account: a trade against itself 0.72–0.89 (сантехник /
сантехнику 0.89, электрик / электромонтажом 0.72, положить / класть плитку 0.87), against another trade at most 0.61;
the floor 0.65. Across languages uneven: manicure 0.89, elektryk 0.67 reach; hydraulik 0.55, plumber 0.63 do not.
Whole sentences separate badly (a plumber's need 0.65 to the plumber, 0.62 to the electrician) — the slot matters.

Seen live: «potrzebny elektryk» answered in Russian — no Polish letter, no word okay-chat's list knows; okay-chat
falls back to its trigram detector there, the edge to the default.

## Step 8 (2026-10-29): the model lane, and more of okay-chat's answers

Seen live in Telegram: everything but an offer, a need and a deal's answer got «Чем я могу вам помочь?». Now:
`помощь` is okay-chat's help built from its intent table's rows — only the intents the edge acts on; `мои
предложения` / `мои заявки` list the person's listings (D1); a need with no trade in it («ищу работу», «pracy w
Krakowie») is not searched. The rest — small talk, «что ты можешь?», «что есть?», «что интересного?» — goes to the
MODEL LANE (`Lane`): Workers AI, `LANE = "@cf/google/gemma-4-26b-a4b-it"`, told the help rows and the five newest
offers and that it may say nothing else; a reply with a link is dropped; no model or no answer (the free plan's neurons
spent: `entry.js` turns a model's error into `{error}`) and okay-chat's canned «это рынок спроса и предложения…» and
the help are said instead.

Measured on the account, the user's own messages: gemma-4-26b-a4b-it with its thinking off ~1.1–1.6 s and ~4 neurons
a reply (~2300 a day free), answering in the language it is told; qwen3-30b-a3b and gemma with thinking on spent the
whole budget thinking and said nothing; llama-3.1-8b answered but invented («два человека») and replied to Polish in
Russian.

The language with no evidence keeps to the message's alphabet: «мои предложения» after a Polish turn was answered in
Polish (seen live) — now Cyrillic with no evidence is Russian (or the last Cyrillic language), Latin English (or the
last Latin one). A Telegram update that fails is answered 200 and logged, not retried by Telegram for hours.

`scripts/e2e.sh`: 49 checks (the lane off: `LANE=""`). The Worker 306 KB gzipped.

## Step 9 (2026-10-29): the lane remembers, is capped, and does not claim what it did not do

The lane reads the conversation's last six messages (each cut at 400 characters; user and assistant by turns, as
Gemma's template demands). A day's budget (`Budget`, one object): `LANE_DAILY` replies, 2000 unless set (~8000
neurons), then the canned answer — the free plan's own limit is never what stops a reply. okay-chat's second guard
ported (`ModelLane.commits`: promises, confessions) and the edge's own: the lane records nothing, so a reply saying
«записал», «сохранил», «zapisałem», «I saved» is not said (seen live: «Записал: вы предлагаете услуги электрика» for
a sentence nothing had recorded); the prompt says so too, and asks the side when it is not said — «Я электрик из
Кракова» → «Вы хотите предложить свои услуги или ищете работу? …». The help's «мои заявки» row says what the edge
does. `scripts/e2e.sh`: 49 checks, the lane on with a budget of 0 (the object asked, the model never).

## Step 10 (2026-10-29): the web page, on okay-ui

https://okay-chat-edge.okay-effects.workers.dev/ — a third door to the same conversations. The page is an okay-ui
program (`page/`, okay-ui 0.1.0): one screen shown again on every change — the conversation as blocks (`me`, `bot`),
a line, a button — its screens on okay-faces' DOM face (`Dom.run(root, skin, follow = true)`: what is typed, the
focus and the scroll kept; the end kept in view). The conversation is the effect `Talk` (`Say`, `Read`, `Changes`),
its handler `Talk.overHttp` (fetch into okay-scala's `Async`); the page's own words in the browser's language, what
the chat says the chat's. This browser's conversation is `web:` and 32 hex digits, kept in localStorage — the only
key to it. What another party tells it arrives as a turn: the page reads the conversation after each message, every
20 s while in view, and on coming back into view.

Served as the Worker's static assets (`[assets]`, `worker/public/`, page.js by `sbt pageJs`, 307 KB gzipped): free,
outside the 100 000 requests a day. Checked in Chromium (Playwright), locally and live: two browsers — an offer, a
need finding it, «спроси 1», the provider's «Вас спрашивают… (сделка 6)», «берусь 6», the seeker told the contact.

THE OPEN API CLOSED to what is not a page's: `/c/<id>` takes `web:<32 hex>` only (a Telegram conversation's id is a
number anyone could try — it was readable and writable by anyone), other ids only with `OPEN_API` set (tests);
`/deals` only with `OPEN_API`. `scripts/e2e.sh`: 52 checks.

## Step 11 (2026-10-29): the page's socket

The page no longer reads the conversation every 20 s: it opens one WebSocket to its conversation's object
(`/c/<id>/socket`), accepted there by `Sockets.accept` — Cloudflare's hibernation API, so an idle page costs the
object nothing — and each turn the object keeps (a reply, and what another party tells this one) is pushed to it as
`{n, turn}`. By the number the page adds the next turn, leaves one it has, and reads the conversation once on a gap;
lost, the socket is opened again (1 s doubling to 30 s, at once when the page comes back into view) and the
conversation read once. What comes while the program is not listening (sending, a screen being drawn) waits in the
handler for the next listener.

The socket is passed through `entry.js` (`/c/web:<32 hex>/socket` only): okay-serverless' `Objects.call` answers with
a plain response, and the 101's socket end would be lost on the way.

Measured in Chromium, locally and live, two pages: each made one read, one socket, one POST a message — the other
side's «Вас спрашивают…» and «…согласились взяться» came by themselves, and 25 s of quiet made no request (the old
page: one every 20 s). A server restarted under an open page: the socket closed, opened again in a second, one read,
the next reply by the new socket. `scripts/e2e.sh`: 55 checks (a socket's push, `scripts/socket-check.mjs`).

## Step 12 (2026-10-29): vacancies from the internet

«вакансии scala, Вроцлав», «ищу работу java разработчиком удалённо», «szukam pracy python Kraków», «jobs devops in
Germany» — a message whose words name work (and which is not an offer of one's own) is a job search (`Jobs`): the
keyword, the place (cities and countries of PL/UA/DE by their stems, in four languages) and «удалённо / zdalnie /
remote» read from it, and Workable's public search asked (`jobs.workable.com/api/v1/jobs?query=…&location=…
&workplace=remote`): the first five, each with the employer, the place, the workplace and the employer's page.
okay-chat's rule for sources (specs/jobs.md: only open, legally clean ones — Workable's robots.txt says `search=yes,
ai-input=yes`; pracuj.pl, OLX, LinkedIn forbid collection and are not read). Workable searches in English: a search
with no Latin word is put into English by the model lane (within its budget) — «бухгалтером в Польше» → «accountant»,
Poland, 36. One request a search, ~150 KB, read by the platform's JSON.parse (the 10 ms of CPU). «ищу работу» alone
asks which work. The help names it, so the lane knows it too.

Measured live: «вакансии scala» 266 in all; «… во Вроцлаве» 1; «python удалённо» 3541; «szukam pracy java Kraków»
3, answered in Polish; «бухгалтером удалённо» → accountant, 2482. Workable's own relevance is loose («java» in Kraków
gives a Data & AI Engineer). `scripts/e2e.sh`: 62 checks, Workable's stand-in in `scripts/fake-telegram.mjs`
(`JOBS_API`).

Not yet: okay-chat's 208 employers' own boards (Greenhouse, Ashby — 0.4–2.4 MB a board: an import by cron, a board
a tick, to stay inside 10 ms), more than the first five («ещё»), a search remembered and its new vacancies told.

## Step 13 (2026-10-29): the employers' boards, «ещё», a search remembered

THE BOARDS (`Boards`, `Vacancies`): okay-chat's 206 employers' boards (its job-sources.json — Greenhouse, Ashby,
Workable accounts, their public posting APIs) imported by a cron trigger (`*/2 * * * *`, free), one board a tick, all
in ~7 hours. Measured: the largest board 1.4 MB parses in 1.6 ms (the tick's 10 ms of CPU); the boards hold 24 498
vacancies, 1 540 of them in Poland, Ukraine, Europe (`JOBS_WHERE`) — only those kept, the rest (mostly US remote) left
to Workable's search; D1 written the changes only (new inserted, gone deleted), at most 300 new a tick — the first
filling ~11 000 rows written of the free 100 000 a day. A full-text index (FTS5, accents folded: «wroclaw» finds
Wrocław) so a search reads its matches, not the table. A search: the boards' first, then Workable's, one of each title
and employer. `GET /vacancies`: how many, from how many boards.

«ещё» (the router's `next`, and więcej / następne / следующие / ще): the next five of the same search (the conversation
keeps it). «запомни» (the router's `remember`) after a search: kept (`Asks`, D1, five a person) — the boards' new
vacancies that answer it told as they are imported (a board seen before), and each remembered search asked of Workable
again in turn, one a tick, for what was posted since; told by the person's door (Telegram's bot, the page's socket).
«хватит вакансий» / «stop» / «przestań» forgets them.

Live (2026-10-29): the cron's ticks `ok`; one measured by `wrangler tail` took 42 ms of CPU and 1 s of wall time —
above the free plan's nominal 10 ms, and still `ok`; to watch (a board read could be cut to the vacancies of this
market before they become Scala values). Boards with nothing in Poland, Ukraine or Europe keep nothing — the first
hour holds few.

`scripts/e2e.sh`: 71 checks — a stand-in board that gains a vacancy between two cron ticks (`--test-scheduled`):
imported, searched beside Workable, remembered, the new one told, «хватит вакансий».

Run: `scripts/data.sh` (okay-chat beside this repository), `scripts/kv.sh --local`, then `cd worker && npx wrangler
dev` and `curl -X POST --data 'нужен электрик' http://127.0.0.1:8787/c/ann`.

Next: `specs/plan.md`.
