// The measure, on Node's V8 (Workers' engine): a cold start, a message's CPU, and the vector layer's accuracy on
// okay-chat's held-out half. `node bench/bench.mjs` after `sbt worker`.
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";

const t0 = performance.now();
const { edge } = await import("../worker/okay.js");
const tLoad = performance.now();
const file = readFileSync(new URL("../data/static.safetensors", import.meta.url));
const bytes = file.buffer.slice(file.byteOffset, file.byteOffset + file.byteLength);
const built = readFileSync(new URL("../data/intents.json", import.meta.url), "utf8");
const corpus = readFileSync(new URL("../data/corpus-intents.json", import.meta.url), "utf8");
const probe = readFileSync(new URL("../data/probe.json", import.meta.url), "utf8");
const t1 = performance.now();
edge.init(built, probe);
const t2 = performance.now();
edge.warm();
const t3 = performance.now();
edge.load(bytes);
const t4 = performance.now();
console.log(`rules and probe ${(t2 - t1).toFixed(1)} ms, warming the rules ${(t3 - t2).toFixed(1)} ms (the start); ` +
  `the table from its bytes ${(t4 - t3).toFixed(1)} ms (the first request)`);
console.log(`module load ${(tLoad - t0).toFixed(1)} ms`);
let tf = performance.now(); edge.route("первое сообщение"); console.log(`the first message ${(performance.now() - tf).toFixed(2)} ms`);

// the messages: every authored example, and some of a person's own
const examples = JSON.parse(corpus).intents.flatMap(i => {
  const ex = i.examples ?? [];
  const texts = Array.isArray(ex) ? ex : Object.values(ex).flat();
  return texts.map(t => [t, i.name]);
});
const mine = ["нужен электрик, не работает розетка", "ремонтирую холодильники, Вроцлав", "что интересного сегодня вечером",
  "potrzebny hydraulik w Krakowie", "привет как дела", "мои заявки", "I can teach English online"];
for (const m of mine) console.log("  ", JSON.stringify(edge.route(m)), m);

// the first call of each message (cold caches), then many rounds
const msgs = examples.map(e => e[0]);
let t = performance.now();
for (const m of msgs) edge.route(m);
const first = (performance.now() - t) / msgs.length;
const rounds = 50;
t = performance.now();
for (let r = 0; r < rounds; r++) for (const m of msgs) edge.route(m);
const warm = (performance.now() - t) / (rounds * msgs.length);
console.log(`a message: first pass ${(first * 1000).toFixed(0)} µs, warm ${(warm * 1000).toFixed(0)} µs (${msgs.length} messages)`);

// okay-chat's TestStaticTier split: SHA-1 of the lower-cased text, its first byte even → the pool
const semantic = new Set(JSON.parse(built).intents.filter(i => i.semantic !== false).map(i => i.name));
const rows = examples.filter(e => semantic.has(e[1]));
const half = s => (createHash("sha1").update(s.toLowerCase(), "utf8").digest()[0] & 1) === 0;
const pool = rows.filter(r => half(r[0])), test = rows.filter(r => !half(r[0]));
const { tools } = await import("./tools.js");
console.log("vector layer, held out:", JSON.stringify(tools.evaluate(bytes, pool, test)), `pool ${pool.length}`);
