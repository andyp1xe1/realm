// The worker against in-memory D1 and R2, owner-authed by a fixed token, and request helpers for tests.
import assert from "node:assert/strict";
import worker from "../src/index.ts";
import { mockD1, mockR2 } from "./mock.ts";

export const TOKEN = "secret-token";
export const O = "https://realm.test";

export function setup() {
  const env = { DB: mockD1(), BUCKET: mockR2(), REALM_TOKEN: TOKEN } as never;
  const call = (path: string, init: RequestInit & { auth?: boolean } = {}) => {
    const headers = new Headers(init.headers);
    if (init.auth !== false && path.startsWith("/api")) headers.set("authorization", `Bearer ${TOKEN}`);
    return worker.fetch(new Request(O + path, { ...init, headers, duplex: "half" } as RequestInit), env);
  };
  return { env, call };
}

type Call = ReturnType<typeof setup>["call"];

export async function mkLink(call: Call, body: object) {
  const r = await call("/api/links", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  assert.equal(r.status, 201);
  return r.json();
}

export const put = (call: Call, path: string, body: string, type?: string) =>
  call(path, {
    method: "PUT",
    body,
    headers: { "content-length": String(body.length), ...(type ? { "content-type": type } : {}) },
  });

export const post = (call: Call, path: string, body: object, method = "POST") =>
  call(path, { method, headers: { "content-type": "application/json" }, body: JSON.stringify(body) });

export const tick = () => new Promise((r) => setTimeout(r, 2)); // ids sort by millisecond
