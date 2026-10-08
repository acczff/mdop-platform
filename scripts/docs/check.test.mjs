import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { anchors, checkLinks, sourceIndex } from "./check.mjs";

test("heading anchors preserve Chinese text and resolve duplicates", () => {
  assert.deepEqual(
    [...anchors("# 文档 `API`\n## 文档 API\n## 1. 初次运行\n")],
    ["文档-api", "文档-api-1", "1-初次运行"],
  );
});

test("code examples do not contribute headings or broken links", () => {
  assert.deepEqual(
    [...anchors("# Start\n```md\n# Example\n[ignored](absent.md)\n```\n")],
    ["start"],
  );
});

test("source navigation includes actual module dependencies, test scope and routes", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mdop-index-"));
  try {
    const pom = "backend/mdop-boot/pom.xml";
    const router = "frontend/apps/admin/src/router/index.ts";
    fs.mkdirSync(path.dirname(path.join(root, pom)), { recursive: true });
    fs.mkdirSync(path.dirname(path.join(root, router)), { recursive: true });
    fs.writeFileSync(
      path.join(root, pom),
      "<dependencies><dependency><groupId>io.github.acczff.mdop</groupId><artifactId>mdop-security</artifactId></dependency><dependency><groupId>${project.groupId}</groupId><artifactId>mdop-test-support</artifactId><scope>test</scope></dependency><dependency><groupId>org.springframework.boot</groupId><artifactId>external</artifactId></dependency></dependencies>",
    );
    fs.writeFileSync(
      path.join(root, router),
      "{ path: '/users', component: () => import('../views/UsersView.vue') }",
    );
    const result = sourceIndex(root, [pom, router]);
    assert.match(result, /mdop-security、mdop-test-support \(test\)/);
    assert.match(result, /`\/users`/);
    assert.doesNotMatch(result, /external/);
    fs.writeFileSync(
      path.join(root, router),
      "{ path: '/account', component: () => import('../views/AccountView.vue') }",
    );
    assert.notEqual(sourceIndex(root, [pom, router]), result);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test("checks tracked targets and anchors, URL encoding, reference links and portable paths", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "mdop-docs-"));
  try {
    fs.writeFileSync(path.join(root, "guide.md"), "# 使用说明\n");
    fs.writeFileSync(
      path.join(root, "README.md"),
      "[ok](guide.md#使用说明)\n[encoded](guide.md#%E4%BD%BF%E7%94%A8%E8%AF%B4%E6%98%8E)\n[missing](missing.md)\n[bad](guide.md#absent)\n[local](C:/private.md)\n[ref]: absent.md\n```md\n[ignore](unknown.md)\n```\n",
    );
    fs.writeFileSync(
      path.join(root, "missing.md"),
      "Ignored files cannot satisfy public documentation",
    );
    const errors = checkLinks(root, ["README.md", "guide.md"]);
    assert.equal(errors.length, 4);
    assert.ok(errors.some((e) => e.includes("missing target missing.md")));
    assert.ok(errors.some((e) => e.includes("missing anchor")));
    assert.ok(errors.some((e) => e.includes("nonportable")));
    assert.ok(errors.some((e) => e.includes("missing target absent.md")));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
