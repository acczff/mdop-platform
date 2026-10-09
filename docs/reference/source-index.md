# 源码导航（自动生成）

> 由 `node scripts/docs/check.mjs --write-index` 生成。只列当前源码中的模块依赖、页面、控制器入口与迁移；接口字段及授权以控制器、请求模型和服务校验为准。

返回 [文档中心](../README.md) · [架构与代码阅读](architecture.md) · [接口约定](api.md)

## 后端模块依赖

| 模块 | 直接依赖的内部模块（含测试依赖） | 定义 |
|---|---|---|
| mdop-boot | mdop-purchasing、mdop-security、mdop-system、mdop-master-data、mdop-wms、mdop-integration、mdop-test-support (test) | [pom.xml](../../backend/mdop-boot/pom.xml) |
| mdop-common | 无 | [pom.xml](../../backend/mdop-common/pom.xml) |
| mdop-integration | mdop-common、mdop-wms | [pom.xml](../../backend/mdop-integration/pom.xml) |
| mdop-master-data | mdop-common、mdop-security | [pom.xml](../../backend/mdop-master-data/pom.xml) |
| mdop-purchasing | mdop-master-data、mdop-security、mdop-common | [pom.xml](../../backend/mdop-purchasing/pom.xml) |
| mdop-security | mdop-common | [pom.xml](../../backend/mdop-security/pom.xml) |
| mdop-system | mdop-common、mdop-security | [pom.xml](../../backend/mdop-system/pom.xml) |
| mdop-test-support | mdop-common | [pom.xml](../../backend/mdop-test-support/pom.xml) |
| mdop-wms | mdop-common、mdop-security、mdop-master-data | [pom.xml](../../backend/mdop-wms/pom.xml) |

## 管理端页面

| 路径 | 源码 |
|---|---|
| `/purchasing` | [PurchasingView.vue](../../frontend/apps/admin/src/views/PurchasingView.vue) |
| `/users` | [UsersView.vue](../../frontend/apps/admin/src/views/UsersView.vue) |
| `/account` | [AccountView.vue](../../frontend/apps/admin/src/views/AccountView.vue) |
| `/freezes` | [FreezesView.vue](../../frontend/apps/admin/src/views/FreezesView.vue) |
| `/cross-transfers` | [CrossTransfersView.vue](../../frontend/apps/admin/src/views/CrossTransfersView.vue) |
| `/sales` | [SalesView.vue](../../frontend/apps/admin/src/views/SalesView.vue) |
| `/finished-goods` | [FinishedGoodsView.vue](../../frontend/apps/admin/src/views/FinishedGoodsView.vue) |
| `/production` | [ProductionView.vue](../../frontend/apps/admin/src/views/ProductionView.vue) |
| `/issues` | [IssuesView.vue](../../frontend/apps/admin/src/views/IssuesView.vue) |
| `/counts` | [CountsView.vue](../../frontend/apps/admin/src/views/CountsView.vue) |
| `/warehouses` | [WarehousesView.vue](../../frontend/apps/admin/src/views/WarehousesView.vue) |
| `/catalog` | [CatalogView.vue](../../frontend/apps/admin/src/views/CatalogView.vue) |
| `/messages` | [MessagesView.vue](../../frontend/apps/admin/src/views/MessagesView.vue) |
| `/quality` | [QualityView.vue](../../frontend/apps/admin/src/views/QualityView.vue) |
| `/transfers` | [TransfersView.vue](../../frontend/apps/admin/src/views/TransfersView.vue) |
| `/inventory` | [InventoryView.vue](../../frontend/apps/admin/src/views/InventoryView.vue) |
| `/purchase-returns` | [PurchaseReturnsView.vue](../../frontend/apps/admin/src/views/PurchaseReturnsView.vue) |
| `/corrections` | [CorrectionsView.vue](../../frontend/apps/admin/src/views/CorrectionsView.vue) |
| `/receiving` | [ReceivingView.vue](../../frontend/apps/admin/src/views/ReceivingView.vue) |

根路径及未知路径跳转见 [index.ts](../../frontend/apps/admin/src/router/index.ts)。页面可见性不代替后端授权。

## HTTP 控制器

表中为类级前缀；无类前缀时列方法路径。方法数量不包含 Spring Security 登录/退出和框架健康接口；此表不是 OpenAPI 契约，也不将模拟接口当作真实系统适配。

