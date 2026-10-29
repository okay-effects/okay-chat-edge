# AGENTS.md — okay-chat-edge

Read before anything else, then `README.md` and `specs/plan.md`.

- okay-chat's chat on Cloudflare Workers' FREE plan: Scala 3 on Scala.js, no model on a message's path. okay-chat
  (`../okay-chat`, the commercial service, a JVM process) is the reference: its data (`scripts/data.sh` copies them
  into `data/`, not committed) and its behaviour. Nothing here edits okay-chat or its `okay/` submodule.
- The free plan's limits are the design's (developers.cloudflare.com/workers/platform/limits): 10 ms of CPU a
  request, 1 s to start an isolate, 3 MB gzipped a Worker, 100 000 requests a day. Every change that could move a
  number is measured (`bench/`), and the numbers in README.md are kept true.
- `sbt worker` links the Worker (`worker/okay.js`); `sbt pageJs` the web page (`worker/public/page.js`, an okay-ui
  program — the frontend is okay-ui / okay-faces, the user's rule); `sbt toolsJs` the build-time tools (`bench/tools.js`: the
  probe's fit, the measures — never in the Worker). `node bench/bench.mjs`, `node bench/rules.mjs`;
  `npx wrangler dev` in `worker/`.
- Public on GitHub (okay-effects/okay-chat-edge, the owner's decision 2026-10-29), okay-chat's data in `data/`
  included. Lines at most 120 wide. Commits: plain
  messages, no attribution trailers.
