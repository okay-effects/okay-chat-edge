# The plan

Each step measured against the free plan's limits before the next one starts.

1. DONE — the router: rules, the static table, the probe; CPU, start and size measured (README.md).
2. DONE — a conversation: a Durable Object for each (SQLite storage), its turns durable, on okay-serverless; the table
   in KV. Left for the page: a WebSocket with hibernation (the object then routes too: the table from KV in it).
3. DONE — the deal: okay-match's scenario engine ported, a deal an object (a transition a step), global numbers,
   «берусь N» / «отказываюсь N» from a conversation, notices and unlocked addresses written into the parties'
   conversations. A party is a conversation until profiles (with the shared marketplace data in D1).
4. Candidates: ranked by the same table's vectors in the Worker (no Vectorize: not on the free plan).
5. Slots and replies: places, amounts, dates (okay-intent's pure extractors), and okay-chat's phrasings.
6. The page: Scala.js, okay-faces; a résumé's PDF read IN THE BROWSER (pdf.js), its text sent — pdfbox is the JVM's,
   and parsing a PDF would not fit 10 ms.
7. The optional model lane: Workers AI within its 10 000 free neurons a day, capped as okay-chat caps it, or off.

Open: e-mail (a code to register) — what the free plan allows. The table is in KV now (no longer in the Worker's
3 MB); int8 would halve its read if the first request's 10 ms ever get tight.
