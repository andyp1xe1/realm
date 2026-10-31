// realm — a personal dump. One Worker, one R2 bucket, one D1 database, any number of realms.
//
//   /api/realms/<id>/...  owner access to one realm, `Authorization: Bearer <owner>`, where <owner> is a
//                         Cloudflare login that can manage this worker, or the optional $REALM_TOKEN
//   /api/...              the same routes on the first realm, plus /api/realms and /api/links
//   /s/<token>/...        the same routes on the link's realm, limited by the link's scope
//
// Scopes: drop (upload only) · read (list, search, download) · full (read + upload + edit/delete)
//         owner (everything, incl. realms and links). A read link can be a selection: it then
//         sees only the items picked for it, and a selection of one downloads at the bare link.

export interface Env {
  DB: D1Database;
  BUCKET: R2Bucket;
  /** Optional fixed owner token, for scripts and agents that can't log in with Cloudflare. */
  REALM_TOKEN?: string;
  /** The Cloudflare account this worker runs on. Set by `realm login`; enables owner access by Cloudflare login. */
  REALM_ACCOUNT?: string;
  /** This worker's script name on Cloudflare, if not "realm". */
  REALM_WORKER?: string;
}

/** Bump with every release, so clients can tell a deployment needs updating. */
const VERSION = 3;

type Scope = "owner" | "full" | "read" | "drop";

interface Realm {
  id: string;
  name: string;
  created: number;
}

interface Link {
  token: string;
  scope: Scope;
  realm: string;
  selection: number; // 1 = sees only its link_items
  label: string | null;
  expires: number | null;
  max_uses: number | null;
  uses: number;
  max_bytes: number | null;
  bytes: number;
  created: number;
}

interface Item {
  id: string;
  kind: string;
  text: string | null;
  name: string | null;
  mime: string | null;
  size: number | null;
  key?: string | null;
  realm: string;
  src: string | null;
  created: number;
}

interface Ctx {
  req: Request;
  env: Env;
  url: URL;
  scope: Scope;
  link: Link | null;
  realm: Realm | null; // null on owner routes above any one realm
  base: string; // absolute URL prefix for this principal, e.g. https://x/s/abc
  src: string;
}

class HttpError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

const SCOPES: Record<string, Scope[]> = {
  read: ["owner", "full", "read"],
  write: ["owner", "full", "drop"],
  modify: ["owner", "full"],
  owner: ["owner"],
};

const MAX_LIMIT = 200;
const KINDS = ["note", "link", "image", "audio", "video", "file"];

// Each step runs once, in order, as one transaction. Append new steps; never edit old ones.
export const MIGRATIONS: string[][] = [
  [
    `CREATE TABLE IF NOT EXISTS items (
      id      TEXT PRIMARY KEY,          -- time-sortable, newest = largest
      kind    TEXT NOT NULL,             -- note | link | image | audio | video | file
      text    TEXT,                      -- note body or file caption
      name    TEXT,                      -- file name
      mime    TEXT,
      size    INTEGER,
      key     TEXT,                      -- R2 object key (files only)
      src     TEXT,                      -- who dumped it: android, desktop, cli, link:<label>
      created INTEGER NOT NULL           -- epoch ms
    )`,
    `CREATE INDEX IF NOT EXISTS items_kind ON items(kind, id)`,
    `CREATE VIRTUAL TABLE IF NOT EXISTS items_fts USING fts5(text, name, content='items', content_rowid='rowid')`,
    `CREATE TRIGGER IF NOT EXISTS items_ai AFTER INSERT ON items BEGIN
      INSERT INTO items_fts(rowid, text, name) VALUES (new.rowid, new.text, new.name);
    END`,
    `CREATE TRIGGER IF NOT EXISTS items_ad AFTER DELETE ON items BEGIN
      INSERT INTO items_fts(items_fts, rowid, text, name) VALUES ('delete', old.rowid, old.text, old.name);
    END`,
    `CREATE TRIGGER IF NOT EXISTS items_au AFTER UPDATE ON items BEGIN
      INSERT INTO items_fts(items_fts, rowid, text, name) VALUES ('delete', old.rowid, old.text, old.name);
      INSERT INTO items_fts(rowid, text, name) VALUES (new.rowid, new.text, new.name);
    END`,
    `CREATE TABLE IF NOT EXISTS links (
      token    TEXT PRIMARY KEY,
      scope    TEXT NOT NULL,            -- item | drop | read | full
      item     TEXT,                     -- for scope=item
      label    TEXT,
      expires  INTEGER,                  -- epoch ms, NULL = never
      max_uses INTEGER,                  -- NULL = unlimited
      uses     INTEGER NOT NULL DEFAULT 0,
      max_bytes INTEGER,                 -- upload budget for drop/full links, NULL = unlimited
      bytes    INTEGER NOT NULL DEFAULT 0,
      created  INTEGER NOT NULL
    )`,
    `CREATE TABLE IF NOT EXISTS uploads (
      id      TEXT PRIMARY KEY,
      key     TEXT NOT NULL,
      r2id    TEXT NOT NULL,
      name    TEXT NOT NULL,
      mime    TEXT NOT NULL,
      text    TEXT,
      src     TEXT,
      owner   TEXT,                      -- link token that started it, NULL = owner
      created INTEGER NOT NULL
    )`,
  ],
  // Realms. Everything so far lands in "Main", and item links become read links with a selection of one.
  [
    `CREATE TABLE realms (id TEXT PRIMARY KEY, name TEXT NOT NULL, created INTEGER NOT NULL)`,
    `INSERT INTO realms (id, name, created) VALUES ('main', 'Main', CAST(strftime('%s', 'now') AS INTEGER) * 1000)`,
    `ALTER TABLE items ADD COLUMN realm TEXT NOT NULL DEFAULT 'main'`,
    `DROP INDEX items_kind`,
    `CREATE INDEX items_realm ON items(realm, id)`,
    `CREATE INDEX items_realm_kind ON items(realm, kind, id)`,
    `ALTER TABLE uploads ADD COLUMN realm TEXT NOT NULL DEFAULT 'main'`,
    `ALTER TABLE links ADD COLUMN realm TEXT NOT NULL DEFAULT 'main'`,
    `ALTER TABLE links ADD COLUMN selection INTEGER NOT NULL DEFAULT 0`,
    `CREATE TABLE link_items (token TEXT NOT NULL, item TEXT NOT NULL, PRIMARY KEY (token, item))`,
    `CREATE INDEX link_items_item ON link_items(item)`,
    `INSERT INTO link_items (token, item) SELECT token, item FROM links WHERE scope = 'item'`,
    `UPDATE links SET scope = 'read', selection = 1 WHERE scope = 'item'`,
    `ALTER TABLE links DROP COLUMN item`,
  ],
  // Answers about Cloudflare logins, by token hash, shared by every isolate and location.
  [
    `CREATE TABLE owners (key TEXT PRIMARY KEY, ok INTEGER NOT NULL, until INTEGER NOT NULL)`,
  ],
];

const migrated = new WeakMap<D1Database, Promise<void>>();

/** Brings the database up to date once per isolate, so deploying new code is the whole upgrade. */
function ready(env: Env): Promise<void> {
  let p = migrated.get(env.DB);
  if (!p) {
    p = migrate(env.DB).catch((e) => {
      migrated.delete(env.DB);
      throw e;
    });
    migrated.set(env.DB, p);
  }
  return p;
}

