import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { join } from "node:path";

test("handlers in a realm can't get a context without one, and selection is a boolean", () => {
  const root = join(import.meta.dirname, "..");
  const r = spawnSync(join(root, "node_modules/.bin/tsc"), ["-p", "test/types"], { cwd: root, encoding: "utf8" });
  assert.equal(r.status, 0, r.stdout + r.stderr);
});
