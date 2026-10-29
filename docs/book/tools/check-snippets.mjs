// Verifies the interactive textbook against the repository so it cannot silently go stale.
//   node docs/book/tools/check-snippets.mjs
// Checks: code excerpts match their data-source file (contiguous lines, indentation-insensitive);
// tour anchors exist in their excerpt; glossary links resolve; tracer JSON is valid; in-book jumps and
// explorer panes resolve; quiz answers exist.
import { readFileSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const bookPath = process.argv[2] ? resolve(process.argv[2]) : join(root, "docs", "book", "index.html");
const html = readFileSync(bookPath, "utf8");
const errors = [];

const unescape = (s) => s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&");
const norm = (text) => text.replace(/\r\n/g, "\n").split("\n").map((l) => l.trim());

// 1. Code excerpts
let figures = 0;
for (const fig of html.matchAll(/<figure class="fig code"[^>]*data-source="([^"]+)"[^>]*>([\s\S]*?)<\/figure>/g)) {
  figures++;
  const [, source, inner] = fig;
  const pre = inner.match(/<pre class="src">([\s\S]*?)<\/pre>/);
  if (!pre) { errors.push(`${source}: figure has no <pre class="src">`); continue; }
  const snippet = unescape(pre[1]).replace(/^\n/, "").replace(/\s+$/, "");
  const file = join(root, source);
  if (!existsSync(file)) { errors.push(`${source}: file does not exist`); continue; }
  const want = norm(snippet);
  const have = norm(readFileSync(file, "utf8"));
  const found = have.some((_, i) => want.every((line, k) => have[i + k] === line));
  if (!found) {
    const firstBad = want.find((line) => line && !have.includes(line));
    errors.push(`${source}: excerpt does not match the file${firstBad ? `; first unmatched line: "${firstBad}"` : " (lines exist but not contiguously)"}`);
  }
  for (const [, at] of inner.matchAll(/data-at="([^"]*)"/g)) {
    if (!snippet.includes(unescape(at))) errors.push(`${source}: tour anchor "${unescape(at)}" not found in excerpt`);
  }
}

// 2. Glossary links
const terms = new Set([...html.matchAll(/id="term-([^"]+)"/g)].map((m) => m[1]));
for (const [, term] of html.matchAll(/data-term="([^"]+)"/g)) {
  if (!terms.has(term)) errors.push(`glossary: term "${term}" is linked but not defined`);
}

// 3. Tracer specs
let tracers = 0;
for (const [, json] of html.matchAll(/<script type="application\/json">([\s\S]*?)<\/script>/g)) {
  tracers++;
  let spec;
  try { spec = JSON.parse(json); } catch (e) { errors.push(`tracer #${tracers}: invalid JSON (${e.message})`); continue; }
  for (const sc of spec.scenarios) {
    if (!sc.steps.length) errors.push(`tracer #${tracers} "${sc.label}": no steps`);
    for (const st of sc.steps) {
      if (!(st.lane >= 0 && st.lane < spec.lanes.length)) errors.push(`tracer #${tracers} "${sc.label}": lane ${st.lane} out of range`);
      if (st.deep !== undefined && typeof st.deep !== "string") errors.push(`tracer #${tracers} "${sc.label}": "deep" must be a string`);
    }
    if (!sc.result || typeof sc.result.ok !== "boolean") errors.push(`tracer #${tracers} "${sc.label}": missing result`);
  }
}

// 4. In-book jumps (data-jump="heading-id") and explorer panes
const ids = new Set([...html.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]));
for (const [, id] of html.matchAll(/data-jump=\\?"([^"\\]+)\\?"/g)) {
  if (!ids.has(id)) errors.push(`jump: no element with id "${id}"`);
}
const panes = new Set([...html.matchAll(/class="pane" data-name="([^"]+)"/g)].map((m) => m[1]));
for (const [, pane] of html.matchAll(/data-pane="([^"]+)"/g)) {
  if (!panes.has(pane)) errors.push(`explorer: button opens pane "${pane}", which does not exist`);
}

// 5. Quizzes
let questions = 0;
for (const [, answer, body] of html.matchAll(/<div class="q" data-answer="(\w)">([\s\S]*?)<\/div>/g)) {
  questions++;
  if (!body.includes(`data-opt="${answer}"`)) errors.push(`quiz question ${questions}: answer "${answer}" is not an option`);
  if (!body.includes('class="q-why"')) errors.push(`quiz question ${questions}: missing explanation`);
}

if (errors.length) {
  console.error(`Textbook check failed (${errors.length}):\n  - ` + errors.join("\n  - "));
  process.exit(1);
}
console.log(`Textbook check passed: ${figures} code excerpts, ${terms.size} glossary terms, ${tracers} tracers, ${questions} quiz questions.`);
