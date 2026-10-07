# MDOP Admin

`@mdop/admin` 是 MDOP 统一管理端应用。当前提供会话登录、主数据、采购入库、生产物料协同、成品出入库、库存运营与消息管理页面。当前验收范围见 [WMS 公共流程验收清单](../../../docs/project/WMS公共流程验收清单.md)。

## 技术组成

- Vue 3：管理端视图框架。
- TypeScript：启用严格类型检查。
- Vue Router：提供各业务页面路由，实际入口见 `src/router/index.ts`。
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
- `App.spec.ts`：验证登录请求与会话过期处理；业务组件测试覆盖表单、权限入口、库存精度、失败重试与迟到响应保护。

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

- `/purchase-returns` 支持不合格品分批退货申请、审批、撤销、实际交接确认与历史；仓管确认实际退货时才扣库存。审批人与申请人必须不同，消息页面可查询模拟 ERP 反馈。退款、财务单据与真实 ERP 不在本轮范围。

- 已实现登录、退出、会话恢复、导航、仓库管理和采购收货页面；完整 IAM、用户授权管理和 JWT 不在本切片范围内。
- `api.ts` 统一发送同源请求、处理 CSRF、会话过期和错误；写请求失败不会自动重发。收货提交使用固定的单据幂等键。
- 草稿编辑与提交分开，已提交记录只读。库存数量以十进制字符串传输，剩余量使用六位定点整数计算。
- 基础资料当前仅支持新建与查询；消息管理支持状态筛选、处理记录、失败重放、模拟接收对账和隔离消息提示。差异与冲正页面已支持申请、跨人员审批与历史；`/quality` 支持本地模拟质量结果和合格数量整行上架。真实 QMS、序列号、复检、部分上架与客户退货尚未实现。
- 共享能力只在职责确认后进入 `packages/*`，不从旧项目复制页面和 Store。
