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

import {
  HttpError,
  VERSION,
  cors,
  json,
  need,
  publicItem,
  safeEqual,
  text,
  wantsHtml,
  type Ctx,
  type Env,
  type Item,
  type Link,
  type Realm,
  type Upload,
} from "./common.ts";
import {
  abortUpload,
  completeUpload,
  createFromBody,
  deleteItem,
  getItem,
  list,
  patchItem,
  putFile,
  raw,
  startUpload,
  uploadPart,
} from "./items.ts";
import { agentHelp, htmlPage } from "./page.ts";
import { createRealm, deleteRealm, links, realmsWithStats, renameRealm } from "./realms.ts";

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
  [`CREATE TABLE owners (key TEXT PRIMARY KEY, ok INTEGER NOT NULL, until INTEGER NOT NULL)`],
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
  const version = async () =>
    (await db.prepare("SELECT v FROM meta WHERE k = 'schema'").first<{ v: number }>())?.v ?? 0;
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
      await env.BUCKET.resumeMultipartUpload(up.key, up.r2id)
        .abort()
        .catch(() => {});
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
    const ctx: Ctx = {
      req,
      env,
      url,
      scope: "owner",
      link: null,
      realm: null,
      base: `${url.origin}/api`,
      src: src || "api",
    };
    return owner(ctx, parts.slice(1));
  }

  if (parts[0] === "s" && parts[1]) {
    const row = await env.DB.prepare(
      "SELECT links.*, realms.name AS realm_name, realms.created AS realm_created FROM links JOIN realms ON realms.id = links.realm WHERE token = ?",
    )
      .bind(parts[1])
      .first<Link & { realm_name: string; realm_created: number }>();
    if (!row || (row.expires && row.expires < Date.now())) throw new HttpError(404, "link not found or expired");
    if (row.max_uses != null && row.uses >= row.max_uses) throw new HttpError(410, "link used up");
    const { realm_name, realm_created, ...link } = row;
    const ctx: Ctx = {
      req,
      env,
      url,
      scope: link.scope,
      link,
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
  const shared = await env.DB.prepare("SELECT ok, until FROM owners WHERE key = ?")
    .bind(key)
    .first<{ ok: number; until: number }>();
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
  await env.DB.prepare("INSERT OR REPLACE INTO owners (key, ok, until) VALUES (?, ?, ?)")
    .bind(key, ok ? 1 : 0, until)
    .run();
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
      )
        .bind(l.token)
        .all<Item>();
      const n = await ctx.env.DB.prepare("SELECT COUNT(*) AS n FROM link_items WHERE token = ?")
        .bind(l.token)
        .first<{ n: number }>();
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
    .bind(ctx.link.token)
    .all<{ item: string }>();
  return results.length === 1 ? results[0].item : null;
}
