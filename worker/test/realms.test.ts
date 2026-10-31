import { test } from "node:test";
import assert from "node:assert/strict";
import worker, { MIGRATIONS, owners } from "../src/index.ts";
import { mockD1, mockR2 } from "./mock.ts";
import { TOKEN, O, setup, mkLink, put, post, tick } from "./setup.ts";

// ---------- realms ----------

test("realms: create, list, isolate, rename, delete", async () => {
  const { call, env } = setup();
  let l = await (await call("/api/realms")).json();
  assert.deepEqual(
    l.realms.map((r: { id: string; name: string }) => [r.id, r.name]),
    [["main", "Main"]],
  );
  assert.equal((await post(call, "/api/realms", { name: "  " })).status, 400);
  const trip = await (await post(call, "/api/realms", { name: "Trip" })).json();
  const T = `/api/realms/${trip.id}`;

  await call("/api/items", { method: "POST", body: "in main" });
  const n = await (await call(`${T}/items`, { method: "POST", body: "packing list" })).json();
  assert.equal(n.realm, trip.id);
  await tick();
  const f = await (await put(call, `${T}/files/map.png`, "PNG")).json();
  assert.equal((await call(`/api/items/${n.id}`)).status, 404); // /api/items is the first realm
  assert.equal((await call(`${T}/items/${n.id}`)).status, 200);
  assert.deepEqual(
    (await (await call(`${T}/items?q=packing`)).json()).items.map((i: { id: string }) => i.id),
    [n.id],
  );
  assert.equal((await (await call("/api/items?q=packing")).json()).items.length, 0);

  l = await (await call("/api/realms")).json();
  assert.deepEqual(
    l.realms.map((r: { count: number }) => r.count),
    [1, 2],
  );
  assert.equal((await (await post(call, T, { name: "Holiday" }, "PATCH")).json()).name, "Holiday");

  // links are per realm; /api/links shows them all
  const rl = await (await post(call, `${T}/links`, { scope: "read" })).json();
  assert.equal(rl.realm, trip.id);
  const p = new URL(rl.url).pathname;
  assert.deepEqual(
    (await (await call(`${p}/items`)).json()).items.map((i: { id: string }) => i.id),
    [f.id, n.id],
  );
  assert.match(
    await (await call(p, { headers: { accept: "text/html" } })).text(),
    /<h1><span>realm\/<\/span>Holiday<\/h1>/,
  );
  await mkLink(call, { scope: "drop" });
  assert.equal((await (await call("/api/links")).json()).links.length, 2);
  assert.equal((await (await call(`${T}/links`)).json()).links.length, 1);
  assert.equal((await post(call, `/api/realms/main/links`, { items: [n.id] })).status, 404); // not in that realm

  // multipart uploads stay in the realm they started in
  const { upload } = await (await post(call, `${T}/uploads`, { name: "big" })).json();
  assert.equal((await put(call, `/api/uploads/${upload}/1`, "x")).status, 404);

  assert.equal((await call(T, { method: "DELETE" })).status, 200);
  assert.equal((await call(T)).status, 404);
  assert.equal((await call(`${p}/items`)).status, 404);
  assert.equal((env as any).BUCKET.objects.size, 0);
  assert.equal((env as any).BUCKET.uploads.size, 0);
  assert.equal((await (await call("/api/realms")).json()).realms.length, 1);
});

test("selections: only picked items, editable, die with their last item", async () => {
  const { call } = setup();
  const a = await (await put(call, "/api/files/a.txt", "AAA")).json();
  await tick();
  const b = await (await call("/api/items", { method: "POST", body: "note b" })).json();
  const c = await (await call("/api/items", { method: "POST", body: "note c" })).json();
  assert.equal((await post(call, "/api/links", { scope: "full", items: [a.id] })).status, 400);
  assert.equal((await post(call, "/api/links", { items: [] })).status, 400);
  assert.equal((await post(call, "/api/links", { items: [a.id, "nope"] })).status, 404);

  const l = await mkLink(call, { items: [a.id, b.id, a.id] });
  assert.deepEqual(l.items, [a.id, b.id]);
  const p = new URL(l.url).pathname;
  const ids = async () => (await (await call(`${p}/items`)).json()).items.map((i: { id: string }) => i.id);
  assert.deepEqual(await ids(), [b.id, a.id]);
  assert.match(await (await call(p)).text(), /sees only the items picked/); // two items: help, not a download
  assert.equal((await call(`${p}/items/${c.id}`)).status, 404);
  assert.equal((await call(p, { method: "POST", body: "x" })).status, 403);

  assert.equal((await post(call, `/api/links/${l.token}`, { items: [c.id] }, "PATCH")).status, 200);
  assert.deepEqual(await ids(), [c.id]);
  assert.equal(await (await call(p)).text(), "note c"); // one item: the link is the download
  const listed = (await (await call("/api/links")).json()).links[0];
  assert.deepEqual(listed.items, [c.id]);
  const whole = await mkLink(call, { scope: "read" });
  assert.equal((await post(call, `/api/links/${whole.token}`, { items: [c.id] }, "PATCH")).status, 400);

  await call(`/api/items/${c.id}`, { method: "DELETE" });
  assert.equal((await call(`${p}/items`)).status, 404);
  assert.equal((await call(`${new URL(whole.url).pathname}/items`)).status, 200);
});

