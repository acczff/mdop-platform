# 架构总览

> 类型：解释 | 状态：当前 | 适用：0.1 开发分支 | 责任：平台组

## 一张图

```
浏览器（Vue 管理端）
      │  同源 /api + 会话 + CSRF
      ▼
mdop-boot            启动、全局异常、时钟、跨模块测试
      │
      ├── mdop-security   认证与会话
      ├── mdop-system     账号、角色、权限
      ├── mdop-master-data 主数据（仓库聚合用 JPA，其余 JDBC）
      ├── mdop-purchasing 采购
      ├── mdop-sales      销售
      ├── mdop-manufacturing 生产
      ├── mdop-finance    财务
      ├── mdop-wms        仓储
      └── mdop-integration 消息与外部适配
                    │
                    ▼
              MySQL 8.4（Flyway 管理表结构）
```

跨模块协作只通过 `mdop-common` 的接口（端口），实现放在 `mdop-integration` 或各业务模块。

## 分层

单个模块内部按四层组织：

| 层 | 负责 | 不负责 |
|---|---|---|
| 接口层 `api` | HTTP 映射、参数校验、功能权限 | 业务规则、SQL |
| 应用层 `application` | 用例编排、事务、幂等、审计、查询端口 | 读查询 SQL、框架细节 |
| 领域层 `domain` | 业务规则、状态机、仓储接口 | Spring、数据库 |
| 基础设施层 `infrastructure` | SQL、行映射、外部适配 | 业务分支 |

依赖方向：接口 → 应用 → 领域 ← 基础设施。详见[模块模板](../30-development/module-template.md)。

当前落地范围（其余模块仍按子域平铺，迁移时按模板逐个推进）：

| 模块 | 分层 | 数据访问 |
|---|---|---|
| `mdop-master-data/warehouse` | 四层完整 | `WarehouseRepository` |
| `mdop-purchasing` | 四层完整 | `PurchaseRepository` |
| `mdop-wms/receiving` | 四层完整 | `ReceivingQueries`（查询端口） |
| `mdop-sales` / `mdop-manufacturing` / `mdop-finance` | 四层归位，`domain` 暂空 | 读查询仍在应用层，待按切片迁移 |

层间方向由 `node scripts/checks/architecture/check.mjs` 自动校验：`api` 不得引用 `infrastructure`，
`domain` 不得依赖 Spring/JDBC。

## 关键约束

- 数量与金额以十进制字符串传输，禁止浮点累计。
- 库存写入必须与流水、单据同事务；冲正保留原记录。
- 写操作遵循“仓库 → 单据 → 余额”的加锁顺序。
- 幂等键绑定操作者与载荷，失败后原样重试。
- 迁移不可改写，表结构以 Flyway 为准。

## 继续阅读

| 文档 | 内容 |
|---|---|
| [代码阅读与调用链](code-reading.md) | 从一笔收货读到 SQL |
| [源码导航](source-index.md) | 自动生成的模块、页面、控制器、迁移清单 |
| [接口约定](api-contract.md) | 错误、分页、幂等、版本 |
| [架构决策](../50-decisions/README.md) | 为什么采用当前方案 |