async function migrate(db: D1Database) {
  await db.prepare("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v INTEGER NOT NULL)").run();
  const version = async () => (await db.prepare("SELECT v FROM meta WHERE k = 'schema'").first<{ v: number }>())?.v ?? 0;
  for (let v = await version(); v < MIGRATIONS.length; v = await version()) {
    try {
      await db.batch([
        ...MIGRATIONS[v].map((sql) => db.prepare(sql)),
        db.prepare("INSERT OR REPLACE INTO meta (k, v) VALUES ('schema', ?)").bind(v + 1),
      ]);
    } catch (e) {
      if ((await version()) <= v) throw e; // otherwise another isolate got there first
    }
  }
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    if (req.method === "OPTIONS") return cors(new Response(null, { status: 204 }));
    try {
      await ready(env);
      return cors(await handle(req, env));
    } catch (e) {
      if (e instanceof HttpError) return cors(json({ error: e.message }, e.status));
      console.error(e);
      return cors(json({ error: "internal error" }, 500));
    }
  },

  /** Daily: abort multipart uploads nobody finished, forget dead links. */
  async scheduled(_event: unknown, env: Env): Promise<void> {
    await ready(env);
    const cutoff = Date.now() - 24 * 3600 * 1000;
    const { results } = await env.DB.prepare("SELECT * FROM uploads WHERE created < ?").bind(cutoff).all<Upload>();
    for (const up of results) {
      await env.BUCKET.resumeMultipartUpload(up.key, up.r2id).abort().catch(() => {});
      await env.DB.prepare("DELETE FROM uploads WHERE id = ?").bind(up.id).run();
    }
    await env.DB.batch([
      env.DB.prepare(
        "DELETE FROM links WHERE (expires IS NOT NULL AND expires < ?) OR (max_uses IS NOT NULL AND uses >= max_uses)",
      ).bind(Date.now()),
      env.DB.prepare("DELETE FROM link_items WHERE token NOT IN (SELECT token FROM links)"),
      env.DB.prepare("DELETE FROM owners WHERE until < ?").bind(Date.now()),
    ]);
  },
};

async function handle(req: Request, env: Env): Promise<Response> {
  const url = new URL(req.url);
  let parts: string[];
  try {
    parts = url.pathname.split("/").filter(Boolean).map(decodeURIComponent);
  } catch {
    throw new HttpError(400, "bad path encoding");
  }
  const src = (req.headers.get("x-realm-source") || "").slice(0, 40);

  if (parts[0] === "api") {
    const auth = req.headers.get("authorization") || "";
    const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
    const fixed = !!env.REALM_TOKEN && safeEqual(token, env.REALM_TOKEN);
    if (!fixed && !(await cloudflareOwner(env, token))) throw new HttpError(401, "bad token");
    const ctx: Ctx = { req, env, url, scope: "owner", link: null, realm: null, base: `${url.origin}/api`, src: src || "api" };
    return owner(ctx, parts.slice(1));
  }

  if (parts[0] === "s" && parts[1]) {
    const row = await env.DB.prepare(
      "SELECT links.*, realms.name AS realm_name, realms.created AS realm_created FROM links JOIN realms ON realms.id = links.realm WHERE token = ?",
    ).bind(parts[1]).first<Link & { realm_name: string; realm_created: number }>();
    if (!row || (row.expires && row.expires < Date.now())) throw new HttpError(404, "link not found or expired");
    if (row.max_uses != null && row.uses >= row.max_uses) throw new HttpError(410, "link used up");
    const { realm_name, realm_created, ...link } = row;
    const ctx: Ctx = {
      req, env, url, scope: link.scope, link,
      realm: { id: link.realm, name: realm_name, created: realm_created },
      base: `${url.origin}/s/${link.token}`,
      src: link.label ? `link:${link.label}` : "link",
    };
    return route(ctx, parts.slice(2));
  }

  if (parts.length === 0) return text("realm\n");
  throw new HttpError(404, "not found");
}

// Answers about Cloudflare logins, by token hash: a yes holds 5 minutes, a no 30 seconds. This isolate's
// copy is a free first look; the owners table is shared by every isolate and location, and survives
// evictions and redeploys. Only on a miss in both is Cloudflare asked. Exported for tests.
export const owners = new Map<string, { ok: boolean; until: number }>();

/**
 * Whether [token] is a Cloudflare login (OAuth or API token) that can manage this worker: the owner is
 * whoever can see its settings on its own account. Cloudflare being unreachable is a 503, not a "no".
 */
async function cloudflareOwner(env: Env, token: string): Promise<boolean> {
  if (!env.REALM_ACCOUNT || !token) return false;
  const key = await sha256(token);
  const now = Date.now();
  const known = owners.get(key);
  if (known && known.until > now) return known.ok;
  const shared = await env.DB.prepare("SELECT ok, until FROM owners WHERE key = ?").bind(key).first<{ ok: number; until: number }>();
  if (shared && shared.until > now) {
    owners.set(key, { ok: !!shared.ok, until: shared.until });
    return !!shared.ok;
  }

  let res: Response;
  try {
    res = await fetch(
      `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(env.REALM_ACCOUNT)}/workers/scripts/${encodeURIComponent(env.REALM_WORKER || "realm")}/settings`,
      { headers: { authorization: `Bearer ${token}` } },
    );
  } catch {
    throw new HttpError(503, "can't check your Cloudflare login right now");
  }
  if (res.status >= 500 || res.status === 429) throw new HttpError(503, "can't check your Cloudflare login right now");
  await res.body?.cancel();
  const ok = res.ok;
  const until = Date.now() + (ok ? 5 * 60_000 : 30_000);
  if (owners.size > 500) owners.clear();
  owners.set(key, { ok, until });
  await env.DB.prepare("INSERT OR REPLACE INTO owners (key, ok, until) VALUES (?, ?, ?)").bind(key, ok ? 1 : 0, until).run();
  return ok;
}

async function sha256(s: string): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return btoa(String.fromCharCode(...new Uint8Array(d)));
}

/** Owner routes that sit above any one realm; the rest go to a realm. */
async function owner(ctx: Ctx, p: string[]): Promise<Response> {
  const m = ctx.req.method;
  const [a, b, ...rest] = p;

  if (a === "realms") {
    if (!b) {
      if (m === "GET") return json({ realms: await realmsWithStats(ctx.env) });
      if (m === "POST") return createRealm(ctx);
      throw new HttpError(404, "not found");
    }
    const realm = await ctx.env.DB.prepare("SELECT * FROM realms WHERE id = ?").bind(b).first<Realm>();
    if (!realm) throw new HttpError(404, "no such realm");
    if (!rest.length) {
      if (m === "GET") return json((await realmsWithStats(ctx.env, realm.id))[0]);
      if (m === "PATCH") return renameRealm(ctx, realm);
      if (m === "DELETE") return deleteRealm(ctx, realm);
      throw new HttpError(404, "not found");
    }
    return route({ ...ctx, realm, base: `${ctx.base}/realms/${encodeURIComponent(realm.id)}` }, rest);
  }

  if (a === "links" && !(m === "POST" && !b)) return links(ctx, b);
  if (a === "info" || (!a && (m === "GET" || m === "HEAD"))) return route(ctx, p);

  // Everything else works on the first realm, so clients that predate realms keep working.
  const first = await ctx.env.DB.prepare("SELECT * FROM realms ORDER BY created, id LIMIT 1").first<Realm>();
  if (!first) throw new HttpError(404, "no realms yet; create one with POST /api/realms");
  return route({ ...ctx, realm: first }, p);
}

async function route(ctx: Ctx, p: string[]): Promise<Response> {
  const m = ctx.req.method;
  const [a, b, c] = p;

  if (!a) {
    if (m !== "GET" && m !== "HEAD") return createFromBody(ctx); // POST to the bare link = dump something
    const only = await soleItem(ctx);
    if (only) return raw(ctx, only);
    return wantsHtml(ctx.req) ? htmlPage(ctx) : text(agentHelp(ctx), 200, "text/markdown; charset=utf-8");
  }

  if (a === "info" && !b && m === "GET") return info(ctx);

  if (a === "raw" && !b) {
    const only = await soleItem(ctx);
    if (only) return raw(ctx, only);
  }

  if (a === "items") {
    if (!b) {
      if (m === "GET") return list(ctx);
      if (m === "POST") return createFromBody(ctx);
    } else if (!c) {
      if (m === "GET") return json(publicItem(await getItem(ctx, b, "read")));
      if (m === "PATCH") return patchItem(ctx, b);
      if (m === "DELETE") return deleteItem(ctx, b);
    } else if (c === "raw" && (m === "GET" || m === "HEAD")) {
      return raw(ctx, b);
    }
  }

  if (a === "files" && b && m === "PUT") {
    need(ctx, "write");
    const item = await putFile(ctx, b, ctx.req.headers.get("content-type"), ctx.req, ctx.url.searchParams.get("text"));
    return json(publicItem(item), 201);
  }

  if (a === "uploads") {
    need(ctx, "write");
    if (!b && m === "POST") return startUpload(ctx);
    if (b && c === "complete" && m === "POST") return completeUpload(ctx, b);
    if (b && c && m === "PUT") return uploadPart(ctx, b, c);
    if (b && !c && m === "DELETE") return abortUpload(ctx, b);
  }

  if (a === "links" && !c) return links(ctx, b);

  throw new HttpError(404, "not found");
}

