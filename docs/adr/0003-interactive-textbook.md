# ADR 0003 — An interactive textbook as static HTML, verified against the code in CI

**Status:** Accepted

## Context

The Markdown chapters explain the system, but a learner can only read them. Concepts such as a request crossing layers, floating-point drift, or which validation layer rejects an input are easier to understand by trying them. The book must stay accurate as the code changes; a textbook that quotes code that no longer exists teaches the wrong thing.

## Decision

1. Add an interactive edition at `docs/book/`: one `index.html` with all chapters (hash-routed views), plus `book.css` and `book.js` (now one page per chapter; see the amendment). Vanilla JavaScript, no build step, no npm dependencies. It opens directly from disk and can be served by any static host such as GitHub Pages.
2. Components are declared in HTML with data attributes and enhanced by `book.js`: code tours (`figure.code`), animated tracers (`.tracer` with a JSON spec), quizzes (`.quiz`), glossary pop-ups (`.term`), and chapter-specific playgrounds (`data-playground`).
3. Syntax highlighting uses Prism 1.29.0 loaded from cdnjs. If it fails to load, code renders as plain text.
4. `docs/book/tools/check-snippets.mjs` runs in CI. It fails the build if any code excerpt is not a contiguous copy of its `data-source` file (ignoring indentation), if a tour anchor is missing from its excerpt, if a glossary link has no definition, if tracer JSON is invalid, or if a quiz answer is not one of its options.
5. Simulations (the API console and tracers) run in the browser and mirror the application's rules. Their status codes and error bodies are recorded from the running service, and the page says so.
6. The Markdown chapters in `docs/chapters/` stay the primary, diff-friendly text. The book is the interactive companion and is updated in the same commit as the chapter it covers.

## Alternatives

- **Static site generator (Docusaurus, MkDocs):** search, theming and versioned docs, but it adds a Node or Python toolchain and a build step to a Java project, and interactive widgets would still need custom code.
- **Notebook-style live Java (JShell in the browser, or a backend sandbox):** truly executable snippets, but heavy, slow to start, and it would require hosting an execution service.
- **Excerpts loaded at runtime from the repository:** no drift by construction, but `fetch` fails when the page is opened from disk, and raw files from GitHub would show `main`, not the reader's checkout.

## Consequences

- Excerpts are duplicated in `index.html`, but CI catches every drift, so a code change that invalidates the book fails its pull request until the book is updated.
- Simulated behavior can diverge from the real service in ways the checker cannot detect. Mitigation: each chapter's "Run it yourself" section exercises the real API, and simulator responses are re-recorded when endpoints change.
- One HTML file grows with every chapter. If it passes roughly 300 KB, split into one page per chapter sharing `book.css` and `book.js`. (Done after Chapter 3, at 300.5 KB; see the amendment below.)
- Quiz progress lives in the reader's `localStorage` only. There are no accounts, no tracking, and nothing leaves the browser.

## Amendment: one page per chapter (after Chapter 3)

`index.html` reached 300.5 KB with Chapter 3, and Chapter 4 would add roughly 80 KB more. Every edit and review touched one very large file.

**Decision.** The book is now one page per view: `index.html` (home), `ch-01.html`, `ch-02.html`, `ch-03.html`, `hood.html` and `glossary.html`, all sharing `book.css` and `book.js`. Still no build step, and every page still opens from disk.

- **Navigation.** Each page carries the same contents rail as static HTML, so it works without JavaScript. The checker fails if any page's rail differs from `index.html`'s. Hash routing is gone; links are ordinary `page.html#heading-id` URLs. `book.js` redirects old single-page links such as `index.html#ch-03` to `ch-03.html`.
- **Glossary.** Pop-ups on every page need every definition, and `fetch` doesn't work from disk. The glossary therefore lives once, in `glossary.js` (a script that sets `window.BOOK_GLOSSARY`). `glossary.html` renders it, and the checker loads it to resolve `data-term` links. Alternatives rejected: copying the `<dl>` into every page (six copies to keep in step), or keeping it only on `glossary.html` (pop-ups would break elsewhere).
- **Quiz scores across pages.** The rail shows every chapter's score, but a page only contains its own quiz and answer key. Each quiz now also saves its score (`quizscore.<id>`) when it renders. The rail's `data-total` attributes give the question count before a quiz has been opened, and the checker verifies them. Answer keys (`quiz.<id>`) are unchanged, so earlier progress is kept. A chapter's score shows in the rail of other pages after that chapter's page has been opened once.
- **Consequence.** The rail is duplicated in six files. Adding a chapter means editing every page's rail; the checker makes a missed page fail CI rather than drift quietly.
