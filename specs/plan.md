# The plan

Each step measured against the free plan's limits before the next one starts.

1. DONE — the router: rules, the static table, the probe; CPU, start and size measured (README.md).
2. DONE — a conversation: a Durable Object for each (SQLite storage), its turns durable, on okay-serverless; the table
   in KV. Left for the page: a WebSocket with hibernation (the object then routes too: the table from KV in it).
3. DONE — the deal: okay-match's scenario engine ported, a deal an object (a transition a step), global numbers,
   «берусь N» / «отказываюсь N» from a conversation, notices and unlocked addresses written into the parties'
   conversations. A party is a conversation until profiles (with the shared marketplace data in D1).
4. DONE — candidates: the market in D1, a listing's subject okay-chat's `what` slot, its vector `Embed`'s (trigrams;
   bge-m3 on Workers AI with an account — not yet run), ranked in the Worker; «спроси 1 2» asks. Left: bge-m3 measured
   (separation on okay-chat's trades, as its docs/encoders.md measures). A need is told when an offer comes (done).
5. DONE — replies: okay-chat's phrases for what the edge says, in four languages, and its language detection.
   Left: slots — places, amounts, dates (okay-intent's pure extractors); a bare «берусь» meaning the one deal awaiting.
5a. DONE — Telegram: a webhook door to the conversations, the other side told by the bot (`scripts/deploy.sh`).
6. DONE (the chat) — the page: okay-ui on okay-faces' DOM face, polling; the WebSocket (hibernation) done; left: a résumé's PDF
   read in the browser. Was: Scala.js, okay-faces; a résumé's PDF read IN THE BROWSER (pdf.js), its text sent — pdfbox is the JVM's,
   and parsing a PDF would not fit 10 ms.
7. DONE — the model lane: Workers AI (gemma-4-26b-a4b-it), within the 10 000 free neurons a day (~2300 replies);
   what the edge does not act on. Left: a per-day cap of our own (okay-chat's), the conversation's last turns as
   context, okay-chat's stray/promise checks on the reply.

8. DONE (2026-10-29) — without Cloudflare: one chat, two platforms (`edge/` a crossProject). Cloudflare the main home;
   okay-serverless' host (JVM, SQLite, the JDK's HTTP and WebSockets, a model server of OpenAI's API) the fallback,
   `sbt edgeJVM/run`. The page's socket by `App.objectFor` (no longer entry.js'). The end-to-end checks pass on both
   (76 and 76). Left: the lane measured on a local model server (rozum's OpenAI API); a deploy recipe for a VPS.

Open: e-mail (a code to register) — what the free plan allows. The table is in KV now (no longer in the Worker's
3 MB); int8 would halve its read if the first request's 10 ms ever get tight.
