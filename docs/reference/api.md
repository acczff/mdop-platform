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

带幂等键的接口重试须复用原键和原业务载荷；版本冲突先读最新状态。有些操作仅由状态/版本控制，账号创建、授权等并不提供通用幂等键。账号写入网络失败、409、5xx 或解析失败后必须成功刷新核对，不能通过关闭弹窗绕过限制。

`/api/local/*` 仅在 `local`/`test` 下启用，并需模拟权限；模拟接口与消息管理员的能力不能当作细粒度业务仓库隔离。真正的外部接入还需单独确定身份、签名、契约、重试和对账责任，不开放这些模拟端点代替生产集成。

接口服务端校验和异人审核不因前端隐藏按钮而省略。`SYSTEM_ADMIN` 只有 `iam:manage`，不具备旧版 `ROLE_ADMIN` 的业务全权限；代码中的兼容权限分支不表示可以在账号页面分配该旧角色。
