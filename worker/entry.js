// okay-chat-edge's Worker: its logic is Scala (okay.js, linked by `sbt worker`); here only the entry point and the
// data the Worker carries — the static table (bytes), okay-chat's built intents (rules) and the probe's weights,
// imported as modules. The router is made and warmed at the isolate's start, not in a request.
import { worker } from "./okay.js";
import table from "../data/static.safetensors";
import intents from "../data/intents.json.txt";
import probe from "../data/probe.json.txt";

worker.init(table, intents, probe);
worker.warm();

export default {
  fetch: (request, env, ctx) => worker.fetch(request, env, ctx),
};
