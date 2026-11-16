# 技术债登记

> 类型：参考 | 状态：当前 | 适用：0.1 开发分支 | 责任：平台组

登记“已知但不在本轮修”的问题，写清**触发条件**与**处理方式**，避免两类失败：
一是没人记得，二是有人兴冲冲地大规模重写。规则只有一条——**不单独排期，按触发条件随改动推进**。

## 债务清单

| # | 问题 | 现状（2026-10） | 触发条件 | 处理方式 |
|---|---|---|---|---|
| 1 | `SELECT *` | 主代码 104 处：`CatalogService` 14、`ProductionReversalService` 10、`PurchaseReturnService` 8、`CrossTransferService` 8 | 改动该文件时 | 改为显式列名，保持字段名不变；跑该域集成测试 |
| 2 | 深分页 | 列表用 `LIMIT ? OFFSET ?`，`page` 上限 1,000,000；`LIKE '%term%'` 无法走索引 | 列表 P95 超 300ms，或单表超 100 万行 | 改键集分页（`WHERE id < :cursor`）；模糊搜索改前缀或全文索引 |
| 3 | 模块内测试缺失 | `mdop-master-data` 与 `mdop-wms` 已建立模块内单元测试；其余业务模块仍主要依赖 `mdop-boot` 集成测试 | 改动该模块核心路径时 | 补 3~5 个切片测试（仓储显式列、锁顺序、状态机分支），集成测试只留跨模块验收 |
| 4 | 主数据层间直连 | `WarehouseService` 直接 import 2 个 `infrastructure` 类型（已入基线） | 下次改该模块时 | 引入领域端口，`JpaRepository` 留在 `infrastructure` 实现 |
| 6 | 富服务 | `PurchaseService`、`ProductionClosureService`、`IssueService` 仍混有写语句、幂等、审计 | 该用例有实质改动时 | 按用例抽协作者，保留原事务入口；不整体重写 |
| 9 | 数据完整性错误泛化 | `DataIntegrityViolationException` 统一映射 409，不指出具体约束 | 排障成本成为问题时 | 按约束名映射错误码，保留通用兜底 |

## 使用方式

- 改到相关代码时，先看本表；命中触发条件就顺手处理，不命中不主动重构。
- 处理完把该行删掉，并在 PR 描述里写明“债务 #N 已清偿”。
- 新增债务必须写清触发条件，否则不进本表——写不出触发条件说明还没想清楚。
- 架构层面的存量例外不在这里，在 [`scripts/checks/architecture/baseline.json`](../../scripts/checks/architecture/baseline.json)，每条带原因。

## 相关文档

- [模块模板](module-template.md)：分层、命名、迁移步骤
- [贡献指南](../../CONTRIBUTING.md)：提交前检查
- [架构总览](../10-architecture/README.md)：依赖方向与落地范围
