// Verifies the interactive textbook against the repository so it cannot silently go stale.
//   node docs/book/tools/check-snippets.mjs [book-directory]
// Checks every page in docs/book/: code excerpts match their data-source file (contiguous lines,
// indentation-insensitive); tour anchors exist in their excerpt; glossary links resolve against glossary.js;
// tracer JSON is valid and beginner layers are complete; playground narration has what/why/deep for every move
// book.js narrates, and nothing unused; links between pages and to headings resolve;
// explorer panes resolve; quiz answers exist; the contents rail is identical on every page and its quiz totals
// match the quizzes.
import { readFileSync, existsSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { runInNewContext } from "node:vm";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const bookDir = process.argv[2] ? resolve(process.argv[2]) : join(root, "docs", "book");
const pages = readdirSync(bookDir).filter((f) => f.endsWith(".html")).sort();
const html = Object.fromEntries(pages.map((p) => [p, readFileSync(join(bookDir, p), "utf8").replace(/\r\n/g, "\n")]));
const errors = [];

const unescape = (s) => s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&");
const norm = (text) => text.replace(/\r\n/g, "\n").split("\n").map((l) => l.trim());

// Glossary data: glossary.js assigns window.BOOK_GLOSSARY = [[id, term, html], ...].
const sandbox = { window: {} };
runInNewContext(readFileSync(join(bookDir, "glossary.js"), "utf8"), sandbox);
const glossary = sandbox.window.BOOK_GLOSSARY || [];
const terms = new Set();
for (const [id, term, def] of glossary) {
  if (terms.has(id)) errors.push(`glossary.js: "${id}" is defined twice`);
  if (!term || !def) errors.push(`glossary.js: "${id}" needs a term and a definition`);
  terms.add(id);
}

// Element ids per page. glossary.html renders its entries as <dt id="term-…"> at load.
const ids = Object.fromEntries(pages.map((p) => [p, new Set([...html[p].matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]))]));
if (ids["glossary.html"]) for (const id of terms) ids["glossary.html"].add("term-" + id);

let figures = 0, tracers = 0, questions = 0;
const quizCounts = {};
const narrationKeys = new Map(); // key -> page that defines it
// Links, in pages and in glossary definitions. Plain and JSON-escaped (href=\"…\") forms.
const checkLinks = (where, text, page) => {
  for (const [, href] of text.matchAll(/href=\\?"([^"\\]*)\\?"/g)) {
    if (/^(https?:|mailto:)/.test(href)) continue;
    const [file, id] = href.split("#");
    const target = file || page;
    if (file && !file.endsWith(".html")) { if (!existsSync(join(bookDir, file))) errors.push(`${where}: link to missing file "${href}"`); continue; }
    if (!html[target]) { errors.push(`${where}: link to missing page "${href}"`); continue; }
    if (id && !ids[target].has(id)) errors.push(`${where}: link "${href}" has no element with id "${id}"`);
  }
  if (/data-jump/.test(text)) errors.push(`${where}: data-jump is from the single-page edition; link to page.html#id instead`);
};
for (const [id, , def] of glossary) checkLinks(`glossary.js "${id}"`, def, "glossary.html");

