// realm — a personal dump. One Worker, one R2 bucket, one D1 database.
//
//   /api/...        owner access, `Authorization: Bearer $REALM_TOKEN`
//   /s/<token>/...  the same routes, limited by the link's scope
//
// Scopes: item (download one item) · drop (upload only) · read (list, search, download)
//         full (read + upload + edit/delete) · owner (everything, incl. managing links)

export interface Env {
  DB: D1Database;
  BUCKET: R2Bucket;
  REALM_TOKEN: string;
}

type Scope = "owner" | "full" | "read" | "drop" | "item";

interface Link {
  token: string;
  scope: Scope;
  item: string | null;
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
  src: string | null;
  created: number;
}

interface Ctx {
  req: Request;
  env: Env;
  url: URL;
  scope: Scope;
  link: Link | null;
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

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    if (req.method === "OPTIONS") return cors(new Response(null, { status: 204 }));
    try {
      return cors(await handle(req, env));
    } catch (e) {
      if (e instanceof HttpError) return cors(json({ error: e.message }, e.status));
      console.error(e);
      return cors(json({ error: "internal error" }, 500));
    }
  },

  /** Daily: abort multipart uploads nobody finished, forget dead links. */
  async scheduled(_event: unknown, env: Env): Promise<void> {
    const cutoff = Date.now() - 24 * 3600 * 1000;
    const { results } = await env.DB.prepare("SELECT * FROM uploads WHERE created < ?").bind(cutoff).all<Upload>();
    for (const up of results) {
      await env.BUCKET.resumeMultipartUpload(up.key, up.r2id).abort().catch(() => {});
      await env.DB.prepare("DELETE FROM uploads WHERE id = ?").bind(up.id).run();
    }
    await env.DB.prepare(
      "DELETE FROM links WHERE (expires IS NOT NULL AND expires < ?) OR (max_uses IS NOT NULL AND uses >= max_uses)",
    ).bind(Date.now()).run();
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
    if (!env.REALM_TOKEN || !safeEqual(token, env.REALM_TOKEN)) throw new HttpError(401, "bad token");
    const ctx: Ctx = { req, env, url, scope: "owner", link: null, base: `${url.origin}/api`, src: src || "api" };
    return route(ctx, parts.slice(1));
  }

  if (parts[0] === "s" && parts[1]) {
    const link = await env.DB.prepare("SELECT * FROM links WHERE token = ?").bind(parts[1]).first<Link>();
    if (!link || (link.expires && link.expires < Date.now())) throw new HttpError(404, "link not found or expired");
    if (link.max_uses != null && link.uses >= link.max_uses) throw new HttpError(410, "link used up");
    const ctx: Ctx = {
      req, env, url, scope: link.scope, link,
      base: `${url.origin}/s/${link.token}`,
      src: link.label ? `link:${link.label}` : "link",
    };
    return route(ctx, parts.slice(2));
  }

  if (parts.length === 0) return text("realm\n");
  throw new HttpError(404, "not found");
}

async function route(ctx: Ctx, p: string[]): Promise<Response> {
  const m = ctx.req.method;
  const [a, b, c] = p;

  if (!a) {
    if (m !== "GET" && m !== "HEAD") {
      if (ctx.scope === "item") throw new HttpError(405, "method not allowed");
      return createFromBody(ctx); // POST to the bare link = dump something
    }
    if (ctx.scope === "item") return raw(ctx, ctx.link!.item!);
    return wantsHtml(ctx.req) ? htmlPage(ctx) : text(agentHelp(ctx), 200, "text/markdown; charset=utf-8");
  }

  if (a === "raw" && !b && ctx.scope === "item") return raw(ctx, ctx.link!.item!);

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

  if (a === "links") {
    need(ctx, "owner");
    if (!b && m === "GET") return listLinks(ctx);
    if (!b && m === "POST") return createLink(ctx);
    if (b && m === "DELETE") {
      await ctx.env.DB.prepare("DELETE FROM links WHERE token = ?").bind(b).run();
      return json({ ok: true });
    }
  }

  throw new HttpError(404, "not found");
}

