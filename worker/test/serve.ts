// Run the worker on plain Node with in-memory D1/R2: `node test/serve.ts [port]` (token: dev)
import { createServer } from "node:http";
import { Readable } from "node:stream";
import worker from "../src/index.ts";
import { mockD1, mockR2 } from "./mock.ts";

const env = { DB: mockD1(), BUCKET: mockR2(), REALM_TOKEN: process.env.REALM_TOKEN || "dev" } as never;
const port = Number(process.argv[2] || 8787);

createServer(async (req, res) => {
  const hasBody = req.method !== "GET" && req.method !== "HEAD";
  const request = new Request(`http://localhost:${port}${req.url}`, {
    method: req.method,
    headers: req.headers as Record<string, string>,
    body: hasBody ? (Readable.toWeb(req) as ReadableStream) : undefined,
    duplex: "half",
  } as RequestInit);
  const r = await worker.fetch(request, env);
  res.writeHead(r.status, Object.fromEntries(r.headers));
  if (r.body) for await (const chunk of r.body as unknown as AsyncIterable<Uint8Array>) res.write(chunk);
  res.end();
}).listen(port, () => console.log(`realm dev server on http://localhost:${port} (token: dev)`));
