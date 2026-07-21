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

I1.1 已在 `mdop-master-data` 实现仓库主数据后端闭环，统一接口前缀为 `/api/master-data/warehouses`。当前能力包括创建、详情、分页筛选、启停、受控修改、审计、乐观并发控制和统一错误响应；不包含删除接口、前端页面、库区、库位或库存业务。

数据库结构由 `db/migration/masterdata/V202607210001__create_mdm_warehouse.sql` 管理。完整业务边界、字段、接口和验收标准见 [I1.1 仓库主数据方案](../docs/project/I1.1仓库主数据方案.md)。

## 最小安全基线

`mdop-security` 负责 Spring Security 配置，`mdop-boot` 负责装配与集成测试。`/actuator/health` 允许匿名健康探测，其余 HTTP 接口默认需要认证。

当前只建立公开与受保护接口的边界，不实现用户、角色、登录、JWT 或数据权限。`mdop-boot` 使用仅测试范围的 `spring-security-test` 验证匿名请求会被拒绝，以及临时测试身份可以访问受保护接口。

模块职责和依赖规则以 [I0 工程基线方案](../docs/project/I0工程基线方案.md) 为准。