// ---------- items ----------

async function list(ctx: Ctx): Promise<Response> {
  need(ctx, "read");
  const q = ctx.url.searchParams;
  const limit = Math.min(Math.max(parseInt(q.get("limit") || "50") || 50, 1), MAX_LIMIT);
  const where: string[] = [];
  const args: unknown[] = [];

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
  const sql = `SELECT items.* FROM ${from} ${where.length ? "WHERE " + where.join(" AND ") : ""} ORDER BY items.id ${order} LIMIT ?`;
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
    src: ctx.src,
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
    size: obj?.size ?? null, key, src: ctx.src, created: Date.now(),
  };
  await insert(ctx.env, item);
  return item;
}

async function insert(env: Env, i: Item) {
  await env.DB.prepare(
    "INSERT INTO items (id, kind, text, name, mime, size, key, src, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
  ).bind(i.id, i.kind, i.text, i.name, i.mime, i.size, i.key ?? null, i.src, i.created).run();
}

async function getItem(ctx: Ctx, id: string, perm: keyof typeof SCOPES): Promise<Item> {
  if (ctx.scope === "item") {
    if (perm !== "read" || ctx.link!.item !== id) throw new HttpError(403, "forbidden");
  } else {
    need(ctx, perm);
  }
  const item = await ctx.env.DB.prepare("SELECT * FROM items WHERE id = ?").bind(id).first<Item>();
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
  await ctx.env.DB.batch([
    ctx.env.DB.prepare("DELETE FROM items WHERE id = ?").bind(id),
    ctx.env.DB.prepare("DELETE FROM links WHERE item = ?").bind(id),
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
  text: string | null; src: string | null; owner: string | null; created: number;
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
    "INSERT INTO uploads (id, key, r2id, name, mime, text, src, owner, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
  ).bind(id, key, mp.uploadId, name, mime, j.text || null, ctx.src, ctx.link?.token ?? null, Date.now()).run();
  return json({ upload: id }, 201);
}

async function loadUpload(ctx: Ctx, id: string): Promise<Upload> {
  const up = await ctx.env.DB.prepare("SELECT * FROM uploads WHERE id = ?").bind(id).first<Upload>();
  if (!up || (ctx.scope !== "owner" && up.owner !== ctx.link?.token)) throw new HttpError(404, "no such upload");
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
    size: obj.size, key: up.key, src: up.src, created: Date.now(),
  };
  await ctx.env.DB.batch([
    ctx.env.DB.prepare("DELETE FROM uploads WHERE id = ?").bind(id),
    ctx.env.DB.prepare(
      "INSERT INTO items (id, kind, text, name, mime, size, key, src, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
    ).bind(item.id, item.kind, item.text, item.name, item.mime, item.size, item.key, item.src, item.created),
  ]);
  return json(publicItem(item), 201);
}

async function abortUpload(ctx: Ctx, id: string): Promise<Response> {
  const up = await loadUpload(ctx, id);
  await ctx.env.BUCKET.resumeMultipartUpload(up.key, up.r2id).abort().catch(() => {});
  await ctx.env.DB.prepare("DELETE FROM uploads WHERE id = ?").bind(id).run();
  return json({ ok: true });
}

// ---------- links ----------

async function createLink(ctx: Ctx): Promise<Response> {
  const j = (await ctx.req.json().catch(() => ({}))) as {
    scope?: Scope; item?: string; ttl?: number; uses?: number; label?: string; max_bytes?: number;
  };
  const scope = j.scope || (j.item ? "item" : "drop");
  if (!["item", "drop", "read", "full"].includes(scope)) throw new HttpError(400, "scope must be item|drop|read|full");
  if (scope === "item") {
    if (!j.item) throw new HttpError(400, "item required");
    const exists = await ctx.env.DB.prepare("SELECT id FROM items WHERE id = ?").bind(j.item).first();
    if (!exists) throw new HttpError(404, "no such item");
  }
  const link: Link = {
    token: randomToken(),
    scope,
    item: scope === "item" ? j.item! : null,
    label: j.label?.slice(0, 40) || null,
    expires: j.ttl && j.ttl > 0 ? Date.now() + j.ttl * 1000 : null,
    max_uses: j.uses && j.uses > 0 ? j.uses : null,
    uses: 0,
    max_bytes: j.max_bytes && j.max_bytes > 0 ? j.max_bytes : null,
    bytes: 0,
    created: Date.now(),
  };
  await ctx.env.DB.prepare(
    "INSERT INTO links (token, scope, item, label, expires, max_uses, uses, max_bytes, bytes, created) VALUES (?, ?, ?, ?, ?, ?, 0, ?, 0, ?)",
  ).bind(link.token, link.scope, link.item, link.label, link.expires, link.max_uses, link.max_bytes, link.created).run();
  return json(publicLink(ctx, link), 201);
}

