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
├─ components/WorkspaceShell.vue
├─ App.vue
├─ navigation.ts
├─ api.ts
├─ quantity.ts
├─ receiving.ts
├─ style.css
├─ views/
└─ main.ts
```

- `main.ts`：创建 Vue 应用并装配 Pinia、Vue Router。
- `router/index.ts`：维护业务路由和默认跳转。
- `App.vue`：管理会话恢复、登录与退出。
- `WorkspaceShell.vue`、`navigation.ts`：按原有权限筛选六组业务导航，每次只展开一个分组，只有一个可见菜单的分组直接显示入口；当前路由所在分组自动展开，窄屏默认收起导航。账号入口位于右上角菜单，导航与业务内容独立滚动。
- `App.spec.ts`：验证登录请求与会话过期处理；业务组件测试覆盖表单、权限入口、库存精度、失败重试与迟到响应保护。

## 运行与验证

推荐在仓库根目录使用 `.\scripts\pnpm.cmd run <动作>`（当前 PATH 先包含 `scripts` 与所选 Node）。下面的直接命令仅在 `frontend` 目录且已经配置正确 pnpm 入口时执行：

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

- `/purchasing` 支持采购需求、订单的列表/详情、草稿、提交、异人审核、整单转单、取消释放及审计。待确认请求按账号保留在 sessionStorage，可原样重试；刷新和切单不保留可操作旧详情。范围见[采购 P1](../../../docs/erp/采购P1实现与验收.md)及[采购 P2](../../../docs/erp/采购P2实现与验收.md)：分批安排、剩余额度、WMS 送达/失败重试、撤回和收货引用。具有 WMS 查询权限时可跳转 `/receiving?warehouseId=...&arrivalId=...`；[P3](../../../docs/erp/采购P3实现与验收.md) 增加分类数量、未完成事项、来源事实及结案快照。结案后事实变化显示待核对，金额核算仍留 R3。

- `/purchase-returns` 支持不合格品分批退货申请、审批、撤销、实际交接确认与历史；仓管确认实际退货时才扣库存。审批人与申请人必须不同，消息页面可查询模拟 ERP 反馈。退款、财务单据与真实 ERP 不在本轮范围。

- 已实现登录、退出、会话恢复、导航、主数据及四条 WMS 公共流程页面，相关 PR 已合并 main；本轮新增 `/users` 用户与固定角色分配、账号审计，以及 `/account` 本人改密；完整 IAM、组织权限、SSO 和 JWT 不在首版范围。
- `api.ts` 统一发送同源请求、处理 CSRF、会话过期和错误；写请求失败不会自动重发。收货提交使用固定的单据幂等键。
- 草稿编辑与提交分开，已提交记录只读。库存数量以十进制字符串传输，剩余量使用六位定点整数计算。
- `/catalog` 基础资料支持供应商、客户、物料、基本单位与单组织维护、启停和历史；已引用物料的单位/追踪策略锁定，库位沿用新增与查询。范围和升级要求见[主数据实现与验收](../../../docs/erp/主数据实现与验收.md)。
- 基础资料以“分类、筛选、列表”展示，新增位于筛选区右侧；支持关键词与启用状态组合筛选、只读提示和键盘页签。设置条件后才显示清空筛选及匹配数量，无筛选时仅显示已加载数量；达到 1000 条时明确提示可能未包含全部记录，不冒充服务端总量或分页。维护规则在编辑时显示；表格在窄屏内横向滚动。
- 消息管理支持状态筛选、处理记录、失败重放、模拟接收对账和隔离消息提示。差异与冲正页面支持申请、跨人员审批与历史；`/quality` 支持本地模拟质量结果和合格数量整行上架。真实 QMS、序列号、复检、部分上架与客户退货尚未实现。
- 共享能力只在职责确认后进入 `packages/*`，不从旧项目复制页面和 Store。

## 生产需求与订单

`/production-orders` 提供按成品仓查看手工/销售需求、整量转工单、明确选版、固定 BOM、独立审核及安全取消。`manufacturing:write/review` 分离职责；待核对写请求按账号保存并原样重试。生产地点在基础资料中维护。范围与验收见[生产订单实现](../../../docs/erp/生产订单实现与验收.md)。

## BOM 版本

`/boms` 支持产品/版本搜索、状态筛选和服务端分页；详情提供草稿维护、发布、复制、停用及原版本/历史。只有 `bom:write` 可写，发布版不可编辑；未知写入按账号保留原请求，重试/查询异常处理与业务单据一致。接口及边界见 [BOM 实现与验收](../../../docs/erp/BOM版本实现与验收.md)。
