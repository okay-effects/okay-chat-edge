# The plan

Each step measured against the free plan's limits before the next one starts.

1. DONE — the router: rules, the static table, the probe; CPU, start and size measured (README.md).
2. A conversation: a Durable Object for each (SQLite storage — on the free plan), the turn's state and its history;
   WebSocket with hibernation for a page, plain POST for a bot. On okay-serverless's Cloudflare effects.
3. The deal: okay-chat's state machine (pure data) ported — what of okay-match is pure, cross-built; its storage the
   object's SQLite, the marketplace's shared data D1.
4. Candidates: ranked by the same table's vectors in the Worker (no Vectorize: not on the free plan).
5. Slots and replies: places, amounts, dates (okay-intent's pure extractors), and okay-chat's phrasings.
6. The page: Scala.js, okay-faces; a résumé's PDF read IN THE BROWSER (pdf.js), its text sent — pdfbox is the JVM's,
   and parsing a PDF would not fit 10 ms.
7. The optional model lane: Workers AI within its 10 000 free neurons a day, capped as okay-chat caps it, or off.

Open: e-mail (a code to register) — what the free plan allows; the table int8 (half the bytes) if the Worker grows
past 3 MB.
