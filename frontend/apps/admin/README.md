# MDOP Admin

`@mdop/admin` 是 MDOP 统一管理端应用。当前提供会话登录、仓库管理、收货基础资料和采购收货操作页面。

## 技术组成

- Vue 3：管理端视图框架。
- TypeScript：启用严格类型检查。
- Vue Router：提供仓库管理、基础资料与采购收货三个业务路由。
- Pinia：已完成最小装配，当前没有业务 Store（状态仓库）。
- Vite：开发服务器和生产构建工具。
- Vitest 与 Vue Test Utils：单元测试和组件挂载测试。
- Prettier 与 ESLint：提供统一格式和 Vue、TypeScript 静态检查。

## 源码结构

```text
src
├─ __tests__
│  ├─ App.spec.ts
│  ├─ Warehouses.spec.ts
│  ├─ Receiving.spec.ts
│  └─ Quantity.spec.ts
├─ router
│  └─ index.ts
├─ App.vue
├─ api.ts
├─ quantity.ts
├─ receiving.ts
├─ style.css
├─ views/
└─ main.ts
```

- `main.ts`：创建 Vue 应用并装配 Pinia、Vue Router。
- `router/index.ts`：维护业务路由和默认跳转。
- `App.vue`：管理会话恢复、登录、退出和应用导航。
- `App.spec.ts`：验证登录请求与会话过期处理；其他组件测试覆盖仓库冲突和收货提交确认。

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

- 已实现登录、退出、会话恢复、导航、仓库管理和采购收货页面；完整 IAM、用户授权管理和 JWT 不在本切片范围内。
- `api.ts` 统一发送同源请求、处理 CSRF、会话过期和错误；写请求失败不会自动重发。收货提交使用固定的单据幂等键。
- 草稿编辑与提交分开，已提交记录只读。库存数量以十进制字符串传输，剩余量使用六位定点整数计算。
- 基础资料当前仅支持新建与查询；消息管理支持状态筛选、处理记录、失败重放、模拟接收对账和隔离消息提示。差异处理、序列号和冲正页面尚未实现。
- 共享能力只在职责确认后进入 `packages/*`，不从旧项目复制页面和 Store。
