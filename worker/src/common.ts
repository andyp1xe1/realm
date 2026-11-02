// Types, errors and small helpers shared across the worker.

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

/**
 * The GitHub "owner/repo" of a fork, so clients can tell its API from realm's and know where its releases are.
 * Set it when you fork; realm itself has none.
 */
export const FORK: string | undefined = undefined;

/** Bump with every change to the API, so clients can tell a deployment needs updating. */
export const VERSION = 3;

export type Scope = "owner" | "full" | "read" | "drop";

export interface Realm {
  id: string;
  name: string;
  created: number;
}

export interface Link {
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

export interface Item {
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

export interface Upload {
  id: string;
  key: string;
  r2id: string;
  name: string;
  mime: string;
  text: string | null;
  realm: string;
  src: string | null;
  owner: string | null;
  created: number;
}

export interface Ctx {
  req: Request;
  env: Env;
  url: URL;
  scope: Scope;
  link: Link | null;
  realm: Realm | null; // null on owner routes above any one realm
  base: string; // absolute URL prefix for this principal, e.g. https://x/s/abc
  src: string;
}

export class HttpError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export const SCOPES: Record<string, Scope[]> = {
  read: ["owner", "full", "read"],
  write: ["owner", "full", "drop"],
  modify: ["owner", "full"],
  owner: ["owner"],
};

export const MAX_LIMIT = 200;
export const KINDS = ["note", "link", "image", "audio", "video", "file"];

export function need(ctx: Ctx, perm: keyof typeof SCOPES) {
  if (!SCOPES[perm].includes(ctx.scope)) throw new HttpError(403, `this link can't ${perm}`);
}

export function publicItem(i: Item) {
  const { key: _key, ...rest } = i;
  return rest;
}

export function newId(): string {
  const t = Date.now().toString(36).padStart(9, "0");
  const r = crypto.getRandomValues(new Uint8Array(4));
  return t + Array.from(r, (b) => (b % 36).toString(36)).join("");
}

export function randomToken(): string {
  const b = crypto.getRandomValues(new Uint8Array(18));
  return btoa(String.fromCharCode(...b))
    .replace(/\+/g, "-")
    .replace(/\//g, "_");
}

export function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

export function cleanName(n: string): string {
  const s = n
    .split(/[\\/]/)
    .pop()!
    // oxlint-disable-next-line no-control-regex -- stripping control characters is the point
    .replace(/[\x00-\x1f]/g, "")
    .trim();
  return (s || "file").slice(0, 200);
}

export function kindOf(mime: string): string {
  if (mime.startsWith("image/")) return "image";
  if (mime.startsWith("audio/")) return "audio";
  if (mime.startsWith("video/")) return "video";
  return "file";
}

const MIME: Record<string, string> = {
  png: "image/png",
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  gif: "image/gif",
  webp: "image/webp",
  heic: "image/heic",
  svg: "image/svg+xml",
  mp3: "audio/mpeg",
  m4a: "audio/mp4",
  ogg: "audio/ogg",
  opus: "audio/ogg",
  wav: "audio/wav",
  mp4: "video/mp4",
  mov: "video/quicktime",
  webm: "video/webm",
  mkv: "video/x-matroska",
  pdf: "application/pdf",
  txt: "text/plain",
  md: "text/markdown",
  json: "application/json",
  zip: "application/zip",
  csv: "text/csv",
  html: "text/html",
};

export function guessMime(name: string): string {
  return MIME[name.split(".").pop()!.toLowerCase()] || "application/octet-stream";
}

export function wantsHtml(req: Request): boolean {
  return (req.headers.get("accept") || "").includes("text/html");
}

export function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

export function text(body: string, status = 200, type = "text/plain; charset=utf-8"): Response {
  return new Response(body, { status, headers: { "content-type": type } });
}

export function cors(res: Response): Response {
  const h = new Headers(res.headers);
  h.set("access-control-allow-origin", "*");
  h.set("access-control-allow-methods", "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS");
  h.set("access-control-allow-headers", "authorization, content-type, range, x-realm-source");
  h.set("access-control-expose-headers", "content-range, content-length, content-disposition, etag");
  return new Response(res.body, { status: res.status, statusText: res.statusText, headers: h });
}
