# Frontend

MDOP 前端使用 pnpm（高效的 Node.js 包管理器）工作区管理应用和共享包。

## 工具链

- Node.js：`>=24.18.0 <25`，使用 Node.js 24 LTS（长期支持版本）。
- Corepack：负责提供并管理 pnpm 命令入口。
- pnpm：固定为 `11.13.0`。
- 管理端：Vue 3、TypeScript、Vue Router、Pinia、Vite 和 Vitest。

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

## 首次准备

在 `frontend` 目录执行：

```powershell
corepack enable pnpm
pnpm install
```

## 常用命令

```powershell
pnpm run dev
pnpm run test
pnpm run type-check
pnpm run build
```

- `dev`：启动 admin 开发服务器。
- `test`：执行单元测试并自动退出。
- `type-check`：执行 TypeScript 和 Vue 类型检查。
- `build`：完成类型检查和生产构建。

生产构建输出位于 `apps/admin/dist`，依赖目录位于 `node_modules`；两者均已被 Git 忽略。

## 已完成验证

- `pnpm run dev`：开发服务器能够在本机启动并显示 MDOP 管理端应用壳。
- `pnpm run test`：1 个 Vitest 测试文件、1 个测试用例通过。
- `pnpm run type-check`：TypeScript 与 Vue 类型检查通过。
- `pnpm run build`：Vite 生产构建通过。

## 当前边界

当前只建立 I0 前端工程基线：

- admin 应用能够启动、测试、类型检查和生产构建。
- Vue Router 与 Pinia 已完成最小装配。
- 不实现登录、完整 IAM、JWT、菜单、数据权限或真实 API 客户端。
- 不创建 WMS 业务页面、接口或数据库表。
