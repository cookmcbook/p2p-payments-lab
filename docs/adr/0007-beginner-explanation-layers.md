# ADR 0007 — Explain every step in plain words before the technical detail

**Status:** Accepted

## Context

ADR 0004 gave each tracer step a `deep` field, so the book could show what frameworks and the database really do. Readers got the depth, but a reader new to backend work found the deep text hard to follow. It assumed vocabulary it hadn't explained, such as "isolation level", "row header", "proxy" and "persistence context". It also said *how* something happens without first saying *what* is happening in everyday terms, or *why* the step matters for the money.

The intended reader includes a new hire on their first week: comfortable with basic programming, but not with Spring, SQL transactions or concurrency.

## Decision

1. Tracer steps gain two beginner layers. `what` describes the step in plain words, with any new term defined in the same sentence. `why` says what would go wrong without the step, or what it protects. The renderer shows them first, labelled **What's happening** and **Why it matters**. `deep` moves into a **How it works underneath** fold-out. Once the reader opens the fold-out, it stays open as they step on.
2. In any tracer that uses `what` on one step, every step has both `what` and `why`. `check-snippets.mjs` enforces this, so a new scenario can't silently skip the beginner layer.
3. Code-tour notes may start with `<span class="reads">`, shown as **In plain words**. It reads the highlighted code aloud as a sentence (for example, "sort the postings by account id and handle each one in turn") and explains unfamiliar syntax such as lambdas, `Optional` or `<>`. The existing note then explains the design reason.
4. Playgrounds narrate each move in plain words next to their technical log. In the race playground, that narration is the **What just happened** box.
5. A chapter that relies on ideas earlier chapters didn't teach opens with a collapsed primer, **New to backend work? Start here**. Each idea in it is given as What / Why / Where you'll see it.

Chapter 3 is the first chapter written this way. Chapters 1, 2 and the Under the hood companion predate this ADR and are converted separately.

## Alternatives

- **Rewrite `deep` in simpler language:** loses the exact framework and database detail that ADR 0004 exists to provide, and advanced readers asked for it.
- **A global "beginner mode" toggle:** hides the plain layer from readers who don't know they need it, and doubles the number of states to test.
- **Lean on the glossary pop-ups alone:** definitions help with words, but don't say why a step exists or what a line of code means as a sentence.

## Consequences

- Every new tracer step costs two more short paragraphs to write, and those must stay consistent with `deep` and the recorded logs.
- The main path stays short: the lane note is still one line, and the deep text is one click away.
- Readers can stop at the level they need: lane note, plain words, then the internals.