test("info tells a client what a link is without spending a use", async () => {
  const { call } = setup();
  const f = await (await put(call, "/api/files/a.txt", "AAA")).json();
  const one = await mkLink(call, { item: f.id, uses: 1, label: "bob" });
  const p = new URL(one.url).pathname;
  for (let i = 0; i < 3; i++) {
    const info = await (await call(`${p}/info`)).json();
    assert.equal(info.scope, "read");
    assert.deepEqual(info.realm.name, "Main");
    assert.equal(info.items, 1);
    assert.equal(info.item.name, "a.txt");
    assert.equal(info.uses_left, 1);
  }
  assert.equal(await (await call(p)).text(), "AAA");
  assert.equal((await call(`${p}/info`)).status, 410);

  const d = await (
    await call(`${new URL((await mkLink(call, { scope: "drop", max_bytes: 5 })).url).pathname}/info`)
  ).json();
  assert.deepEqual([d.scope, d.items, d.bytes_left], ["drop", undefined, 5]);
  const o = await (await call("/api/info")).json();
  assert.deepEqual([o.scope, o.realm, typeof o.version], ["owner", null, "number"]);
  assert.match(await (await call("/api")).text(), /## Realms/);
});

test("an existing database migrates: items land in Main, item links become selections", async () => {
  const D1 = mockD1();
  for (const sql of MIGRATIONS[0]) D1.db.exec(sql); // as deployed before realms, without the meta table
  D1.db.exec(`INSERT INTO items (id, kind, text, created) VALUES ('old1', 'note', 'from before', 1)`);
  D1.db.exec(`INSERT INTO links (token, scope, item, created) VALUES ('tok', 'item', 'old1', 1)`);
  const env = { DB: D1, BUCKET: mockR2(), REALM_TOKEN: TOKEN } as never;
  const call = (path: string) =>
    worker.fetch(new Request(O + path, { headers: { authorization: `Bearer ${TOKEN}` } }), env);

  const l = await (await call("/api/realms/main/items?q=before")).json();
  assert.deepEqual(
    l.items.map((i: { id: string }) => i.id),
    ["old1"],
  );
  assert.equal(await (await call("/s/tok")).text(), "from before");
  const links = await (await call("/api/links")).json();
  assert.deepEqual([links.links[0].scope, links.links[0].items, links.links[0].realm], ["read", ["old1"], "main"]);
  assert.equal(D1.db.prepare("SELECT v FROM meta WHERE k = 'schema'").get()!.v, MIGRATIONS.length);
});

// ---------- owner by Cloudflare login ----------

test("a Cloudflare login that can manage the worker is the owner", async () => {
  const seen: string[] = [];
  const real = globalThis.fetch;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const u = String(input);
    seen.push(u);
    const auth = new Headers(init?.headers).get("authorization");
    if (!u.startsWith("https://api.cloudflare.com/")) return real(input, init);
    if (auth === "Bearer cf-down") return new Response("oops", { status: 502 });
    const mine = u.endsWith("/accounts/acc1/workers/scripts/realm/settings") && auth === "Bearer cf-owner";
    return new Response(JSON.stringify({ success: mine }), { status: mine ? 200 : 403 });
  }) as typeof fetch;
  try {
    const env = { DB: mockD1(), BUCKET: mockR2(), REALM_ACCOUNT: "acc1" } as never;
    const call = (token: string) =>
      worker.fetch(new Request(O + "/api/realms", { headers: { authorization: `Bearer ${token}` } }), env);

    const asked = () => seen.filter((u) => u.includes("cloudflare")).length;
    assert.equal((await call("cf-owner")).status, 200);
    assert.equal((await call("cf-owner")).status, 200);
    assert.equal(asked(), 1); // remembered in this isolate
    owners.clear(); // as if a fresh isolate, or another location
    assert.equal((await call("cf-owner")).status, 200);
    assert.equal(asked(), 1); // remembered in the database too
    assert.equal((await call("cf-stranger")).status, 401);
    assert.equal((await call("cf-down")).status, 503);
    assert.equal((await call("")).status, 401);

    // without REALM_ACCOUNT, Cloudflare isn't asked; REALM_TOKEN still works when set
    const fixed = { DB: mockD1(), BUCKET: mockR2(), REALM_TOKEN: TOKEN } as never;
    const n = seen.length;
    assert.equal(
      (await worker.fetch(new Request(O + "/api/realms", { headers: { authorization: "Bearer cf-owner" } }), fixed))
        .status,
      401,
    );
    assert.equal(seen.length, n);
    assert.equal(
      (await worker.fetch(new Request(O + "/api/realms", { headers: { authorization: `Bearer ${TOKEN}` } }), fixed))
        .status,
      200,
    );
  } finally {
    globalThis.fetch = real;
  }
});
