#!/usr/bin/env node
/**
 * 架构边界检查。
 *
 * 把《模块模板》里的约定变成可执行规则，避免“文档写了、代码没做”：
 * 1. 跨模块 import 必须先在 pom 中声明依赖（禁止靠传递依赖搭便车）；
 * 2. 控制器不得直接依赖其它模块的 Service（跨模块只走 common 契约）；
 * 3. 迁移只允许修改本模块拥有前缀的表；
 * 4. 迁移文件名必须为 V<12 或 14 位时间戳>__蛇形描述.sql；
 * 5. 已分层模块：api 与 application 不引用 infrastructure，domain 不依赖 Spring/JDBC。
 *
 * 用法：
 *   node scripts/checks/architecture/check.mjs             # 检查，新增违规时退出码 1
 *   node scripts/checks/architecture/check.mjs --report    # 打印全部违规（含基线）
 *   node scripts/checks/architecture/check.mjs --write-baseline  # 把当前违规冻结为基线
 *
 * 基线只用于存量债务：每条都必须在 baseline.json 写明原因。修复后基线自动变小，
 * 检查会提示“已修复”，提示维护者删除对应条目。
 */
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, "../../..");
const BASELINE = path.join(HERE, "baseline.json");

/** Java 包名到 Maven 模块的映射。 */
export const MODULE_BY_PACKAGE = {
  common: "mdop-common",
  security: "mdop-security",
  system: "mdop-system",
  masterdata: "mdop-master-data",
  purchasing: "mdop-purchasing",
  sales: "mdop-sales",
  manufacturing: "mdop-manufacturing",
  finance: "mdop-finance",
  wms: "mdop-wms",
  integration: "mdop-integration",
  test: "mdop-test-support",
};

/** 每个模块拥有的表前缀；跨前缀 DDL 视为越界。 */
export const OWNED_TABLE_PREFIXES = {
  "mdop-wms": ["wms_"],
  "mdop-purchasing": ["pur_"],
  "mdop-sales": ["sal_"],
  "mdop-manufacturing": ["mfg_"],
  "mdop-finance": ["fin_"],
  "mdop-master-data": ["mdm_"],
  "mdop-system": ["iam_", "sys_"],
  "mdop-integration": ["integration_"],
};

const MIGRATION_NAME = /^V(\d{12,14})__[a-z0-9]+(?:_[a-z0-9]+)*\.sql$/;
const JAVA_IMPORT = /^import\s+(?:static\s+)?io\.github\.acczff\.mdop\.([a-z0-9]+)\.([A-Za-z0-9_.]+);/gm;
const SERVICE_IMPORT = /^import\s+io\.github\.acczff\.mdop\.([a-z0-9]+)\.[A-Za-z0-9_.]*Service;/gm;

/** 跨模块 import 是否已在 pom 中声明。 */
export function importViolations(module, file, source) {
  const result = [];
  for (const match of source.matchAll(JAVA_IMPORT)) {
    const target = MODULE_BY_PACKAGE[match[1]];
    if (!target || target === module) continue;
    result.push({
      rule: "IMPORT",
      subject: `${module} → ${target}`,
      detail: `${file} 引用 ${match[1]}.${match[2]}`,
    });
  }
  return result;
}

/** 控制器直接依赖其它模块的 Service。 */
export function controllerLeaks(module, file, source) {
  // 只检查 HTTP 入口；内部适配器允许转调具体模块。
  if (!/\/api\/[^/]*Controller\.java$|\/[^/]*Controller\.java$/.test(file)) return [];
  const result = [];
  for (const match of source.matchAll(SERVICE_IMPORT)) {
    const target = MODULE_BY_PACKAGE[match[1]];
    if (!target || target === module) continue;
    result.push({
      rule: "CONTROLLER_LEAK",
      subject: `${file} → ${target}`,
      detail: "控制器直接依赖其它模块的 Service，应改走 common 契约",
    });
  }
  return result;
}

