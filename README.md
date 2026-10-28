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
`scripts/e2e.sh`: 21 checks — the deal's, and an offer kept, a need answered with the plumber and not the English
teacher, «спроси 5» refused, «спроси 1» asking the plumber. The Worker 318 KB gzipped.

Run: `scripts/data.sh` (okay-chat beside this repository), `scripts/kv.sh --local`, then `cd worker && npx wrangler
dev` and `curl -X POST --data 'нужен электрик' http://127.0.0.1:8787/c/ann`.

Next: `specs/plan.md`.
