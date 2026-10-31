// Minimal in-memory stand-ins for D1 (backed by node:sqlite) and R2, enough to exercise the worker.
import { DatabaseSync } from "node:sqlite";

class Stmt {
  db: DatabaseSync;
  sql: string;
  args: unknown[];
  constructor(db: DatabaseSync, sql: string, args: unknown[] = []) {
    this.db = db;
    this.sql = sql;
    this.args = args;
  }
  bind(...args: unknown[]) {
    return new Stmt(this.db, this.sql, args);
  }
  async all() {
    return {
      results: this.db
        .prepare(this.sql)
        .all(...(this.args as never[]))
        .map((r) => ({ ...r })),
    };
  }
  async first() {
    const r = this.db.prepare(this.sql).get(...(this.args as never[]));
    return r ? { ...r } : null;
  }
  async run() {
    const r = this.db.prepare(this.sql).run(...(this.args as never[]));
    return { meta: { changes: Number(r.changes) } };
  }
}

/** An empty database, like a fresh D1; the worker creates its tables on the first request. */
export function mockD1(db = new DatabaseSync(":memory:")) {
  return {
    db,
    prepare: (sql: string) => new Stmt(db, sql),
    // Like D1, a batch is one transaction.
    batch: async (stmts: Stmt[]) => {
      db.exec("BEGIN");
      try {
        const out = [];
        for (const s of stmts) out.push(await s.run());
        db.exec("COMMIT");
        return out;
      } catch (e) {
        db.exec("ROLLBACK");
        throw e;
      }
    },
  };
}

async function bytes(body: unknown): Promise<Uint8Array> {
  if (body instanceof ArrayBuffer) return new Uint8Array(body);
  if (body instanceof Uint8Array) return body;
  if (typeof body === "string") return new TextEncoder().encode(body);
  return new Uint8Array(await new Response(body as ReadableStream).arrayBuffer());
}

export function mockR2() {
  const objects = new Map<string, { data: Uint8Array; type?: string }>();
  const uploads = new Map<string, { key: string; type?: string; parts: Map<number, Uint8Array> }>();
  const meta = (key: string, o: { data: Uint8Array }) => ({ key, size: o.data.length, httpEtag: `"${key}"` });
  let n = 0;

  const resume = (key: string, uploadId: string) => ({
    uploadId,
    key,
    async uploadPart(part: number, body: unknown) {
      uploads.get(uploadId)!.parts.set(part, await bytes(body));
      return { partNumber: part, etag: `etag-${part}` };
    },
    async complete(parts: { partNumber: number; etag: string }[]) {
      const up = uploads.get(uploadId)!;
      const chunks = parts.map((p) => up.parts.get(p.partNumber)!);
      const data = new Uint8Array(chunks.reduce((a, c) => a + c.length, 0));
      let off = 0;
      for (const c of chunks) {
        data.set(c, off);
        off += c.length;
      }
      objects.set(key, { data, type: up.type });
      uploads.delete(uploadId);
      return meta(key, { data });
    },
    async abort() {
      uploads.delete(uploadId);
    },
  });

  return {
    objects,
    uploads,
    async put(key: string, body: unknown, opts?: { httpMetadata?: { contentType?: string } }) {
      const o = { data: await bytes(body), type: opts?.httpMetadata?.contentType };
      objects.set(key, o);
      return meta(key, o);
    },
    async head(key: string) {
      const o = objects.get(key);
      return o ? meta(key, o) : null;
    },
    async get(key: string, opts?: { range?: { offset?: number; length?: number; suffix?: number }; onlyIf?: Headers }) {
      const o = objects.get(key);
      if (!o) return null;
      if (opts?.onlyIf?.get("if-none-match") === meta(key, o).httpEtag) return meta(key, o); // no body
      let data = o.data;
      const r = opts?.range;
      if (r) {
        if ((r.offset ?? 0) >= o.data.length) throw new Error("InvalidRange");
        const start = r.offset ?? o.data.length - (r.suffix ?? 0);
        const end = r.length != null ? start + r.length : o.data.length;
        data = o.data.slice(start, end);
      }
      return { ...meta(key, o), body: new Response(data).body };
    },
    async delete(keys: string | string[]) {
      for (const k of [keys].flat()) objects.delete(k);
    },
    async createMultipartUpload(key: string, opts?: { httpMetadata?: { contentType?: string } }) {
      const id = `mp${++n}`;
      uploads.set(id, { key, type: opts?.httpMetadata?.contentType, parts: new Map() });
      return resume(key, id);
    },
    resumeMultipartUpload: resume,
  };
}