/** 迁移命名与表归属。 */
export function migrationViolations(module, file, source) {
  const result = [];
  const name = path.basename(file);
  if (!MIGRATION_NAME.test(name))
    result.push({
      rule: "MIGRATION_NAME",
      subject: file,
      detail: "迁移文件名应为 V<12 或 14 位时间戳>__蛇形描述.sql",
    });
  const prefixes = OWNED_TABLE_PREFIXES[module];
  if (!prefixes) return result;
  const ddl = /(?:CREATE|ALTER)\s+TABLE\s+`?([a-z0-9_]+)`?/gi;
  for (const match of source.matchAll(ddl)) {
    const table = match[1].toLowerCase();
    if (prefixes.some((prefix) => table.startsWith(prefix))) continue;
    result.push({
      rule: "DDL_OWNERSHIP",
      subject: `${module} → ${table}`,
      detail: `${file} 修改了不属于本模块前缀的表`,
    });
  }
  return result;
}

/** 已分层模块的层间依赖方向。 */
export function layerViolations(module, file, source) {
  const result = [];
  const normalized = file.replaceAll("\\", "/");
  if (normalized.includes("/api/")) {
    for (const match of source.matchAll(JAVA_IMPORT)) {
      if (MODULE_BY_PACKAGE[match[1]] !== module) continue;
      if (!match[2].includes("infrastructure")) continue;
      result.push({
        rule: "API_LAYER",
        subject: file,
        detail: `接口层引用了基础设施实现 ${match[2]}，应改为依赖应用层端口`,
      });
    }
  }
  if (normalized.includes("/application/")) {
    for (const match of source.matchAll(JAVA_IMPORT)) {
      if (MODULE_BY_PACKAGE[match[1]] !== module) continue;
      if (!match[2].includes("infrastructure")) continue;
      result.push({
        rule: "APPLICATION_LAYER",
        subject: file,
        detail: `应用层直接依赖基础设施实现 ${match[2]}，应改为依赖领域端口`,
      });
    }
  }
  if (normalized.includes("/domain/")) {
    const usesFramework = /^import\s+(?:static\s+)?(?:org\.springframework|java\.sql)\./m.test(source);
    if (usesFramework || /\bJdbcClient\b/.test(source))
      result.push({
        rule: "DOMAIN_LAYER",
        subject: file,
        detail: "领域层不得依赖 Spring、JDBC 或 JdbcClient",
      });
  }
  return result;
}

/**
 * 从 pom.xml 读取模块自有的 mdop 依赖。
 *
 * <p>先去掉 {@code <parent>} 块再取第一个 artifactId，否则父工程的
 * {@code mdop-backend} 会被误判为模块自身，导致自身 artifactId 被当成依赖。
 */
/** 唯一允许直接写库存余额表的实现。P0 收口完成后，其余写入必须改为经该入口。 */
export const BALANCE_WRITE_ALLOWLIST = ["/wms/inventory/ledger/InventoryLedger.java"];

const BALANCE_WRITE_SQL =
  /\b(INSERT\s+INTO|REPLACE\s+INTO|UPDATE|DELETE\s+FROM)\s+`?wms_inventory_balance`?/gi;

/**
 * 库存余额写入约束：除 InventoryLedger 外，主代码不得直接写 wms_inventory_balance。
 *
 * <p>余额是库存的唯一事实来源，写入必须同时满足「仓库→单据→余额」加锁顺序、
 * 流水与余额同事务、可用量不超现存量。散落的 UPDATE 无法被类型系统约束，
 * 只能靠静态规则拦住新增。
 *
 * <p>subject 带语句条数：同一文件新增写入会改变 subject，从而被基线比较发现。
 */
export function inventoryWriteViolations(file, source) {
  if (BALANCE_WRITE_ALLOWLIST.some((allowed) => file.endsWith(allowed))) return [];
  const code = source
    .replaceAll(/\/\*[\s\S]*?\*\//g, "")
    .replaceAll(/(^|[^:])\/\/.*$/gm, "$1");
  const count = [...code.matchAll(BALANCE_WRITE_SQL)].length;
  if (count === 0) return [];
  return [
    {
      rule: "BALANCE_WRITE",
      subject: `writes=${count}`,
      detail: `库存余额必须经 InventoryLedger 写入；本文件直接写表 ${count} 处`,
    },
  ];
}

export function declaredDependencies(pomXml) {
  const withoutParent = pomXml.replace(/<parent>[\s\S]*?<\/parent>/, "");
  const self = /<artifactId>([^<]+)<\/artifactId>/.exec(withoutParent)?.[1];
  const found = new Set();
  for (const match of withoutParent.matchAll(/<artifactId>(mdop-[a-z-]+)<\/artifactId>/g)) {
    if (match[1] !== self) found.add(match[1]);
  }
  return found;
}

/** 与基线比较：新增的是错误，消失的是可清理项。 */
export function compare(violations, baseline) {
  const key = (item) => `${item.rule}|${item.file ?? ""}|${item.subject}`;
  const frozen = new Set((baseline?.exceptions ?? []).map(key));
  const current = new Set(violations.map(key));
  return {
    added: violations.filter((item) => !frozen.has(key(item))),
    fixed: (baseline?.exceptions ?? []).filter((item) => !current.has(key(item))),
  };
}

function walk(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === "target" || entry.name === "node_modules") return [];
      return walk(full);
    }
    return [full];
  });
}

