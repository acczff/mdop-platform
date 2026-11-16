# 模块模板

> 类型：参考 | 状态：稳定 | 适用：0.1 开发分支 | 责任：平台组

## 四层与职责

```
<module>/src/main/java/.../<domain>/
├─ api/              接口层
├─ application/      应用层
├─ domain/           领域层
└─ infrastructure/   基础设施层
```

| 层 | 放什么 | 允许依赖 | 禁止 |
|---|---|---|---|
| `api` | 控制器、请求/响应模型、参数校验、权限注解 | `application` | 业务规则、SQL、基础设施实现 |
| `application` | 用例服务、事务、幂等、审计、跨模块契约调用、查询端口 | `domain`、`common` | 新增读查询 SQL、Web 类型 |
| `domain` | 仓储接口、值对象、状态规则 | JDK | Spring、JDBC、Web |
| `infrastructure` | 仓储实现、SQL、行映射、外部适配 | `domain`、Spring、JDBC | 业务分支、事务边界 |

依赖方向：**api → application → domain ← infrastructure**。

### 两处刻意保留的例外

1. **查询端口由使用方拥有**。收货域的 `ReceivingQueries` 放在 `application`，实现 `JdbcReceivingQueries` 在
   `infrastructure`。原因：端口返回的是带 `@JsonFormat` 的读模型，若下沉到 `domain` 会把序列化注解带进领域层。
   这类端口只读、只返回记录，不改变依赖方向（`application` 不引用实现类）。
2. **写语句与少量读辅助类暂留应用层**。采购与收货已把主子表的读查询搬进仓储/端口，但 `INSERT`/`UPDATE`
   仍在用例服务里，与事务边界同处；`PurchaseFulfillment`、`SalesFulfillment` 这类“按单据汇总事实”的辅助类
   也仍在 `application`。迁移它们要先有“写仓储 + 返回受影响行数”和“事实查询对象”的约定，属下一阶段工作。
   新代码不得再往 `application` 里加读查询 SQL。

## 命名

| 类型 | 命名 | 例子 |
|---|---|---|
| 控制器 | `XxxController` | `PurchaseController` |
| 请求/响应 | `XxxModels` 内的 record | `Draft`、`Action` |
| 用例服务 | `XxxService` | `PurchaseService` |
| 仓储接口 | `XxxRepository` | `PurchaseRepository` |
| 仓储实现 | `JdbcXxxRepository` | `JdbcPurchaseRepository` |
| 查询端口 | `XxxQueries` | `ReceivingQueries` |
| 查询端口实现 | `JdbcXxxQueries` | `JdbcReceivingQueries` |
| 查询对象 | `XxxQuery` | `InventoryBalanceQuery` |
| 外部适配 | `XxxAdapter` / `XxxSource` | `SalesShippingAdapter` |

## 已落地样例

| 模块 | 分层情况 | 数据访问 |
|---|---|---|
| `mdop-purchasing` | `api` / `application` / `domain` / `infrastructure` | `PurchaseRepository` + `JdbcPurchaseRepository`，主代码 `SELECT *` 为 0 |
| `mdop-wms/receiving` | 同上，另保留 `ReceivingAccess` 在域根（被 inventory 共用） | `ReceivingQueries`/`JdbcReceivingQueries`（读）+ `ArrivalLockPort`/`JdbcArrivalLock`（锁），收货与质检服务 `SELECT *` 为 0 |
| `mdop-master-data/warehouse` | 早于本模板的四层分法（`api`/`application`/`domain`/`infrastructure`） | `WarehouseRepository` + `WarehouseSpecifications` |
| `mdop-sales` / `mdop-manufacturing` / `mdop-finance` | 四层归位，`domain` 暂为空包（等规则沉淀） | 读查询仍在应用层，按切片迁移 |

行为均未变：接口路径、返回字段、事务边界、锁顺序、幂等语义保持原样。

## 关键约束（改动前必读）

- **加锁顺序固定**：仓库 → 单据/到货通知 → 余额（按 `stockKey` 排序）。任何新路径都必须遵守，否则与收货提交形成环路死锁。
- **幂等键不换**：失败后必须用同一个键重试；换键会绕过校验产生重复单据。
- **事务边界只在应用层**：控制器与仓储实现都不开事务。
- **查询用显式列名**：新增查询不要用 `SELECT *`，避免建表后返回结构悄悄变化。
- **跨模块只走 `common` 契约**：控制器与用例服务不得直接依赖其它模块的 Service。

## 迁移步骤（每个模块照此执行）

1. **建目录**：`api`、`application`、`domain`、`infrastructure`，各加 `package-info.java` 说明职责。
2. **搬类不改行为**：先移动文件、改包名与导入，跑该模块相关测试。
3. **抽读查询**：把 `SELECT` 从服务搬到端口/仓储，接口按“使用方拥有”放置；一次只搬一组查询。
4. **显式列名**：替换 `SELECT *`，保持字段名不变，避免接口漂移。
5. **补注释**：公共类写职责与约束，SQL 写业务意图。
6. **跑全量**：模块测试 + 全量回归 + 文档检查 + 架构边界检查。

## 验证命令

```bash
node scripts/checks/architecture/check.mjs        # 边界与分层规则（新增违规退出码 1）
node --test scripts/checks/architecture/check.test.mjs
node scripts/checks/docs/check.mjs                # 链接、锚点、源码导航
./backend/mvnw -f backend/pom.xml verify   # 全量后端回归
```

## 新模块接入清单

- [ ] 模块已登记到父 POM 与 `mdop-boot` 依赖
- [ ] 四层目录与 `package-info` 就位
- [ ] 表前缀、迁移版本已确认唯一
- [ ] 读查询在端口/仓储，SQL 在 `infrastructure`
- [ ] 控制器只做映射、校验、权限
- [ ] 写操作有幂等与版本校验
- [ ] 事务边界只在应用层
- [ ] 相关测试覆盖正常、异常、权限、并发
- [ ] `node scripts/checks/architecture/check.mjs` 无新增违规
- [ ] [源码导航](../10-architecture/source-index.md)已更新
