import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  collect,
  compare,
  controllerLeaks,
  declaredDependencies,
  dmlOwnershipViolations,
  importViolations,
  layerViolations,
  inventoryWriteViolations,
  migrationViolations,
} from "./check.mjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");

test("importViolations 识别跨模块引用", () => {
  const source = "import io.github.acczff.mdop.wms.receiving.application.ReceivingService;";
  const found = importViolations("mdop-purchasing", "A.java", source);
  assert.equal(found.length, 1);
  assert.equal(found[0].subject, "mdop-purchasing → mdop-wms");
});

test("importViolations 忽略本模块与 JDK 引用", () => {
  const source = [
    "import io.github.acczff.mdop.purchasing.application.PurchaseService;",
    "import java.util.List;",
  ].join("\n");
  assert.deepEqual(importViolations("mdop-purchasing", "A.java", source), []);
});

test("controllerLeaks 只对控制器生效", () => {
  const source = "import io.github.acczff.mdop.wms.receiving.application.ReceivingService;";
  assert.equal(controllerLeaks("mdop-purchasing", "purchasing/api/PurchaseController.java", source).length, 1);
  assert.equal(controllerLeaks("mdop-purchasing", "purchasing/infrastructure/Adapter.java", source).length, 0);
});

test("controllerLeaks 忽略其它模块的适配器", () => {
  const source = "import io.github.acczff.mdop.integration.purchasing.PurchaseReceivingAdapter;";
  assert.deepEqual(controllerLeaks("mdop-purchasing", "purchasing/api/PurchaseController.java", source), []);
});

test("declaredDependencies 排除自身", () => {
  const pom = "<artifactId>mdop-wms</artifactId><artifactId>mdop-common</artifactId><artifactId>mdop-wms</artifactId>";
  assert.deepEqual([...declaredDependencies(pom)], ["mdop-common"]);
});

test("declaredDependencies 不被父工程 artifactId 误导", () => {
  const pom = [
    "<project>",
    "  <parent><groupId>io.github.acczff.mdop</groupId><artifactId>mdop-backend</artifactId></parent>",
    "  <artifactId>mdop-sales</artifactId>",
    "  <dependencies><dependency><artifactId>mdop-common</artifactId></dependency></dependencies>",
    "</project>",
  ].join("");
  const declared = [...declaredDependencies(pom)];
  assert.deepEqual(declared, ["mdop-common"]);
  assert.ok(!declared.includes("mdop-sales"), "自身不应出现在依赖集合");
  assert.ok(!declared.includes("mdop-backend"), "父工程不应出现在依赖集合");
});

test("migrationViolations 允许本域建表", () => {
  const source = "CREATE TABLE wms_receipt (id BIGINT);";
  assert.deepEqual(migrationViolations("mdop-wms", "wms/V202610050002__receiving_core.sql", source), []);
});

test("migrationViolations 拦截跨域 DDL", () => {
  const source = "ALTER TABLE wms_receipt ADD COLUMN x INT;";
  const found = migrationViolations("mdop-manufacturing", "manufacturing/V202610090001__x.sql", source);
  assert.equal(found.length, 1);
  assert.equal(found[0].rule, "DDL_OWNERSHIP");
});

test("migrationViolations 检查命名格式", () => {
  const found = migrationViolations("mdop-wms", "wms/V1__bad.sql", "CREATE TABLE wms_x (id BIGINT);");
  assert.equal(found.length, 1);
  assert.equal(found[0].rule, "MIGRATION_NAME");
});

test("layerViolations 拦截接口层引用基础设施", () => {
  const source = "import io.github.acczff.mdop.wms.receiving.infrastructure.JdbcReceivingQueries;";
  const found = layerViolations("mdop-wms", "backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/receiving/api/A.java", source);
  assert.equal(found.length, 1);
  assert.equal(found[0].rule, "API_LAYER");
});

test("layerViolations 拦截领域层依赖框架", () => {
  const source = "import org.springframework.stereotype.Service;";
  const found = layerViolations(
    "mdop-purchasing",
    "backend/mdop-purchasing/src/main/java/io/github/acczff/mdop/purchasing/domain/A.java",
    source,
  );
  assert.equal(found.length, 1);
  assert.equal(found[0].rule, "DOMAIN_LAYER");
});

