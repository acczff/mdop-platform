import { createHash } from "node:crypto";
import { spawn, execFileSync } from "node:child_process";
import * as fs from "node:fs";
import * as path from "node:path";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";

const fail = (message) => {
  throw new Error(message);
};
const digest = async (file) => {
  const hash = createHash("sha256");
  for await (const chunk of fs.createReadStream(file)) hash.update(chunk);
  return hash.digest("hex");
};
const client = 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec "$@"';
const dumpOptions = [
  "--single-transaction",
  "--skip-lock-tables",
  "--no-tablespaces",
  "--set-gtid-purged=OFF",
  "--column-statistics=0",
  "--hex-blob",
  "--default-character-set=utf8mb4",
  "--skip-comments",
  "--skip-dump-date",
  "--order-by-primary",
];

function argumentsFor(container, database, command, args = []) {
  if (
    !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(container) ||
    !/^[a-zA-Z0-9_]+$/.test(database)
  )
    fail("Invalid container/database name");
  // Credentials stay inside the container; no password is copied into host arguments or logs.
  return [
    "exec",
    "-i",
    container,
    "sh",
    "-c",
    client,
    "mdop-db",
    command,
    "-h127.0.0.1",
    ...args,
    database,
  ];
}

function sql(container, database, query) {
  const script =
    'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql -h127.0.0.1 -u"$MYSQL_USER" --batch --skip-column-names "$@"';
  const args = argumentsFor(container, database, "mysql");
  return execFileSync(
    "docker",
    [...args.slice(0, 5), script, "mdop-db", database, "-e", query],
    { encoding: "utf8", maxBuffer: 8 * 1024 * 1024 },
  ).trim();
}

async function transfer(
  container,
  database,
  filename,
  restoring = false,
  facts = false,
) {
  const command = restoring ? "mysql" : "mysqldump";
  const script = `export MYSQL_PWD="$MYSQL_PASSWORD"; exec ${command} -h127.0.0.1 -u"$MYSQL_USER" "$@"`;
  const options = restoring
    ? ["--default-character-set=utf8mb4"]
    : [
        ...dumpOptions,
        ...(facts
          ? [
              "--no-create-info",
              "--skip-extended-insert",
              "--skip-add-locks",
              "--skip-disable-keys",
              "--compact",
            ]
          : []),
      ];
  const args = argumentsFor(container, database, command);
  const child = spawn(
    "docker",
    [...args.slice(0, 5), script, "mdop-db", ...options, database],
    { stdio: ["pipe", "pipe", "pipe"], windowsHide: true },
  );
  let errors = "";
  child.stderr.on("data", (chunk) => {
    errors = (errors + chunk.toString()).slice(-4096);
  });
  const done = new Promise((resolve, reject) => {
    child.on("error", reject);
    child.on("close", (code) =>
      code === 0
        ? resolve()
        : reject(new Error(`Database command failed (${code}): ${errors}`)),
    );
  });
  const hash = createHash("sha256");
  let io;
  if (restoring) {
    child.stdout.resume();
    io = pipeline(fs.createReadStream(filename), child.stdin);
  } else {
    child.stdin.end();
    io = facts
      ? pipeline(child.stdout, hash)
      : pipeline(child.stdout, fs.createWriteStream(filename, { flags: "wx" }));
  }
  await Promise.all([done, io]);
  return facts ? hash.digest("hex") : undefined;
}

export async function verifySnapshot(directory, jar) {
  const metadata = JSON.parse(
    fs.readFileSync(path.join(directory, "snapshot.json"), "utf8"),
  );
  if (
    metadata.schema !== 1 ||
    !/^[0-9a-f]{64}$/.test(metadata.dataSha256) ||
    !Array.isArray(metadata.migrations)
  )
    fail("Invalid snapshot metadata");
  if (
    (await digest(path.join(directory, "database.sql"))) !== metadata.dumpSha256
  )
    fail("Backup checksum mismatch");
  if ((await digest(jar)) !== metadata.applicationSha256)
    fail("Application does not match this backup");
  return metadata;
}

export async function backup(
  container,
  database,
  jar,
  directory,
  writesStopped,
) {
  if (!writesStopped)
    fail(
      "Stop application writes and message consumers, then pass --writes-stopped",
    );
  if (fs.existsSync(directory)) fail("Snapshot directory already exists");
  const tables = sql(
    container,
    database,
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
  );
  if (Number(tables) < 1) fail("Source database is empty");
  const migrations = sql(
    container,
    database,
    "SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank",
  ).split("\n");
  const applicationSha256 = await digest(jar);
  fs.mkdirSync(directory, { recursive: true });
  const before = await transfer(container, database, undefined, false, true);
  await transfer(container, database, path.join(directory, "database.sql"));
  const after = await transfer(container, database, undefined, false, true);
  if (before !== after)
    fail(
      "Database changed during backup; snapshot is incomplete, stop all writers",
    );
  const result = {
    schema: 1,
    createdAt: new Date().toISOString(),
    database,
    tables: Number(tables),
    migrations,
    applicationSha256,
    dumpSha256: await digest(path.join(directory, "database.sql")),
    dataSha256: before,
  };
  fs.writeFileSync(
    path.join(directory, "snapshot.json"),
    JSON.stringify(result, null, 2) + "\n",
    { flag: "wx" },
  );
  return result;
}

export async function restore(
  container,
  database,
  jar,
  directory,
  emptyTarget,
) {
  if (!emptyTarget)
    fail("Create a separate empty database, then pass --empty-target");
  const metadata = await verifySnapshot(directory, jar);
  if (
    sql(
      container,
      database,
      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
    ) !== "0"
  )
    fail("Refusing to restore into a non-empty database");
  await transfer(
    container,
    database,
    path.join(directory, "database.sql"),
    true,
  );
  if (
    (await transfer(container, database, undefined, false, true)) !==
    metadata.dataSha256
  )
    fail("Restored data differs from snapshot; keep application stopped");
  return metadata;
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    const [command, ...args] = process.argv.slice(2);
    let result;
    if (command === "verify" && args.length === 2)
      result = await verifySnapshot(...args);
    else if (
      command === "backup" &&
      args.length === 5 &&
      args[4] === "--writes-stopped"
    )
      result = await backup(...args.slice(0, 4), true);
    else if (
      command === "restore" &&
      args.length === 5 &&
      args[4] === "--empty-target"
    )
      result = await restore(...args.slice(0, 4), true);
    else
      fail(
        "Usage: database.mjs backup CONTAINER DATABASE JAR DIRECTORY --writes-stopped | restore CONTAINER DATABASE JAR DIRECTORY --empty-target | verify DIRECTORY JAR",
      );
    console.log(
      JSON.stringify({
        command,
        database: result.database,
        migrations: result.migrations.length,
        tables: result.tables,
        verified: true,
      }),
    );
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
