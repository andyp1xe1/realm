// Builds what the app uploads to Cloudflare: dist/worker.js, the worker as one ES module,
// and dist/worker.json, the upload settings, read from wrangler.jsonc and src/common.ts.
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { experimental_readRawConfig } from "wrangler";
import { FORK, VERSION } from "../src/common.ts";

const root = join(import.meta.dirname, "..");
const dist = join(root, "dist");
const { rawConfig: config } = experimental_readRawConfig({ config: join(root, "wrangler.jsonc") });

// wrangler names the bundle after the entry file, so take the one .js file it writes.
const out = mkdtempSync(join(tmpdir(), "realm-worker-"));
execFileSync(join(root, "node_modules/.bin/wrangler"), ["deploy", "--dry-run", "--outdir", out], {
  cwd: root,
  stdio: ["ignore", "ignore", "inherit"],
});
const bundles = readdirSync(out).filter((f) => f.endsWith(".js"));
if (bundles.length !== 1) throw new Error(`expected one bundle from wrangler, got ${bundles.join(", ")}`);
// The source map isn't shipped, so drop the comment that points to it.
const code = readFileSync(join(out, bundles[0]), "utf8").replace(/\n\/\/# sourceMappingURL=.*\n?$/, "\n");
rmSync(out, { recursive: true });

const manifest = {
  ...(FORK === undefined ? {} : { fork: FORK }),
  version: VERSION,
  name: config.name,
  main_module: "worker.js",
  sha256: createHash("sha256").update(code).digest("hex"),
  compatibility_date: config.compatibility_date,
  r2_buckets: (config.r2_buckets ?? []).map(({ binding, bucket_name }) => ({ binding, bucket_name })),
  d1_databases: (config.d1_databases ?? []).map(({ binding, database_name }) => ({ binding, database_name })),
  crons: config.triggers?.crons ?? [],
};

rmSync(dist, { recursive: true, force: true });
mkdirSync(dist);
writeFileSync(join(dist, "worker.js"), code);
writeFileSync(join(dist, "worker.json"), JSON.stringify(manifest, null, 2) + "\n");
console.log(`dist/worker.js ${manifest.sha256}`);
