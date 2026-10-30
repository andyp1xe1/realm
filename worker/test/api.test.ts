import { test } from "node:test";
import assert from "node:assert/strict";
import worker, { MIGRATIONS } from "../src/index.ts";
import { mockD1, mockR2 } from "./mock.ts";

const TOKEN = "secret-token";
const O = "https://realm.test";

function setup() {
  const env = { DB: mockD1(), BUCKET: mockR2(), REALM_TOKEN: TOKEN } as never;
  const call = (path: string, init: RequestInit & { auth?: boolean } = {}) => {
    const headers = new Headers(init.headers);
    if (init.auth !== false && path.startsWith("/api")) headers.set("authorization", `Bearer ${TOKEN}`);
    return worker.fetch(new Request(O + path, { ...init, headers, duplex: "half" } as RequestInit), env);
  };
  return { env, call };
}

test("auth required", async () => {
  const { call } = setup();
  assert.equal((await call("/api/items", { auth: false })).status, 401);
  assert.equal((await call("/s/nope/items")).status, 404);
});

test("notes: create, list, search, patch, delete", async () => {
  const { call } = setup();
  let r = await call("/api/items", { method: "POST", body: "buy oat milk and coffee" });
  assert.equal(r.status, 201);
  const a = await r.json();
  assert.equal(a.kind, "note");
  r = await call("/api/items", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ text: "https://example.com/x" }) });
  const b = await r.json();
  assert.equal(b.kind, "link");

  let l = await (await call("/api/items")).json();
  assert.deepEqual(l.items.map((i: { id: string }) => i.id), [b.id, a.id]);

  l = await (await call("/api/items?q=oat+cof")).json();
  assert.equal(l.items.length, 1);
  assert.equal(l.items[0].id, a.id);
  l = await (await call(`/api/items?q=${encodeURIComponent('weird "quote')}`)).json();
  assert.equal(l.items.length, 0);

  l = await (await call("/api/items?kind=link")).json();
  assert.equal(l.items.length, 1);

  l = await (await call(`/api/items?after=${a.id}`)).json();
  assert.deepEqual(l.items.map((i: { id: string }) => i.id), [b.id]);

  r = await call(`/api/items/${a.id}`, { method: "PATCH", headers: { "content-type": "application/json" }, body: JSON.stringify({ text: "buy tea" }) });
  assert.equal((await r.json()).text, "buy tea");
  l = await (await call("/api/items?q=tea")).json();
  assert.equal(l.items.length, 1);
  l = await (await call("/api/items?q=oat")).json();
  assert.equal(l.items.length, 0);

  assert.equal((await call(`/api/items/${a.id}`, { method: "DELETE" })).status, 200);
  assert.equal((await call(`/api/items/${a.id}`)).status, 404);
});

test("pagination", async () => {
  const { call } = setup();
  for (let i = 0; i < 5; i++) {
    await call("/api/items", { method: "POST", body: `n${i}` });
    await new Promise((r) => setTimeout(r, 2));
  }
  const p1 = await (await call("/api/items?limit=3")).json();
  assert.equal(p1.items.length, 3);
  assert.ok(p1.next);
  const p2 = await (await call(`/api/items?limit=3&before=${p1.next}`)).json();
  assert.equal(p2.items.length, 2);
  assert.equal(p2.next, null);
});

test("files: put, raw, range, form, delete", async () => {
  const { call, env } = setup();
  let r = await call("/api/files/hello%20world.txt?text=greeting", {
    method: "PUT", body: "hello file", headers: { "content-length": "10" },
  });
  assert.equal(r.status, 201);
  const f = await r.json();
  assert.equal(f.name, "hello world.txt");
  assert.equal(f.mime, "text/plain");
  assert.equal(f.size, 10);
  assert.equal(f.text, "greeting");
  assert.equal(f.key, undefined);

  r = await call(`/api/items/${f.id}/raw`);
  assert.equal(await r.text(), "hello file");
  assert.match(r.headers.get("content-disposition")!, /filename\*=UTF-8''hello%20world.txt/);

  r = await call(`/api/items/${f.id}/raw`, { headers: { range: "bytes=6-" } });
  assert.equal(r.status, 206);
  assert.equal(await r.text(), "file");
  assert.equal(r.headers.get("content-range"), "bytes 6-9/10");
  r = await call(`/api/items/${f.id}/raw`, { headers: { range: "bytes=-4" } });
  assert.equal(r.headers.get("content-range"), "bytes 6-9/10");

  const form = new FormData();
  form.append("file", new Blob([new Uint8Array([1, 2, 3])], { type: "image/png" }), "a.png");
  form.append("file", new Blob(["x"]), "b.bin");
  form.append("text", "two files");
  r = await call("/api/items", { method: "POST", body: form });
  const multi = await r.json();
  assert.equal(multi.items.length, 2);
  assert.equal(multi.items[0].kind, "image");
  assert.equal(multi.items[0].text, "two files");

  assert.equal((await (await call("/api/items?kind=image")).json()).items.length, 1);
  assert.equal((await (await call("/api/items?q=hello")).json()).items.length, 1);

  await call(`/api/items/${f.id}`, { method: "DELETE" });
  assert.equal((env as any).BUCKET.objects.size, 2);
});

