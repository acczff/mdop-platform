# 运维索引

> 类型：导航 | 状态：当前 | 适用：0.1 开发分支 | 责任：平台组

| 场景 | 入口 |
|---|---|
| 本地启动与停止 | [脚本说明](../../scripts/README.md)、[快速开始](../00-start/quickstart.md) |
| 容器与端口 | [部署说明](../../deploy/README.md) |
| 发布包与升级 | [发布与恢复手册](../../deploy/release/RUNBOOK.md) |
| 备份、恢复与演练 | [发布与恢复保障](release-recovery.md) |
| CI 检查与产物 | [CI 自动验收](ci.md) |
| 当前版本与验证证据 | [版本记录](status.md) |

## 排障顺序

1. 看健康检查：`/actuator/health` 的 db、rabbit 是否为 UP。
2. 看应用日志：本地在 `logs/local/`，容器看 `docker logs`。
3. 看数据库迁移：启动失败先确认 Flyway 版本与校验和。
4. 看端口占用：后端、前端、MySQL、RabbitMQ、Redis。
5. 看消息：Outbox/Inbox 是否有积压或死信。

## 注意

- 本仓库没有跨平台一键启动脚本；Linux 由 CI 覆盖验证。
- 升级既有数据库前先备份并验证恢复，空库首次启动与升级是两种操作。
- 本地脚本会停止整个 Compose 项目的服务，不区分是否由本次启动。
