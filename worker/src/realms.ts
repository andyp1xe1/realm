import {
  HttpError,
  json,
  need,
  newId,
  purge,
  randomToken,
  linkFromRow,
  type Ctx,
  type Env,
  type Link,
  type LinkRow,
  type OwnerCtx,
  type Realm,
  type RealmCtx,
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

async function realmName(ctx: OwnerCtx): Promise<string> {
  const j = (await ctx.req.json().catch(() => ({}))) as { name?: string };
  const name = (j.name || "").trim().slice(0, 60);
  if (!name) throw new HttpError(400, "name required");
  return name;
}

export async function createRealm(ctx: OwnerCtx): Promise<Response> {
  const realm: Realm = { id: newId(), name: await realmName(ctx), created: Date.now() };
  await ctx.env.DB.prepare("INSERT INTO realms (id, name, created) VALUES (?, ?, ?)")
    .bind(realm.id, realm.name, realm.created)
    .run();
  return json({ ...realm, count: 0, updated: null }, 201);
}

export async function renameRealm(ctx: OwnerCtx, realm: Realm): Promise<Response> {
  const name = await realmName(ctx);
  await ctx.env.DB.prepare("UPDATE realms SET name = ? WHERE id = ?").bind(name, realm.id).run();
  return json((await realmsWithStats(ctx.env, realm.id))[0]);
}

/** Deletes the realm with everything in it, its links included. */
export async function deleteRealm(ctx: OwnerCtx, realm: Realm): Promise<Response> {
  const db = ctx.env.DB;
  const { results: files } = await db
    .prepare("SELECT key FROM items WHERE realm = ? AND key IS NOT NULL")
    .bind(realm.id)
    .all<{ key: string }>();
  const { results: ups } = await db.prepare("SELECT * FROM uploads WHERE realm = ?").bind(realm.id).all<Upload>();
  await db.batch([
    db
      .prepare("INSERT OR IGNORE INTO doomed (key) SELECT key FROM items WHERE realm = ? AND key IS NOT NULL")
      .bind(realm.id),
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
  await purge(
    ctx.env,
    files.map((f) => f.key),
  );
  return json({ ok: true });
}

/** Owner link management; on a realm's routes it only sees that realm's links. */
export async function links(ctx: Ctx, token: string | undefined): Promise<Response> {
  need(ctx, "owner");
  const realm = "realm" in ctx ? ctx.realm.id : null;
  const m = ctx.req.method;
  if (!token && m === "GET") return listLinks(ctx, realm);
  if (!token && m === "POST") {
    if (!("realm" in ctx)) throw new HttpError(404, "create links under /api/realms/<realm>/links");
    return createLink(ctx);
  }
  if (token && (m === "DELETE" || m === "PATCH")) {
    const row = await ctx.env.DB.prepare("SELECT * FROM links WHERE token = ?").bind(token).first<LinkRow>();
    const link = row && linkFromRow(row);
    if (!link || (realm && link.realm !== realm)) throw new HttpError(404, "no such link");
    if (m === "PATCH") return patchLink(ctx, link);
    await ctx.env.DB.batch([
      ctx.env.DB.prepare("DELETE FROM link_items WHERE token = ?").bind(token),
      ctx.env.DB.prepare("DELETE FROM links WHERE token = ?").bind(token),
    ]);
    return json({ ok: true });
  }
  throw new HttpError(404, "not found");
}

interface NewLink {
  scope?: Scope;
  items?: string[];
  ttl?: number;
  uses?: number;
  label?: string;
  max_bytes?: number;
}

const NEW_LINK_FIELDS: string[] = ["scope", "items", "ttl", "uses", "label", "max_bytes"] satisfies (keyof NewLink)[];

async function createLink(ctx: RealmCtx): Promise<Response> {
  const j = (await ctx.req.json().catch(() => ({}))) as NewLink;
  const unknown = Object.keys(j).filter((k) => !NEW_LINK_FIELDS.includes(k));
  if (unknown.length)
    throw new HttpError(400, `unknown field ${unknown[0]}; a link takes ${NEW_LINK_FIELDS.join(", ")}`);
  const items = j.items;
  const scope = j.scope || (items ? "read" : "drop");
  if (!["drop", "read", "full"].includes(scope)) throw new HttpError(400, "scope must be drop|read|full");
  if (items && scope !== "read") throw new HttpError(400, "a link to picked items can only read");
  const link: Link = {
    token: randomToken(),
    scope,
    realm: ctx.realm.id,
    selection: !!items,
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
      link.selection ? 1 : 0,
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

async function checkSelection(ctx: Ctx, realm: string, items: unknown): Promise<string[]> {
  if (!Array.isArray(items) || !items.length || !items.every((i) => typeof i === "string")) {
    throw new HttpError(400, "items must be a list of item ids");
  }
  const ids = [...new Set(items as string[])];
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

async function listLinks(ctx: Ctx, realm: string | null): Promise<Response> {
  const inRealm = realm ? " AND links.realm = ?" : "";
  const args = [Date.now(), ...(realm ? [realm] : [])];
  const { results } = await ctx.env.DB.prepare(
    `SELECT * FROM links WHERE (expires IS NULL OR expires > ?)${inRealm} ORDER BY created DESC`,
  )
    .bind(...args)
    .all<LinkRow>();
  const { results: picks } = await ctx.env.DB.prepare(
    `SELECT link_items.* FROM link_items JOIN links ON links.token = link_items.token WHERE (expires IS NULL OR expires > ?)${inRealm}`,
  )
    .bind(...args)
    .all<{ token: string; item: string }>();
  const byLink = new Map<string, string[]>();
  for (const p of picks) byLink.set(p.token, [...(byLink.get(p.token) ?? []), p.item]);
  return json({ links: results.map(linkFromRow).map((l) => publicLink(ctx, l, byLink.get(l.token) ?? [])) });
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
