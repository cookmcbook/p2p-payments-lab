// Chapter 3 experiment: fire many simultaneous transfers from one account at the running service, then check
// that no money was created or destroyed. Needs Node 18+ and the lab overlay (infra/compose.lab.yaml).
//   node infra/lab/race.mjs [--requests 100] [--amount 100] [--fund 1000] [--opposite]
// --opposite also sends the same number of transfers back from B to A, to show that they don't deadlock.
const args = {};
for (let i = 2; i < process.argv.length; i++) {
  const name = process.argv[i].replace(/^--/, "");
  const next = process.argv[i + 1];
  if (next !== undefined && !next.startsWith("--")) { args[name] = next; i++; } else args[name] = true;
}
const base = process.env.API ?? "http://localhost:8080";
const requests = Number(args.requests ?? 100);
const amount = Number(args.amount ?? 100);
const fund = Number(args.fund ?? 1000);
const opposite = Boolean(args.opposite);

async function call(method, path, body) {
  const started = performance.now();
  const res = await fetch(base + path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null, ms: performance.now() - started };
}

async function openAccount(name) {
  const res = await call("POST", "/api/v1/accounts", { displayName: name, currency: "USD" });
  if (res.status !== 201) throw new Error(`could not open account: ${res.status}`);
  return res.body.id;
}
const balance = async (id) => (await call("GET", `/api/v1/accounts/${id}/balance`)).body.ledgerMinor;
const transfer = (from, to) => call("POST", "/lab/ledger/transfers", { fromAccountId: from, toAccountId: to, amountMinor: amount, currency: "USD" });
const dollars = (cents) => (cents < 0 ? "-" : "") + "$" + (Math.abs(cents) / 100).toFixed(2);
const percentile = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))];

const a = await openAccount("Race A");
const b = await openAccount("Race B");
const funded = await call("POST", "/lab/ledger/funding", { accountId: a, amountMinor: fund, currency: "USD" });
if (funded.status === 404) throw new Error("POST /lab/ledger/funding returned 404: start the stack with -f infra/compose.lab.yaml");
if (opposite) await call("POST", "/lab/ledger/funding", { accountId: b, amountMinor: fund, currency: "USD" });

// While the burst runs, sample the connection pool every 10 ms (needs /actuator/metrics, exposed by the lab overlay).
const gauge = async (name) => {
  const res = await fetch(`${base}/actuator/metrics/${name}`).catch(() => null);
  return res?.ok ? (await res.json()).measurements[0].value : null;
};
// The samples are HTTP requests too, so they queue behind the burst: a short burst can finish before any lands.
const pool = { active: 0, pending: 0, samples: 0, available: (await gauge("hikaricp.connections.max")) !== null };
let sampling = pool.available;
const sampler = (async () => {
  while (sampling) {
    const [active, pending] = await Promise.all([gauge("hikaricp.connections.active"), gauge("hikaricp.connections.pending")]);
    if (sampling) pool.samples++; else break;
    pool.active = Math.max(pool.active, active ?? 0);
    pool.pending = Math.max(pool.pending, pending ?? 0);
    await new Promise((r) => setTimeout(r, 10));
  }
})();

const label = opposite ? `${requests} A→B and ${requests} B→A` : `${requests} A→B`;
console.log(`Sending ${label} transfers of ${dollars(amount)} at once. ${opposite ? "A and B each start" : "A starts"} with ${dollars(fund)}.`);
const started = performance.now();
const results = await Promise.all([
  ...Array.from({ length: requests }, () => transfer(a, b)),
  ...(opposite ? Array.from({ length: requests }, () => transfer(b, a)) : []),
]);
const elapsed = performance.now() - started;
sampling = false;
await sampler;

const byStatus = {};
for (const r of results) byStatus[r.status] = (byStatus[r.status] ?? 0) + 1;
const times = results.map((r) => r.ms).sort((x, y) => x - y);
const [balA, balB] = [await balance(a), await balance(b)];
const total = fund * (opposite ? 2 : 1);

console.log(`Status codes: ${Object.entries(byStatus).map(([s, n]) => `${s} × ${n}`).join(", ")}`);
console.log(`Latency ms: min ${times[0].toFixed(0)}, median ${percentile(times, 0.5).toFixed(0)}, p95 ${percentile(times, 0.95).toFixed(0)}, max ${times.at(-1).toFixed(0)}`);
console.log(`Throughput: ${(results.length / (elapsed / 1000)).toFixed(0)} requests/s over ${elapsed.toFixed(0)} ms`);
if (pool.available) console.log(pool.samples
  ? `Connection pool (${pool.samples} samples): at most ${pool.active} connections in use, at most ${pool.pending} requests waiting for one`
  : "Connection pool: the burst finished before a sample landed (try --requests 1000)");
console.log(`Balances: A ${dollars(balA)}, B ${dollars(balB)}, A + B ${dollars(balA + balB)}`);

const problems = [];
if (balA < 0 || balB < 0) problems.push("a customer balance went negative");
if (balA + balB !== total) problems.push(`money was created or destroyed: A + B should be ${dollars(total)}`);
if (!opposite && (byStatus[201] ?? 0) !== Math.min(requests, Math.floor(fund / amount))) problems.push("unexpected number of successful transfers");
if (Object.keys(byStatus).some((s) => s !== "201" && s !== "409")) problems.push("some requests failed with something other than 201 or 409");
console.log(problems.length ? `INVARIANT BROKEN: ${problems.join("; ")}` : "Invariants hold: no negative balance, and no money created or destroyed.");
process.exitCode = problems.length ? 1 : 0;
