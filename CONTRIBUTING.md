# 贡献指南

## 基本流程

1. 外部贡献者先 fork 仓库，从最新 `main` 创建短生命周期分支；依赖未合并 PR 时在描述中明确基线。
2. 一个分支只处理一个明确目标。
3. 先更新相关业务或技术文档，再实现代码和测试。
4. 本地完成适用的格式化、测试和构建检查。
5. 通过 Pull Request 合并，禁止直接向 `main` 提交日常功能。

PR 创建和更新后，核对最新提交的 `Documentation verify`、`Backend verify`、`Frontend verify` 和 `Release candidate` 四项 CI 检查；失败时先查看步骤日志与诊断产物。绿色检查不替代业务审查，未配置分支保护时也不会自动阻止合并。具体触发、报告和边界见 [CI 自动验收](docs/project/CI自动验收.md)。

推荐分支名：

- 维护者使用 `acczff/<description>`，例如 `acczff/inventory-freeze`；外部贡献者在自己的 fork 使用 `<username>/<description>`。不要求所有人使用维护者用户名。

依赖 PR 按顺序合并，上一项进入 `main` 后将下一项基线切回 `main`。清理分支前先 fetch、备份并验证提交已被主干包含；删除已合并分支不改写历史。不把提交人的自审描述为独立审批，也不把本地验证描述为远端 CI 通过。

提交信息使用固定的中文类型和全角冒号，格式为 `类型：中文说明`。功能、修复、文档分别对应 `feat`、`fix`、`docs`，不在同一提交中混用中英文前缀。例如：

```text
功能：建立采购收货领域模型
修复：拒绝已失效会话
文档：明确 I0 完成定义
```

## 完成定义

先按[快速开始](docs/guides/quickstart.md)准备工具链和依赖，从仓库根目录运行对应检查：

| 改动 | 本地检查 |
|---|---|
| Java / SQL / 认证 / 消息 | `.\backend\mvnw.cmd -f backend/pom.xml verify`，当前终端使用 JDK 25，Docker 可连接 |
| 前端 | `.\scripts\pnpm.cmd run verify`，Node 与 scripts 入口在当前 PATH |
| 文档 / 路由 / 控制器 / 模块 / 迁移 | `node scripts/docs/check.mjs`；导航变化先 `node scripts/docs/check.mjs --write-index` |
| 文档工具 | `node --test scripts/docs/check.test.mjs` |
| 发布 / 备份工具 | `node --test scripts/release/release.test.mjs` |
| 全部业务 | `.\mdop.cmd verify`；文档和发布工具检查另行执行 |

Linux CI 的 Maven 命令为 `./backend/mvnw -f backend/pom.xml verify`；前端在 `frontend` 目录使用锁定版本 pnpm 安装并执行 `verify`。不要提交运行日志、测试报告、备份、node_modules 或构建包。CI 当前对所有 PR 完整运行，不根据文件路径跳过。

README 只保留稳定概览；功能范围更新所属业务文档，版本/测试证据更新[版本记录](docs/project/status.md)，文档分层规则见[文档中心](docs/README.md)。历史测试不改写成当前结果，已应用迁移不修改。

每个变更至少满足：

- 范围和验收标准明确。
- 代码、配置、数据库和文档保持一致。
- 正常、异常、权限和并发场景得到适当验证。
- 不破坏模块依赖边界。
- 不包含真实企业数据、个人信息、明文密钥或生产配置。
- Pull Request 说明变更内容、原因、影响和验证方式。

## 架构变更

新增系统、中间件、跨模块依赖或重大技术方案前，必须先更新总体蓝图或新增 ADR，记录背景、备选方案、选择理由、代价和重新评估条件。