export function collect(root = ROOT) {
  const violations = [];
  const modules = fs
    .readdirSync(path.join(root, "backend"), { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name.startsWith("mdop-"))
    .map((entry) => entry.name);

  const poms = new Map();
  for (const module of modules) {
    const pom = path.join(root, "backend", module, "pom.xml");
    if (fs.existsSync(pom)) poms.set(module, declaredDependencies(fs.readFileSync(pom, "utf8")));
  }

  for (const file of walk(path.join(root, "backend"))) {
    const relative = path.relative(root, file).replaceAll("\\", "/");
    const module = relative.split("/")[1];

    if (relative.endsWith(".java") && relative.includes("/src/main/java/")) {
      const source = fs.readFileSync(file, "utf8");
      const found = [
        ...importViolations(module, relative, source),
        ...controllerLeaks(module, relative, source),
        ...layerViolations(module, relative, source),
        ...inventoryWriteViolations(relative, source),
      ];
      for (const item of found) {
        if (item.rule !== "IMPORT") {
          violations.push({ ...item, file: relative });
          continue;
        }
        const target = item.subject.split(" → ")[1];
        const declared = poms.get(module);
        if (!declared) continue;
        if (declared.has(target)) continue;
        violations.push({ ...item, file: relative });
      }
    }

    if (relative.endsWith(".sql") && relative.includes("/db/migration/")) {
      const source = fs.readFileSync(file, "utf8");
      for (const item of migrationViolations(module, relative, source))
        violations.push({ ...item, file: relative });
    }
  }
  return violations;
}

function readBaseline() {
  if (!fs.existsSync(BASELINE)) return { exceptions: [] };
  return JSON.parse(fs.readFileSync(BASELINE, "utf8"));
}

function report(violations) {
  const groups = new Map();
  for (const item of violations) {
    if (!groups.has(item.rule)) groups.set(item.rule, []);
    groups.get(item.rule).push(item);
  }
  for (const [rule, items] of [...groups].sort()) {
    console.log(`\n${rule} (${items.length})`);
    for (const item of items) console.log(`  - ${item.subject}: ${item.detail}`);
  }
}

function main() {
  const args = new Set(process.argv.slice(2));
  const violations = collect();
  const baseline = readBaseline();

  if (args.has("--write-baseline")) {
    const exceptions = violations
      .map((item) => ({ ...item, reason: "存量债务：待按模块模板逐项收敛" }))
      .sort((a, b) => `${a.rule}${a.file}${a.subject}`.localeCompare(`${b.rule}${b.file}${b.subject}`));
    fs.writeFileSync(BASELINE, `${JSON.stringify({ exceptions }, null, 2)}\n`, "utf8");
    console.log(`已写入基线：${exceptions.length} 条`);
    return;
  }

  if (args.has("--report")) {
    report(violations);
    console.log(`\n合计 ${violations.length} 条（其中基线冻结 ${baseline.exceptions?.length ?? 0} 条）`);
  }

  const { added, fixed } = compare(violations, baseline);
  if (fixed.length) {
    console.log(`\n已修复 ${fixed.length} 条，请从 baseline.json 删除对应条目：`);
    for (const item of fixed) console.log(`  - ${item.rule} ${item.subject}`);
  }
  if (added.length) {
    console.error(`\n发现 ${added.length} 条新增架构违规：`);
    for (const item of added) console.error(`  - [${item.rule}] ${item.subject}: ${item.detail}`);
    process.exitCode = 1;
    return;
  }
  console.log(`\n架构检查通过：无新增违规（基线 ${baseline.exceptions?.length ?? 0} 条）。`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  main();
}