| 控制器 | 路径前缀 / 方法路径 | 映射方法数 | Profile 限制 |
|---|---|---:|---|
| [ErpMessageController.java](../../backend/mdop-integration/src/main/java/io/github/acczff/mdop/integration/local/ErpMessageController.java) | `/api/local/erp-messages` | 2 | local,test |
| [ErpSimulatorController.java](../../backend/mdop-integration/src/main/java/io/github/acczff/mdop/integration/local/ErpSimulatorController.java) | `/api/local/erp-arrivals` | 2 | local,test |
| [DeliveryController.java](../../backend/mdop-integration/src/main/java/io/github/acczff/mdop/integration/messaging/DeliveryController.java) | `/api/integration/messages` | 6 | — |
| [FinishedFeedbackController.java](../../backend/mdop-integration/src/main/java/io/github/acczff/mdop/integration/messaging/FinishedFeedbackController.java) | `/api/integration/finished-receipts` | 1 | — |
| [IssueFeedbackController.java](../../backend/mdop-integration/src/main/java/io/github/acczff/mdop/integration/messaging/IssueFeedbackController.java) | `/api/integration/material-issues` | 1 | — |
| [SalesFeedbackController.java](../../backend/mdop-integration/src/main/java/io/github/acczff/mdop/integration/messaging/SalesFeedbackController.java) | `/api/integration/sales-orders` | 1 | — |
| [CatalogController.java](../../backend/mdop-master-data/src/main/java/io/github/acczff/mdop/masterdata/catalog/CatalogController.java) | `/api/master-data` | 10 | — |
| [WarehouseController.java](../../backend/mdop-master-data/src/main/java/io/github/acczff/mdop/masterdata/warehouse/api/WarehouseController.java) | `/api/master-data/warehouses` | 5 | — |
| [PurchaseController.java](../../backend/mdop-purchasing/src/main/java/io/github/acczff/mdop/purchasing/PurchaseController.java) | `/api/v1/purchasing/documents` | 8 | — |
| [SessionController.java](../../backend/mdop-security/src/main/java/io/github/acczff/mdop/security/SessionController.java) | `GET /api/auth/csrf`<br>`GET /api/auth/me` | 2 | — |
| [AccountController.java](../../backend/mdop-system/src/main/java/io/github/acczff/mdop/system/identity/AccountController.java) | `GET /api/iam/options`<br>`GET /api/iam/users`<br>`GET /api/iam/users/{id}/audit`<br>`POST /api/iam/users`<br>`POST /api/iam/users/{id}/access`<br>`POST /api/iam/users/{id}/status`<br>`POST /api/iam/users/{id}/password`<br>`POST /api/auth/password` | 8 | — |
| [CountController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/CountController.java) | `/api/v1/wms/counts` | 6 | — |
| [CrossTransferController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/CrossTransferController.java) | `/api/v1/wms/cross-transfers` | 10 | — |
| [FinishedGoodsController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/FinishedGoodsController.java) | `/api/v1/wms/finished-receipts` | 4 | — |
| [FreezeController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/FreezeController.java) | `/api/v1/wms/freezes` | 4 | — |
| [InventoryController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/InventoryController.java) | `/api/v1/wms/stock` | 3 | — |
| [IssueController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/IssueController.java) | `/api/v1/wms/issues` | 6 | — |
| [LocalFinishedGoodsController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/LocalFinishedGoodsController.java) | `/api/local/finished-receipts` | 3 | local,test |
| [LocalMesController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/LocalMesController.java) | `/api/local/mes-demands` | 2 | local,test |
| [LocalProductionController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/LocalProductionController.java) | `/api/local/mes-production` | 3 | local,test |
| [LocalSalesController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/LocalSalesController.java) | `/api/local/sales-orders` | 2 | local,test |
| [ProductionController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/ProductionController.java) | `/api/v1/wms/production` | 4 | — |
| [ProductionReversalController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/ProductionReversalController.java) | `/api/v1/wms/production-reversals` | 5 | — |
| [SalesController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/SalesController.java) | `/api/v1/wms/sales-orders` | 9 | — |
| [TransferController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/inventory/TransferController.java) | `/api/v1/wms/transfers` | 3 | — |
| [CorrectionController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/receiving/CorrectionController.java) | `/api/v1/wms/corrections` | 5 | — |
| [LocalQualityController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/receiving/LocalQualityController.java) | `/api/local/qms-results` | 1 | local,test |
| [PurchaseReturnController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/receiving/PurchaseReturnController.java) | `/api/v1/wms/purchase-returns` | 7 | — |
| [QualityController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/receiving/QualityController.java) | `/api/v1/wms/quality` | 3 | — |
| [ReceivingController.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/receiving/ReceivingController.java) | `/api/v1/wms` | 9 | — |