test("multipart upload", async () => {
  const { call } = setup();
  let r = await call("/api/uploads", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ name: "big.mkv", text: "movie" }) });
  const { upload } = await r.json();
  const parts = [];
  for (const [i, chunk] of ["aaaa", "bbbb", "cc"].entries()) {
    r = await call(`/api/uploads/${upload}/${i + 1}`, { method: "PUT", body: chunk, headers: { "content-length": String(chunk.length) } });
    parts.push(await r.json());
  }
  r = await call(`/api/uploads/${upload}/complete`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ parts: parts.reverse() }) });
  assert.equal(r.status, 201);
  const item = await r.json();
  assert.equal(item.kind, "video");
  assert.equal(item.size, 10);
  assert.equal(await (await call(`/api/items/${item.id}/raw`)).text(), "aaaabbbbcc");
});

async function mkLink(call: ReturnType<typeof setup>["call"], body: object) {
  const r = await call("/api/links", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
  assert.equal(r.status, 201);
  return r.json();
}

test("links: scopes, uses, expiry, help", async () => {
  const { call } = setup();
  const note = await (await call("/api/items", { method: "POST", body: "secret plan" })).json();
  const file = await (await call("/api/files/a.txt", { method: "PUT", body: "AAA", headers: { "content-length": "3" } })).json();

  // a link to one item: the link itself is the download, limited uses
  const il = await mkLink(call, { item: file.id, uses: 2 });
  assert.equal(il.scope, "read");
  assert.deepEqual(il.items, [file.id]);
  assert.equal(il.url, `${O}/s/${il.token}`);
  const p = new URL(il.url).pathname;
  assert.equal(await (await call(p)).text(), "AAA");
  assert.equal(await (await call(p + "/raw")).text(), "AAA");
  assert.equal((await call(p)).status, 410);

  const il2 = await mkLink(call, { item: file.id });
  const p2 = new URL(il2.url).pathname;
  const only = await (await call(`${p2}/items`)).json();
  assert.deepEqual(only.items.map((i: { id: string }) => i.id), [file.id]);
  assert.equal((await call(`${p2}/items/${note.id}/raw`)).status, 404);
  assert.equal((await call(`${p2}/items/${file.id}`)).status, 200);

  // drop link: can add, can't read
  const dl = await mkLink(call, { scope: "drop", label: "bob" });
  const d = new URL(dl.url).pathname;
  assert.equal((await call(`${d}/items`)).status, 403);
  let r = await call(d, { method: "POST", body: "from bob" });
  assert.equal(r.status, 201);
  assert.equal((await r.json()).src, "link:bob");
  r = await call(`${d}/files/x.bin`, { method: "PUT", body: "z", headers: { "content-length": "1" } });
  assert.equal(r.status, 201);
  assert.equal((await call(`${d}/items/${note.id}`, { method: "DELETE" })).status, 403);
  const help = await (await call(d)).text();
  assert.match(help, /## Add things/);
  assert.doesNotMatch(help, /## Find things/);
  const html = await call(d, { headers: { accept: "text/html" } });
  assert.match(await html.text(), /<textarea/);

  // read link
  const rl = await mkLink(call, { scope: "read", ttl: 3600 });
  const rp = new URL(rl.url).pathname;
  const l = await (await call(`${rp}/items?q=secret`)).json();
  assert.equal(l.items[0].id, note.id);
  assert.equal((await call(rp, { method: "POST", body: "nope" })).status, 403);
  assert.equal((await call(`${rp}/links`)).status, 403);
  assert.match(await (await call(rp, { headers: { accept: "text/html" } })).text(), /secret plan/);

  // full link: can delete, can't manage links
  const fl = await mkLink(call, { scope: "full" });
  const fp = new URL(fl.url).pathname;
  assert.equal((await call(`${fp}/items/${note.id}`, { method: "DELETE" })).status, 200);
  assert.equal((await call(`${fp}/links`)).status, 403);

  // expiry + revoke
  const xl = await mkLink(call, { scope: "read", ttl: -1 });
  assert.equal(xl.expires, null);
  const links = await (await call("/api/links")).json();
  assert.equal(links.links.length, 6);
  await call(`/api/links/${rl.token}`, { method: "DELETE" });
  assert.equal((await call(`${rp}/items`)).status, 404);

  // deleting an item kills its links
  await call(`/api/items/${file.id}`, { method: "DELETE" });
  assert.equal((await call(p2)).status, 404);
});

test("multipart via drop link is owner-bound", async () => {
  const { call } = setup();
  const a = new URL((await mkLink(call, { scope: "drop" })).url).pathname;
  const b = new URL((await mkLink(call, { scope: "drop" })).url).pathname;
  const { upload } = await (await call(`${a}/uploads`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ name: "x" }) })).json();
  assert.equal((await call(`${b}/uploads/${upload}/1`, { method: "PUT", body: "x", headers: { "content-length": "1" } })).status, 404);
  assert.equal((await call(`${a}/uploads/${upload}/1`, { method: "PUT", body: "x", headers: { "content-length": "1" } })).status, 200);
});