/** What a client needs to know before connecting. Free: it never spends a use. */
async function info(ctx: Ctx): Promise<Response> {
  const l = ctx.link;
  const out: Record<string, unknown> = { version: VERSION, scope: ctx.scope, realm: ctx.realm };
  if (l) {
    Object.assign(out, {
      label: l.label,
      expires: l.expires,
      uses_left: l.max_uses != null ? l.max_uses - l.uses : null,
      bytes_left: l.max_bytes != null ? l.max_bytes - l.bytes : null,
    });
    if (l.selection) {
      const { results } = await ctx.env.DB.prepare(
        "SELECT items.* FROM link_items JOIN items ON items.id = link_items.item WHERE link_items.token = ? ORDER BY items.id DESC LIMIT 2",
      ).bind(l.token).all<Item>();
      const n = await ctx.env.DB.prepare("SELECT COUNT(*) AS n FROM link_items WHERE token = ?").bind(l.token).first<{ n: number }>();
      out.items = n?.n ?? 0;
      if (results.length === 1) out.item = publicItem(results[0]);
    }
  }
  return json(out);
}

/** The item a selection of exactly one points at; its link downloads it directly. */
async function soleItem(ctx: Ctx): Promise<string | null> {
  if (!ctx.link?.selection) return null;
  const { results } = await ctx.env.DB.prepare("SELECT item FROM link_items WHERE token = ? LIMIT 2")
    .bind(ctx.link.token).all<{ item: string }>();
  return results.length === 1 ? results[0].item : null;
}

// ---------- realms ----------

async function realmsWithStats(env: Env, id?: string) {
  const { results } = await env.DB.prepare(
    `SELECT realms.*,
       (SELECT COUNT(*) FROM items WHERE items.realm = realms.id) AS count,
       (SELECT MAX(created) FROM items WHERE items.realm = realms.id) AS updated
     FROM realms ${id ? "WHERE id = ?" : ""} ORDER BY created, id`,
  ).bind(...(id ? [id] : [])).all<Realm & { count: number; updated: number | null }>();
  return results;
}

async function realmName(ctx: Ctx): Promise<string> {
  const j = (await ctx.req.json().catch(() => ({}))) as { name?: string };
  const name = (j.name || "").trim().slice(0, 60);
  if (!name) throw new HttpError(400, "name required");
  return name;
}

async function createRealm(ctx: Ctx): Promise<Response> {
  const realm: Realm = { id: newId(), name: await realmName(ctx), created: Date.now() };
  await ctx.env.DB.prepare("INSERT INTO realms (id, name, created) VALUES (?, ?, ?)").bind(realm.id, realm.name, realm.created).run();
  return json({ ...realm, count: 0, updated: null }, 201);
}

async function renameRealm(ctx: Ctx, realm: Realm): Promise<Response> {
  const name = await realmName(ctx);
  await ctx.env.DB.prepare("UPDATE realms SET name = ? WHERE id = ?").bind(name, realm.id).run();
  return json((await realmsWithStats(ctx.env, realm.id))[0]);
}

/** Deletes the realm with everything in it, its links included. */
async function deleteRealm(ctx: Ctx, realm: Realm): Promise<Response> {
  const db = ctx.env.DB;
  const { results: files } = await db.prepare("SELECT key FROM items WHERE realm = ? AND key IS NOT NULL").bind(realm.id).all<{ key: string }>();
  const { results: ups } = await db.prepare("SELECT * FROM uploads WHERE realm = ?").bind(realm.id).all<Upload>();
  await db.batch([
    db.prepare("DELETE FROM link_items WHERE token IN (SELECT token FROM links WHERE realm = ?)").bind(realm.id),
    db.prepare("DELETE FROM links WHERE realm = ?").bind(realm.id),
    db.prepare("DELETE FROM uploads WHERE realm = ?").bind(realm.id),
    db.prepare("DELETE FROM items WHERE realm = ?").bind(realm.id),
    db.prepare("DELETE FROM realms WHERE id = ?").bind(realm.id),
  ]);
  for (const up of ups) await ctx.env.BUCKET.resumeMultipartUpload(up.key, up.r2id).abort().catch(() => {});
  for (let i = 0; i < files.length; i += 1000) await ctx.env.BUCKET.delete(files.slice(i, i + 1000).map((f) => f.key));
  return json({ ok: true });
}

// ---------- items ----------

async function list(ctx: Ctx): Promise<Response> {
  need(ctx, "read");
  const q = ctx.url.searchParams;
  const limit = Math.min(Math.max(parseInt(q.get("limit") || "50") || 50, 1), MAX_LIMIT);
  const where = ["items.realm = ?"];
  const args: unknown[] = [ctx.realm!.id];
  if (ctx.link?.selection) {
    where.push("items.id IN (SELECT item FROM link_items WHERE token = ?)");
    args.push(ctx.link.token);
  }

  const kinds = [...new Set((q.get("kind") || "").split(",").map((k) => k.trim()))].filter((k) => KINDS.includes(k));
  if (kinds.length) {
    where.push(`items.kind IN (${kinds.map(() => "?").join(",")})`);
    args.push(...kinds);
  }
  const before = q.get("before");
  if (before) {
    where.push("items.id < ?");
    args.push(before);
  }
  const after = q.get("after");
  if (after) {
    where.push("items.id > ?");
    args.push(after);
  }

  let from = "items";
  const search = (q.get("q") || "").trim();
  if (search) {
    const fts = ftsQuery(search);
    if (fts) {
      from = "items JOIN items_fts ON items_fts.rowid = items.rowid";
      where.unshift("items_fts MATCH ?");
      args.unshift(fts);
    }
  }

  // `after` pages forward (oldest first) so clients can poll for new items.
  const order = after && !before ? "ASC" : "DESC";
  const sql = `SELECT items.* FROM ${from} WHERE ${where.join(" AND ")} ORDER BY items.id ${order} LIMIT ?`;
  const { results } = await ctx.env.DB.prepare(sql).bind(...args, limit + 1).all<Item>();
  const more = results.length > limit;
  const items = results.slice(0, limit);
  return json({
    items: items.map(publicItem),
    next: more && order === "DESC" ? items[items.length - 1].id : null,
  });
}

