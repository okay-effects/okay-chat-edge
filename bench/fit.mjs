// THE BUILD STEP: the probe fitted on the authored examples, written to data/probe.json for the Worker
import { readFileSync, writeFileSync } from "node:fs";
const { tools } = await import("./tools.js");
const file = readFileSync(new URL("../data/static.safetensors", import.meta.url));
const bytes = file.buffer.slice(file.byteOffset, file.byteOffset + file.byteLength);
const built = readFileSync(new URL("../data/intents.json", import.meta.url), "utf8");
const corpus = readFileSync(new URL("../data/corpus-intents.json", import.meta.url), "utf8");
const t = performance.now();
const probe = tools.fit(bytes, built, corpus);
writeFileSync(new URL("../data/probe.json", import.meta.url), probe);
console.log(`probe fitted in ${(performance.now() - t).toFixed(0)} ms: ${probe.length} bytes, classes ${JSON.parse(probe).classes}`);
