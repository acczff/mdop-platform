import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import { fileURLToPath } from "node:url";

const sha = (bytes) => createHash("sha256").update(bytes).digest("hex");
const read = (file) => JSON.parse(fs.readFileSync(file, "utf8"));
const write = (file, value) =>
  fs.writeFileSync(file, JSON.stringify(value, null, 2) + "\n");
const fail = (message) => {
  throw new Error(message);
};
const safe = (name) =>
  typeof name === "string" &&
  /^[a-zA-Z0-9_./\-\u3400-\u9fff]+$/.test(name) &&
  !name.startsWith("/") &&
  name.split("/").every((s) => s && s !== "." && s !== "..");

export function files(root, prefix = "") {
  return fs
    .readdirSync(path.join(root, prefix), { withFileTypes: true })
    .flatMap((entry) => {
      const name = prefix + entry.name;
      if (!safe(name) || entry.isSymbolicLink()) fail(`Unsafe file: ${name}`);
      if (entry.isDirectory()) return files(root, name + "/");
      if (!entry.isFile()) fail(`Not a regular file: ${name}`);
      return [name];
    })
    .sort();
}

function hashes(root, names = files(root)) {
  return Object.fromEntries(
    names.map((name) => [name, sha(fs.readFileSync(path.join(root, name)))]),
  );
}

function checkFiles(root, expected, excluded = []) {
  if (!expected || typeof expected !== "object" || Array.isArray(expected))
    fail("Invalid file map");
  const names = Object.keys(expected).sort();
  if (
    !names.length ||
    names.some((name) => !safe(name) || !/^[0-9a-f]{64}$/.test(expected[name]))
  )
    fail("Invalid file entry");
  if (
    JSON.stringify(files(root).filter((name) => !excluded.includes(name))) !==
    JSON.stringify(names)
  )
    fail("Missing or unexpected files");
  for (const [name, digest] of Object.entries(expected)) {
    if (sha(fs.readFileSync(path.join(root, name))) !== digest)
      fail(`Checksum mismatch: ${name}`);
  }
}

function copy(source, destination) {
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.copyFileSync(source, destination);
}

function metadata(root) {
  const git = (...args) =>
    execFileSync("git", ["-C", root, ...args], { encoding: "utf8" }).trim();
  if (git("status", "--porcelain", "--untracked-files=no"))
    fail("Commit tracked changes before stamping a release");
  const buildCommit = git("rev-parse", "HEAD");
  const sourceCommit = process.env.MDOP_SOURCE_COMMIT || buildCommit;
  if (!/^[0-9a-f]{40}$/.test(sourceCommit)) fail("Invalid source commit");
  return {
    buildCommit,
    sourceCommit,
    version: read(path.join(root, "frontend/package.json")).version,
    backendVersion: fs
      .readFileSync(path.join(root, "backend/mdop-boot/pom.xml"), "utf8")
      .match(/<parent>[\s\S]*?<version>([^<]+)<\/version>/)[1],
    runId: process.env.GITHUB_RUN_ID || "local",
    runAttempt: process.env.GITHUB_RUN_ATTEMPT || "1",
  };
}

export function stamp(kind, input, output, build) {
  if (!["backend", "frontend"].includes(kind)) fail("Unknown component");
  if (fs.existsSync(output)) fail("Output already exists");
  fs.mkdirSync(output, { recursive: true });
  if (kind === "backend") {
    if (!fs.lstatSync(input).isFile() || fs.lstatSync(input).isSymbolicLink())
      fail("Expected a regular JAR");
    copy(input, path.join(output, "mdop.jar"));
  } else {
    const names = files(input);
    if (
      !names.includes("index.html") ||
      !names.some((name) => name.startsWith("assets/"))
    )
      fail("Missing frontend build");
    for (const name of names)
      copy(path.join(input, name), path.join(output, name));
  }
  write(path.join(output, "component.json"), {
    schema: 1,
    kind,
    build,
    files: hashes(output),
  });
}

function component(root, kind) {
  const result = read(path.join(root, "component.json"));
  if (result.schema !== 1 || result.kind !== kind) fail("Unexpected component");
  checkFiles(root, result.files, ["component.json"]);
  return result;
}

