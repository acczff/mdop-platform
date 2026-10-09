# HTTP 接口约定

当前没有 OpenAPI/Swagger 发布页。本文统一跨接口约定；全部控制器入口见[源码导航](source-index.md)，具体请求字段、默认分页与状态以控制器、请求 record/DTO、服务校验及对应集成测试为准。模拟端点不能作为生产系统集成契约。

## 登录与会话

1. `GET /api/auth/csrf`，保存响应中的 `headerName` 与 `token`，保留服务端 Cookie。
2. `POST /api/auth/login`，表单格式 `application/x-www-form-urlencoded`，字段 `username`、`password`，同时携带上一步 CSRF 头与 Cookie；成功返回 204。
3. 登录后 CSRF 会轮换，重新 `GET /api/auth/csrf`。`GET /api/auth/me` 返回规范账号名和 authorities。
4. 后续写请求携带最新 CSRF 和同一会话。`POST /api/auth/logout` 成功返回 204；本人改密也会使当前会话退出。

浏览器使用同源 `/api`，Cookie 为 HttpOnly、SameSite=Strict，默认会话超时 30 分钟；没有 JWT。账号停用、启用、权限或密码变化递增版本，旧会话下一请求失效。登录/退出由安全过滤链处理，因此不出现在自动控制器表中。

可从已登录的前端浏览器网络面板查看请求；不要把包含 Cookie、密码和 CSRF 的复制请求贴到 Issue。客户端实现参考 [api.ts](../../frontend/apps/admin/src/api.ts) 和 [App.vue](../../frontend/apps/admin/src/App.vue)。

## 数据与错误

| 项目 | 约定 |
|---|---|
| 数量 | 输入可采用十进制字符串，例如 `"60.000000"`；最大整数 12 位、小数 6 位，正负/零限制按动作判断；响应数量字符串不转浮点累计 |
| 日期 | 业务日期 `YYYY-MM-DD`；审计时间读取接口实际时区信息，前端按设备时区显示，不自行加偏移 |
| 版本 | 需版本的写入带最新非负整数 `version`，不接受小数版本 |
| 分页 | 无统一全局分页结构：仓库/库存等分页接口与目录数组接口不同；先看所属控制器返回类型 |
| 400 | 请求格式、字段、数量或业务输入不合法；根据 detail/fieldErrors 修正 |
| 401 | 未登录或账号会话失效；重新认证 |
| 403 | 操作权限、仓库范围或 CSRF 不通过；不能靠重发解决 |
| 404 / 409 | 分别检查资源是否存在，以及版本、状态、唯一性或幂等冲突 |
| 5xx / 网络中断 | 写入结果可能已生效，先查询结果与流水，再决定是否重试 |

业务异常一般为 Spring ProblemDetail，包含 `status`、`detail`、业务 `code`，字段错误可带 `fieldErrors`。认证过滤器和代理错误不保证同样 JSON 结构，客户端必须容忍非 JSON 响应；204 不解析响应体。

## 主数据维护

主数据目录新增维护、启停与审计接口，完整字段、版本规则及旧单位参数兼容说明见[主数据实现与验收](../erp/主数据实现与验收.md)。基础资料创建按唯一编码防重，维护按 version 防覆盖；均不使用收货单据的幂等键协议。

## 采购需求与订单

`/api/v1/purchasing/documents` 提供 P1 需求与订单接口，字段和状态见[采购 P1](../erp/采购P1实现与验收.md)以及其链接的请求模型。GET 列表的 `warehouseId`、`kind=REQUEST|ORDER` 必填，`page` 从 0 开始，`size` 默认 20、最大 100；输出 `items,total,page,size`。所有写操作携带 `idempotencyKey`，编辑、状态动作、转单还需当前 `version` 与原因。同键同载荷返回原结果单据的当前状态；键不能跨操作者、路径或载荷重用。

前端在发送前将待确认请求保存在当前标签页的 sessionStorage，按账号隔离，刷新后仍可原样重试；这不替代服务端幂等。明确的 4xx 拒绝后重新读取再操作，网络/5xx/解析失败保留原请求。不能通过清理浏览器数据或换账号重新建单来解决未知结果。P1 不提供到货投递、库存过账或金额接口。

## 操作示例：收货草稿与提交

先通过页面或通知查询获取实际通知 ID、行 ID 和库位。下例只展示结构，数值 ID 必须替换；批次、Date Code、有效期的必填性取决于物料策略。

`POST /api/v1/wms/arrival-notices/{noticeId}/receipts`：

```json
{
  "idempotencyKey": "demo-draft-0001",
  "items": [{
    "arrivalItemId": 123,
    "locationId": 456,
    "quantity": "60.000000",
    "batchNo": "DEMO-BATCH-A"
  }]
}
```

从返回对象 `receipt` 读取草稿 ID 和 version；保存草稿不增加库存。随后 `POST /api/v1/wms/receipts/{receiptId}/submit`：

```json
{"idempotencyKey":"demo-submit-0001","version":0}
```

`version` 使用读取到的最新值，不永远写 0。提交与草稿创建分别使用自己的键。数量超通知剩余量、状态不允许、越仓或批次策略不符会被拒绝；具体模型见 [ReceivingModels.java](../../backend/mdop-wms/src/main/java/io/github/acczff/mdop/wms/receiving/ReceivingModels.java)。

## 重试和外部边界

