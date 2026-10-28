import { readFileSync } from "node:fs";
const { worker } = await import("../worker/okay.js");
const f = readFileSync(new URL("../data/static.safetensors", import.meta.url));
worker.init(f.buffer.slice(f.byteOffset, f.byteOffset + f.byteLength), readFileSync(new URL("../data/intents.json", import.meta.url), "utf8"),
  readFileSync(new URL("../data/probe.json", import.meta.url), "utf8"));
for (const m of ["zzz qqq", "привет как дела", "нужен сантехник", "zzz qqq", "potrzebny hydraulik w Krakowie", "привет"]) {
  const t = performance.now(); const r = worker.route(m); console.log((performance.now() - t).toFixed(2), "ms", r.by, m);
}