## Flyway 迁移

历史迁移不可改写；新结构新增版本。下表不代表任意运行环境已应用迁移。

| 版本文件 | 所属模块 |
|---|---|
| [V202607210001__create_mdm_warehouse.sql](../../backend/mdop-master-data/src/main/resources/db/migration/masterdata/V202607210001__create_mdm_warehouse.sql) | mdop-master-data |
| [V202610050001__receiving_master_data.sql](../../backend/mdop-master-data/src/main/resources/db/migration/masterdata/V202610050001__receiving_master_data.sql) | mdop-master-data |
| [V202610050002__receiving_core.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610050002__receiving_core.sql) | mdop-wms |
| [V202610050003__reliable_messaging.sql](../../backend/mdop-integration/src/main/resources/db/migration/integration/V202610050003__reliable_messaging.sql) | mdop-integration |
| [V202610050004__receiving_corrections.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610050004__receiving_corrections.sql) | mdop-wms |
| [V202610050005__simulated_corrections.sql](../../backend/mdop-integration/src/main/resources/db/migration/integration/V202610050005__simulated_corrections.sql) | mdop-integration |
| [V202610060001__quality_putaway.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610060001__quality_putaway.sql) | mdop-wms |
| [V202610060002__purchase_returns.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610060002__purchase_returns.sql) | mdop-wms |
| [V202610060003__simulated_purchase_returns.sql](../../backend/mdop-integration/src/main/resources/db/migration/integration/V202610060003__simulated_purchase_returns.sql) | mdop-integration |
| [V202610060004__warehouse_transfers.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610060004__warehouse_transfers.sql) | mdop-wms |
| [V202610070001__inventory_counts.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610070001__inventory_counts.sql) | mdop-wms |
| [V202610070002__material_issues.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610070002__material_issues.sql) | mdop-wms |
| [V202610070003__issue_feedback.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610070003__issue_feedback.sql) | mdop-wms |
| [V202610070004__mes_results.sql](../../backend/mdop-integration/src/main/resources/db/migration/integration/V202610070004__mes_results.sql) | mdop-integration |
| [V202610070005__production_usage.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610070005__production_usage.sql) | mdop-wms |
| [V202610070006__production_reversals.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610070006__production_reversals.sql) | mdop-wms |
| [V202610070007__finished_goods.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610070007__finished_goods.sql) | mdop-wms |
| [V202610070008__warehouse_results.sql](../../backend/mdop-integration/src/main/resources/db/migration/integration/V202610070008__warehouse_results.sql) | mdop-integration |
| [V202610070009__sales_outbound.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610070009__sales_outbound.sql) | mdop-wms |
| [V202610070010__cross_warehouse_transfer.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610070010__cross_warehouse_transfer.sql) | mdop-wms |
| [V202610070011__inventory_freeze.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610070011__inventory_freeze.sql) | mdop-wms |
| [V202610070012__partial_transfer_receiving.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610070012__partial_transfer_receiving.sql) | mdop-wms |
| [V202610080001__identity_access.sql](../../backend/mdop-system/src/main/resources/db/migration/system/V202610080001__identity_access.sql) | mdop-system |
| [V202610080002__catalog_lifecycle.sql](../../backend/mdop-master-data/src/main/resources/db/migration/masterdata/V202610080002__catalog_lifecycle.sql) | mdop-master-data |
| [V202610080003__master_data_snapshots.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610080003__master_data_snapshots.sql) | mdop-wms |
| [V202610090001__purchase_documents.sql](../../backend/mdop-purchasing/src/main/resources/db/migration/purchasing/V202610090001__purchase_documents.sql) | mdop-purchasing |
| [V202610090002__purchase_arrangements.sql](../../backend/mdop-purchasing/src/main/resources/db/migration/purchasing/V202610090002__purchase_arrangements.sql) | mdop-purchasing |
| [V202610090003__purchase_arrival_source.sql](../../backend/mdop-wms/src/main/resources/db/migration/wms/V202610090003__purchase_arrival_source.sql) | mdop-wms |
| [V202610090004__purchase_closure.sql](../../backend/mdop-purchasing/src/main/resources/db/migration/purchasing/V202610090004__purchase_closure.sql) | mdop-purchasing |
