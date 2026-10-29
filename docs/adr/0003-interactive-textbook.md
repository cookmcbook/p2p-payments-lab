# ADR 0003 — An interactive textbook as static HTML, verified against the code in CI

**Status:** Accepted

## Context

The Markdown chapters explain the system, but a learner can only read them. Concepts such as a request crossing layers, floating-point drift, or which validation layer rejects an input are easier to understand by trying them. The book must stay accurate as the code changes; a textbook that quotes code that no longer exists teaches the wrong thing.

## Decision

1. Add an interactive edition at `docs/book/`: one `index.html` with all chapters (hash-routed views), plus `book.css` and `book.js`. Vanilla JavaScript, no build step, no npm dependencies. It opens directly from disk and can be served by any static host such as GitHub Pages.
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
- One HTML file grows with every chapter. If it passes roughly 300 KB, split into one page per chapter sharing `book.css` and `book.js`.
- Quiz progress lives in the reader's `localStorage` only. There are no accounts, no tracking, and nothing leaves the browser.
