// The calls app/core's ProtocolTest makes through the app's own client, in order, and a worker's answers held to a recording of them.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { setup } from "./setup.ts";

export type Sent = { method: string; path: string; type?: string; body?: unknown };
export type Got = { status: number; type: string; body: unknown };
export type Exchange = { name: string; request: Sent; response: Got };
export type Recording = { version: number; calls: Exchange[] };

type Answers = Record<string, any>;

const JSON_TYPE = "application/json";
const realm = (a: Answers) => `/api/realms/${a.createRealm.id}`;
const link = (a: Answers) => `/s/${a.createLink.token}`;

export const APP_CALLS: { name: string; send: (a: Answers) => Sent }[] = [
  { name: "info", send: () => ({ method: "GET", path: "/api/info" }) },
  { name: "realms", send: () => ({ method: "GET", path: "/api/realms" }) },
  {
    name: "createRealm",
    send: () => ({ method: "POST", path: "/api/realms", type: JSON_TYPE, body: { name: "Trips" } }),
  },
  {
    name: "renameRealm",
    send: (a) => ({ method: "PATCH", path: realm(a), type: JSON_TYPE, body: { name: "Travel" } }),
  },
  {
    name: "note",
    send: (a) => ({ method: "POST", path: `${realm(a)}/items`, type: JSON_TYPE, body: { text: "pack the tent" } }),
  },
  {
    name: "file",
    send: (a) => ({
      method: "PUT",
      path: `${realm(a)}/files/list.txt?text=packing`,
      type: "text/plain",
      body: "tent, stove",
    }),
  },
  {
    name: "startUpload",
    send: (a) => ({
      method: "POST",
      path: `${realm(a)}/uploads`,
      type: JSON_TYPE,
      body: { name: "map.txt", type: "text/plain" },
    }),
  },
  {
    name: "uploadPart",
    send: (a) => ({
      method: "PUT",
      path: `${realm(a)}/uploads/${a.startUpload.upload}/1`,
      type: "application/octet-stream",
      body: "north",
    }),
  },
  {
    name: "completeUpload",
    send: (a) => ({
      method: "POST",
      path: `${realm(a)}/uploads/${a.startUpload.upload}/complete`,
      type: JSON_TYPE,
      body: { parts: [{ part: 1, etag: a.uploadPart.etag }] },
    }),
  },
  {
    name: "startUploadAgain",
    send: (a) => ({
      method: "POST",
      path: `${realm(a)}/uploads`,
      type: JSON_TYPE,
      body: { name: "late.txt", type: "text/plain" },
    }),
  },
  {
    name: "abortUpload",
    send: (a) => ({ method: "DELETE", path: `${realm(a)}/uploads/${a.startUploadAgain.upload}` }),
  },
  { name: "page", send: (a) => ({ method: "GET", path: `${realm(a)}/items?limit=60` }) },
  { name: "search", send: (a) => ({ method: "GET", path: `${realm(a)}/items?q=tent&limit=60` }) },
  { name: "item", send: (a) => ({ method: "GET", path: `${realm(a)}/items/${a.note.id}` }) },
  {
    name: "edit",
    send: (a) => ({
      method: "PATCH",
      path: `${realm(a)}/items/${a.note.id}`,
      type: JSON_TYPE,
      body: { text: "pack the big tent" },
    }),
  },
  { name: "raw", send: (a) => ({ method: "GET", path: `${realm(a)}/items/${a.file.id}/raw` }) },
  {
    name: "createLink",
    send: (a) => ({
      method: "POST",
      path: `${realm(a)}/links`,
      type: JSON_TYPE,
      body: { scope: "full", label: "Ana" },
    }),
  },
  { name: "links", send: (a) => ({ method: "GET", path: `${realm(a)}/links` }) },
  { name: "linkInfo", send: (a) => ({ method: "GET", path: `${link(a)}/info` }) },
  { name: "linkPage", send: (a) => ({ method: "GET", path: `${link(a)}/items?limit=60` }) },
  {
    name: "linkNote",
    send: (a) => ({ method: "POST", path: `${link(a)}/items`, type: JSON_TYPE, body: { text: "bring snacks" } }),
  },
  { name: "revoke", send: (a) => ({ method: "DELETE", path: `${realm(a)}/links/${a.createLink.token}` }) },
  { name: "delete", send: (a) => ({ method: "DELETE", path: `${realm(a)}/items/${a.note.id}` }) },
  { name: "help", send: () => ({ method: "GET", path: "/api/" }) },
  { name: "deleteRealm", send: (a) => ({ method: "DELETE", path: realm(a) }) },
];

