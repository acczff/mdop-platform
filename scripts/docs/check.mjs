import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

export function repositoryFiles(root) {
  return [
    ...new Set(
      execFileSync(
        "git",
        ["ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        { cwd: root, encoding: "utf8" },
      )
        .split("\0")
        .filter(Boolean),
    ),
  ].sort();
}

function prose(markdown) {
  // Fenced examples describe other files and must not be treated as live links/headings.
  return markdown.replace(
    /^ {0,3}(`{3,}|~{3,})[^\n]*\n[\s\S]*?^ {0,3}\1[^\n]*$/gm,
    "",
  );
}

export function anchors(markdown) {
  const result = new Set();
  for (const match of prose(markdown).matchAll(/^#{1,6}\s+(.+)$/gm)) {
    const base = match[1]
      .trim()
      .replace(/\s+#+$/, "")
      .replace(/<[^>]+>/g, "")
      .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
      .toLowerCase()
      .replace(/[^\p{L}\p{N}\p{M}_\-\s]/gu, "")
      .replace(/\s/g, "-");
    let id = base;
    for (let suffix = 1; result.has(id); suffix++) id = `${base}-${suffix}`;
    result.add(id);
  }
  for (const match of prose(markdown).matchAll(
    /\b(?:id|name)=["']([^"']+)["']/g,
  ))
    result.add(match[1]);
  return result;
}

export function checkLinks(root, files) {
  const known = new Set(files);
  const errors = [];
  for (const file of files.filter((f) => f.endsWith(".md"))) {
    const source = prose(fs.readFileSync(path.join(root, file), "utf8"));
    const targets = [
      ...source.matchAll(
        /!?\[[^\]\n]*\]\(\s*(<[^>]+>|[^\s)]+)(?:\s+["'][^\n]*["'])?\s*\)/g,
      ),
    ].map((m) => m[1]);
    targets.push(
      ...[...source.matchAll(/^ {0,3}\[[^\]]+\]:\s*(<[^>]+>|\S+)/gm)].map(
        (m) => m[1],
      ),
    );
    for (const raw of targets) {
      const target = raw.replace(/^<|>$/g, "");
      if (/^(https?:|mailto:)/i.test(target)) continue;
      if (/^(?:[a-z][\w+.-]*:|\/|\\)/i.test(target)) {
        errors.push(`${file}: nonportable link ${target}`);
        continue;
      }
      let decoded;
      try {
        decoded = decodeURIComponent(target);
      } catch {
        errors.push(`${file}: invalid URL ${target}`);
        continue;
      }
      const [pathname, fragment] = decoded.split("#", 2);
      const destination = pathname
        ? path.posix.normalize(
            path.posix.join(path.posix.dirname(file), pathname.split("?")[0]),
          )
        : file;
      const isDirectory = files.some((f) => f.startsWith(`${destination}/`));
      if (!known.has(destination) && !isDirectory) {
        errors.push(`${file}: missing target ${target}`);
        continue;
      }
      if (
        fragment &&
        destination.endsWith(".md") &&
        !anchors(fs.readFileSync(path.join(root, destination), "utf8")).has(
          fragment,
        )
      )
        errors.push(`${file}: missing anchor ${target}`);
    }
  }
  return errors;
}

export function sourceIndex(root, files) {
  const read = (f) => fs.readFileSync(path.join(root, f), "utf8");
  const link = (f) => `[${path.posix.basename(f)}](../../${f})`;
  const out = [
    "# 源码导航（自动生成）",
    "",
    "> 由 `node scripts/docs/check.mjs --write-index` 生成。只列当前源码中的模块依赖、页面、控制器入口与迁移；接口字段及授权以控制器、请求模型和服务校验为准。",
    "",
    "返回 [文档中心](../README.md) · [架构与代码阅读](architecture.md) · [接口约定](api.md)",
    "",
    "## 后端模块依赖",
    "",
    "| 模块 | 直接依赖的内部模块（含测试依赖） | 定义 |",
    "|---|---|---|",
  ];
  for (const f of files.filter((f) =>
    /^backend\/mdop-[^/]+\/pom.xml$/.test(f),
  )) {
    const dependencies = [
      ...read(f).matchAll(/<dependency>[\s\S]*?<\/dependency>/g),
    ]
      .map((m) => m[0])
      .filter((s) =>
        /<groupId>\s*(?:io\.github\.acczff\.mdop|\$\{project\.groupId\})\s*<\/groupId>/.test(
          s,
        ),
      )
      .map(
        (s) =>
          s.match(/<artifactId>([^<]+)<\/artifactId>/)[1] +
          (s.includes("<scope>test</scope>") ? " (test)" : ""),
      );
    out.push(
      `| ${f.split("/")[1]} | ${dependencies.join("、") || "无"} | ${link(f)} |`,
    );
  }
  out.push("", "## 管理端页面", "", "| 路径 | 源码 |", "|---|---|");
  const router = "frontend/apps/admin/src/router/index.ts";
  for (const m of read(router).matchAll(
    /path: '([^']+)',\s*component: \(\) => import\('\.\.\/([^']+)'\)/g,
  ))
    out.push(`| \`${m[1]}\` | ${link(`frontend/apps/admin/src/${m[2]}`)} |`);
  out.push(
    "",
    "根路径及未知路径跳转见 " + link(router) + "。页面可见性不代替后端授权。",
    "",
    "## HTTP 控制器",
    "",
    "表中为类级前缀；无类前缀时列方法路径。方法数量不包含 Spring Security 登录/退出和框架健康接口；此表不是 OpenAPI 契约，也不将模拟接口当作真实系统适配。",
    "",
    "| 控制器 | 路径前缀 / 方法路径 | 映射方法数 | Profile 限制 |",
    "|---|---|---:|---|",
  );
  for (const f of files.filter(
    (f) => f.endsWith("Controller.java") && f.includes("/src/main/"),
  )) {
    const source = read(f);
    const base = source.match(/@RequestMapping\("([^"]+)"\)/)?.[1];
    const mappings = [
      ...source.matchAll(
        /@(Get|Post|Put|Patch|Delete)Mapping(?:\("([^"]*)"\))?/g,
      ),
    ];
    if (!mappings.length) continue;
    const profile =
      source.match(/@Profile\(([^\n]+)\)/)?.[1].replace(/[{}" ]/g, "") || "—";
    out.push(
      `| ${link(f)} | ${base ? `\`${base}\`` : mappings.map((m) => `\`${m[1].toUpperCase()} ${m[2] || "/"}\``).join("<br>")} | ${mappings.length} | ${profile} |`,
    );
  }
  out.push(
    "",
    "## Flyway 迁移",
    "",
    "历史迁移不可改写；新结构新增版本。下表不代表任意运行环境已应用迁移。",
    "",
    "| 版本文件 | 所属模块 |",
    "|---|---|",
  );
  for (const f of files
    .filter((f) => /\/db\/migration\/[^/]+\/V[^/]+\.sql$/.test(f))
    .sort((a, b) =>
      path.posix.basename(a).localeCompare(path.posix.basename(b)),
    ))
    out.push(`| ${link(f)} | ${f.split("/")[1]} |`);
  return out.join("\n") + "\n";
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  const root = path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    "../..",
  );
  let files = repositoryFiles(root);
  const index = "docs/reference/source-index.md";
  const expected = sourceIndex(root, files);
  if (process.argv.includes("--write-index")) {
    fs.mkdirSync(path.join(root, "docs/reference"), { recursive: true });
    fs.writeFileSync(path.join(root, index), expected);
    files = repositoryFiles(root);
  }
  const errors = checkLinks(root, files);
  if (
    !fs.existsSync(path.join(root, index)) ||
    fs.readFileSync(path.join(root, index), "utf8").replaceAll("\r\n", "\n") !==
      expected
  )
    errors.push(
      "Source index is stale: node scripts/docs/check.mjs --write-index",
    );
  if (errors.length) {
    console.error(errors.join("\n"));
    process.exitCode = 1;
  } else
    console.log(
      `Documentation verified: ${files.filter((f) => f.endsWith(".md")).length} Markdown files; local links, anchors and source index match.`,
    );
}