// ---------- review fixes ----------

const put = (call: ReturnType<typeof setup>["call"], path: string, body: string, type?: string) =>
  call(path, { method: "PUT", body, headers: { "content-length": String(body.length), ...(type ? { "content-type": type } : {}) } });

test("item links are read-only", async () => {
  const { call } = setup();
  const f = await (await put(call, "/api/files/a.txt", "AAA")).json();
  const p = new URL((await mkLink(call, { item: f.id })).url).pathname;
  assert.equal((await call(`${p}/items/${f.id}`, { method: "DELETE" })).status, 403);
  assert.equal((await call(`${p}/items/${f.id}`, { method: "PATCH", headers: { "content-type": "application/json" }, body: '{"text":"x"}' })).status, 403);
  assert.equal(await (await call(p)).text(), "AAA");
});

test("use-limited links ignore Range, so every fetch costs a use", async () => {
  const { call } = setup();
  const f = await (await put(call, "/api/files/a.txt", "0123456789")).json();
  const p = new URL((await mkLink(call, { item: f.id, uses: 1 })).url).pathname;
  const r = await call(p, { headers: { range: "bytes=-999999" } });
  assert.equal(r.status, 200);
  assert.equal(await r.text(), "0123456789");
  assert.equal((await call(p, { headers: { range: "bytes=1-" } })).status, 410);
});

test("bad ranges and bad paths are client errors", async () => {
  const { call } = setup();
  const f = await (await put(call, "/api/files/a.txt", "0123456789")).json();
  assert.equal((await call(`/api/items/${f.id}/raw`, { headers: { range: "bytes=5-2" } })).status, 416);
  assert.equal((await call(`/api/items/${f.id}/raw`, { headers: { range: "bytes=50-" } })).status, 416);
  assert.equal((await call("/api/items/%E0%A4%A")).status, 400);
  const many = Array.from({ length: 300 }, (_, i) => `k${i}`).concat("note").join(",");
  assert.equal((await call(`/api/items?kind=${many}`)).status, 200);
});

test("scriptable types download with nosniff and a sandbox CSP", async () => {
  const { call } = setup();
  for (const [name, type, inline] of [
    ["x.html", "text/html", false], ["x.svg", "image/svg+xml", false], ["x.xml", "text/xml", false],
    ["x.png", "image/png", true], ["x.txt", "text/plain", true],
  ] as const) {
    const f = await (await put(call, `/api/files/${name}`, "<b>hi</b>", type)).json();
    const r = await call(`/api/items/${f.id}/raw`);
    assert.equal(r.headers.get("content-disposition")!.startsWith("inline"), inline, name);
    assert.equal(r.headers.get("x-content-type-options"), "nosniff");
    assert.match(r.headers.get("content-security-policy")!, /sandbox/);
  }
});

test("etag revalidation returns 304", async () => {
  const { call } = setup();
  const f = await (await put(call, "/api/files/a.txt", "AAA")).json();
  const r = await call(`/api/items/${f.id}/raw`);
  const etag = r.headers.get("etag")!;
  assert.equal((await call(`/api/items/${f.id}/raw`, { headers: { "if-none-match": etag } })).status, 304);
});

