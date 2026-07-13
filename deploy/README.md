# Deploy（部署）

本目录保存 MDOP 平台可版本化的部署资源，包括本地基础设施编排、应用镜像构建、环境配置模板、健康检查和后续监控配置。

真实凭据、生产配置、数据库备份和运行数据不得进入仓库。

## 1. 当前范围

当前已经建立：

- MySQL 8.4.10 LTS（长期支持版）本地容器
- MySQL 健康检查
- MySQL 数据卷持久化
- 本地环境变量模板
- 仅本机开放的数据库端口

RabbitMQ（消息队列）、Redis（缓存数据库）和应用容器将在后续迭代加入。

## 2. 目录结构

```text
deploy
├─ compose
│  └─ compose.local.yml
├─ env
│  ├─ .env.example
│  └─ .env.local
├─ docker
└─ monitoring
```

说明：

- `compose.local.yml`：本地容器编排配置
- `.env.example`：可以提交的环境变量模板
- `.env.local`：包含本地真实密码，不得提交
- `docker`：后续保存应用镜像构建文件
- `monitoring`：后续保存监控配置

## 3. 前置条件

本地需要安装并启动：

- Docker Desktop（Docker 桌面程序）
- Docker Engine（Docker 容器引擎）
- Docker Compose（容器编排工具）

检查命令：

```powershell
docker version
docker compose version
docker context show
```

当前应使用：

```text
desktop-linux
```

## 4. 首次启动

以下命令必须在项目根目录执行。

复制本地环境配置：

```powershell
Copy-Item deploy/env/.env.example deploy/env/.env.local
```

修改 `.env.local`，为应用账号和管理员账号设置两个不同的本地密码。

不要将真实密码写入 `.env.example`，也不要提交 `.env.local`。

校验 Compose 配置：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml config --quiet
```

启动 MySQL：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml up -d mysql
```

查看运行状态：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml ps
```

MySQL 正常运行时应显示：

```text
healthy
```

## 5. 常用操作

查看日志：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml logs --tail 100 mysql
```

持续查看日志：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml logs -f mysql
```

停止 MySQL，但保留容器：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml stop mysql
```

重新启动已停止的 MySQL：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml start mysql
```

删除容器和网络，但保留数据卷：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml down
```

## 6. 数据持久化与清理

MySQL 数据保存在命名数据卷：

```text
mdop-local_mysql-data
```

执行普通的 `down` 后，数据卷仍然保留。

以下命令会删除容器、网络和数据卷，并永久清空本地数据库：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml down -v
```

只有在确认本地数据库没有需要保留的数据时才能执行该命令。

修改 MySQL 初始化账号或初始化密码后，需要删除旧数据卷并重新初始化；这些初始化变量不会自动修改已有数据库中的账号。

## 7. 安全约束

- MySQL 端口只绑定到 `127.0.0.1`，不向局域网公开。
- Spring Boot 应用使用普通账号，不使用 `root` 管理员账号。
- 普通账号密码和管理员密码必须不同。
- `.env.local`、数据库备份和运行数据不得提交。
- 本地环境变量方案不得直接作为生产环境密钥方案。
- 生产环境必须使用专门的 Secrets（密钥管理机制）。

## 8. 故障排查

Docker 服务无法连接时：

```powershell
docker version
docker context show
```

查看容器状态：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml ps
```

查看 MySQL 最近日志：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml logs --tail 100 mysql
```

如果端口 `3306` 被占用，应先查明占用程序，不要直接结束未知进程，也不要随意修改团队约定端口。
