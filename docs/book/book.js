/* P2P Payments Lab textbook — interactive components. No build step; vanilla JS.
   Components are declared in index.html with data attributes and enhanced here. */
(function () {
  "use strict";

  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const el = (tag, attrs = {}, ...children) => {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k === "class") node.className = v;
      else if (k === "text") node.textContent = v;
      else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
      else node.setAttribute(k, v);
    }
    for (const c of children) node.append(c);
    return node;
  };
  const reducedMotion = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  // Per-viewer conveniences only (quiz answers, theme). Storage can be unavailable; never rely on it.
  const store = {
    get(key, fallback) {
      try { const v = localStorage.getItem("p2plab." + key); return v === null ? fallback : JSON.parse(v); }
      catch { return fallback; }
    },
    set(key, value) {
      try { localStorage.setItem("p2plab." + key, JSON.stringify(value)); } catch { /* ignore */ }
    },
  };

  /* ---------------- theme ---------------- */
  function initTheme() {
    const saved = store.get("theme", "system");
    const apply = (mode) => {
      if (mode === "system") document.documentElement.removeAttribute("data-theme");
      else document.documentElement.setAttribute("data-theme", mode);
      $$(".theme-toggle button").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.mode === mode)));
    };
    $$(".theme-toggle button").forEach((b) => b.addEventListener("click", () => { store.set("theme", b.dataset.mode); apply(b.dataset.mode); }));
    if (saved !== "system") apply(saved); else $$(".theme-toggle button").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.mode === "system")));
  }

  /* ---------------- router (one view per chapter) ---------------- */
  function initRouter() {
    const views = $$("[data-view]");
    const names = views.map((v) => v.dataset.view);
    const show = () => {
      const want = location.hash.slice(1);
      const name = names.includes(want) ? want : "home";
      views.forEach((v) => (v.hidden = v.dataset.view !== name));
      $$(".toc a").forEach((a) => a.toggleAttribute("aria-current", a.getAttribute("href") === "#" + name));
      $$(".toc a[aria-current]").forEach((a) => a.setAttribute("aria-current", "page"));
      buildSubToc(name);
      $(".rail").classList.remove("open");
      window.scrollTo(0, 0);
      const view = views.find((v) => v.dataset.view === name);
      document.title = (view.dataset.title ? view.dataset.title + " · " : "") + "P2P Payments Lab Textbook";
      view.dispatchEvent(new CustomEvent("viewshown"));
    };
    window.addEventListener("hashchange", show);
    show();
    // <a href="#view" data-jump="heading-id">: open the view, then scroll to a heading inside it.
    document.addEventListener("click", (e) => {
      const a = e.target.closest("a[data-jump]");
      if (!a) return;
      e.preventDefault();
      if (location.hash !== a.getAttribute("href")) { location.hash = a.getAttribute("href"); }
      const target = document.getElementById(a.dataset.jump);
      if (target) requestAnimationFrame(() => target.scrollIntoView({ behavior: reducedMotion() ? "auto" : "smooth" }));
    });
    const menu = $(".topbar button");
    if (menu) menu.addEventListener("click", () => {
      const open = $(".rail").classList.toggle("open");
      menu.setAttribute("aria-expanded", String(open));
    });
  }

  function buildSubToc(name) {
    $$(".subtoc").forEach((s) => s.remove());
    const link = $(`.toc a[href="#${name}"]`);
    const view = $(`[data-view="${name}"]`);
    if (!link || !view) return;
    const heads = $$("h2[id]", view);
    if (!heads.length) return;
    const list = el("ul", { class: "subtoc" });
    heads.forEach((h) => list.append(el("li", {}, el("button", {
      type: "button", text: h.dataset.short || h.textContent,
      onclick: () => { $(".rail").classList.remove("open"); h.scrollIntoView({ behavior: reducedMotion() ? "auto" : "smooth" }); },
    }))));
    link.parentElement.append(list);
  }

  /* ---------------- glossary popovers ---------------- */
  function initTerms() {
    let pop = null;
    const close = () => { if (pop) { pop.remove(); pop = null; } };
    document.addEventListener("click", (e) => {
      const t = e.target.closest(".term");
      if (!t) { if (!e.target.closest(".term-pop")) close(); return; }
      const dt = document.getElementById("term-" + t.dataset.term);
      close();
      if (!dt) return;
      const dd = dt.nextElementSibling;
      pop = el("div", { class: "term-pop", role: "dialog" }, el("b", { text: dt.textContent }));
      pop.append(...Array.from(dd.childNodes).map((n) => n.cloneNode(true)));
      document.body.append(pop);
      const r = t.getBoundingClientRect();
      const left = Math.min(window.scrollX + r.left, window.scrollX + document.documentElement.clientWidth - pop.offsetWidth - 16);
      pop.style.left = Math.max(16, left) + "px";
      pop.style.top = window.scrollY + r.bottom + 6 + "px";
    });
    document.addEventListener("keydown", (e) => { if (e.key === "Escape") close(); });
    $$(".term").forEach((t) => t.setAttribute("type", "button"));
  }

  /* ---------------- copyable commands ---------------- */
  function initCommands() {
    $$(".cmd").forEach((box) => {
      const pre = $("pre", box);
      const btn = el("button", { class: "btn ghost copy", type: "button", text: "Copy" });
      btn.addEventListener("click", () => {
        const done = () => { btn.textContent = "Copied"; setTimeout(() => (btn.textContent = "Copy"), 1500); };
        const fallback = () => { const range = document.createRange(); range.selectNodeContents(pre); const s = getSelection(); s.removeAllRanges(); s.addRange(range); btn.textContent = "Selected"; };
        try { navigator.clipboard.writeText(pre.textContent).then(done, fallback); } catch { fallback(); }
      });
      box.append(btn);
    });
  }

  /* ---------------- code tours ---------------- */
  function highlight(line, lang) {
    const P = window.Prism;
    if (P && P.languages[lang]) return P.highlight(line, P.languages[lang], lang);
    return line.replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" })[c]);
  }

  function initCode(fig) {
    const src = $("pre.src", fig);
    const lang = fig.dataset.lang;
    const text = src.textContent.replace(/^\n/, "").replace(/\s+$/, "");
    const lines = text.split("\n");
    const list = el("ol", { class: "lines", "aria-label": "Source code" });
    const items = lines.map((line) => {
      const li = el("li");
      const code = el("span");
      code.innerHTML = highlight(line, lang) || " ";
      li.append(code);
      list.append(li);
      return li;
    });
    src.after(list);

    const notes = $$("ol.notes > li", fig).map((n) => {
      const at = n.dataset.at;
      const start = lines.findIndex((l) => l.includes(at));
      if (start < 0) console.warn("code note anchor not found:", at, fig.dataset.source);
      const span = Number(n.dataset.span || 1);
      return { html: n.innerHTML, start, end: start + span - 1 };
    }).filter((n) => n.start >= 0);
    if (!notes.length) return;

    const text$ = el("p", { class: "tour-text", "aria-live": "polite" });
    const count = el("span");
    const prev = el("button", { class: "btn ghost", type: "button", "aria-label": "Previous note", text: "‹" });
    const next = el("button", { class: "btn ghost", type: "button", "aria-label": "Next note", text: "›" });
    const tour = el("div", { class: "tour" }, text$, el("div", { class: "tour-nav" }, prev, count, next));
    list.after(tour);
    let current = 0;
    const go = (i) => {
      current = (i + notes.length) % notes.length;
      const n = notes[current];
      text$.innerHTML = n.html;
      count.textContent = `${current + 1} / ${notes.length}`;
      items.forEach((li, idx) => {
        const on = idx >= n.start && idx <= n.end;
        li.classList.toggle("on", on);
        li.classList.toggle("dim", !on);
      });
    };
    notes.forEach((n, i) => {
      for (let k = n.start; k <= n.end; k++) {
        items[k].classList.add("has-note");
        if (!items[k].dataset.note) { items[k].dataset.note = i; items[k].addEventListener("click", () => go(i)); }
      }
    });
    prev.addEventListener("click", () => go(current - 1));
    next.addEventListener("click", () => go(current + 1));
    go(0);
  }

  /* ---------------- request / startup tracer ---------------- */
  function initTracer(box) {
    const spec = JSON.parse($("script[type='application/json']", box).textContent);
    const chipsBox = $(".chips", box);
    const reqBox = $(".request", box);
    const log = $(".printout:not(.request)", box);
    const result = $(".result", box);
    const lanesBox = $(".lanes", box);
    const progress = $(".progress", box);
    const [playBtn, stepBtn, resetBtn] = $$(".controls button", box);

    const lanes = spec.lanes.map((l) => {
      const note = el("p", { class: "note" });
      const name = el("div", { class: "name" }, l.name);
      if (l.detail) name.append(el("small", { text: l.detail }));
      const li = el("li", { class: "lane" }, name, note);
      lanesBox.append(li);
      return { li, note };
    });
    const packet = el("div", { class: "packet", "aria-hidden": "true" });
    lanesBox.append(packet);

    // Optional per-step "deep" text: what is happening underneath the one-line note.
    let deep = null;
    if (spec.scenarios.some((sc) => sc.steps.some((st) => st.deep))) {
      deep = el("div", { class: "deep", "aria-live": "polite" });
      result.before(el("div", { class: "deep-box" }, el("span", { class: "label", text: "Under the hood" }), deep));
    }
    const showDeep = (text) => {
      if (!deep) return;
      deep.replaceChildren(...text.split("\n\n").map((para) => { const p = el("p"); p.innerHTML = para; return p; }));
    };

    let scenario = spec.scenarios[0];
    let i = 0;
    let timer = null;

    const movePacket = (laneIdx) => {
      const li = lanes[laneIdx].li;
      packet.style.transform = `translateY(${li.offsetTop + 12}px)`;
    };
    const stop = () => { clearInterval(timer); timer = null; playBtn.textContent = "Play"; };
    const reset = () => {
      stop();
      i = 0;
      lanes.forEach((l) => { l.li.className = "lane"; l.note.textContent = ""; });
      packet.className = "packet";
      log.innerHTML = "";
      result.innerHTML = "";
      reqBox.textContent = scenario.request;
      progress.textContent = `0 / ${scenario.steps.length}`;
      showDeep("Press <b>Step</b> to go one step at a time. Each step explains what is happening underneath it.");
      stepBtn.disabled = false; playBtn.disabled = false;
      movePacket(scenario.steps[0].lane);
    };
    const appendLog = (text, cls) => {
      const line = el("span", cls ? { class: cls } : {}, text + "\n");
      log.append(line);
      log.scrollTop = log.scrollHeight;
    };
    const step = () => {
      if (i >= scenario.steps.length) return;
      const s = scenario.steps[i];
      lanes.forEach((l) => l.li.classList.remove("active"));
      const lane = lanes[s.lane];
      lane.li.classList.add("visited", s.fail ? "failed" : "active");
      lane.note.textContent = s.text;
      movePacket(s.lane);
      if (s.fail) packet.classList.add("fail");
      if (s.log) appendLog(s.log, s.fail ? "fail" : "");
      showDeep(s.deep || s.text);
      i++;
      progress.textContent = `${i} / ${scenario.steps.length}`;
      if (i === scenario.steps.length) finish();
    };
    const finish = () => {
      stop();
      stepBtn.disabled = true; playBtn.disabled = true;
      const r = scenario.result;
      if (r.ok) packet.classList.add("ok");
      lanes.forEach((l) => { if (!l.li.classList.contains("visited")) l.li.classList.add("skipped"); });
      result.replaceChildren(el("span", { class: "pill " + (r.ok ? "ok" : "fail"), text: r.status }), el("span", { text: r.summary }));
    };
    const play = () => {
      if (timer) { stop(); return; }
      if (i >= scenario.steps.length) reset();
      playBtn.textContent = "Pause";
      step();
      timer = setInterval(step, reducedMotion() ? 1600 : 1250);
    };

    spec.scenarios.forEach((sc, idx) => {
      const chip = el("button", { class: "chip", type: "button", "aria-pressed": String(idx === 0), text: sc.label });
      chip.addEventListener("click", () => {
        scenario = sc;
        $$(".chip", chipsBox).forEach((c) => c.setAttribute("aria-pressed", String(c === chip)));
        reset();
      });
      chipsBox.append(chip);
    });
    playBtn.addEventListener("click", play);
    stepBtn.addEventListener("click", () => { stop(); step(); });
    resetBtn.addEventListener("click", reset);
    window.addEventListener("resize", () => movePacket(scenario.steps[Math.max(0, i - 1)].lane));
    box.closest("[data-view]").addEventListener("viewshown", reset);
    reset();
  }

  /* ---------------- explorer: pick a layer or topic, see its panel ---------------- */
  function initExplorer(box) {
    const tabs = $$("[data-pane]", box);
    const panes = $$(".pane[data-name]", box);
    const show = (name) => {
      tabs.forEach((t) => t.setAttribute("aria-pressed", String(t.dataset.pane === name)));
      panes.forEach((p) => (p.hidden = p.dataset.name !== name));
    };
    tabs.forEach((t) => { t.type = "button"; t.addEventListener("click", () => show(t.dataset.pane)); });
    if (tabs.length) show(tabs[0].dataset.pane);
  }

  /* ---------------- quizzes ---------------- */
  const quizListeners = [];
  function quizScore(id) {
    const box = $(`.quiz[data-quiz="${id}"]`);
    if (!box) return null;
    const qs = $$(".q", box);
    const answers = store.get("quiz." + id, {});
    let answered = 0, right = 0;
    qs.forEach((q, idx) => { if (answers[idx]) { answered++; if (answers[idx] === q.dataset.answer) right++; } });
    return { total: qs.length, answered, right };
  }
  function initQuiz(box) {
    const id = box.dataset.quiz;
    const qs = $$(".q", box);
    let answers = store.get("quiz." + id, {});
    const meter = el("div", { class: "meter" }, el("i"));
    const label = el("span");
    const resetBtn = el("button", { class: "btn ghost", type: "button", text: "Reset quiz" });
    box.prepend(el("div", { class: "quiz-bar" }, label, meter, resetBtn));

    const render = () => {
      qs.forEach((q, idx) => {
        const chosen = answers[idx];
        const why = $(".q-why", q);
        q.classList.remove("correct", "incorrect");
        $$(".q-opts button", q).forEach((b) => {
          b.classList.remove("right", "wrong");
          b.disabled = Boolean(chosen);
          b.removeAttribute("aria-pressed");
          if (chosen) {
            if (b.dataset.opt === q.dataset.answer) b.classList.add("right");
            if (b.dataset.opt === chosen && chosen !== q.dataset.answer) b.classList.add("wrong");
            if (b.dataset.opt === chosen) b.setAttribute("aria-pressed", "true");
          }
        });
        why.hidden = !chosen;
        if (chosen) q.classList.add(chosen === q.dataset.answer ? "correct" : "incorrect");
      });
      const s = quizScore(id);
      label.textContent = `${s.right} correct · ${s.answered} of ${s.total} answered`;
      $("i", meter).style.width = (100 * s.answered / s.total) + "%";
      quizListeners.forEach((fn) => fn());
    };
    qs.forEach((q, idx) => {
      const n = $(".q-text", q);
      n.prepend(el("span", { class: "qn", text: String(idx + 1).padStart(2, "0") }));
      $$(".q-opts button", q).forEach((b) => {
        b.type = "button";
        b.addEventListener("click", () => {
          if (answers[idx]) return;
          answers = { ...answers, [idx]: b.dataset.opt };
          store.set("quiz." + id, answers);
          render();
        });
      });
    });
    resetBtn.addEventListener("click", () => { answers = {}; store.set("quiz." + id, answers); render(); });
    render();
  }
  function initScores() {
    const paint = () => {
      $$("[data-score]").forEach((node) => {
        const s = quizScore(node.dataset.score);
        if (!s) return;
        node.textContent = s.answered ? `${s.right}/${s.total}` : `quiz 0/${s.total}`;
      });
    };
    quizListeners.push(paint);
    paint();
  }

  /* ---------------- playground: floating point vs minor units ---------------- */
  function initMoney(box) {
    const amount = $("#money-amount", box);
    const times = $("#money-times", box);
    const err = $(".err", box);
    const out = { dbl: $("[data-out=double]", box), cents: $("[data-out=cents]", box) };
    const run = () => {
      const a = amount.value.trim();
      const n = Number(times.value);
      if (!/^\d{1,7}(\.\d{1,2})?$/.test(a)) { err.textContent = "Enter dollars with at most two decimal places, for example 19.99."; return; }
      if (!Number.isInteger(n) || n < 1 || n > 1000000) { err.textContent = "Repeat between 1 and 1,000,000 times."; return; }
      err.textContent = "";
      const [whole, frac = ""] = a.split(".");
      const cents = Number(whole) * 100 + Number(frac.padEnd(2, "0"));
      // A JavaScript number is an IEEE 754 binary64 value: exactly Java's double.
      const d = Number(a);
      let sum = 0;
      for (let k = 0; k < n; k++) sum += d;
      const totalCents = cents * n;
      const exact = (totalCents / 100).toFixed(2);
      const ok = sum === totalCents / 100;
      out.dbl.className = "readout " + (ok ? "good" : "bad");
      $(".big", out.dbl).textContent = String(sum);
      $("p", out.dbl).textContent = ok ? "Matches the exact total this time." : `Off from ${exact} by ${Math.abs(sum - totalCents / 100).toExponential(2)}. sum == ${exact} is false.`;
      out.cents.className = "readout good";
      $(".big", out.cents).textContent = totalCents.toLocaleString("en-US");
      $("p", out.cents).textContent = `minor units = $${Number(exact).toLocaleString("en-US", { minimumFractionDigits: 2 })} exactly. Integer addition never rounds.`;
    };
    $$(".chip", box).forEach((c) => c.addEventListener("click", () => { amount.value = c.dataset.amount; times.value = c.dataset.times; run(); }));
    amount.addEventListener("input", run);
    times.addEventListener("input", run);
    run();
  }

  /* ---------------- playground: API simulator (mirrors Chapter 2 rules) ---------------- */
  function initSim(box) {
    const method = $("#sim-method", box);
    const path = $("#sim-path", box);
    const body = $("#sim-body", box);
    const out = $(".printout", box);
    const accounts = new Map();
    let lastId = null;
    const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : "10000000-1000-4000-8000-100000000000".replace(/[018]/g, (c) => (c ^ (Math.random() * 16) >> (c / 4)).toString(16)));
    const javaTrimEmpty = (s) => s.replace(/^[\u0000- ]+|[\u0000- ]+$/g, "").length === 0;
    const micros = () => new Date().toISOString().replace("Z", String(Math.floor(Math.random() * 1000)).padStart(3, "0") + "Z");
    const problem = (status, title, detail, instance, headers = {}) => ({
      status, headers: { ...headers, "Content-Type": "application/problem+json" },
      body: { type: "about:blank", title, status, detail, instance },
    });
    const notAllowed = (m, allow, p) => problem(405, "Method Not Allowed", `Method '${m}' is not supported.`, p, { Allow: allow });

    function handle(m, p, raw) {
      if (p === "/api/v1/payments/capabilities") {
        if (m !== "GET") return notAllowed(m, "GET", p);
        return { status: 200, headers: { "Content-Type": "application/json" }, body: { service: "p2p-payments-lab", paymentProcessingEnabled: false, accountsEnabled: true, chapter: 2 } };
      }
      const r = p.match(/^\/api\/v1\/accounts(?:\/([^/]+)(\/balance)?)?$/);
      if (!r) return problem(404, "Not Found", `No static resource ${p.slice(1)}.`, p);
      const [, id, balance] = r;
      if (!id) {
        if (m !== "POST") return notAllowed(m, "POST", p);
        let req;
        try { req = JSON.parse(raw); } catch { return problem(400, "Bad Request", "Failed to read request", p); }
        if (req === null || typeof req !== "object" || Array.isArray(req)) return problem(400, "Bad Request", "Failed to read request", p);
        const name = req.displayName, cur = req.currency;
        const invalid = typeof name !== "string" || javaTrimEmpty(name) || name.length > 80 || cur !== "USD";
        if (invalid) return problem(400, "Bad Request", "Invalid request content.", p);
        const account = { id: uuid(), displayName: name.trim(), currency: "USD", createdAt: micros() };
        accounts.set(account.id, account);
        lastId = account.id;
        return { status: 201, headers: { Location: "/api/v1/accounts/" + account.id, "Content-Type": "application/json" }, body: account };
      }
      if (m !== "GET") return notAllowed(m, "GET", p);
      if (!UUID_RE.test(id)) return problem(400, "Bad Request", `Failed to convert 'id' with value: '${id}'`, p);
      const acct = accounts.get(id.toLowerCase());
      if (!acct) return problem(404, "Account not found", "Account not found", p);
      if (balance) return { status: 200, headers: { "Content-Type": "application/json" }, body: { accountId: acct.id, currency: "USD", ledgerMinor: 0, availableMinor: 0 } };
      return { status: 200, headers: { "Content-Type": "application/json" }, body: acct };
    }
    const REASONS = { 200: "OK", 201: "Created", 400: "Bad Request", 404: "Not Found", 405: "Method Not Allowed" };
    const send = () => {
      const p = path.value.trim().replace(/\{last\}/g, lastId || "(create an account first)");
      const res = handle(method.value, p, body.value);
      const cls = res.status < 300 ? "s2" : "s4";
      out.replaceChildren(
        el("span", { class: "status " + cls, text: `HTTP/1.1 ${res.status} ${REASONS[res.status]}\n` }),
        ...Object.entries(res.headers).map(([k, v]) => el("span", { text: `${k}: ${v}\n` })),
        el("span", { text: "\n" + JSON.stringify(res.body, null, 2) + "\n" }),
      );
    };
    const presets = {
      create: ["POST", "/api/v1/accounts", '{"displayName": "Alice Example", "currency": "USD"}'],
      padded: ["POST", "/api/v1/accounts", '{"displayName": "   Bob   ", "currency": "USD"}'],
      eur: ["POST", "/api/v1/accounts", '{"displayName": "Carol", "currency": "EUR"}'],
      blank: ["POST", "/api/v1/accounts", '{"displayName": "   ", "currency": "USD"}'],
      broken: ["POST", "/api/v1/accounts", '{"displayName": "Dan", '],
      get: ["GET", "/api/v1/accounts/{last}", ""],
      balance: ["GET", "/api/v1/accounts/{last}/balance", ""],
      unknown: ["GET", "/api/v1/accounts/00000000-0000-0000-0000-000000000000", ""],
      malformed: ["GET", "/api/v1/accounts/not-a-uuid", ""],
      list: ["GET", "/api/v1/accounts", ""],
    };
    $$(".chip", box).forEach((c) => c.addEventListener("click", () => {
      const [m, p, b] = presets[c.dataset.preset];
      method.value = m; path.value = lastId ? p.replace("{last}", lastId) : p; body.value = b;
      send();
    }));
    $("#sim-send", box).addEventListener("click", send);
    $("form", box).addEventListener("submit", (e) => { e.preventDefault(); send(); });
    method.value = "POST"; path.value = presets.create[1]; body.value = presets.create[2];
    send();
  }

  /* ---------------- playground: defense in depth ---------------- */
  function initLayers(box) {
    const name = $("#layers-name", box);
    const cur = $("#layers-currency", box);
    const shown = $(".visible-ws", box);
    const rows = { api: $("[data-layer=api]", box), domain: $("[data-layer=domain]", box), db: $("[data-layer=db]", box) };
    const verdict = $(".verdict", box);
    const entry = () => $("input[name=layers-entry]:checked", box).value;
    const show = (s) => s.replace(/ /g, "·").replace(/\t/g, "→").replace(/\n/g, "↵") || "(empty)";
    const set = (key, state, pill, why) => {
      const li = rows[key];
      li.className = state;
      $(".pill", li).className = "pill " + (state === "reject" ? "fail" : state === "pass" ? "ok" : "pending");
      $(".pill", li).textContent = pill;
      $(".why", li).textContent = why;
    };
    const run = () => {
      const raw = name.value.replace(/\\t/g, "\t");
      const c = cur.value;
      const e = entry();
      shown.textContent = `name as sent: "${show(raw)}" (${raw.length} chars)`;
      let stoppedBy = null;
      let value = raw;

      // Layer 1: Bean Validation on CreateAccountRequest (only on the HTTP path).
      if (e !== "http") set("api", "bypass", "bypassed", e === "java" ? "Code that calls the domain directly never goes through the HTTP request DTO." : "SQL clients talk straight to PostgreSQL.");
      else {
        const trimmed = raw.replace(/^[\u0000- ]+|[\u0000- ]+$/g, "");
        if (!trimmed.length) stoppedBy = ["api", "@NotBlank: blank after trimming → 400 Invalid request content."];
        else if (raw.length > 80) stoppedBy = ["api", `@Size(max = 80): ${raw.length} characters → 400.`];
        else if (c !== "USD") stoppedBy = ["api", `@Pattern(regexp = "USD"): "${c}" does not match → 400.`];
        if (stoppedBy) set("api", "reject", "rejected", stoppedBy[1]); else set("api", "pass", "passed", "@NotBlank, @Size and @Pattern all pass.");
      }
      // Layer 2: Account record constructor (HTTP and Java paths).
      if (stoppedBy) set("domain", "unreached", "not reached", "An earlier layer already rejected the input.");
      else if (e === "sql") set("domain", "bypass", "bypassed", "Raw SQL never constructs an Account.");
      else if (c !== "USD") { stoppedBy = ["domain", `Currency is an enum with one constant, USD. Currency.${c || "?"} does not compile.`]; set("domain", "reject", "rejected", stoppedBy[1]); }
      else if (!raw.trim().length) { stoppedBy = ["domain", "displayName.isBlank() → IllegalArgumentException."]; set("domain", "reject", "rejected", stoppedBy[1]); }
      else {
        value = raw.trim();
        if (value.length > 80) { stoppedBy = ["domain", `After strip() the name is still ${value.length} characters → IllegalArgumentException.`]; set("domain", "reject", "rejected", stoppedBy[1]); }
        else set("domain", "pass", "passed", `isBlank() is false; strip() stores "${show(value)}".`);
      }
      // Layer 3: PostgreSQL constraints from V2__accounts.sql.
      if (stoppedBy) set("db", "unreached", "not reached", "An earlier layer already rejected the input.");
      else if (c !== "USD") { stoppedBy = ["db", `CHECK (currency = 'USD') fails → ERROR: violates check constraint "account_currency_check".`]; set("db", "reject", "rejected", stoppedBy[1]); }
      else if (value.length > 80) { stoppedBy = ["db", "VARCHAR(80) → ERROR: value too long for type character varying(80)."]; set("db", "reject", "rejected", stoppedBy[1]); }
      else if (!value.replace(/^ +| +$/g, "").length) { stoppedBy = ["db", "CHECK (char_length(btrim(display_name)) > 0) fails → ERROR: violates check constraint."]; set("db", "reject", "rejected", stoppedBy[1]); }
      else set("db", "pass", "passed", `btrim() removes spaces only; ${value.replace(/^ +| +$/g, "").length} character(s) remain, so the CHECK passes.`);

      if (stoppedBy) { verdict.className = "verdict rejected"; verdict.textContent = "Rejected. Nothing was stored."; }
      else if (!value.trim().length) { verdict.className = "verdict leak"; verdict.textContent = "Stored, and it shouldn't have been: a name that looks blank got into the table. Exercise 7 asks you to close this gap."; }
      else { verdict.className = "verdict stored"; verdict.textContent = `Stored as "${show(value)}".`; }
    };
    $$(".chip", box).forEach((chip) => chip.addEventListener("click", () => {
      if ("name" in chip.dataset) name.value = chip.dataset.name === "TAB" ? "\\t" : chip.dataset.name === "LONG" ? "x".repeat(81) : chip.dataset.name;
      if ("currency" in chip.dataset) cur.value = chip.dataset.currency;
      run();
    }));
    [name, cur].forEach((i) => i.addEventListener("input", run));
    $$("input[name=layers-entry]", box).forEach((r) => r.addEventListener("change", run));
    run();
  }

  /* ---------------- playground: the microsecond bug ---------------- */
  function initMicros(box) {
    const fix = $("#micros-fix", box);
    const outs = { clock: $("[data-out=clock]", box), resp: $("[data-out=response]", box), read: $("[data-out=read]", box), eq: $("[data-out=equal]", box) };
    let nanos = "";
    const fresh = () => {
      const iso = new Date().toISOString(); // millisecond precision
      // Six more digits for micro- and nanoseconds. The final three stay below 500 so the stored
      // value is the same whether the driver rounds or truncates.
      nanos = iso.replace("Z", String(Math.floor(Math.random() * 1000)).padStart(3, "0") + String(Math.floor(Math.random() * 500)).padStart(3, "0") + "Z");
    };
    const render = () => {
      const stored = nanos.replace(/(\.\d{6})\d{3}Z$/, "$1Z");
      const response = fix.checked ? stored : nanos;
      outs.clock.textContent = nanos;
      outs.resp.textContent = response;
      outs.read.textContent = stored;
      const equal = response === stored;
      outs.eq.parentElement.className = "readout " + (equal ? "good" : "bad");
      outs.eq.textContent = equal ? "equal" : "not equal";
    };
    $("#micros-run", box).addEventListener("click", () => { fresh(); render(); });
    fix.addEventListener("change", render);
    fresh(); render();
  }

  /* ---------------- playground: Docker build cache ---------------- */
  // Docker's rule: a step is reused (CACHED) only if its instruction, the files it copies, and every
  // earlier step in the same stage are unchanged. The first miss forces every later step in that stage to run.
  function initCache(box) {
    const naive = $("#cache-naive", box);
    const lists = $$("ol.build-steps", box);
    const summary = $(".verdict", box);
    let change = "src";
    const run = () => {
      const variant = naive.checked ? "naive" : "real";
      lists.forEach((l) => (l.hidden = l.dataset.variant !== variant));
      const list = lists.find((l) => l.dataset.variant === variant);
      const missed = {};
      let jarRebuilt = false;
      const slow = [];
      let ran = 0;
      $$("li", list).forEach((li) => {
        if ("meta" in li.dataset) {
          // USER, EXPOSE and ENTRYPOINT only change image settings; BuildKit doesn't run them as steps.
          li.className = "";
          $(".pill", li).className = "pill pending";
          $(".pill", li).textContent = "settings";
          return;
        }
        const on = (li.dataset.on || "").split(" ").filter(Boolean);
        const stage = li.dataset.stage;
        const hit = !missed[stage] && !on.includes(change) && !(on.includes("jar") && jarRebuilt);
        if (!hit) { missed[stage] = true; ran++; if (li.dataset.slow) slow.push(li.dataset.slow); }
        if (li.dataset.out === "jar" && !hit) jarRebuilt = true;
        li.className = hit ? "cached" : "rebuilt";
        $(".pill", li).className = "pill " + (hit ? "ok" : "fail");
        $(".pill", li).textContent = hit ? "cached" : "runs";
      });
      if (!ran) { summary.className = "verdict stored"; summary.textContent = "Every step is reused. The build finishes in seconds and produces an identical image."; return; }
      // Re-downloading dependencies is the expensive outcome the Dockerfile's step order exists to avoid.
      const downloads = slow.some((text) => text.includes("download"));
      summary.className = "verdict " + (downloads ? "leak" : "stored");
      summary.textContent = `${ran} step${ran === 1 ? "" : "s"} run again.` +
        (slow.length ? ` Slow: ${slow.join("; ")}.` : " None of them is slow.") +
        (downloads ? " Every dependency is downloaded again." : "");
    };
    $$(".chip", box).forEach((c) => c.addEventListener("click", () => {
      change = c.dataset.change;
      $$(".chip", box).forEach((x) => x.setAttribute("aria-pressed", String(x === c)));
      run();
    }));
    naive.addEventListener("change", run);
    run();
  }

  /* ---------------- playground: two transactions racing for one balance ---------------- */
  // Mirrors PostgreSQL READ COMMITTED: a read sees committed postings plus the transaction's own; an INSERT stays
  // invisible to the other transaction until COMMIT. With the lock, a second FOR NO KEY UPDATE waits for COMMIT.
  function initRace(box) {
    const lock = $("#race-lock", box);
    const log = $(".printout", box);
    const verdict = $(".verdict", box);
    const committedOut = $("[data-out=committed]", box);
    const holderOut = $("[data-out=holder]", box);
    const cols = $$(".tx", box).map((c) => ({ list: $(".tx-steps", c), seen: $(".seen", c), btn: $("button", c) }));
    const START = 1000, SPEND = 1000;
    const cents = (c) => (c < 0 ? "−" : "") + "$" + (Math.abs(c) / 100).toFixed(2);
    const LABEL = {
      begin: "BEGIN",
      lock: "SELECT … FROM account WHERE id = A FOR NO KEY UPDATE",
      read: "SELECT SUM(amount_minor) FROM posting WHERE account_id = A",
      check: "balance − $10.00 ≥ 0 ?",
      insert: "INSERT postings: A −1000, B +1000",
      commit: "COMMIT",
    };
    let committed, holder, tx, timer;
    const plan = () => (lock.checked ? ["begin", "lock", "read", "check", "insert", "commit"] : ["begin", "read", "check", "insert", "commit"]);
    const over = (t) => t.state === "done" || t.state === "rolledback";

    const say = (t, text, cls) => { log.append(el("span", cls ? { class: cls } : {}, `T${t.n}  ${text}\n`)); log.scrollTop = log.scrollHeight; };
    const finish = (t, state) => {
      t.state = state;
      if (holder === t.n) {
        holder = null;
        // Releasing the lock wakes the waiting transaction: its SELECT ... FOR NO KEY UPDATE returns now.
        const other = tx.find((o) => o.state === "waiting");
        if (other) advance(other);
      }
    };
    function advance(t) {
      if (over(t)) return false;
      const step = plan()[t.i];
      if (step === "begin") say(t, "BEGIN");
      else if (step === "lock") {
        if (holder && holder !== t.n) {
          if (t.state !== "waiting") { t.state = "waiting"; say(t, `${LABEL.lock}  … waits: T${holder} holds the lock`, "fail"); }
          return false;
        }
        holder = t.n; t.state = "ready";
        say(t, `${LABEL.lock}  → granted`);
      } else if (step === "read") {
        // READ COMMITTED: committed rows plus this transaction's own uncommitted ones.
        t.seen = committed + t.pending;
        say(t, `${LABEL.read}  → ${t.seen}`);
      } else if (step === "check") {
        if (t.seen - SPEND < 0) {
          say(t, `${cents(t.seen)} is not enough → InsufficientFundsException → ROLLBACK, 409`, "fail");
          t.i++; finish(t, "rolledback"); return true;
        }
        say(t, `${cents(t.seen)} − $10.00 = ${cents(t.seen - SPEND)} → allowed`);
      } else if (step === "insert") {
        t.pending = -SPEND;
        say(t, `${LABEL.insert}  (visible only to T${t.n} until COMMIT)`);
      } else if (step === "commit") {
        committed += t.pending; t.pending = 0;
        say(t, `COMMIT → everyone now sees A = ${cents(committed)}`, "ok");
        t.i++; finish(t, "done"); return true;
      }
      t.i++;
      return true;
    }

    function render() {
      const steps = plan();
      tx.forEach((t, k) => {
        const c = cols[k];
        c.list.replaceChildren(...steps.map((s, idx) => {
          let cls = idx < t.i ? "done" : idx === t.i && !over(t) ? (t.state === "waiting" ? "waiting" : "next") : "";
          if (t.state === "rolledback" && idx === t.i - 1) cls = "failed";
          return el("li", { class: cls, text: LABEL[s] });
        }));
        c.seen.textContent = t.state === "waiting" ? "Waiting inside PostgreSQL for the lock…"
          : t.state === "done" ? "Committed." : t.state === "rolledback" ? "Rolled back: nothing written."
          : t.seen === null ? "Hasn't read the balance yet." : `Read the balance as ${cents(t.seen)}.`;
        c.btn.disabled = over(t) || t.state === "waiting";
      });
      committedOut.textContent = cents(committed);
      committedOut.parentElement.className = "readout " + (committed < 0 ? "bad" : "");
      const pending = tx.filter((t) => t.pending).map((t) => `T${t.n} has an uncommitted −$10.00`);
      holderOut.textContent = [holder ? `Row A locked by T${holder}.` : lock.checked ? "Row A is not locked." : "No locks taken.", ...pending].join(" ");
      verdict.hidden = !tx.every(over);
      if (tx.every(over)) {
        const wins = tx.filter((t) => t.state === "done").length;
        if (committed < 0) {
          verdict.className = "verdict leak";
          verdict.textContent = `Overdrawn: A is ${cents(committed)}. Both transactions read $10.00 before either committed, so both checks passed. No database rule fired: each entry balanced on its own.`;
        } else {
          verdict.className = "verdict stored";
          verdict.textContent = `Correct: A is ${cents(committed)}. ${wins} transfer succeeded and the other was refused.` +
            (lock.checked ? " The lock made the second transaction read the balance only after the first committed." : " This order was safe only because T1 finished before T2 read anything. Try “take turns”.");
        }
      }
    }
    const reset = () => {
      clearInterval(timer); timer = null;
      committed = START; holder = null;
      tx = [1, 2].map((n) => ({ n, i: 0, seen: null, pending: 0, state: "ready" }));
      log.replaceChildren();
      $$("[data-order]", box).forEach((b) => b.setAttribute("aria-pressed", "false"));
      render();
    };
    const play = (order, chip) => {
      reset();
      chip.setAttribute("aria-pressed", "true");
      let turn = 0;
      const tick = () => {
        if (tx.every(over)) { clearInterval(timer); timer = null; return; }
        const preferred = order === "serial" ? tx.find((t) => !over(t)) : tx[turn % 2];
        const other = tx.find((t) => t !== preferred && !over(t));
        if (!advance(preferred) && other) advance(other);
        turn++;
        render();
      };
      tick();
      timer = setInterval(tick, reducedMotion() ? 1300 : 900);
    };
    cols.forEach((c, k) => c.btn.addEventListener("click", () => { clearInterval(timer); timer = null; advance(tx[k]); render(); }));
    $$("[data-order]", box).forEach((b) => b.addEventListener("click", () => play(b.dataset.order, b)));
    $("[data-reset]", box).addEventListener("click", reset);
    lock.addEventListener("change", reset);
    reset();
  }

  /* ---------------- boot ---------------- */
  function boot() {
    initTheme();
    $$("figure.code").forEach(initCode);
    $$(".tracer").forEach(initTracer);
    $$(".quiz").forEach(initQuiz);
    initScores();
    initTerms();
    initCommands();
    $$("[data-playground=money]").forEach(initMoney);
    $$("[data-playground=sim]").forEach(initSim);
    $$("[data-playground=layers]").forEach(initLayers);
    $$("[data-playground=micros]").forEach(initMicros);
    $$("[data-playground=cache]").forEach(initCache);
    $$("[data-playground=race]").forEach(initRace);
    $$(".explorer").forEach(initExplorer);
    initRouter();
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot); else boot();
})();