export const RECORDINGS = join(import.meta.dirname, "..", "protocol");

export function recordings(): Recording[] {
  return readdirSync(RECORDINGS)
    .filter((f) => /^\d+\.json$/.test(f))
    .map((f) => JSON.parse(readFileSync(join(RECORDINGS, f), "utf8")) as Recording)
    .sort((a, b) => a.version - b.version);
}

type Call = ReturnType<typeof setup>["call"];

async function exchange(call: Call, sent: Sent): Promise<Got> {
  const body =
    sent.body === undefined ? undefined : typeof sent.body === "string" ? sent.body : JSON.stringify(sent.body);
  const headers: Record<string, string> = {};
  if (sent.type) headers["content-type"] = sent.type;
  if (body !== undefined) headers["content-length"] = String(new TextEncoder().encode(body).length);
  const res = await call(sent.path, { method: sent.method, headers, body });
  const type = (res.headers.get("content-type") ?? "").split(";")[0].trim();
  const text = await res.text();
  return { status: res.status, type, body: type === JSON_TYPE ? JSON.parse(text) : text };
}

export async function record(version: number): Promise<Recording> {
  const { call } = setup();
  const answers: Answers = {};
  const calls: Exchange[] = [];
  for (const { name, send } of APP_CALLS) {
    const request = send(answers);
    const response = await exchange(call, request);
    answers[name] = response.body;
    calls.push({ name, request, response });
  }
  return { version, calls };
}

export async function replay(recording: Recording): Promise<string[]> {
  const { call } = setup();
  const ids = new Map<string, string>();
  const problems: string[] = [];
  for (const { name, request, response } of recording.calls) {
    const got = await exchange(call, swap(request, ids) as Sent);
    if (got.status !== response.status) problems.push(`${name}: status ${got.status}, recorded ${response.status}`);
    if (got.type !== response.type)
      problems.push(`${name}: type ${got.type || "none"}, recorded ${response.type || "none"}`);
    if (response.type === JSON_TYPE) {
      problems.push(...kept(response.body, got.body, name));
      learn(response.body, got.body, ids);
    }
  }
  return problems;
}

function kept(recorded: unknown, got: unknown, at: string): string[] {
  if (recorded === null) return [];
  if (kind(recorded) !== kind(got)) return [`${at}: ${kind(got)}, recorded ${kind(recorded)}`];
  if (Array.isArray(recorded)) {
    const g = got as unknown[];
    return recorded.flatMap((r, i) => (i < g.length ? kept(r, g[i], `${at}[${i}]`) : []));
  }
  if (kind(recorded) !== "object") return [];
  const g = got as Record<string, unknown>;
  return Object.entries(recorded as Record<string, unknown>).flatMap(([k, v]) =>
    k in g ? kept(v, g[k], `${at}.${k}`) : [`${at}.${k}: gone`],
  );
}

function kind(v: unknown): string {
  return v === null ? "null" : Array.isArray(v) ? "array" : typeof v;
}

function learn(recorded: unknown, got: unknown, ids: Map<string, string>) {
  if (typeof recorded === "string" && typeof got === "string" && recorded !== got) ids.set(recorded, got);
  else if (Array.isArray(recorded) && Array.isArray(got)) recorded.forEach((r, i) => learn(r, got[i], ids));
  else if (kind(recorded) === "object" && kind(got) === "object") {
    for (const [k, v] of Object.entries(recorded as Record<string, unknown>))
      learn(v, (got as Record<string, unknown>)[k], ids);
  }
}

function swap(v: unknown, ids: Map<string, string>): unknown {
  if (typeof v === "string") {
    if (ids.has(v)) return ids.get(v);
    return [...ids.keys()].sort((a, b) => b.length - a.length).reduce((s, k) => s.split(k).join(ids.get(k)!), v);
  }
  if (Array.isArray(v)) return v.map((x) => swap(x, ids));
  if (kind(v) === "object") return Object.fromEntries(Object.entries(v as object).map(([k, x]) => [k, swap(x, ids)]));
  return v;
}