人工补货仍通过 POST `/api/v1/purchasing/documents` 创建需求，可传 `originalOrderId`。原单必须存在、为同仓采购订单且已有实际退供，调用者须有原仓权限；不符合分别返回 404/409/403。该引用建单后不可更换；编辑省略或传 null 都保留原值，转采购订单时自动继承。返回字段为 `original_order_id`，详情 `related` 同仓双向列出来源及补货单据。关联不恢复原单额度、不自动批准或计算补货数量。

采购 P2 在 `/api/v1/purchasing/documents/{id}/arrangements` POST 创建安排，字段为 `idempotencyKey`、父单 `version`、`expectedDate`、`reason` 及 `lines[{orderLineId,quantity}]`。同路径追加 `/{arrangementId}/deliver` 或 `/withdraw` POST，使用 P1 动作请求（键、版本、原因），返回父单最新详情。详情 `arrangements` 带持久化行映射及 WMS 收货引用；未知结果仍按原键原载荷重试。待送达/失败占用额度，撤回确认才释放；所有安排撤回前拒绝取消订单。完整事务及来源边界见[采购 P2](../erp/采购P2实现与验收.md)。

采购 P3 的同一详情接口返回 `fulfillment`（逐行数量字符串、来源 `notices`、`blockers`、`canClose`、`outcome`）、`closure`（不可变快照、原因、操作者/时间）和 `closureMatches`。POST `/api/v1/purchasing/documents/{id}/actions/close` 使用原动作请求和 `purchasing:write` 权限；服务端重查事实，不接受客户端累计数或 `canClose`。少收、未决差异、待检/上架/退供、版本/来源冲突均返回 409。结案为 `CLOSED`，结果 `QUALIFIED` 或 `WITH_RETURNS`；`closureMatches=false` 表示需核对，不能覆盖原快照。详细口径见[采购 P3](../erp/采购P3实现与验收.md)。

## 销售订单与发货安排

入口 `/api/v1/sales/documents`。GET 列表带 `warehouseId`、零基 `page`、`size`，返回 `items/total/page/size`；GET `/{id}` 返回订单、`lines`、`arrangements`、`fulfillment`、`closure`、`closureMatches` 和审计。角色 `SALES_OPERATOR` 拥有 `sales:read/write`，`SALES_REVIEWER` 拥有 `sales:read/review`；每次均校验仓库范围。两个销售角色不能同账号授予，旧账号不会因角色目录扩展自动获得权限。

| 动作 | 路径与请求要点 |
|---|---|
| 新建 / 编辑 | POST 根路径 / PUT `/{id}`；`idempotencyKey,warehouseId,customerId,purpose,neededDate,customerReference?,lines[{materialId,quantity}]`；编辑还需 `version,reason` |
| 提交 / 审批 / 驳回 / 取消 / 结案 | POST `/{id}/actions/{submit,approve,reject,cancel,close}`；`idempotencyKey,version,reason` |
| 安排发货 | POST `/{id}/arrangements`；`idempotencyKey,version,orderLineId,quantity,expectedDate,reason`；一次一行，不接受客户端累计数 |
| 送达 / 撤回 | POST `/{id}/arrangements/{arrangementId}/{deliver,withdraw}`；`idempotencyKey,version,reason` |

已批准订单不允许改客户、仓库和数量。PENDING 安排包含尚未送达和失败待重试，继续占授权；WITHDRAWN 才释放。详情的 WMS 引用、批次、状态及实际数量用于跟踪，`fulfillment.lines` 给出 ordered/allocated/shipped/remaining 字符串。服务端重算结案门禁与流水，客户端 `canClose` 只控制展示。`closureMatches=false` 不覆盖原结案快照。正式销售不使用 `/api/local/sales-orders` 建单；该接口仅保留旧模拟来源，并拒绝 `MDOP-SALES-` 保留前缀。

## 重试与模拟边界

带幂等键的接口重试须复用原键和原业务载荷；版本冲突先读最新状态。有些操作仅由状态/版本控制，账号创建、授权等并不提供通用幂等键。账号写入网络失败、409、5xx 或解析失败后必须成功刷新核对，不能通过关闭弹窗绕过限制。

`/api/local/*` 仅在 `local`/`test` 下启用，并需模拟权限；模拟接口与消息管理员的能力不能当作细粒度业务仓库隔离。真正的外部接入还需单独确定身份、签名、契约、重试和对账责任，不开放这些模拟端点代替生产集成。

接口服务端校验和异人审核不因前端隐藏按钮而省略。`SYSTEM_ADMIN` 只有 `iam:manage`，不具备旧版 `ROLE_ADMIN` 的业务全权限；代码中的兼容权限分支不表示可以在账号页面分配该旧角色。

## BOM 版本接口

生产需求及工单接口位于同一根路径下的 `/api/v1/manufacturing/demands`、`/orders` 和 `/sales-sources`，使用独立 `manufacturing:read/write/review` 及成品仓权限；生产地点复用 `/api/master-data/production-sites`。请求和生命周期详见[生产订单接口](../erp/生产订单实现与验收.md#3-页面接口和迁移)。

`/api/v1/manufacturing/boms` 提供列表、详情、草稿创建/编辑、复制及发布/停用。读取要求 `bom:read`，写入要求 `bom:write`；单组织共享资料不按仓库分组，系统管理员和旧 ADMIN 不绕过权限。请求字段、状态及恢复规则见 [BOM 接口表](../erp/BOM版本实现与验收.md#3-页面与接口)。BOM 发布不生成生产订单或库存动作。