function ftsQuery(s: string): string {
  return s
    .split(/\s+/)
    .map((t) => t.replace(/"/g, ""))
    .filter(Boolean)
    .map((t) => `"${t}"*`)
    .join(" ");
}

async function createFromBody(ctx: Ctx): Promise<Response> {
  need(ctx, "write");
  const ct = (ctx.req.headers.get("content-type") || "").toLowerCase();

  if (ct.startsWith("multipart/form-data")) {
    const form = await ctx.req.formData();
    const caption = (form.get("text") as string | null) || null;
    const created: Item[] = [];
    for (const [k, v] of form.entries()) {
      if (typeof v === "string") continue;
      const f = v as File;
      created.push(await putFile(ctx, f.name || k, f.type, f, created.length ? null : caption));
    }
    if (!created.length) {
      if (!caption?.trim()) throw new HttpError(400, "nothing to save");
      created.push(await insertNote(ctx, caption));
    }
    return json(created.length === 1 ? publicItem(created[0]) : { items: created.map(publicItem) }, 201);
  }

  let body: string;
  if (ct.includes("json")) {
    const j = (await ctx.req.json().catch(() => null)) as { text?: string } | null;
    body = j?.text ?? "";
  } else {
    body = await ctx.req.text();
  }
  if (!body.trim()) throw new HttpError(400, "empty note");
  return json(publicItem(await insertNote(ctx, body)), 201);
}

async function insertNote(ctx: Ctx, body: string): Promise<Item> {
  await consume(ctx);
  const item: Item = {
    id: newId(),
    kind: /^https?:\/\/\S+$/.test(body.trim()) ? "link" : "note",
    text: body,
    name: null, mime: null, size: null, key: null,
    realm: ctx.realm!.id, src: ctx.src,
    created: Date.now(),
  };
  await insert(ctx.env, item);
  return item;
}

async function putFile(
  ctx: Ctx,
  name: string,
  mime: string | null,
  body: Request | File,
  caption: string | null,
): Promise<Item> {
  await consume(ctx);
  name = cleanName(name);
  const given = (mime || "").split(";")[0].trim().toLowerCase();
  const generic = !given || given === "application/octet-stream" || given === "application/x-www-form-urlencoded";
  const type = generic ? guessMime(name) : given;
  const key = `f/${newId()}/${name}`;

  // R2 streams need a known length, and the length is what we charge against a link's byte budget.
  const data = body instanceof Request ? body.body : body.stream();
  const length = body instanceof Request ? requireLength(body) : body.size;
  if (!data) throw new HttpError(400, "empty body");
  await chargeBytes(ctx, length);
  const obj = await ctx.env.BUCKET.put(key, data, { httpMetadata: { contentType: type } });

  // The id is taken after the upload so it sorts after anything created meanwhile (clients poll by id).
  const item: Item = {
    id: newId(), kind: kindOf(type), text: caption || null, name, mime: type,
    size: obj?.size ?? null, key, realm: ctx.realm!.id, src: ctx.src, created: Date.now(),
  };
  await insert(ctx.env, item);
  return item;
}

async function insert(env: Env, i: Item) {
  await insertStmt(env, i).run();
}

function insertStmt(env: Env, i: Item) {
  return env.DB.prepare(
    "INSERT INTO items (id, kind, text, name, mime, size, key, realm, src, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
  ).bind(i.id, i.kind, i.text, i.name, i.mime, i.size, i.key ?? null, i.realm, i.src, i.created);
}

async function getItem(ctx: Ctx, id: string, perm: keyof typeof SCOPES): Promise<Item> {
  need(ctx, perm);
  const item = ctx.link?.selection
    ? await ctx.env.DB.prepare(
      "SELECT items.* FROM items JOIN link_items ON link_items.item = items.id AND link_items.token = ? WHERE items.id = ? AND items.realm = ?",
    ).bind(ctx.link.token, id, ctx.realm!.id).first<Item>()
    : await ctx.env.DB.prepare("SELECT * FROM items WHERE id = ? AND realm = ?").bind(id, ctx.realm!.id).first<Item>();
  if (!item) throw new HttpError(404, "no such item");
  return item;
}

async function patchItem(ctx: Ctx, id: string): Promise<Response> {
  const item = await getItem(ctx, id, "modify");
  const j = (await ctx.req.json().catch(() => ({}))) as { text?: string | null };
  if (j.text === undefined) throw new HttpError(400, "nothing to change");
  const next = j.text || null;
  if (!next && !item.key) throw new HttpError(400, "a note can't be empty");
  await ctx.env.DB.prepare("UPDATE items SET text = ? WHERE id = ?").bind(next, id).run();
  return json(publicItem({ ...item, text: next }));
}

async function deleteItem(ctx: Ctx, id: string): Promise<Response> {
  const item = await getItem(ctx, id, "modify");
  // A selection left empty would show nothing, so its link goes too.
  await ctx.env.DB.batch([
    ctx.env.DB.prepare("DELETE FROM items WHERE id = ?").bind(id),
    ctx.env.DB.prepare("DELETE FROM link_items WHERE item = ?").bind(id),
    ctx.env.DB.prepare(
      "DELETE FROM links WHERE selection = 1 AND NOT EXISTS (SELECT 1 FROM link_items WHERE link_items.token = links.token)",
    ),
  ]);
  if (item.key) await ctx.env.BUCKET.delete(item.key); // an orphaned object is better than a dangling item
  return json({ ok: true });
}

async function raw(ctx: Ctx, id: string): Promise<Response> {
  const item = await getItem(ctx, id, "read");
  const head = ctx.req.method === "HEAD";

  if (!item.key) {
    if (!head) await consume(ctx);
    return new Response(head ? null : item.text, {
      headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "private, max-age=0" },
    });
  }

  if (head) {
    const obj = await ctx.env.BUCKET.head(item.key);
    if (!obj) throw new HttpError(404, "file missing from storage");
    return new Response(null, { headers: fileHeaders(ctx, item, obj.size, obj.httpEtag) });
  }

  // Use-limited links ignore Range: every fetch is a whole download and costs one use.
  const limited = ctx.link?.max_uses != null;
  const range = limited ? null : parseRange(ctx.req.headers.get("range"));
  if (range === "invalid") throw new HttpError(416, "bad range");
  let obj: R2ObjectBody | R2Object | null;
  try {
    obj = await ctx.env.BUCKET.get(item.key, { onlyIf: ctx.req.headers, ...(range ? { range } : {}) });
  } catch (e) {
    if (range) throw new HttpError(416, "range not satisfiable");
    throw e;
  }
  if (!obj) throw new HttpError(404, "file missing from storage");
  const headers = fileHeaders(ctx, item, obj.size, obj.httpEtag);
  if (!("body" in obj)) return new Response(null, { status: 304, headers }); // If-None-Match hit
  try {
    await consume(ctx);
  } catch (e) {
    await obj.body.cancel();
    throw e;
  }
  if (!range) return new Response(obj.body, { headers });

  const r = range as { offset?: number; length?: number; suffix?: number };
  const start = r.offset ?? Math.max(0, obj.size - (r.suffix ?? 0));
  const end = r.length != null ? Math.min(start + r.length, obj.size) - 1 : obj.size - 1;
  headers.set("content-range", `bytes ${start}-${end}/${obj.size}`);
  headers.set("content-length", String(end - start + 1));
  return new Response(obj.body, { status: 206, headers });
}

function fileHeaders(ctx: Ctx, item: Item, size: number, etag: string): Headers {
  // Only types that can't run script are shown inline; html/svg/xml & co. always download.
  const mime = item.mime || "";
  const dl = ctx.url.searchParams.has("dl") || !INLINE.test(mime);
  const h = new Headers({
    "content-type": item.mime || "application/octet-stream",
    "content-length": String(size),
    "content-disposition": `${dl ? "attachment" : "inline"}; filename="${(item.name || "file").replace(/[^\x20-\x7e]|"/g, "_")}"; filename*=UTF-8''${encodeURIComponent(item.name || "file")}`,
    "accept-ranges": "bytes",
    etag,
    "cache-control": "private, max-age=3600",
    "x-content-type-options": "nosniff",
  });
  // Chrome's PDF viewer refuses to run in a sandboxed document; PDFs can't script the origin anyway.
  if (mime !== "application/pdf") h.set("content-security-policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
  return h;
}

const INLINE = /^(image\/(png|jpeg|gif|webp|avif|heic|bmp)|audio\/[\w.+-]+|video\/[\w.+-]+|text\/plain|application\/pdf)$/;

/** Uploads must declare their size: R2 needs it to stream, and it's what byte budgets charge. */
function requireLength(req: Request): number {
  const n = Number(req.headers.get("content-length"));
  if (!req.headers.get("content-length") || !Number.isFinite(n) || n < 0) throw new HttpError(411, "content-length required");
  return n;
}

/** Charge an upload against the link's byte budget, if it has one. */
async function chargeBytes(ctx: Ctx, n: number) {
  if (!ctx.link || ctx.link.max_bytes == null) return;
  const r = await ctx.env.DB.prepare(
    "UPDATE links SET bytes = bytes + ? WHERE token = ? AND bytes + ? <= max_bytes",
  ).bind(n, ctx.link.token, n).run();
  if (!r.meta.changes) throw new HttpError(413, "this link's upload budget is used up");
}

function parseRange(h: string | null): R2Range | "invalid" | null {
  const m = h?.match(/^bytes=(\d*)-(\d*)$/);
  if (!m || (!m[1] && !m[2])) return null;
  if (!m[1]) return parseInt(m[2]) > 0 ? { suffix: parseInt(m[2]) } : "invalid";
  const offset = parseInt(m[1]);
  if (!m[2]) return { offset };
  const end = parseInt(m[2]);
  return end >= offset ? { offset, length: end - offset + 1 } : "invalid";
}

// ---------- multipart uploads (for files bigger than one request) ----------

interface Upload {
  id: string; key: string; r2id: string; name: string; mime: string;
  text: string | null; realm: string; src: string | null; owner: string | null; created: number;
}

async function startUpload(ctx: Ctx): Promise<Response> {
  const j = (await ctx.req.json().catch(() => ({}))) as { name?: string; type?: string; text?: string };
  if (!j.name) throw new HttpError(400, "name required");
  await consume(ctx); // a use is spent when the upload starts, so a 1-use link can't open endless uploads
  const id = newId();
  const name = cleanName(j.name);
  const mime = j.type || guessMime(name);
  const key = `f/${id}/${name}`;
  const mp = await ctx.env.BUCKET.createMultipartUpload(key, { httpMetadata: { contentType: mime } });
  await ctx.env.DB.prepare(
    "INSERT INTO uploads (id, key, r2id, name, mime, text, realm, src, owner, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
  ).bind(id, key, mp.uploadId, name, mime, j.text || null, ctx.realm!.id, ctx.src, ctx.link?.token ?? null, Date.now()).run();
  return json({ upload: id }, 201);
}

async function loadUpload(ctx: Ctx, id: string): Promise<Upload> {
  const up = await ctx.env.DB.prepare("SELECT * FROM uploads WHERE id = ?").bind(id).first<Upload>();
  if (!up || up.realm !== ctx.realm!.id || (ctx.scope !== "owner" && up.owner !== ctx.link?.token)) {
    throw new HttpError(404, "no such upload");
  }
  return up;
}

async function uploadPart(ctx: Ctx, id: string, n: string): Promise<Response> {
  const up = await loadUpload(ctx, id);
  const part = parseInt(n);
  if (!(part >= 1 && part <= 10000)) throw new HttpError(400, "part must be 1..10000");
  const length = requireLength(ctx.req);
  const body = ctx.req.body;
  if (!body) throw new HttpError(400, "empty part");
  await chargeBytes(ctx, length);
  const mp = ctx.env.BUCKET.resumeMultipartUpload(up.key, up.r2id);
  const res = await mp.uploadPart(part, body);
  return json({ part: res.partNumber, etag: res.etag });
}

async function completeUpload(ctx: Ctx, id: string): Promise<Response> {
  const up = await loadUpload(ctx, id);
  const j = (await ctx.req.json().catch(() => ({}))) as { parts?: { part: number; etag: string }[] };
  if (!j.parts?.length) throw new HttpError(400, "parts required");
  const mp = ctx.env.BUCKET.resumeMultipartUpload(up.key, up.r2id);
  const obj = await mp.complete(
    j.parts.sort((x, y) => x.part - y.part).map((p) => ({ partNumber: p.part, etag: p.etag })),
  );
  const item: Item = {
    id: newId(), kind: kindOf(up.mime), text: up.text, name: up.name, mime: up.mime,
    size: obj.size, key: up.key, realm: up.realm, src: up.src, created: Date.now(),
  };
  await ctx.env.DB.batch([ctx.env.DB.prepare("DELETE FROM uploads WHERE id = ?").bind(id), insertStmt(ctx.env, item)]);
  return json(publicItem(item), 201);
}

async function abortUpload(ctx: Ctx, id: string): Promise<Response> {
  const up = await loadUpload(ctx, id);
  await ctx.env.BUCKET.resumeMultipartUpload(up.key, up.r2id).abort().catch(() => {});
  await ctx.env.DB.prepare("DELETE FROM uploads WHERE id = ?").bind(id).run();
  return json({ ok: true });
}

// ---------- links ----------

/** Owner link management; on a realm's routes it only sees that realm's links. */
async function links(ctx: Ctx, token: string | undefined): Promise<Response> {
  need(ctx, "owner");
  const m = ctx.req.method;
  if (!token && m === "GET") return listLinks(ctx);
  if (!token && m === "POST") return createLink(ctx);
  if (token && (m === "DELETE" || m === "PATCH")) {
    const link = await ctx.env.DB.prepare("SELECT * FROM links WHERE token = ?").bind(token).first<Link>();
    if (!link || (ctx.realm && link.realm !== ctx.realm.id)) throw new HttpError(404, "no such link");
    if (m === "PATCH") return patchLink(ctx, link);
    await ctx.env.DB.batch([
      ctx.env.DB.prepare("DELETE FROM link_items WHERE token = ?").bind(token),
      ctx.env.DB.prepare("DELETE FROM links WHERE token = ?").bind(token),
    ]);
    return json({ ok: true });
  }
  throw new HttpError(404, "not found");
}

async function createLink(ctx: Ctx): Promise<Response> {
  const j = (await ctx.req.json().catch(() => ({}))) as {
    scope?: Scope | "item"; items?: string[]; item?: string; ttl?: number; uses?: number; label?: string; max_bytes?: number;
  };
  // {scope: "item", item} is how clients asked for a one-item link before selections.
  const items = j.items ?? (j.item ? [j.item] : undefined);
  const scope = j.scope === "item" ? "read" : j.scope || (items ? "read" : "drop");
  if (!["drop", "read", "full"].includes(scope)) throw new HttpError(400, "scope must be drop|read|full");
  if (items && scope !== "read") throw new HttpError(400, "a link to picked items can only read");
  const link: Link = {
    token: randomToken(),
    scope,
    realm: ctx.realm!.id,
    selection: items ? 1 : 0,
    label: j.label?.slice(0, 40) || null,
    expires: j.ttl && j.ttl > 0 ? Date.now() + j.ttl * 1000 : null,
    max_uses: j.uses && j.uses > 0 ? j.uses : null,
    uses: 0,
    max_bytes: j.max_bytes && j.max_bytes > 0 ? j.max_bytes : null,
    bytes: 0,
    created: Date.now(),
  };
  const picked = items ? await checkSelection(ctx, link.realm, items) : [];
  await ctx.env.DB.batch([
    ctx.env.DB.prepare(
      "INSERT INTO links (token, scope, realm, selection, label, expires, max_uses, uses, max_bytes, bytes, created) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, 0, ?)",
    ).bind(link.token, link.scope, link.realm, link.selection, link.label, link.expires, link.max_uses, link.max_bytes, link.created),
    ...selectionStmts(ctx, link.token, picked),
  ]);
  return json(publicLink(ctx, link, picked), 201);
}

/** Replaces what a selection link sees. */
async function patchLink(ctx: Ctx, link: Link): Promise<Response> {
  const j = (await ctx.req.json().catch(() => ({}))) as { items?: string[] };
  if (!j.items) throw new HttpError(400, "nothing to change");
  if (!link.selection) throw new HttpError(400, "this link sees the whole realm");
  const picked = await checkSelection(ctx, link.realm, j.items);
  await ctx.env.DB.batch([
    ctx.env.DB.prepare("DELETE FROM link_items WHERE token = ?").bind(link.token),
    ...selectionStmts(ctx, link.token, picked),
  ]);
  return json(publicLink(ctx, link, picked));
}

/** The picked ids, deduplicated, once they're all known to be in the realm. */
async function checkSelection(ctx: Ctx, realm: string, items: unknown): Promise<string[]> {
  if (!Array.isArray(items) || !items.length || !items.every((i) => typeof i === "string")) {
    throw new HttpError(400, "items must be a list of item ids");
  }
  const ids = [...new Set(items as string[])];
  // json_each keeps it one bound parameter; D1 allows only 100 per query.
  const found = await ctx.env.DB.prepare(
    "SELECT COUNT(*) AS n FROM items WHERE realm = ? AND id IN (SELECT value FROM json_each(?))",
  ).bind(realm, JSON.stringify(ids)).first<{ n: number }>();
  if (found?.n !== ids.length) throw new HttpError(404, "no such item");
  return ids;
}

function selectionStmts(ctx: Ctx, token: string, ids: string[]) {
  if (!ids.length) return [];
  return [
    ctx.env.DB.prepare("INSERT INTO link_items (token, item) SELECT ?, value FROM json_each(?)").bind(token, JSON.stringify(ids)),
  ];
}

async function listLinks(ctx: Ctx): Promise<Response> {
  const realm = ctx.realm ? " AND links.realm = ?" : "";
  const args = [Date.now(), ...(ctx.realm ? [ctx.realm.id] : [])];
  const { results } = await ctx.env.DB.prepare(
    `SELECT * FROM links WHERE (expires IS NULL OR expires > ?)${realm} ORDER BY created DESC`,
  ).bind(...args).all<Link>();
  const { results: picks } = await ctx.env.DB.prepare(
    `SELECT link_items.* FROM link_items JOIN links ON links.token = link_items.token WHERE (expires IS NULL OR expires > ?)${realm}`,
  ).bind(...args).all<{ token: string; item: string }>();
  const byLink = new Map<string, string[]>();
  for (const p of picks) byLink.set(p.token, [...(byLink.get(p.token) ?? []), p.item]);
  return json({ links: results.map((l) => publicLink(ctx, l, byLink.get(l.token) ?? [])) });
}

function publicLink(ctx: Ctx, l: Link, items: string[]) {
  const { selection, ...rest } = l;
  return { ...rest, ...(selection ? { items } : {}), url: `${ctx.url.origin}/s/${l.token}` };
}

/** Count one use of a limited link; throws once the link is spent. */
async function consume(ctx: Ctx) {
  if (!ctx.link || ctx.link.max_uses == null) return;
  const r = await ctx.env.DB.prepare(
    "UPDATE links SET uses = uses + 1 WHERE token = ? AND uses < max_uses",
  ).bind(ctx.link.token).run();
  if (!r.meta.changes) throw new HttpError(410, "link used up");
}

// ---------- helpers ----------

function need(ctx: Ctx, perm: keyof typeof SCOPES) {
  if (!SCOPES[perm].includes(ctx.scope)) throw new HttpError(403, `this link can't ${perm}`);
}

function publicItem(i: Item) {
  const { key: _key, ...rest } = i;
  return rest;
}

function newId(): string {
  const t = Date.now().toString(36).padStart(9, "0");
  const r = crypto.getRandomValues(new Uint8Array(4));
  return t + Array.from(r, (b) => (b % 36).toString(36)).join("");
}

function randomToken(): string {
  const b = crypto.getRandomValues(new Uint8Array(18));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_");
}

function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

function cleanName(n: string): string {
  const s = n.split(/[\\/]/).pop()!.replace(/[\x00-\x1f]/g, "").trim();
  return (s || "file").slice(0, 200);
}

function kindOf(mime: string): string {
  if (mime.startsWith("image/")) return "image";
  if (mime.startsWith("audio/")) return "audio";
  if (mime.startsWith("video/")) return "video";
  return "file";
}

const MIME: Record<string, string> = {
  png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", webp: "image/webp",
  heic: "image/heic", svg: "image/svg+xml", mp3: "audio/mpeg", m4a: "audio/mp4", ogg: "audio/ogg",
  opus: "audio/ogg", wav: "audio/wav", mp4: "video/mp4", mov: "video/quicktime", webm: "video/webm",
  mkv: "video/x-matroska", pdf: "application/pdf", txt: "text/plain", md: "text/markdown",
  json: "application/json", zip: "application/zip", csv: "text/csv", html: "text/html",
};

function guessMime(name: string): string {
  return MIME[name.split(".").pop()!.toLowerCase()] || "application/octet-stream";
}

function wantsHtml(req: Request): boolean {
  return (req.headers.get("accept") || "").includes("text/html");
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function text(body: string, status = 200, type = "text/plain; charset=utf-8"): Response {
  return new Response(body, { status, headers: { "content-type": type } });
}

function cors(res: Response): Response {
  const h = new Headers(res.headers);
  h.set("access-control-allow-origin", "*");
  h.set("access-control-allow-methods", "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS");
  h.set("access-control-allow-headers", "authorization, content-type, range, x-realm-source");
  h.set("access-control-expose-headers", "content-range, content-length, content-disposition, etag");
  return new Response(res.body, { status: res.status, statusText: res.statusText, headers: h });
}

// ---------- docs for agents & a tiny page for humans ----------

function agentHelp(ctx: Ctx): string {
  // Above any one realm, the owner gets the realm routes spelled out with a placeholder id.
  const B = ctx.realm ? ctx.base : `${ctx.base}/realms/<realm>`;
  const auth = ctx.scope === "owner" ? ` -H "Authorization: Bearer $REALM_TOKEN"` : "";
  const exp = ctx.link?.expires ? `Expires ${new Date(ctx.link.expires).toISOString()}.` : "Does not expire.";
  const uses = ctx.link?.max_uses != null ? ` ${ctx.link.max_uses - ctx.link.uses} uses left.` : "";
  const picked = ctx.link?.selection ? " This link sees only the items picked for it." : "";
  const out = [
    `# realm — ${ctx.realm ? `"${ctx.realm.name}", ` : ""}${ctx.scope} access`,
    ``,
    `realm is a personal dump of notes and files, kept in realms.${picked} ${ctx.link ? exp + uses : ""}`,
    `Base URL: ${B}`,
    `No login needed beyond this URL${auth ? " plus the bearer token" : ""}. All responses are JSON unless noted.`,
    `curl -s${auth} "${B}/info"   # what this access allows, without spending a use`,
    ``,
  ];
  if (ctx.scope === "owner" && !ctx.realm) {
    out.push(
      `## Realms`,
      `curl -s${auth} "${ctx.base}/realms"   → {"realms": [{id, name, created, count, updated}]}`,
      `curl -s${auth} -X POST "${ctx.base}/realms" -H 'content-type: application/json' -d '{"name":"Trip"}'`,
      `curl -s${auth} -X PATCH "${ctx.base}/realms/<realm>" -H 'content-type: application/json' -d '{"name":"Holiday"}'`,
      `curl -s${auth} -X DELETE "${ctx.base}/realms/<realm>"   # deletes everything in it`,
      `The routes below work per realm. Without /realms/<realm>, they use the first realm.`,
      ``,
    );
  }
  const item = `Item: {id, kind: note|link|image|audio|video|file, text, name, mime, size, realm, src, created(epoch ms)}`;
  if (SCOPES.read.includes(ctx.scope)) {
    out.push(
      `## Find things`,
      `curl -s${auth} "${B}/items?limit=20"                   # newest first`,
      `curl -s${auth} "${B}/items?q=invoice+march"            # full-text search over text and file names`,
      `curl -s${auth} "${B}/items?kind=image,file&limit=50"   # filter by kind`,
      `curl -s${auth} "${B}/items?before=<id>"                # next page (response has "next")`,
      `→ {"items": [Item...], "next": "<id>"|null}`,
      item,
      ``,
      `## Fetch an item`,
      `curl -s${auth} "${B}/items/<id>"                       # metadata`,
      `curl -sLo out.bin${auth} "${B}/items/<id>/raw"         # file bytes (or note text). Supports Range.`,
      ``,
    );
  }
  if (SCOPES.write.includes(ctx.scope)) {
    out.push(
      `## Add things`,
      `curl -s${auth} -X POST "${B}/items" --data-binary "some text"          # a note`,
      `curl -s${auth} -T ./report.pdf "${B}/files/report.pdf"                 # a file (≤ 95 MB), ?text=caption optional`,
      `curl -s${auth} -F file=@a.png -F file=@b.png -F text=caption "${B}/items"  # form upload`,
      ``,
      `Bigger files: multipart, parts of equal size (e.g. 32 MiB, last may be smaller):`,
      `curl -s${auth} -X POST "${B}/uploads" -H 'content-type: application/json' -d '{"name":"big.mkv"}'   → {"upload": U}`,
      `curl -s${auth} -T part1 "${B}/uploads/U/1"     → {"part":1,"etag":E1}   (repeat for 2..N)`,
      `curl -s${auth} -X POST "${B}/uploads/U/complete" -H 'content-type: application/json' -d '{"parts":[{"part":1,"etag":"E1"}]}'`,
      ``,
    );
  }
  if (SCOPES.modify.includes(ctx.scope)) {
    out.push(
      `## Edit / remove`,
      `curl -s${auth} -X PATCH "${B}/items/<id>" -H 'content-type: application/json' -d '{"text":"new text"}'`,
      `curl -s${auth} -X DELETE "${B}/items/<id>"`,
      ``,
    );
  }
  if (ctx.scope === "owner") {
    out.push(
      `## Share links`,
      `curl -s${auth} -X POST "${B}/links" -H 'content-type: application/json' -d '{"scope":"drop","ttl":86400,"label":"bob"}'`,
      `curl -s${auth} -X POST "${B}/links" -H 'content-type: application/json' -d '{"items":["<id>","<id>"],"uses":3}'   # just these items`,
      `scope: drop | read | full · items: ids to share only those (read only; one item downloads at the link itself)`,
      `ttl seconds (omit = forever) · uses (omit = unlimited) · max_bytes upload budget`,
      `curl -s${auth} "${B}/links"  ·  curl -s${auth} -X DELETE "${B}/links/<token>"`,
      `curl -s${auth} -X PATCH "${B}/links/<token>" -H 'content-type: application/json' -d '{"items":["<id>"]}'   # change what it shares`,
      ``,
    );
  }
  return out.join("\n");
}

// Material-symbol paths (24px viewBox) so the page uses the same icons as the app.
const ICON: Record<string, string> = {
  attach: "M16.5 6v11.5a4 4 0 0 1-8 0V5a2.5 2.5 0 0 1 5 0v10.5a1 1 0 0 1-2 0V6H10v9.5a2.5 2.5 0 0 0 5 0V5a4 4 0 0 0-8 0v12.5a5.5 5.5 0 0 0 11 0V6h-1.5z",
  send: "M3.4 20.4l17.45-7.48a1 1 0 0 0 0-1.84L3.4 3.6a.993.993 0 0 0-1.39.91L2 9.12c0 .5.37.93.87.99L17 12 2.87 13.88c-.5.07-.87.5-.87 1l.01 4.61c0 .71.73 1.2 1.39.91z",
  file: "M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm4 18H6V4h7v5h5v11z",
  image: "M19 5v14H5V5h14m0-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-4.86 8.86l-3 3.87L9 13.14 6 17h12l-3.86-5.14z",
  audio: "M12 3v9.28a4.39 4.39 0 0 0-1.5-.28C8.01 12 6 14.01 6 16.5S8.01 21 10.5 21c2.31 0 4.2-1.75 4.45-4H15V6h4V3h-7z",
  video: "M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4zm2 14H4V10h16v8z",
  search: "M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
  error: "M11 15h2v2h-2zm0-8h2v6h-2zm1-5C6.47 2 2 6.5 2 12a10 10 0 0 0 20 0c0-5.5-4.5-10-10-10zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16z",
  check: "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
};
const svg = (name: string) => `<svg viewBox="0 0 24 24" aria-hidden=true><path d="${ICON[name]}"/></svg>`;

const FILTERS: [string, string][] = [["", "All"], ["note", "Notes"], ["link", "Links"], ["image", "Images"], ["audio,video", "Audio & video"], ["file", "Files"]];

async function htmlPage(ctx: Ctx): Promise<Response> {
  const canRead = SCOPES.read.includes(ctx.scope);
  const canWrite = SCOPES.write.includes(ctx.scope);
  let feed = "";
  if (canRead && ctx.scope !== "owner") {
    const q = ctx.url.searchParams.get("q") || "";
    const kind = ctx.url.searchParams.get("kind") || "";
    const sub = new URL(`${ctx.base}/items`);
    if (q) sub.searchParams.set("q", q);
    if (kind) sub.searchParams.set("kind", kind);
    const { items } = (await (await list({ ...ctx, url: sub })).json()) as { items: Item[] };
    const href = (k: string) => "?" + new URLSearchParams({ ...(q ? { q } : {}), ...(k ? { kind: k } : {}) }).toString();
    feed =
      `<form class=search>${svg("search")}<input name=q value="${esc(q)}" placeholder="Search the dump" autocomplete=off>` +
      (kind ? `<input type=hidden name=kind value="${esc(kind)}">` : "") + `</form>` +
      `<nav class=chips>${FILTERS.map(([k, l]) => `<a href="${href(k)}"${k === kind ? " class=on" : ""}>${l}</a>`).join("")}</nav>` +
      (items.length ? `<div class=feed>${items.map((i) => itemHtml(ctx, i)).join("")}</div>`
        : `<p class=empty>${q || kind ? "Nothing matches." : "Nothing here yet."}</p>`);
  }
  const composer = canWrite
    ? `<section id=drop class=composer>
<textarea id=t rows=3 placeholder="Type or paste a note, or drop files anywhere"></textarea>
<div class=bar><label class=icon title="Add files">${svg("attach")}<input type=file id=f multiple hidden></label>
<span class=hint>Ctrl+Enter to send</span><button id=go class=send title=Send>${svg("send")}</button></div>
<div id=log></div></section>`
    : "";
  const sub = ctx.scope === "drop" ? "Drop notes and files here" : ctx.link?.selection ? "Shared with you"
    : ctx.scope === "read" ? "Read-only" : "Shared dump";
  const name = ctx.realm ? esc(ctx.realm.name) : "";
  const exp = ctx.link?.expires ? ` · link expires <time data-t="${ctx.link.expires}"></time>` : "";

  const body = `<!doctype html><html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>realm${name ? "/" + name : ""}</title><style>
:root{color-scheme:dark;--bg:#0e0e11;--card:#18181d;--card2:#1f1f26;--line:#2a2a33;--text:#e7e7ea;--mute:#8a8a96;--a:#9d8cff;--on-a:#0e0e11;--tile:#2a2547;--on-tile:#e4deff;--err:#ff7a85}
@media (prefers-color-scheme:light){:root{color-scheme:light;--bg:#fafafb;--card:#f1f1f4;--card2:#eaeaee;--line:#dadae0;--text:#1b1b1f;--mute:#6b6b76;--a:#5b48e8;--on-a:#fff;--tile:#e8e4ff;--on-tile:#1e1650;--err:#d93a4a}}
*{box-sizing:border-box}html{background:var(--bg)}
body{font:15px/22px system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:var(--text);max-width:680px;margin:0 auto;padding:40px 16px 64px}
svg{width:22px;height:22px;fill:currentColor;flex:none}
header{margin:0 4px 20px}h1{margin:0;font-size:20px;font-weight:600;letter-spacing:.02em;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
h1 span{color:var(--mute)}header p{margin:4px 0 0;color:var(--mute);font-size:13px}
.composer{background:var(--card);border-radius:22px;padding:6px;outline:2px solid transparent;transition:outline-color .15s}
body.over .composer{outline-color:var(--a)}
textarea{display:block;width:100%;min-height:72px;max-height:50vh;resize:none;border:0;outline:0;background:none;color:inherit;font:inherit;padding:12px 14px 4px}
textarea::placeholder,input::placeholder{color:var(--mute)}
.bar{display:flex;align-items:center;height:48px}
.icon{display:grid;place-items:center;width:48px;height:48px;border-radius:50%;color:var(--mute);cursor:pointer}.icon:hover{color:var(--text);background:var(--card2)}
.hint{flex:1;text-align:right;padding-right:10px;color:var(--mute);font-size:12px}
.send{display:grid;place-items:center;width:40px;height:40px;margin:4px;border:0;border-radius:50%;background:var(--a);color:var(--on-a);cursor:pointer}.send svg{width:18px;height:18px}.send:hover{filter:brightness(1.1)}
#log:empty{display:none}#log{display:grid;gap:4px;padding:4px 6px 6px}
.up{display:flex;align-items:center;gap:12px;padding:6px;border-radius:14px;background:var(--card2)}
.ring{width:42px;height:42px;border-radius:10px;background:var(--tile);color:var(--on-tile);display:grid;place-items:center;flex:none}
.ring circle{fill:none;stroke-width:2.5}.ring .tr{stroke:currentColor;opacity:.15}.ring .pr{stroke:currentColor;stroke-linecap:round;transition:stroke-dashoffset .2s;transform:rotate(-90deg);transform-origin:center}
.up .t{min-width:0}.up b{display:block;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.up small{color:var(--mute);font-size:12px}
.up.err small,.up.err .ring{color:var(--err)}
.search{display:flex;align-items:center;gap:10px;height:48px;margin:28px 0 10px;padding:0 16px;border-radius:24px;background:var(--card);color:var(--mute)}
.search input{flex:1;height:100%;border:0;outline:0;background:none;color:var(--text);font:inherit}
.chips{display:flex;gap:6px;overflow-x:auto;margin-bottom:18px;scrollbar-width:none}
.chips a{flex:none;height:32px;line-height:32px;padding:0 14px;border-radius:16px;background:var(--card);color:var(--text);text-decoration:none;font-size:13px}
.chips a.on{background:var(--tile);color:var(--on-tile)}
.feed{display:grid;gap:14px}
.card{background:var(--card);border-radius:16px;overflow:hidden}.card>p{margin:0;padding:10px 14px;white-space:pre-wrap;overflow-wrap:anywhere}
.card p a{color:var(--a)}
.file{display:flex;align-items:center;gap:12px;padding:10px;color:inherit;text-decoration:none}.file:hover{background:var(--card2)}
.file .tile{width:42px;height:42px;border-radius:10px;background:var(--tile);color:var(--on-tile);display:grid;place-items:center;flex:none}
.file b{display:block;font-weight:400;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.file small{color:var(--mute);font-size:12px}.file span{min-width:0}
.card.pic{max-width:420px}.card img{display:block;width:100%;max-height:360px;object-fit:cover;background:var(--card2)}
.meta{display:block;margin:4px 0 0 8px;color:var(--mute);font-size:11px;opacity:.85}
.empty{color:var(--mute);text-align:center;margin:48px 0}
</style>
<header><h1>${name ? `<span>realm/</span>${name}` : "realm"}</h1><p>${sub}${exp}</p></header>${composer}${feed}
<script>
const B=${JSON.stringify(ctx.base)},P=32*1024*1024,R=${canRead},$=id=>document.getElementById(id);
for(const t of document.querySelectorAll('time[data-t]')){const d=new Date(+t.dataset.t);t.textContent=d.toLocaleString([], {month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'})}
function size(n){const u=['B','KB','MB','GB'];let i=0;while(n>=1024&&i<3){n/=1024;i++}return (i?n.toFixed(1):n)+' '+u[i]}
const C=2*Math.PI*11;
function row(name){const d=document.createElement('div');d.className='up';
  d.innerHTML='<div class=ring><svg viewBox="0 0 28 28" width=28 height=28><circle class=tr cx=14 cy=14 r=11 /><circle class=pr cx=14 cy=14 r=11 stroke-dasharray="'+C+'" stroke-dashoffset="'+C+'"/></svg></div><div class=t><b></b><small>waiting…</small></div>';
  d.querySelector('b').textContent=name;$('log').append(d);const pr=d.querySelector('.pr'),s=d.querySelector('small');
  return{set(t,f){s.textContent=t;if(f!=null)pr.style.strokeDashoffset=C*(1-Math.min(1,f))},
    done(t){this.set(t,1);d.querySelector('.ring').innerHTML=${JSON.stringify(svg("check"))}},
    fail(t){d.classList.add('err');s.textContent=t;d.querySelector('.ring').innerHTML=${JSON.stringify(svg("error"))}}}}
// XHR rather than fetch: fetch can't report upload progress.
function send(method,url,body,onp,type){return new Promise((ok,no)=>{const x=new XMLHttpRequest();x.open(method,url);if(type)x.setRequestHeader('content-type',type);
  x.upload.onprogress=e=>onp&&onp(e.loaded);x.onload=()=>{let j={};try{j=JSON.parse(x.responseText)}catch{}x.status<300?ok(j):no(new Error(j.error||'HTTP '+x.status))};x.onerror=()=>no(new Error('network error'));x.send(body)})}
async function up(f){const r=row(f.name),of=' of '+size(f.size);
  try{
    if(f.size<=95e6){await send('PUT',B+'/files/'+encodeURIComponent(f.name),f,n=>r.set(size(n)+of,n/f.size),f.type||'application/octet-stream')}
    else{const {upload}=await send('POST',B+'/uploads',JSON.stringify({name:f.name,type:f.type}),null,'application/json');const parts=[];
      for(let i=0;i*P<f.size;i++){const o=i*P;parts.push(await send('PUT',B+'/uploads/'+upload+'/'+(i+1),f.slice(o,o+P),n=>r.set(size(o+n)+of,(o+n)/f.size)))}
      await send('POST',B+'/uploads/'+upload+'/complete',JSON.stringify({parts}),null,'application/json')}
    r.done('sent · '+size(f.size));return true
  }catch(e){r.fail(e.message);return false}}
let busy=false;
async function go(files){if(busy)return;busy=true;let ok=true;const t=$('t').value.trim();
  if(t){const r=row(t.split('\\n')[0].slice(0,60));try{await send('POST',B+'/items',t,null,'text/plain;charset=utf-8');$('t').value='';r.done('note sent')}catch(e){ok=false;r.fail(e.message)}}
  for(const f of files)ok=(await up(f))&&ok;
  busy=false;if(ok&&R&&(t||files.length))setTimeout(()=>location.reload(),700)}
if($('drop')){
  $('go').onclick=()=>go([]);$('f').onchange=e=>{go([...e.target.files]);e.target.value=''};
  let n=0;const b=document.body;
  addEventListener('dragenter',e=>{e.preventDefault();n++;b.classList.add('over')});addEventListener('dragover',e=>e.preventDefault());
  addEventListener('dragleave',()=>{if(--n<=0){n=0;b.classList.remove('over')}});
  addEventListener('drop',e=>{e.preventDefault();n=0;b.classList.remove('over');if(e.dataTransfer.files.length)go([...e.dataTransfer.files])});
  $('t').onpaste=e=>{const fs=[...e.clipboardData.files];if(fs.length){e.preventDefault();go(fs)}};
  $('t').oninput=e=>{e.target.style.height='auto';e.target.style.height=e.target.scrollHeight+'px'};
  $('t').onkeydown=e=>{if(e.key==='Enter'&&(e.ctrlKey||e.metaKey))go([])}}
</script></html>`;
  return text(body, 200, "text/html; charset=utf-8");
}

function itemHtml(ctx: Ctx, i: Item): string {
  const raw = `${ctx.base}/items/${i.id}/raw`;
  const ext = (i.name || "").split(".").pop()!;
  const icon = i.kind === "image" ? "image" : i.kind === "audio" ? "audio" : i.kind === "video" ? "video" : "file";
  let inner = "";
  const pic = i.kind === "image" && (i.size || 0) <= 15 * 1024 * 1024;
  if (pic) inner += `<a href="${raw}"><img src="${raw}" alt="${esc(i.name || "")}" loading=lazy></a>`;
  else if (i.name) {
    inner += `<a class=file href="${raw}?dl"><span class=tile>${svg(icon)}</span><span><b>${esc(i.name)}</b>` +
      `<small>${fmtSize(i.size || 0)}${ext && ext.length <= 5 && ext !== i.name ? " · " + esc(ext.toUpperCase()) : ""}</small></span></a>`;
  }
  if (i.text) inner += `<p>${linkify(esc(i.text))}</p>`;
  const src = i.src && i.src !== "api" ? ` · ${esc(i.src)}` : "";
  return `<article><div class="card${pic ? " pic" : ""}">${inner}</div><span class=meta><time data-t="${i.created}"></time>${src}</span></article>`;
}

function linkify(escaped: string): string {
  // Runs on escaped text, so stop at entities (&quot; etc.) and trailing punctuation.
  return escaped.replace(/https?:\/\/(?:(?!&(?:quot|#39|lt|gt);)[^\s<>"'])+(?<![.,;:!?)\]])/g, (u) => `<a href="${u}" rel=noreferrer target=_blank>${u}</a>`);
}

function esc(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);
}

function fmtSize(n: number): string {
  const u = ["B", "KB", "MB", "GB"];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) (n /= 1024), i++;
  return `${n.toFixed(i ? 1 : 0)} ${u[i]}`;
}
