// Records what the worker answers the app's calls as protocol/<VERSION>.json, never over a recording it no longer keeps to.
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { VERSION } from "../src/common.ts";
import { RECORDINGS, type Recording, record, replay } from "../test/protocol.ts";

const file = join(RECORDINGS, `${VERSION}.json`);
if (existsSync(file)) {
  const problems = await replay(JSON.parse(readFileSync(file, "utf8")) as Recording);
  if (problems.length) {
    console.error(`The worker no longer answers as apps on protocol ${VERSION} expect:\n${problems.join("\n")}`);
    console.error(`Keep those working, or bump VERSION in src/common.ts to ${VERSION + 1} and record that.`);
    process.exit(1);
  }
}
writeFileSync(file, JSON.stringify(await record(VERSION), null, 2) + "\n");
console.log(`recorded ${file}`);
