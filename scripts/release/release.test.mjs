import { test } from "node:test";
import assert from "node:assert/strict";
import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";
import { createHash } from "node:crypto";
import { stamp, assemble, verify } from "./release.mjs";
import { verifySnapshot, backup, restore } from "./database.mjs";

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mdop-release-test-"));
  t.after(() => {
    assert.equal(path.dirname(path.resolve(root)), path.resolve(os.tmpdir()));
    assert.ok(path.basename(root).startsWith("mdop-release-test-"));
    fs.rmSync(root, { recursive: true, force: true });
  });
  const put = (name, text) => {
    fs.mkdirSync(path.dirname(path.join(root, name)), { recursive: true });
    fs.writeFileSync(path.join(root, name), text);
  };
  for (const file of [
    "deploy/release/application-release.yml",
    "deploy/release/nginx.conf",
    "deploy/release/RUNBOOK.md",
    "scripts/audit-inventory.sql",
    "scripts/release/release.mjs",
    "scripts/release/database.mjs",
    "backend/module/src/main/resources/db/migration/system/V1__test.sql",
  ])
    put(file, "fixture");
  put("input.jar", "jar fixture");
  put("web/index.html", "<html>fixture</html>");
  put("web/assets/main.js", "fixture");
  const build = {
    buildCommit: "a".repeat(40),
    sourceCommit: "b".repeat(40),
    version: "0.1.0",
    backendVersion: "0.1.0-SNAPSHOT",
    runId: "12",
    runAttempt: "1",
  };
  const backend = path.join(root, "back"),
    frontend = path.join(root, "front"),
    output = path.join(root, "candidate");
  stamp("backend", path.join(root, "input.jar"), backend, build);
  stamp("frontend", path.join(root, "web"), frontend, build);
  return {
    root,
    put,
    build,
    backend,
    frontend,
    output,
    assemble: () => assemble(root, backend, frontend, output, build),
  };
}

test("matching verified components form a complete checksummed bundle", (t) => {
  const f = fixture(t);
  f.assemble();
  const result = verify(f.output, f.build.buildCommit);
  assert.equal(result.sourceCommit, f.build.sourceCommit);
  assert.equal(result.migrations.length, 1);
  assert.equal(
    fs.readFileSync(path.join(f.output, "backend/mdop.jar"), "utf8"),
    "jar fixture",
  );
});
for (const field of ["buildCommit", "sourceCommit", "runId", "runAttempt"]) {
  test(`rejects mixed component ${field}`, (t) => {
    const f = fixture(t);
    const file = path.join(f.frontend, "component.json"),
      value = JSON.parse(fs.readFileSync(file));
    value.build[field] = "different";
    fs.writeFileSync(file, JSON.stringify(value));
    assert.throws(f.assemble, /different commits or workflow runs/);
  });
}
for (const scenario of ["changed", "missing", "extra"]) {
  test(`rejects ${scenario} deployment payload`, (t) => {
    const f = fixture(t);
    f.assemble();
    const file = path.join(f.output, "frontend/index.html");
    if (scenario === "changed") fs.appendFileSync(file, "tampered");
    if (scenario === "missing") fs.unlinkSync(file);
    if (scenario === "extra")
      fs.writeFileSync(path.join(f.output, "unexpected.env"), "extra");
    assert.throws(
      () => verify(f.output),
      /Checksum mismatch|Missing or unexpected/,
    );
  });
}
test("refuses an unexpected commit, changed manifest or output overwrite", (t) => {
  const f = fixture(t);
  f.assemble();
  assert.throws(() => verify(f.output, "c".repeat(40)), /Unexpected build/);
  assert.throws(f.assemble, /already exists/);
  fs.appendFileSync(path.join(f.output, "manifest.json"), " ");
  assert.throws(() => verify(f.output), /Checksum mismatch/);
});
test("rejects a modified verified component before assembling", (t) => {
  const f = fixture(t);
  fs.appendFileSync(path.join(f.backend, "mdop.jar"), "modified");
  assert.throws(f.assemble, /Checksum mismatch/);
});
test("rejects path traversal entries", (t) => {
  const f = fixture(t);
  f.assemble();
  const file = path.join(f.output, "manifest.json"),
    value = JSON.parse(fs.readFileSync(file));
  value.files["../escape"] = "a".repeat(64);
  fs.writeFileSync(file, JSON.stringify(value));
  assert.throws(() => verify(f.output), /Invalid file/);
});
test("backup verification rejects damaged SQL and mismatched application", async (t) => {
  const f = fixture(t),
    directory = path.join(f.root, "snapshot");
  f.put("snapshot/database.sql", "SQL");
  const hash = (text) => createHash("sha256").update(text).digest("hex");
  f.put(
    "snapshot/snapshot.json",
    JSON.stringify({
      schema: 1,
      migrations: ["1"],
      dataSha256: hash("facts"),
      dumpSha256: hash("SQL"),
      applicationSha256: hash("jar fixture"),
    }),
  );
  await verifySnapshot(directory, path.join(f.root, "input.jar"));
  f.put("wrong.jar", "different");
  await assert.rejects(
    verifySnapshot(directory, path.join(f.root, "wrong.jar")),
    /Application does not match/,
  );
  f.put("snapshot/database.sql", "corrupt");
  await assert.rejects(
    verifySnapshot(directory, path.join(f.root, "input.jar")),
    /Backup checksum/,
  );
});
test("database operations require explicit stop-write and empty-target assertions", async () => {
  await assert.rejects(
    backup("", "", "", "", false),
    /Stop application writes/,
  );
  await assert.rejects(restore("", "", "", "", false), /empty database/);
});
