# Scripts

本目录提供 MDOP 仓库级操作脚本。Windows 用户从仓库根目录通过 `mdop.cmd` 调用，不需要直接执行 `scripts/mdop.ps1`。

## 命令

```powershell
.\mdop.cmd verify
.\mdop.cmd start
.\mdop.cmd status
.\mdop.cmd stop
```

| 动作 | 作用 | 是否需要 `.env.local` | 是否需要 Docker Engine |
|---|---|---:|---:|
| `verify` | 依次执行后端 Maven `verify` 和前端 pnpm `verify` | 否 | 是，后端 Testcontainers 回归需要 |
| `start` | 准备依赖和构建产物，启动基础设施、后端与前端并等待就绪 | 是 | 是 |
| `status` | 显示全部 Compose 容器以及脚本管理的应用进程状态 | 是 | 是 |
| `stop` | 停止脚本管理的应用进程和全部本地 Compose 服务 | 是 | 是 |

不传动作时，`.\mdop.cmd` 默认执行 `verify`。任一动作失败都会返回非零退出码。

## 运行前提

- Windows PowerShell 5.1 或更高版本；
- Java 25；
- Node.js `>=24.18.0 <25`；
- pnpm `11.13.0`，命令名为 `pnpm.cmd`；
- `start`、`status` 和 `stop` 使用 `deploy/env/.env.local`；
- Docker Desktop 已启动，Docker Engine 可访问；
- 后端端口 `8080` 和前端端口 `5173` 未被其他程序占用。

## `verify` 验证边界

`verify` 复用两个已经存在的质量入口，不维护第二套检查逻辑：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml verify
pnpm.cmd --dir .\frontend run verify
```

后端完整验证包含共享 Testcontainers 测试。测试容器使用随机宿主机端口和运行时临时凭据，不读取 `.env.local`，也不要求预先启动本机固定 Compose 容器。

## `start` 启动范围

`start` 按以下顺序执行：

1. 校验所需文件、命令、本地配置、应用端口和 Docker Engine；
2. 校验 Compose 配置；
3. 使用 `pnpm.cmd install --frozen-lockfile` 准备前端依赖；
4. 使用 Maven Wrapper 打包 `mdop-boot` 及其依赖模块，不在此阶段重复运行测试；
5. 启动并等待 MySQL、RabbitMQ 和 Redis 健康；
6. 以 `local` 配置启动后端，等待 `/actuator/health` 返回成功；
7. 启动 admin 前端，等待 `http://127.0.0.1:5173/` 返回成功；
8. 两端都就绪后才写入运行状态文件。

后端和前端作为隐藏的后台进程运行：

| 项目 | 位置 |
|---|---|
| 后端地址 | `http://127.0.0.1:8080` |
| 前端地址 | `http://127.0.0.1:5173` |
| 应用日志 | `logs/local/` |
| 运行状态 | `tmp/mdop-local-state.json` |

`logs/` 和 `tmp/` 均已被 Git 忽略。状态文件同时记录 PID（进程标识符）和进程启动时间，`status` 与 `stop` 会同时校验两者，避免 PID 被系统复用后误操作其他进程。

## 停止和失败清理

`stop` 先停止前端和后端，再执行 Compose `stop`。该操作保留容器、网络和命名数据卷；只有应用与基础设施都成功停止后，运行状态文件才会删除。重复执行 `stop` 是安全的。

需要注意：仓库级 `stop` 会停止当前 Compose 项目中的全部 MySQL、RabbitMQ 和 Redis 服务，不区分这些服务是否由最近一次 `start` 启动。

如果 `start` 中途失败，脚本会：

1. 停止本次启动的前端和后端进程；
2. 只停止本次执行前没有运行、但被本次执行启动的 Compose 服务；
3. 删除未完成的运行状态文件；
4. 保留日志并返回非零退出码。

## 常见问题

- 提示端口被占用：先查明 `8080` 或 `5173` 的监听程序，不要结束未知进程。
- 提示 Docker Engine 不可用：启动 Docker Desktop，并检查 `docker version` 与 `docker context show`。
- 应用未能就绪：查看错误信息列出的 `logs/local/` 日志文件。
- 提示已有应用正在运行：先执行 `.\mdop.cmd status`，确认后使用 `.\mdop.cmd stop`。
- 仅需操作基础设施时：使用 [deploy/README.md](../deploy/README.md) 中的底层 Compose 命令。
