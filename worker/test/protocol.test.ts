import { test } from "node:test";
import assert from "node:assert/strict";
import { VERSION } from "../src/common.ts";
import { recordings, replay } from "./protocol.ts";

test("VERSION is the newest protocol recorded", () => {
  const newest = recordings().at(-1)?.version;
  assert.equal(
    newest,
    VERSION,
    `VERSION in src/common.ts is ${VERSION}, the newest recording protocol/${newest}.json: run npm run record-protocol`,
  );
});

test("the worker answers as apps on its protocol expect", async () => {
  const recording = recordings().find((r) => r.version === VERSION);
  assert.ok(recording, `no protocol/${VERSION}.json: run npm run record-protocol`);
  const problems = await replay(recording);
  assert.deepEqual(
    problems,
    [],
    `This breaks apps that speak protocol ${VERSION}:\n${problems.join("\n")}\n` +
      `Keep them working, or bump VERSION in src/common.ts to ${VERSION + 1} and run npm run record-protocol.`,
  );
});
