# Frontend

MDOP 前端使用 pnpm（高效的 Node.js 包管理器）工作区管理应用和共享包。

## 工具链

- Node.js：`>=24.18.0 <25`，使用 Node.js 24 LTS（长期支持版本）。
- Corepack：负责提供并管理 pnpm 命令入口。
- pnpm：固定为 `11.13.1`。
- 管理端：Vue 3、TypeScript、Vue Router、Pinia、Vite 和 Vitest。
- 质量工具：Prettier 3.9.5 和 ESLint 10.7.0。

用户与固定角色分配、账号审计和本人改密的范围见[用户与权限首版](../docs/project/IAM用户与权限首版.md)。

## 当前结构

```text
frontend
├─ apps
│  └─ admin
├─ package.json
├─ pnpm-lock.yaml
└─ pnpm-workspace.yaml
```

`pnpm-workspace.yaml` 已预留 `packages/*`。共享包只在职责明确后创建，不提前建立空的 `auth`、`api-client`、`ui` 或业务包。

admin 应用的职责、源码结构和验证方式见 [apps/admin/README.md](apps/admin/README.md)。

生产工单中的 `MaterialPlanPanel` 承载材料需求核对和采购建议确认，复用统一请求客户端并保留未知写入的原请求。业务口径及页面边界见[材料需求实现](../docs/erp/材料需求实现与验收.md)。

## 首次准备

在仓库根目录执行（无需修改全局 Corepack 安装）：

```powershell
.\scripts\pnpm.cmd install --frozen-lockfile
```

## 常用命令

推荐使用根目录 `.\mdop.cmd verify` 或 `.\scripts\pnpm.cmd run <动作>`，以保证嵌套 pnpm 命令使用相同版本。手动运行 `dev` 前将 `scripts` 目录加入当前终端 PATH；后端代理由 `MDOP_BACKEND_URL` 指定，默认端口 8081。

```powershell
.\scripts\pnpm.cmd run dev
.\scripts\pnpm.cmd run format
.\scripts\pnpm.cmd run format:check
.\scripts\pnpm.cmd run lint
.\scripts\pnpm.cmd run lint:fix
.\scripts\pnpm.cmd run test
.\scripts\pnpm.cmd run type-check
.\scripts\pnpm.cmd run build
.\scripts\pnpm.cmd run verify
```

- `dev`：启动 admin 开发服务器。
- `format`：自动格式化前端工作区。
- `format:check`：检查格式，不修改文件。
- `lint`：执行 Vue 与 TypeScript 静态检查，警告也会导致失败。
- `lint:fix`：自动修复 ESLint 能够安全修复的问题。
- `test`：执行单元测试并自动退出。
- `type-check`：执行 TypeScript 和 Vue 类型检查。
- `build`：完成类型检查和生产构建。
- `verify`：依次执行格式检查、静态检查、单元测试、类型检查和生产构建。

生产构建输出位于 `apps/admin/dist`，依赖目录位于 `node_modules`；两者均已被 Git 忽略。

## 历史验证（2026-10-07）

CI 的 `Frontend verify` 复用 `pnpm run verify`，先按锁文件安装依赖。`CI` 环境额外输出 `apps/admin/test-results/junit.xml`，生成报告不提交 Git；触发、失败日志及构建产物见 [CI 自动验收](../docs/project/CI自动验收.md)。下列数量为最近一次 WMS 验收记录，CI 结果以相应提交的实际运行结果为准。

- `pnpm.cmd run dev`：管理端业务页面已在隔离环境进行浏览器验收。
- `pnpm.cmd run format:check`：Prettier 格式检查通过。
- `pnpm.cmd run lint`：ESLint 静态检查通过，无警告或错误。
- `pnpm.cmd run test`：19 个 Vitest 测试文件、68 个测试用例通过；详见 [WMS 公共流程验收清单](../docs/project/WMS公共流程验收清单.md)。
- `pnpm.cmd run type-check`：TypeScript 与 Vue 类型检查通过。
- `pnpm.cmd run build`：Vite 生产构建通过。
- `pnpm.cmd run verify`：格式检查、静态检查、单元测试、类型检查和生产构建全部通过。

## 当前边界

当前管理端覆盖四条 WMS 公共流程及基本异常：

- admin 应用能够启动、测试、类型检查和生产构建。
- Vue Router 与 Pinia 已完成最小装配。
- 已实现会话登录、导航、同源 API/CSRF、操作权限入口和仓库范围查询；后端仍执行最终授权。
- 已提供采购入库、生产物料协同、成品出入库、库存运营与消息管理。查询失败和不确定写入按页面处理；先刷新核对事实，有幂等键的操作保持原键与载荷，不能把所有写操作都视为可直接重试。
- 完整 IAM、JWT 和真实外部系统接入尚未实现；前端不直接修改数据库，当前只验证本地模拟业务。

当前分支已包含数据库账号、固定角色、账号审计和本人改密。最新带提交身份的验证数量见[版本记录](../docs/project/status.md)，完整页面清单见[源码导航](../docs/reference/source-index.md)。
