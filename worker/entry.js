// okay-chat-edge's Worker: its logic is Scala (okay.js, linked by `sbt worker`); here only what JavaScript must say —
// the entry point, the Durable Object class, and the data the Worker carries. okay-chat's built intents (the rules)
// and the probe's weights are modules, read and warmed at the isolate's start; the static table (2.9 MB) is in KV,
// read on the isolate's first request (KV cannot be read at the start), kept while the isolate lives.
import { DurableObject } from "cloudflare:workers";
import { edge, worker } from "./okay.js";
import intents from "../data/intents.json.txt";
import probe from "../data/probe.json.txt";

edge.init(intents, probe);
edge.warm();

let loading = null;
const table = async (env) => {
  if (edge.hasTable) return;
  loading ??= env.TABLE.get("static", "arrayBuffer").then((bytes) => {
    if (bytes === null) throw new Error("no static table in KV under `static`: scripts/kv.sh");
    edge.load(bytes);
  });
  await loading;
};

// each Durable Object class hands its calls to the Scala worker, under its namespace's binding
const objectClass = (ns) => class extends DurableObject {
  fetch(request) { return worker.objectFetch(ns, this.ctx, this.env, request); }
  alarm() { return worker.objectAlarm(ns, this.ctx, this.env); }
  webSocketMessage(ws, message) { return worker.objectMessage(ns, this.ctx, this.env, ws, message); }
  webSocketClose(ws) { return worker.objectClose(ns, this.ctx, this.env, ws); }
};

export const Conversation = objectClass("CONVERSATIONS");
export const Deal = objectClass("DEALS");
export const Numbers = objectClass("NUMBERS");
export const Budget = objectClass("BUDGET");

// a model's failure is an answer, not an exception: the free plan's neurons spent, or the model down, and the Scala
// side reads `{error}` as no answer (the lane says its canned line, an embedding finds nobody)
const guarded = (env) => env.AI === undefined ? env : new Proxy(env, {
  get: (target, key) => key !== "AI" ? target[key] : {
    run: (model, input, options) => target.AI.run(model, input, options).catch((e) => ({ error: String(e) })),
  },
});

// A PAGE'S SOCKET, passed through to its conversation's object: the one thing the Scala side cannot carry — an
// object's 101 holds the socket's other end, and okay-serverless' `Objects.call` answers with a plain response. Only a
// page's conversation (web: and 32 hex digits), as the API takes; the object accepts it (`Sockets.accept`, hibernating)
const pageSocket = /^\/c\/(web:[0-9a-f]{32})\/socket$/;
const socketOf = (request, env) => {
  const at = pageSocket.exec(decodeURIComponent(new URL(request.url).pathname));
  if (at === null || request.headers.get("upgrade")?.toLowerCase() !== "websocket") return null;
  const ns = env.CONVERSATIONS;
  return ns.get(ns.idFromName(at[1])).fetch(new Request("https://CONVERSATIONS/socket",
    { headers: { upgrade: "websocket", "okay-object-name": at[1] } }));
};

export default {
  async fetch(request, env, ctx) {
    const socket = socketOf(request, env);
    if (socket !== null) return socket;
    try {
      await table(env);
      return await worker.fetch(request, guarded(env), ctx);
    } catch (e) {
      // Telegram retries an update it got an error for, for hours: one that failed is logged and let go
      if (new URL(request.url).pathname === "/telegram") {
        console.error("telegram update failed", e);
        return new Response("{}", { headers: { "content-type": "application/json" } });
      }
      throw e;
    }
  },
};
