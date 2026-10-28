// the rules' translation checked against Java's semantics, and timed
import { readFileSync } from "node:fs";
const { tools } = await import("./tools.js");
const built = JSON.parse(readFileSync(new URL("../data/intents.json", import.meta.url), "utf8"));
const corpus = JSON.parse(readFileSync(new URL("../data/corpus-intents.json", import.meta.url), "utf8"));
const rules = built.intents.flatMap(i => i.rules ?? []);
const messages = corpus.intents.flatMap(i => { const ex = i.examples ?? []; return Array.isArray(ex) ? ex : Object.values(ex).flat(); });
// and the helps' sample phrases, and words at the edges of \b: Cyrillic, Polish, digits, apostrophes
for (const i of built.intents) for (const h of Object.values(i.help ?? {})) messages.push(h.say);
messages.push("сдаю квартиру", "Сдаётся комната", "мой_профиль", "naprawiam lodówki", "I'm offering", "берусь 3", "3берусь",
  "отменить", "ОТМЕНА!", "co nowego w tym tygodniu?", "what's on this week", "kod 123456", "123456", "x@y.pl");
let t = performance.now(); const n = tools.compileAll(rules); console.log(`${n} rules compiled natively in ${(performance.now() - t).toFixed(1)} ms`);
t = performance.now(); const d = tools.disagreements(rules, messages);
console.log(`${rules.length} rules × ${messages.length} messages: ${d.length} disagreements (${(performance.now() - t).toFixed(0)} ms)`);
for (const x of d.slice(0, 10)) console.log("  ", x.slice(0, 200));