export function assemble(root, backend, frontend, output, build) {
  const parts = [
    [backend, component(backend, "backend")],
    [frontend, component(frontend, "frontend")],
  ];
  for (const [, part] of parts) {
    if (JSON.stringify(part.build) !== JSON.stringify(build))
      fail("Components come from different commits or workflow runs");
  }
  if (fs.existsSync(output)) fail("Output already exists");
  fs.mkdirSync(output, { recursive: true });
  for (const [directory, part] of parts) {
    for (const name of Object.keys(part.files))
      copy(path.join(directory, name), path.join(output, part.kind, name));
  }
  const resources = {
    "deploy/release/application-release.yml": "config/application-release.yml",
    "deploy/release/nginx.conf": "config/nginx.conf",
    "deploy/release/RUNBOOK.md": "RUNBOOK.md",
    "scripts/audit-inventory.sql": "tools/audit-inventory.sql",
    "scripts/release/release.mjs": "tools/release.mjs",
    "scripts/release/database.mjs": "tools/database.mjs",
  };
  for (const [source, target] of Object.entries(resources))
    copy(path.join(root, source), path.join(output, target));
  const migrations = fs
    .globSync("*/src/main/resources/db/migration/*/V*.sql", {
      cwd: path.join(root, "backend"),
    })
    .map((name) => name.replaceAll("\\", "/"));
  const migrationList = migrations
    .map((name) => ({
      name: path.posix.basename(name),
      sha256: sha(fs.readFileSync(path.join(root, "backend", name))),
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
  if (
    !migrationList.length ||
    new Set(migrationList.map((m) => m.name)).size !== migrationList.length
  )
    fail("Invalid migrations");
  write(path.join(output, "frontend/release.json"), build);
  write(path.join(output, "manifest.json"), {
    schema: 1,
    ...build,
    createdAt: new Date().toISOString(),
    migrations: migrationList,
    files: hashes(output),
  });
  const sums = hashes(output);
  fs.writeFileSync(
    path.join(output, "SHA256SUMS"),
    Object.entries(sums)
      .map(([name, digest]) => `${digest}  ${name}`)
      .join("\n") + "\n",
  );
  verify(output, build.buildCommit);
}

export function verify(root, expectedCommit) {
  const manifest = read(path.join(root, "manifest.json"));
  if (
    manifest.schema !== 1 ||
    !/^[0-9a-f]{40}$/.test(manifest.buildCommit) ||
    !/^[0-9a-f]{40}$/.test(manifest.sourceCommit)
  )
    fail("Invalid release manifest");
  if (expectedCommit && manifest.buildCommit !== expectedCommit)
    fail("Unexpected build commit");
  checkFiles(root, manifest.files, ["manifest.json", "SHA256SUMS"]);
  const sums = {};
  for (const line of fs
    .readFileSync(path.join(root, "SHA256SUMS"), "utf8")
    .trimEnd()
    .split("\n")) {
    const match = /^([0-9a-f]{64})  (.+)$/.exec(line);
    if (!match || !safe(match[2]) || Object.hasOwn(sums, match[2]))
      fail("Invalid SHA256SUMS");
    sums[match[2]] = match[1];
  }
  checkFiles(root, sums, ["SHA256SUMS"]);
  if (
    !manifest.files["backend/mdop.jar"] ||
    !manifest.files["frontend/index.html"] ||
    !Array.isArray(manifest.migrations) ||
    !manifest.migrations.length
  )
    fail("Incomplete release");
  return manifest;
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    const [command, ...args] = process.argv.slice(2);
    if (command === "stamp" && args.length === 4)
      stamp(args[1], args[2], args[3], metadata(args[0]));
    else if (command === "assemble" && args.length === 4)
      assemble(...args, metadata(args[0]));
    else if (command === "verify" && (args.length === 1 || args.length === 2)) {
      const result = verify(...args);
      console.log(
        JSON.stringify({
          buildCommit: result.buildCommit,
          sourceCommit: result.sourceCommit,
          migrations: result.migrations.length,
          files: Object.keys(result.files).length,
        }),
      );
    } else
      fail(
        "Usage: release.mjs stamp ROOT backend|frontend INPUT OUTPUT | assemble ROOT BACKEND FRONTEND OUTPUT | verify DIRECTORY [EXPECTED_BUILD_COMMIT]",
      );
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
