// Realms, and the share links into them.

import {
  HttpError,
  json,
  need,
  newId,
  randomToken,
  type Ctx,
  type Env,
  type Link,
  type Realm,
  type Scope,
  type Upload,
} from "./common.ts";

export async function realmsWithStats(env: Env, id?: string) {
  const { results } = await env.DB.prepare(
    `SELECT realms.*,
       (SELECT COUNT(*) FROM items WHERE items.realm = realms.id) AS count,
       (SELECT MAX(created) FROM items WHERE items.realm = realms.id) AS updated
     FROM realms ${id ? "WHERE id = ?" : ""} ORDER BY created, id`,
  )
    .bind(...(id ? [id] : []))
    .all<Realm & { count: number; updated: number | null }>();
  return results;
}

async function realmName(ctx: Ctx): Promise<string> {
  const j = (await ctx.req.json().catch(() => ({}))) as { name?: string };
  const name = (j.name || "").trim().slice(0, 60);
  if (!name) throw new HttpError(400, "name required");
  return name;
}

export async function createRealm(ctx: Ctx): Promise<Response> {
  const realm: Realm = { id: newId(), name: await realmName(ctx), created: Date.now() };
  await ctx.env.DB.prepare("INSERT INTO realms (id, name, created) VALUES (?, ?, ?)")
    .bind(realm.id, realm.name, realm.created)
    .run();
  return json({ ...realm, count: 0, updated: null }, 201);
}

export async function renameRealm(ctx: Ctx, realm: Realm): Promise<Response> {
  const name = await realmName(ctx);
  await ctx.env.DB.prepare("UPDATE realms SET name = ? WHERE id = ?").bind(name, realm.id).run();
  return json((await realmsWithStats(ctx.env, realm.id))[0]);
}

/** Deletes the realm with everything in it, its links included. */
export async function deleteRealm(ctx: Ctx, realm: Realm): Promise<Response> {
  const db = ctx.env.DB;
  const { results: files } = await db
    .prepare("SELECT key FROM items WHERE realm = ? AND key IS NOT NULL")
    .bind(realm.id)
    .all<{ key: string }>();
  const { results: ups } = await db.prepare("SELECT * FROM uploads WHERE realm = ?").bind(realm.id).all<Upload>();
  await db.batch([
    db.prepare("DELETE FROM link_items WHERE token IN (SELECT token FROM links WHERE realm = ?)").bind(realm.id),
    db.prepare("DELETE FROM links WHERE realm = ?").bind(realm.id),
    db.prepare("DELETE FROM uploads WHERE realm = ?").bind(realm.id),
    db.prepare("DELETE FROM items WHERE realm = ?").bind(realm.id),
    db.prepare("DELETE FROM realms WHERE id = ?").bind(realm.id),
  ]);
  for (const up of ups)
    await ctx.env.BUCKET.resumeMultipartUpload(up.key, up.r2id)
      .abort()
      .catch(() => {});
  for (let i = 0; i < files.length; i += 1000) await ctx.env.BUCKET.delete(files.slice(i, i + 1000).map((f) => f.key));
  return json({ ok: true });
}

/** Owner link management; on a realm's routes it only sees that realm's links. */
export async function links(ctx: Ctx, token: string | undefined): Promise<Response> {
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
    scope?: Scope | "item";
    items?: string[];
    item?: string;
    ttl?: number;
    uses?: number;
    label?: string;
    max_bytes?: number;
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
    ).bind(
      link.token,
      link.scope,
      link.realm,
      link.selection,
      link.label,
      link.expires,
      link.max_uses,
      link.max_bytes,
      link.created,
    ),
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
  )
    .bind(realm, JSON.stringify(ids))
    .first<{ n: number }>();
  if (found?.n !== ids.length) throw new HttpError(404, "no such item");
  return ids;
}

function selectionStmts(ctx: Ctx, token: string, ids: string[]) {
  if (!ids.length) return [];
  return [
    ctx.env.DB.prepare("INSERT INTO link_items (token, item) SELECT ?, value FROM json_each(?)").bind(
      token,
      JSON.stringify(ids),
    ),
  ];
}

async function listLinks(ctx: Ctx): Promise<Response> {
  const realm = ctx.realm ? " AND links.realm = ?" : "";
  const args = [Date.now(), ...(ctx.realm ? [ctx.realm.id] : [])];
  const { results } = await ctx.env.DB.prepare(
    `SELECT * FROM links WHERE (expires IS NULL OR expires > ?)${realm} ORDER BY created DESC`,
  )
    .bind(...args)
    .all<Link>();
  const { results: picks } = await ctx.env.DB.prepare(
    `SELECT link_items.* FROM link_items JOIN links ON links.token = link_items.token WHERE (expires IS NULL OR expires > ?)${realm}`,
  )
    .bind(...args)
    .all<{ token: string; item: string }>();
  const byLink = new Map<string, string[]>();
  for (const p of picks) byLink.set(p.token, [...(byLink.get(p.token) ?? []), p.item]);
  return json({ links: results.map((l) => publicLink(ctx, l, byLink.get(l.token) ?? [])) });
}

function publicLink(ctx: Ctx, l: Link, items: string[]) {
  const { selection, ...rest } = l;
  return { ...rest, ...(selection ? { items } : {}), url: `${ctx.url.origin}/s/${l.token}` };
}

/** Count one use of a limited link; throws once the link is spent. */
export async function consume(ctx: Ctx) {
  if (!ctx.link || ctx.link.max_uses == null) return;
  const r = await ctx.env.DB.prepare("UPDATE links SET uses = uses + 1 WHERE token = ? AND uses < max_uses")
    .bind(ctx.link.token)
    .run();
  if (!r.meta.changes) throw new HttpError(410, "link used up");
}