async function listLinks(ctx: Ctx): Promise<Response> {
  const { results } = await ctx.env.DB.prepare(
    "SELECT * FROM links WHERE expires IS NULL OR expires > ? ORDER BY created DESC",
  ).bind(Date.now()).all<Link>();
  return json({ links: results.map((l) => publicLink(ctx, l)) });
}

function publicLink(ctx: Ctx, l: Link) {
  return { ...l, url: `${ctx.url.origin}/s/${l.token}` };
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
  const B = ctx.base;
  const auth = ctx.scope === "owner" ? ` -H "Authorization: Bearer $REALM_TOKEN"` : "";
  const exp = ctx.link?.expires ? `Expires ${new Date(ctx.link.expires).toISOString()}.` : "Does not expire.";
  const uses = ctx.link?.max_uses != null ? ` ${ctx.link.max_uses - ctx.link.uses} uses left.` : "";
  const out = [
    `# realm — ${ctx.scope} access`,
    ``,
    `realm is a personal dump of notes and files. ${ctx.link ? exp + uses : ""}`,
    `Base URL: ${B}`,
    `No login needed beyond this URL${auth ? " plus the bearer token" : ""}. All responses are JSON unless noted.`,
    ``,
  ];
  const item = `Item: {id, kind: note|link|image|audio|video|file, text, name, mime, size, src, created(epoch ms)}`;
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
      `scope: item (needs "item": id) | drop | read | full · ttl seconds (omit = forever) · uses (omit = unlimited) · max_bytes upload budget`,
      `curl -s${auth} "${B}/links"  ·  curl -s${auth} -X DELETE "${B}/links/<token>"`,
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
  const sub = ctx.scope === "drop" ? "Drop notes and files here" : ctx.scope === "read" ? "Read-only" : "Shared dump";
  const exp = ctx.link?.expires ? ` · link expires <time data-t="${ctx.link.expires}"></time>` : "";

  const body = `<!doctype html><html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>realm</title><style>
:root{color-scheme:dark;--bg:#0e0e11;--card:#18181d;--card2:#1f1f26;--line:#2a2a33;--text:#e7e7ea;--mute:#8a8a96;--a:#9d8cff;--on-a:#0e0e11;--tile:#2a2547;--on-tile:#e4deff;--err:#ff7a85}
@media (prefers-color-scheme:light){:root{color-scheme:light;--bg:#fafafb;--card:#f1f1f4;--card2:#eaeaee;--line:#dadae0;--text:#1b1b1f;--mute:#6b6b76;--a:#5b48e8;--on-a:#fff;--tile:#e8e4ff;--on-tile:#1e1650;--err:#d93a4a}}
*{box-sizing:border-box}html{background:var(--bg)}
body{font:15px/22px system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:var(--text);max-width:680px;margin:0 auto;padding:40px 16px 64px}
svg{width:22px;height:22px;fill:currentColor;flex:none}
header{margin:0 4px 20px}h1{display:flex;align-items:center;gap:9px;margin:0;font-size:20px;font-weight:600;letter-spacing:.02em}
h1:before{content:"";width:9px;height:9px;border-radius:50%;background:var(--a)}header p{margin:4px 0 0;color:var(--mute);font-size:13px}
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
<header><h1>realm</h1><p>${sub}${exp}</p></header>${composer}${feed}
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
