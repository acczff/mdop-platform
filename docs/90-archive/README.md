# 归档索引

> 类型：导航 | 状态：历史 | 适用：各迭代当期 | 责任：平台组

归档文档保留当时的结论与证据，**不代表当前分支状态**。当前能力看[项目首页](../../README.md)，当前验证看[版本记录](../40-operations/status.md)。

## 迭代设计（I 系列）

| 迭代 | 文档 | 主题 |
|---|---|---|
| I0 | [工程基线方案](iterations/I0工程基线方案.md) | 工具链、模块骨架、质量门 |
| I1.1 | [仓库主数据方案](iterations/I1.1仓库主数据方案.md) | 仓库聚合与校验 |
| I2–I4 | [消息接入与可靠反馈](iterations/I2-I4消息接入与可靠反馈.md) | Outbox/Inbox、重试、模拟对账 |
| I6 | [收货差异与冲正](iterations/I6收货差异与冲正.md) | 差异处理与反向记录 |
| I7 | [质检与上架](iterations/I7质检与上架.md) | 待检、判定、上架 |
| I8 | [不合格品采购退货](iterations/I8不合格品采购退货.md) | 退货申请与交接 |
| I9 | [库存查询与追溯](iterations/I9库存查询与追溯.md) | 余额、流水、追溯 |
| I10 | [仓内移库](iterations/I10仓内移库.md) | 同仓库位移动 |
| I11 | [库存盘点](iterations/I11库存盘点.md) | 快照盘点与差异 |
| IAM | [用户与权限首版](iterations/IAM用户与权限首版.md) | 账号、角色、仓库范围 |

## 联合验收（R 系列）

| 批次 | 文档 | 范围 |
|---|---|---|
| R1 | [采购销售联合验收](acceptance/R1采购销售联合验收.md) | 采购到销售的数量闭环 |
| R2 | [生产采购销售联合验收](acceptance/R2生产采购销售联合验收.md) | 同一销售来源贯通生产、采购、发货 |
| — | [采购入库阶段验收](acceptance/采购入库阶段验收.md) | 收货到上架 |

## 分域验收记录

`acceptance/erp/` 下的“实现与验收”文档记录各切片的提交、测试数量与演练证据，按当时范围阅读：

- [主数据](acceptance/erp/主数据实现与验收.md)、[采购 P1](acceptance/erp/采购P1实现与验收.md)、[P2](acceptance/erp/采购P2实现与验收.md)、[P3](acceptance/erp/采购P3实现与验收.md)
- [销售](acceptance/erp/销售实现与验收.md)、[BOM](acceptance/erp/BOM版本实现与验收.md)、[生产订单](acceptance/erp/生产订单实现与验收.md)
- [材料需求](acceptance/erp/材料需求实现与验收.md)、[领料消耗退料](acceptance/erp/工单领料消耗退料实现与验收.md)
- [完工与成品入库](acceptance/erp/完工复核与成品入库实现与验收.md)、[工单结束](acceptance/erp/工单核对与结束实现与验收.md)
- [金额依据与往来](acceptance/erp/金额依据与往来确认实现与验收.md)

## 使用约定

1. 不修改归档文档的结论与数字；如需更新，新增“现状说明”并链接到当前文档。
2. 当前文档不依赖归档内容作为依据。
3. 新迭代完成后，把当期设计移入 `iterations/`、验收移入 `acceptance/`，长期规划移入 `planning/`，并在[文档索引](../INDEX.md)登记。
4. 三个子目录：`iterations/`（迭代设计 I*）、`acceptance/`（联合与分域验收）、`planning/`（蓝图、范围与整理报告）。