for (const page of pages) {
  const doc = html[page];

  // 1. Code excerpts
  for (const fig of doc.matchAll(/<figure class="fig code"[^>]*data-source="([^"]+)"[^>]*>([\s\S]*?)<\/figure>/g)) {
    figures++;
    const [, source, inner] = fig;
    const pre = inner.match(/<pre class="src">([\s\S]*?)<\/pre>/);
    if (!pre) { errors.push(`${page} ${source}: figure has no <pre class="src">`); continue; }
    const snippet = unescape(pre[1]).replace(/^\n/, "").replace(/\s+$/, "");
    const file = join(root, source);
    if (!existsSync(file)) { errors.push(`${page} ${source}: file does not exist`); continue; }
    const want = norm(snippet);
    const have = norm(readFileSync(file, "utf8"));
    const found = have.some((_, i) => want.every((line, k) => have[i + k] === line));
    if (!found) {
      const firstBad = want.find((line) => line && !have.includes(line));
      errors.push(`${page} ${source}: excerpt does not match the file${firstBad ? `; first unmatched line: "${firstBad}"` : " (lines exist but not contiguously)"}`);
    }
    for (const [, at] of inner.matchAll(/data-at="([^"]*)"/g)) {
      if (!snippet.includes(unescape(at))) errors.push(`${page} ${source}: tour anchor "${unescape(at)}" not found in excerpt`);
    }
  }

  // 2. Glossary links
  for (const [, term] of doc.matchAll(/data-term="([^"]+)"/g)) {
    if (!terms.has(term)) errors.push(`${page}: term "${term}" is linked but not defined in glossary.js`);
  }

  // 3. Tracer specs
  for (const [, json] of doc.matchAll(/<script type="application\/json">([\s\S]*?)<\/script>/g)) {
    tracers++;
    const name = `${page} tracer #${tracers}`;
    let spec;
    try { spec = JSON.parse(json); } catch (e) { errors.push(`${name}: invalid JSON (${e.message})`); continue; }
    // A tracer that uses the beginner layers ("what", "why") on any step must use them on every step.
    const layered = spec.scenarios.some((sc) => sc.steps.some((st) => st.what !== undefined));
    for (const sc of spec.scenarios) {
      if (!sc.steps.length) errors.push(`${name} "${sc.label}": no steps`);
      sc.steps.forEach((st, k) => {
        if (!(st.lane >= 0 && st.lane < spec.lanes.length)) errors.push(`${name} "${sc.label}": lane ${st.lane} out of range`);
        if (st.deep !== undefined && typeof st.deep !== "string") errors.push(`${name} "${sc.label}": "deep" must be a string`);
        if (layered && !(typeof st.what === "string" && st.what && typeof st.why === "string" && st.why)) {
          errors.push(`${name} "${sc.label}" step ${k + 1}: needs non-empty "what" and "why", like the other steps`);
        }
      });
      if (!sc.result || typeof sc.result.ok !== "boolean") errors.push(`${name} "${sc.label}": missing result`);
    }
  }

  // 3b. Playground narration: every entry has all three layers (ADR 0007).
  for (const [, json] of doc.matchAll(/<script type="application\/json" data-narration>([\s\S]*?)<\/script>/g)) {
    let spec;
    try { spec = JSON.parse(json); } catch (e) { errors.push(`${page} narration: invalid JSON (${e.message})`); continue; }
    for (const [key, n] of Object.entries(spec)) {
      for (const layer of ["what", "why", "deep"]) {
        if (!(typeof n[layer] === "string" && n[layer].trim())) errors.push(`${page} narration "${key}": needs a non-empty "${layer}"`);
      }
      narrationKeys.set(key, page);
    }
  }

  // 4. Links and explorer panes
  checkLinks(page, doc, page);
  const panes = new Set([...doc.matchAll(/class="pane" data-name="([^"]+)"/g)].map((m) => m[1]));
  for (const [, pane] of doc.matchAll(/data-pane="([^"]+)"/g)) {
    if (!panes.has(pane)) errors.push(`${page}: explorer button opens pane "${pane}", which does not exist`);
  }

  // 5. Quizzes
  for (const [, answer, body] of doc.matchAll(/<div class="q" data-answer="(\w)">([\s\S]*?)<\/div>/g)) {
    questions++;
    if (!body.includes(`data-opt="${answer}"`)) errors.push(`${page} quiz question ${questions}: answer "${answer}" is not an option`);
    if (!body.includes('class="q-why"')) errors.push(`${page} quiz question ${questions}: missing explanation`);
  }
  const quizIds = [...doc.matchAll(/<div class="quiz" data-quiz="([^"]+)"/g)].map((m) => m[1]);
  if (quizIds.length > 1) errors.push(`${page}: one quiz per page, found ${quizIds.length}`);
  if (quizIds.length) quizCounts[quizIds[0]] = (doc.match(/<div class="q" data-answer=/g) || []).length;
}

// 6. The contents rail is the same on every page; its quiz totals match the quizzes.
const rail = (doc) => (doc.match(/<nav class="rail"[\s\S]*?<\/nav>/) || [""])[0];
const reference = rail(html["index.html"] || "");
if (!reference) errors.push("index.html: no contents rail");
for (const page of pages) {
  if (rail(html[page]) !== reference) errors.push(`${page}: contents rail differs from index.html; keep every page's <nav class="rail"> identical`);
  for (const [, id, total] of html[page].matchAll(/data-score="([^"]+)" data-total="(\d+)"/g)) {
    if (quizCounts[id] === undefined) errors.push(`${page}: score "${id}" has no quiz`);
    else if (quizCounts[id] !== Number(total)) errors.push(`${page}: score "${id}" says ${total} questions, the quiz has ${quizCounts[id]}`);
  }
  if (/data-score="[^"]+"(?! data-total)/.test(html[page])) errors.push(`${page}: every data-score needs data-total`);
}

// 7. Narration keys used by book.js (narrate(t, "key") with a literal key) exist, and none is defined but unused.
const used = new Set([...readFileSync(join(bookDir, "book.js"), "utf8").matchAll(/\bnarrate\([^,()]+,\s*"([\w-]+)"\)/g)].map((m) => m[1]));
for (const key of used) if (!narrationKeys.has(key)) errors.push(`book.js narrates "${key}", but no page's data-narration defines it`);
for (const [key, page] of narrationKeys) if (!used.has(key)) errors.push(`${page} narration "${key}" is never used by book.js`);

if (errors.length) {
  console.error(`Textbook check failed (${errors.length}):\n  - ` + errors.join("\n  - "));
  process.exit(1);
}
console.log(`Textbook check passed: ${pages.length} pages, ${figures} code excerpts, ${terms.size} glossary terms, ${tracers} tracers, ${narrationKeys.size} narrated moves, ${questions} quiz questions.`);
