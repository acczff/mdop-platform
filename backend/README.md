# Backend

后端已在 I0 迭代中建立 Java 25 LTS、Spring Boot 和 Maven 多模块的模块化单体工程。

当前模块包括：

- `mdop-boot`
- `mdop-common`
- `mdop-security`
- `mdop-system`
- `mdop-master-data`
- `mdop-wms`
- `mdop-integration`
- `mdop-test-support`

## 共享基础设施测试

`mdop-test-support` 集中提供 MySQL、RabbitMQ 和 Redis 的 Testcontainers 测试基座。测试使用固定镜像版本、随机宿主机端口和运行时临时凭据，不依赖本地 Compose、固定容器或 `.env.local`。

`mdop-boot` 仅以测试范围依赖 `mdop-test-support`，Testcontainers 依赖不会进入生产类路径。

从仓库根目录执行完整后端测试：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml test
```

执行测试需要 Java 25 和正在运行的 Docker Engine，但不需要提前启动本地 Compose 服务。

## 后端质量门禁

从仓库根目录执行完整后端验证：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml verify
```

该命令会检查 Java 和 Maven 版本、依赖收敛、启动模块依赖边界与 Java 格式，并运行后端测试、生成 JaCoCo 覆盖率报告。共享 Testcontainers 回归继续使用随机宿主机端口和运行时临时凭据，不依赖 `.env.local` 或本机固定容器。

需要同时验证后端和前端时，使用仓库级入口：

```powershell
.\mdop.cmd verify
```

当前覆盖率仅生成报告，不设置失败阈值。`mdop-boot` 报告入口位于 `backend/mdop-boot/target/site/jacoco/index.html`。

## 仓库主数据

`mdop-master-data` 实现仓库主数据后端闭环，统一接口前缀为 `/api/master-data/warehouses`。当前能力包括创建、详情、分页筛选、启停、受控修改、审计、乐观并发控制和统一错误响应；不包含删除接口。已修复小数版本号、规范化空名称及超大分页偏移的校验问题。

## 会话与收货切片

- `GET /api/auth/csrf` 获取令牌；`POST /api/auth/login` 使用表单账号密码与 CSRF；`GET /api/auth/me` 查询身份；`POST /api/auth/logout` 退出。登录后令牌轮换，客户端重新获取。
- 启动配置提供 ADMIN 与可配置操作员，使用会话、CSRF、操作权限和仓库范围控制；不是完整 IAM。需要异人审核的操作不能由申请人自审，即使申请人是 ADMIN。
- `/api/master-data/suppliers|materials|locations` 提供新建和查询；策略创建后暂不支持修改。库位区域限定为收货暂存、待检或存储。
- `/api/v1/wms/arrival-notices` 和 `/receipts` 按 WMS 设计提供任务、草稿与提交。保存草稿不产生库存；提交时锁定通知和收货单，条件更新累计数量，按固定库存维度顺序锁余额，原子写流水、操作记录和 Outbox。
- `local`/`test` 下的 `/api/local/erp-arrivals` 模拟 ERP 发布到货消息，经 RabbitMQ 与 Inbox 幂等消费建单；真实 ERP 适配器尚未接入。
- 数据库结构由 Flyway 管理；当前候选实现包含 22 个迁移，覆盖主数据及现有 WMS 流程。日常数据库升级需单独安排，运行验收不代表已部署。
- 收货、通知、库存与流水的数量以十进制字符串返回；前端按文本输入和定点整数计算剩余量，保留 `DECIMAL(18,6)` 精度，不经 JavaScript Number 转换。
- Outbox 与业务事务原子落库，后台发布至 RabbitMQ，支持失败重试、隔离及人工重放；发布成功与模拟外部端收到是两个不同的核验结果。外部系统可用性不纳入库存事务。

集成测试验证草稿与提交分离、分批收货、超收、幂等、不可变收货、批次/库位、操作及仓库权限、并发竞争、真实数据库写失败回滚。故障注入仅在临时测试表上增加约束并在 finally 中移除；不会修改本地 Compose 数据库。

数据库结构由 `db/migration/masterdata/V202607210001__create_mdm_warehouse.sql` 管理。完整业务边界、字段、接口和验收标准见 [I1.1 仓库主数据方案](../docs/project/I1.1仓库主数据方案.md)。

## 最小安全基线

`mdop-security` 负责 Spring Security 配置，`mdop-boot` 负责装配与集成测试。`/actuator/health` 允许匿名健康探测，其余 HTTP 接口默认需要认证。

当前实现会话登录、退出、CSRF、操作权限与仓库范围校验，未实现用户管理、授权管理或 JWT。`mdop-boot` 的测试覆盖未认证、无权限、越仓及异人审核等边界。

采购入库、生产物料协同、成品出入库和库存运营的有限验收范围及本轮证据见 [WMS 公共流程验收清单](../docs/project/WMS公共流程验收清单.md)。

模块职责和依赖规则以 [I0 工程基线方案](../docs/project/I0工程基线方案.md) 为准。
