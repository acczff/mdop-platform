# 业务域索引

> 类型：导航 | 状态：当前 | 适用：0.1 开发分支 | 责任：各业务域

每个域固定四类内容：概念（是什么）、流程（怎么走）、规则（什么不允许）、接口（怎么调）。当前文档尚未全部按此拆分，先按下表定位。

| 域 | 概念与流程 | 规则与验收 | 代码入口 |
|---|---|---|---|
| 主数据 | [ERP 主数据](erp/01-主数据.md) | [实现与验收](../90-archive/acceptance/erp/主数据实现与验收.md) | `mdop-master-data` |
| 采购 | [采购设计](erp/02-采购.md) | [P1](../90-archive/acceptance/erp/采购P1实现与验收.md)、[P2](../90-archive/acceptance/erp/采购P2实现与验收.md)、[P3](../90-archive/acceptance/erp/采购P3实现与验收.md) | `mdop-purchasing` |
| 销售 | [销售设计](erp/03-销售.md) | [实现与验收](../90-archive/acceptance/erp/销售实现与验收.md) | `mdop-sales` |
| 库存 | [库存设计](erp/04-库存.md) | [公共流程验收清单](../90-archive/acceptance/WMS公共流程验收清单.md) | `mdop-wms/inventory` |
| 生产 | [生产与物料计划](erp/05-生产与物料计划.md) | [工单结束](../90-archive/acceptance/erp/工单核对与结束实现与验收.md) | `mdop-manufacturing` |
| 财务 | [财务与成本](erp/06-财务与成本.md) | [金额依据与往来](../90-archive/acceptance/erp/金额依据与往来确认实现与验收.md) | `mdop-finance` |
| 收货与质检 | [WMS 全链路设计](wms/01-WMS全链路设计.md) | [质检与上架](../90-archive/iterations/I7质检与上架.md) | `mdop-wms/receiving` |
| 查询与控制 | [查询与控制](erp/07-查询与控制.md) | [库存查询与追溯](../90-archive/iterations/I9库存查询与追溯.md) | `mdop-wms/inventory` |

## 阅读建议

- 先读“概念与流程”，再看“规则与验收”，最后对照代码。
- 验收文档记录的是**当时**的提交与测试证据，不代表当前分支状态；当前状态看[版本记录](../40-operations/status.md)。
- 界面风格与交互约定见[设计说明](../30-development/design-language.md)。

## 待整理

以下文档仍混杂设计与验收，后续按“概念/流程/规则/接口”拆分：

- [采购 P1—P3](erp/README.md) 中的操作演练与验收段落
- [WMS 全链路设计](wms/01-WMS全链路设计.md) 中的长期目标与已实现范围
- 各 `project/I*` 迭代方案（保留原意，仅补状态标注）
