# MDOP Admin

`@mdop/admin` 是 MDOP 统一管理端应用。I0 只建立可启动、可测试、可类型检查和可生产构建的前端工程基线。

## 技术组成

- Vue 3：管理端视图框架。
- TypeScript：启用严格类型检查。
- Vue Router：已完成最小装配，当前没有业务路由。
- Pinia：已完成最小装配，当前没有业务 Store（状态仓库）。
- Vite：开发服务器和生产构建工具。
- Vitest 与 Vue Test Utils：单元测试和组件挂载测试。
- Prettier 与 ESLint：提供统一格式和 Vue、TypeScript 静态检查。

## 源码结构

```text
src
├─ __tests__
│  └─ App.spec.ts
├─ router
│  └─ index.ts
├─ App.vue
└─ main.ts
```

- `main.ts`：创建 Vue 应用并装配 Pinia、Vue Router。
- `router/index.ts`：声明空路由表，为后续经过确认的页面保留入口。
- `App.vue`：仅显示 MDOP 管理端 I0 应用壳。
- `App.spec.ts`：验证应用壳标题和基线说明能够正确渲染。

## 运行与验证

统一从 `frontend` 工作区根目录执行：

```powershell
pnpm.cmd run dev
pnpm.cmd run format:check
pnpm.cmd run lint
pnpm.cmd run test
pnpm.cmd run type-check
pnpm.cmd run build
pnpm.cmd run verify
```

不要在 `apps/admin` 内创建独立锁文件；整个前端工作区统一使用 `frontend/pnpm-lock.yaml`。

## 当前边界

- 不实现登录页、菜单、完整 IAM、JWT 或数据权限。
- 不实现真实 API 客户端或业务状态管理。
- 不创建 WMS 业务页面。
- 共享能力只在职责确认后进入 `packages/*`，不从旧项目复制页面和 Store。
