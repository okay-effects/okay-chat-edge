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

export class Conversation extends DurableObject {
  fetch(request) { return worker.objectFetch("CONVERSATIONS", this.ctx, this.env, request); }
  alarm() { return worker.objectAlarm("CONVERSATIONS", this.ctx, this.env); }
  webSocketMessage(ws, message) { return worker.objectMessage("CONVERSATIONS", this.ctx, this.env, ws, message); }
  webSocketClose(ws) { return worker.objectClose("CONVERSATIONS", this.ctx, this.env, ws); }
}

export default {
  async fetch(request, env, ctx) {
    await table(env);
    return worker.fetch(request, env, ctx);
  },
};