test("layerViolations 拦截应用层引用基础设施", () => {
  const source = "import io.github.acczff.mdop.wms.receiving.infrastructure.JdbcArrivalLock;";
  const found = layerViolations(
    "mdop-wms",
    "backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/receiving/application/A.java",
    source,
  );
  assert.equal(found.length, 1);
  assert.equal(found[0].rule, "APPLICATION_LAYER");
});

test("layerViolations 放行应用层引用 Spring", () => {
  const source = "import org.springframework.stereotype.Service;";
  const found = layerViolations(
    "mdop-purchasing",
    "backend/mdop-purchasing/src/main/java/io/github/acczff/mdop/purchasing/application/A.java",
    source,
  );
  assert.deepEqual(found, []);
});

test("compare 区分新增与已修复", () => {
  const a = { rule: "IMPORT", file: "A.java", subject: "a" };
  const b = { rule: "IMPORT", file: "B.java", subject: "b" };
  assert.deepEqual(compare([a], { exceptions: [b] }), { added: [a], fixed: [b] });
});

test("当前仓库无新增架构违规", () => {
  const violations = collect(ROOT);
  const baseline = JSON.parse(fs.readFileSync(path.join(ROOT, "scripts/checks/architecture/baseline.json"), "utf8"));
  const { added } = compare(violations, baseline);
  assert.deepEqual(added, []);
});

test("inventoryWriteViolations 拦截余额表直写", () => {
  const source = 'db.sql("UPDATE wms_inventory_balance SET on_hand_qty=? WHERE stock_key=?")';
  const found = inventoryWriteViolations("backend/mdop-wms/src/main/java/A.java", source);
  assert.equal(found.length, 1);
  assert.equal(found[0].rule, "BALANCE_WRITE");
  assert.equal(found[0].subject, "writes=1");
});

test("inventoryWriteViolations 放行唯一写入口", () => {
  const source = 'db.sql("UPDATE wms_inventory_balance SET on_hand_qty=?")';
  assert.deepEqual(
    inventoryWriteViolations(
      "backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/ledger/InventoryLedger.java",
      source,
    ),
    [],
  );
});

test("inventoryWriteViolations 忽略查询与注释", () => {
  const source = [
    'db.sql("SELECT on_hand_qty FROM wms_inventory_balance WHERE id=?")',
    "// UPDATE wms_inventory_balance 说明文字",
    "/* INSERT INTO wms_inventory_balance */",
  ].join("\n");
  assert.deepEqual(inventoryWriteViolations("backend/mdop-wms/src/main/java/B.java", source), []);
});

test("inventoryWriteViolations 按文件聚合并带上条数", () => {
  const source = [
    'db.sql("INSERT INTO wms_inventory_balance (id) VALUES (1)")',
    'db.sql("UPDATE wms_inventory_balance SET on_hand_qty=0")',
  ].join("\n");
  const found = inventoryWriteViolations("backend/mdop-wms/src/main/java/C.java", source);
  assert.equal(found.length, 1);
  assert.equal(found[0].subject, "writes=2");
});

test("dmlOwnershipViolations 允许本模块前缀并拦截跨模块 SQL", () => {
  const own = 'db.sql("SELECT * FROM wms_inventory_balance WHERE id=?")';
  assert.deepEqual(
    dmlOwnershipViolations("mdop-wms", "backend/mdop-wms/src/main/java/A.java", own),
    [],
  );

  const cross =
    'db.sql("""SELECT f.* FROM wms_finished_receipt f JOIN mfg_completion c ON c.id=f.id""")';
  const found = dmlOwnershipViolations(
    "mdop-wms",
    "backend/mdop-wms/src/main/java/FinishedGoodsService.java",
    cross,
  );
  assert.equal(found.length, 1);
  assert.equal(found[0].rule, "DML_OWNERSHIP");
  assert.equal(found[0].subject, "mdop-wms → mfg_completion");
});

test("dmlOwnershipViolations 忽略注释与非 SQL 文本", () => {
  const source = [
    "// SELECT * FROM mfg_completion",
    "/* UPDATE pur_document SET status='X' */",
    'String msg = "mfg_completion";',
  ].join("\n");
  assert.deepEqual(
    dmlOwnershipViolations("mdop-wms", "backend/mdop-wms/src/main/java/B.java", source),
    [],
  );
});