test("uploads need a length; drop links have use and byte budgets", async () => {
  const { call } = setup();
  const chunked = new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode("x")); c.close(); } });
  assert.equal((await call("/api/files/a.bin", { method: "PUT", body: chunked })).status, 411);

  // starting a multipart upload spends the use
  const one = new URL((await mkLink(call, { scope: "drop", uses: 1 })).url).pathname;
  const start = () => call(`${one}/uploads`, { method: "POST", headers: { "content-type": "application/json" }, body: '{"name":"x"}' });
  assert.equal((await start()).status, 201);
  assert.equal((await start()).status, 410);

  const b = new URL((await mkLink(call, { scope: "drop", max_bytes: 10 })).url).pathname;
  assert.equal((await put(call, `${b}/files/a.bin`, "123456")).status, 201);
  assert.equal((await put(call, `${b}/files/b.bin`, "123456")).status, 413);
  assert.equal((await put(call, `${b}/files/c.bin`, "1234")).status, 201);
});

test("finished uploads get an id newer than items created meanwhile", async () => {
  const { call } = setup();
  const { upload } = await (await call("/api/uploads", { method: "POST", headers: { "content-type": "application/json" }, body: '{"name":"big"}' })).json();
  await new Promise((r) => setTimeout(r, 5));
  const note = await (await call("/api/items", { method: "POST", body: "meanwhile" })).json();
  const part = await (await put(call, `/api/uploads/${upload}/1`, "data")).json();
  await new Promise((r) => setTimeout(r, 5));
  const item = await (await call(`/api/uploads/${upload}/complete`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ parts: [part] }) })).json();
  assert.ok(item.id > note.id);
  const after = await (await call(`/api/items?after=${note.id}`)).json();
  assert.deepEqual(after.items.map((i: { id: string }) => i.id), [item.id]);
});

test("cleanup aborts stale uploads and drops dead links", async () => {
  const { call, env } = setup();
  await call("/api/uploads", { method: "POST", headers: { "content-type": "application/json" }, body: '{"name":"x"}' });
  await mkLink(call, { scope: "read", uses: 1 });
  const live = await mkLink(call, { scope: "read" });
  const e = env as any;
  await e.DB.prepare("UPDATE uploads SET created = 0").run();
  await e.DB.prepare("UPDATE links SET uses = 1 WHERE max_uses = 1").run();
  await worker.scheduled({}, env);
  assert.equal((await e.DB.prepare("SELECT count(*) n FROM uploads").first()).n, 0);
  assert.equal(e.BUCKET.uploads.size, 0);
  const links = await (await call("/api/links")).json();
  assert.deepEqual(links.links.map((l: { token: string }) => l.token), [live.token]);
});

// ---------- realms ----------

const tick = () => new Promise((r) => setTimeout(r, 2)); // ids sort by millisecond

const post = (call: ReturnType<typeof setup>["call"], path: string, body: object, method = "POST") =>
  call(path, { method, headers: { "content-type": "application/json" }, body: JSON.stringify(body) });

test("realms: create, list, isolate, rename, delete", async () => {
  const { call, env } = setup();
  let l = await (await call("/api/realms")).json();
  assert.deepEqual(l.realms.map((r: { id: string; name: string }) => [r.id, r.name]), [["main", "Main"]]);
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
  assert.deepEqual((await (await call(`${T}/items?q=packing`)).json()).items.map((i: { id: string }) => i.id), [n.id]);
  assert.equal((await (await call("/api/items?q=packing")).json()).items.length, 0);

  l = await (await call("/api/realms")).json();
  assert.deepEqual(l.realms.map((r: { count: number }) => r.count), [1, 2]);
  assert.equal((await (await post(call, T, { name: "Holiday" }, "PATCH")).json()).name, "Holiday");

  // links are per realm; /api/links shows them all
  const rl = await (await post(call, `${T}/links`, { scope: "read" })).json();
  assert.equal(rl.realm, trip.id);
  const p = new URL(rl.url).pathname;
  assert.deepEqual((await (await call(`${p}/items`)).json()).items.map((i: { id: string }) => i.id), [f.id, n.id]);
  assert.match(await (await call(p, { headers: { accept: "text/html" } })).text(), /<h1>Holiday<\/h1>/);
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

  const d = await (await call(`${new URL((await mkLink(call, { scope: "drop", max_bytes: 5 })).url).pathname}/info`)).json();
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
  const call = (path: string) => worker.fetch(new Request(O + path, { headers: { authorization: `Bearer ${TOKEN}` } }), env);

  const l = await (await call("/api/realms/main/items?q=before")).json();
  assert.deepEqual(l.items.map((i: { id: string }) => i.id), ["old1"]);
  assert.equal(await (await call("/s/tok")).text(), "from before");
  const links = await (await call("/api/links")).json();
  assert.deepEqual([links.links[0].scope, links.links[0].items, links.links[0].realm], ["read", ["old1"], "main"]);
  assert.equal(D1.db.prepare("SELECT v FROM meta WHERE k = 'schema'").get()!.v, MIGRATIONS.length);
});
