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

模块职责和依赖规则以 [I0 工程基线方案](../docs/project/I0工程基线方案.md) 为准。
