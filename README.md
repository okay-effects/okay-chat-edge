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

Run: `scripts/data.sh` (okay-chat beside this repository), then `cd worker && npx wrangler dev` and
`curl -X POST --data 'нужен электрик' http://127.0.0.1:8787/route`.

Next: `specs/plan.md`.
