# WMS 公共流程验收清单

本清单按[总体蓝图第 7.0 节](项目总体蓝图.md#70-当前执行边界wms-公共业务闭环)核验四条公共流程及基本异常。运输短少核销、调拨冲正、企业审批策略、真实外部系统接入与生产上线不在本次范围。只修复影响下列验收项的缺口。

## 有限验收范围

| 编号 | 验收项与通过条件 | 主要实现与回归入口 | 本轮结果 |
|---|---|---|---|
| F01 | 采购到货经 Inbox 建单，草稿不入账，分批提交后待检库存与累计收货一致 | ReceivingService、DeliveryService；ReceivingTests、MessagingTests | 通过：本轮全量回归 |
| F02 | QMS 合格/不合格分流保持总量，合格整行上架后才可用；失败查询不保留可操作旧明细 | QualityService、QualityView；QualityTests、Quality.spec | 通过：本轮全量回归 |
| F03 | 差异审批不授权超收；未进入下游的收货可由另一人审批冲正，保留正反流水 | CorrectionService、CorrectionsView；CorrectionTests、Corrections.spec | 通过：本轮全量回归 |
| F04 | 不合格采购退货申请/审批不扣库存，实物确认才扣库；拒绝、取消释放额度 | PurchaseReturnService；PurchaseReturnTests、PurchaseReturns.spec | 通过：本轮全量回归 |
| F05 | 领料预占/释放正确，确认发料转入线边生产归属，企业总实存不变 | IssueService、IssuesView；IssueTests、Issue.spec | 通过：本轮全量回归 |
| F06 | MES 消耗减少线边归属；退料申请保留额度，确认实物才转回，隔离退料不可用 | ProductionService；ProductionTests、Production.spec | 通过：本轮全量回归 |
| F07 | 消耗/退料冲正须异人审核，下游已使用时阻断，保留原记录并发出补偿 | ProductionReversalService；ProductionReversalTests、ProductionReversals.spec | 通过：本轮全量回归 |
| F08 | 完工需求独立建单，成品待检、判定、上架可追溯，不由原料消耗推算产量 | FinishedGoodsService；FinishedGoodsTests、FinishedGoods.spec | 通过：本轮全量回归 |
| F09 | 销售预占、拣货、异人复核、实际出库及反馈完整；取消释放预占，复核前不扣实存 | SalesService；SalesTests、Sales.spec | 通过：本轮全量回归 |
| F10 | 库存查询与来源追溯、同仓移库数量守恒，查询失败不可误操作旧数据 | InventoryService、TransferService；InventoryTests、TransferTests、Inventory.spec、Transfer.spec | 通过：本轮全量回归 |
| F11 | 盘点快照、实盘、异人审核留痕；库存曾变化即拒绝过账，取消/驳回不改库存 | CountService；CountTests、Count.spec | 通过：本轮全量回归 |
| F12 | 冻结保留实存和预占、可用归零；异人解冻按当前量恢复，冻结期间不能发出 | FreezeService；FreezeTests、Freezes.spec | 通过：本轮全量回归 |
| F13 | 跨仓审批预占、发出转在途、分批实收，禁止超收；未收齐保留在途而不虚假完结 | CrossTransferService；CrossTransferTests、CrossTransfers.spec | 通过：本轮全量回归 |
| E01 | 数量、状态、权限、仓库范围、CSRF、自审、冻结和效期拒绝均不产生部分写入 | 各业务集成测试、SessionTests | 通过：本轮全量回归 |
| E02 | 重复与并发请求只入账一次，库存/消息写失败回滚；页面原请求重试、查询失败及迟到响应安全 | 各业务集成测试及前端组件测试 | 通过：本轮全量回归 |
| E03 | 消息失败可重试/重放，乱序补偿不恢复已冲正业务；发布与模拟端接收分开核验 | MessagingTests、MesFeedbackTests、FinishedGoodsTests、SalesTests | 通过：本轮全量回归 |
| E04 | 同版本隔离库核验库存、流水、预占、生产归属及在途；备份恢复后迁移有效且对账无差异 | scripts/audit-inventory.sql、Flyway、隔离恢复演练 | 通过：22 个迁移、47 项对账及恢复启动 |

## 本轮执行记录

验收分支基于 PR #27 的范围文档与 PR #26 的业务实现。代码核验、自动回归、浏览器检查、数据恢复分别记录，历史测试数量不累加为本轮成绩。日常数据库不升级，已完成业务记录不删除。

### 2026-10-07 本轮结果

17 项均完成实现核对及对应回归；四条公共流程已有实现，本轮没有新增业务模型或数据库迁移。该次验收结论限于候选代码与本地模拟环境；随后 PR #17—#28 已合并 main（见下方交付记录），真实系统联调与生产验收尚未执行。

- 后端：`mvnw.cmd -f backend/pom.xml verify` 通过，175 项测试（主数据 13 + 启动模块 162），失败、错误、跳过均为 0。真实 MySQL/RabbitMQ Testcontainers 覆盖上述主流程、事务回滚、幂等、并发、权限、消息补偿及拒绝分支。以本次运行日志为准，不累加 target 中历史探针报告。
- 前端：`scripts/pnpm.cmd run verify` 通过，19 个测试文件、68 项测试；包含格式、ESLint、类型检查与生产构建。本轮新增 6 项用例，并强化原有领料重试及收货弹窗用例。
- 浏览器：同一候选前端连接隔离后端，新增 `FLOW-ACCEPT-1007` 到货 2 件，完成收货提交、模拟 QMS 判定、整行上架，最终合格在手/可用均为 `2.000000`。故障代理验证上架 503 后目标库位和仓库锁定、原请求重试成功；质检明细失败清空处理区；冲正审计失败不开放审批，列表失败不保留旧申请。收货查询失败清空旧操作区，恢复查询后正常显示。
- 其余生产、成品销售及库存流程由本轮全量集成测试覆盖，浏览器抽查既有隔离单据和历史，不把已有记录当作本轮重新操作全流程的证据。
- 恢复：隔离源库备份后恢复至新建 `mdop-flow-restore-1007`，使用本轮构建后端在 8083 启动，关闭消息消费/发布以避免恢复副本处理源环境消息。Flyway 校验 22 个迁移成功，健康检查 `UP`；源库及恢复库各执行 47 项对账，全部差异为 0，结果一致。演练后停止临时后端并移除本轮恢复容器，保留备份与日志；日常数据库未变更。

### 已修复的流程缺口

| 范围 | 复现的问题 | 最小修复 |
|---|---|---|
| 收货 | 明细/列表刷新失败仍可操作旧通知 | 开始查询即清除旧选择和列表；弹窗期间锁定背景入口 |
| 质检上架 | 明细失败仍保留旧上架入口；失败重试可改变内容 | 清除旧明细；保存完整待重试请求并锁定内容，允许原请求重试或刷新核对 |
| 差异冲正 | 列表失败保留旧申请；审计失败仍可审批 | 刷新时清空旧列表；审计成功后才开放所选申请 |
| 生产领料 | 重试弹窗打开时可从背景切仓、刷新、重复打开操作 | 弹窗/历史期间锁定背景操作，保留当前请求与重试内容 |

首次针对性测试复现 7 个失败场景，修复后均通过。上架提示同步澄清冻结库存仍不可用；后端及管理端 README 更新了已有认证、Inbox/Outbox 与页面范围，避免按过时文档验收。

### 证据与交付边界

本地证据保存在仓库忽略目录 `tmp/`：`flow-acceptance-backend.log`、`flow-frontend-verify.log`、`flow-frontend-red.log`、`flow-receiving-red.log`、`flow-acceptance-browser.jpg`、`flow-source-audit.log`、`flow-restored-audit.log`、`flow-restore-boot.log`、`flow-acceptance.sql`。自动测试与本清单纳入 Git，临时备份、环境凭据及运行日志不提交。

原交付分支 `acczff/wms-flow-acceptance` 基于 PR #27 的 `acczff/wms-scope-baseline`。2026-10-07 按用户统一合并授权，PR #17—#28 已依次切换 main 基线并合并；未升级日常环境。运输短少核销未启动；未收齐调拨继续保留已收、剩余在途与原始记录，后续处置按具体工作环境另行确认。

## 主干交付记录（2026-10-07）

合并前已 fetch 并验证全部 12 个 PR head 属于验收提交 `275b02e`，没有冲突或未解决审查线程。#18 已解除草稿状态。采用 merge commit 保留历史，合并后主干 `2ec0be1` 的完整文件树与验收提交完全一致；后续文档同步不改变业务代码。

当前 GitHub 账号与 PR 作者相同，没有制造独立 APPROVED 记录；仓库未配置审查保护或 CI/check/status。本次依据用户直接授权和本地验收合并，不声称远端 CI 或第三方审批通过。

| PR | 原分支 | 合并提交 |
|---|---|---|
| [#17](https://github.com/acczff/mdop-platform/pull/17) | `acczff/warehouse-transfers` | `6d11310` |
| [#18](https://github.com/acczff/mdop-platform/pull/18) | `acczff/inventory-counts` | `5c98054` |
| [#19](https://github.com/acczff/mdop-platform/pull/19) | `acczff/material-issues` | `d4e5c57` |
| [#20](https://github.com/acczff/mdop-platform/pull/20) | `acczff/production-cycle` | `403db90` |
| [#21](https://github.com/acczff/mdop-platform/pull/21) | `acczff/production-reversals` | `a9f40d0` |
| [#22](https://github.com/acczff/mdop-platform/pull/22) | `acczff/finished-goods-receiving` | `809cd41` |
| [#23](https://github.com/acczff/mdop-platform/pull/23) | `acczff/sales-outbound` | `674920c` |
| [#24](https://github.com/acczff/mdop-platform/pull/24) | `acczff/cross-warehouse-transfers` | `25b076a` |
| [#25](https://github.com/acczff/mdop-platform/pull/25) | `acczff/inventory-freeze` | `4bd6fad` |
| [#26](https://github.com/acczff/mdop-platform/pull/26) | `acczff/partial-transfer-receiving` | `4513dd7` |
| [#27](https://github.com/acczff/mdop-platform/pull/27) | `acczff/wms-scope-baseline` | `0ea48a6` |
| [#28](https://github.com/acczff/mdop-platform/pull/28) | `acczff/wms-flow-acceptance` | `2ec0be1` |

分支清理前已创建并验证包含全部引用的 Git bundle，保存在本机忽略目录 `tmp/git-backups/`。仅清理已进入 main 且没有独有未交付提交的工作分支，主干和历史提交保留；后续从最新 main 建分支。数据库备份与 Git bundle 用途不同，不能相互代替。
