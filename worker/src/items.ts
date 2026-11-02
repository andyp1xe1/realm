// Items: listing and search, notes and files, downloads, and multipart uploads for big files.

import {
  HttpError,
  KINDS,
  MAX_LIMIT,
  SCOPES,
  cleanName,
  guessMime,
  json,
  kindOf,
  need,
  newId,
  publicItem,
  type Ctx,
  type Env,
  type Item,
  type Upload,
} from "./common.ts";
import { consume } from "./realms.ts";

export async function list(ctx: Ctx): Promise<Response> {
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
  const { results } = await ctx.env.DB.prepare(sql)
    .bind(...args, limit + 1)
    .all<Item>();
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

export async function createFromBody(ctx: Ctx): Promise<Response> {
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
    name: null,
    mime: null,
    size: null,
    key: null,
    realm: ctx.realm!.id,
    src: ctx.src,
    created: Date.now(),
  };
  await insert(ctx.env, item);
  return item;
}

export async function putFile(
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
    id: newId(),
    kind: kindOf(type),
    text: caption || null,
    name,
    mime: type,
    size: obj?.size ?? null,
    key,
    realm: ctx.realm!.id,
    src: ctx.src,
    created: Date.now(),
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

export async function getItem(ctx: Ctx, id: string, perm: keyof typeof SCOPES): Promise<Item> {
  need(ctx, perm);
  const item = ctx.link?.selection
    ? await ctx.env.DB.prepare(
        "SELECT items.* FROM items JOIN link_items ON link_items.item = items.id AND link_items.token = ? WHERE items.id = ? AND items.realm = ?",
      )
        .bind(ctx.link.token, id, ctx.realm!.id)
        .first<Item>()
    : await ctx.env.DB.prepare("SELECT * FROM items WHERE id = ? AND realm = ?").bind(id, ctx.realm!.id).first<Item>();
  if (!item) throw new HttpError(404, "no such item");
  return item;
}

export async function patchItem(ctx: Ctx, id: string): Promise<Response> {
  const item = await getItem(ctx, id, "modify");
  const j = (await ctx.req.json().catch(() => ({}))) as { text?: string | null };
  if (j.text === undefined) throw new HttpError(400, "nothing to change");
  const next = j.text || null;
  if (!next && !item.key) throw new HttpError(400, "a note can't be empty");
  await ctx.env.DB.prepare("UPDATE items SET text = ? WHERE id = ?").bind(next, id).run();
  return json(publicItem({ ...item, text: next }));
}

export async function deleteItem(ctx: Ctx, id: string): Promise<Response> {
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

export async function raw(ctx: Ctx, id: string): Promise<Response> {
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
  if (mime !== "application/pdf")
    h.set("content-security-policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
  return h;
}

const INLINE =
  /^(image\/(png|jpeg|gif|webp|avif|heic|bmp)|audio\/[\w.+-]+|video\/[\w.+-]+|text\/plain|application\/pdf)$/;

/** Uploads must declare their size: R2 needs it to stream, and it's what byte budgets charge. */
function requireLength(req: Request): number {
  const n = Number(req.headers.get("content-length"));
  if (!req.headers.get("content-length") || !Number.isFinite(n) || n < 0)
    throw new HttpError(411, "content-length required");
  return n;
}

/** Charge an upload against the link's byte budget, if it has one. */
async function chargeBytes(ctx: Ctx, n: number) {
  if (!ctx.link || ctx.link.max_bytes == null) return;
  const r = await ctx.env.DB.prepare("UPDATE links SET bytes = bytes + ? WHERE token = ? AND bytes + ? <= max_bytes")
    .bind(n, ctx.link.token, n)
    .run();
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

export async function startUpload(ctx: Ctx): Promise<Response> {
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
  )
    .bind(id, key, mp.uploadId, name, mime, j.text || null, ctx.realm!.id, ctx.src, ctx.link?.token ?? null, Date.now())
    .run();
  return json({ upload: id }, 201);
}

async function loadUpload(ctx: Ctx, id: string): Promise<Upload> {
  const up = await ctx.env.DB.prepare("SELECT * FROM uploads WHERE id = ?").bind(id).first<Upload>();
  if (!up || up.realm !== ctx.realm!.id || (ctx.scope !== "owner" && up.owner !== ctx.link?.token)) {
    throw new HttpError(404, "no such upload");
  }
  return up;
}

export async function uploadPart(ctx: Ctx, id: string, n: string): Promise<Response> {
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

export async function completeUpload(ctx: Ctx, id: string): Promise<Response> {
  const up = await loadUpload(ctx, id);
  const j = (await ctx.req.json().catch(() => ({}))) as { parts?: { part: number; etag: string }[] };
  if (!j.parts?.length) throw new HttpError(400, "parts required");
  const mp = ctx.env.BUCKET.resumeMultipartUpload(up.key, up.r2id);
  const obj = await mp.complete(
    j.parts.sort((x, y) => x.part - y.part).map((p) => ({ partNumber: p.part, etag: p.etag })),
  );
  const item: Item = {
    id: newId(),
    kind: kindOf(up.mime),
    text: up.text,
    name: up.name,
    mime: up.mime,
    size: obj.size,
    key: up.key,
    realm: up.realm,
    src: up.src,
    created: Date.now(),
  };
  await ctx.env.DB.batch([ctx.env.DB.prepare("DELETE FROM uploads WHERE id = ?").bind(id), insertStmt(ctx.env, item)]);
  return json(publicItem(item), 201);
}

export async function abortUpload(ctx: Ctx, id: string): Promise<Response> {
  const up = await loadUpload(ctx, id);
  await ctx.env.BUCKET.resumeMultipartUpload(up.key, up.r2id)
    .abort()
    .catch(() => {});
  await ctx.env.DB.prepare("DELETE FROM uploads WHERE id = ?").bind(id).run();
  return json({ ok: true });
}
