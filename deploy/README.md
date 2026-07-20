# Deploy（部署）

本目录保存 MDOP 平台可版本化的部署资源，包括本地基础设施编排、应用镜像构建、环境配置模板、健康检查和后续监控配置。

真实凭据、生产配置、数据库备份和运行数据不得进入仓库。

## 1. 当前范围

当前已经建立：

- MySQL 8.4.10 LTS（长期支持版）本地容器
- RabbitMQ 4.3.2 Management（管理版）本地容器
- Redis 8.2.7 本地容器
- 三项基础设施的健康检查
- MySQL、RabbitMQ 和 Redis 命名数据卷
- 本地环境变量模板
- 仅绑定本机地址的服务端口
- Redis AOF（追加文件）持久化基线

应用容器、生产部署和监控系统将在后续迭代加入。

三项基础设施的职责：

```text
MySQL：业务事实和最终数据源
RabbitMQ：可靠跨系统事件
Redis：缓存、限流和短期状态
```

Redis 不作为库存余额、正式幂等结果等核心业务事实的唯一数据源，Redis Pub/Sub 不替代 RabbitMQ。

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
- `.env.example`：可以提交的环境变量模板，只包含安全占位值
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

使用 Docker Desktop 时，当前 Docker Context（运行环境上下文）通常应为：

```text
desktop-linux
```

## 4. 首次配置

以下命令必须在项目根目录执行。

只有在 `.env.local` 不存在时，才从示例文件创建本地配置：

```powershell
Copy-Item deploy/env/.env.example deploy/env/.env.local
```

如果 `.env.local` 已经存在，不要再次复制覆盖。

修改 `.env.local`，至少设置以下本地真实密码：

- MySQL 应用账号密码
- MySQL `root` 管理员密码
- RabbitMQ 管理员密码
- Redis 密码

不同服务和账号应使用不同密码。

不要将真实密码写入 `.env.example`，也不要提交或分享 `.env.local`。

校验 Compose 配置：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml config --quiet
```

查看 Compose 已识别的服务：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml config --services
```

预期包含：

```text
mysql
rabbitmq
redis
```

## 5. 启动与状态检查

日常本地开发优先从仓库根目录使用统一入口：

```powershell
.\mdop.cmd start
.\mdop.cmd status
.\mdop.cmd stop
```

仓库级 `start` 会管理本节的三项基础设施，同时构建并启动后端和前端；`status` 会同时显示容器与受管应用进程；`stop` 会停止应用进程和三项基础设施，但保留容器与命名数据卷。应用日志位于被 Git 忽略的 `logs/local/`，详细规则见 [scripts/README.md](../scripts/README.md)。

以下 Docker Compose 命令是仅操作基础设施的底层入口，不管理后端、前端、应用日志或仓库运行状态。

启动全部本地基础设施：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml up -d
```

只启动单个服务：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml up -d mysql
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml up -d rabbitmq
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml up -d redis
```

查看全部服务状态：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml ps
```

正常运行时，三个服务的状态都应包含：

```text
healthy
```

`Started` 只表示容器进程已经启动；`healthy` 表示服务健康检查已经通过。

## 6. 服务响应验证

验证 MySQL：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysqladmin ping -h 127.0.0.1 -u"$MYSQL_USER" --silent'
```

预期返回：

```text
mysqld is alive
```

验证 RabbitMQ：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml exec -T rabbitmq rabbitmq-diagnostics -q ping
```

预期返回：

```text
Ping succeeded
```

验证 Redis：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml exec -T redis redis-cli ping
```

预期返回：

```text
PONG
```

RabbitMQ 管理界面：

```text
http://127.0.0.1:15672
```

使用 `.env.local` 中配置的 RabbitMQ 用户和密码登录，不要将凭据写入浏览器地址或提交到仓库。

## 7. 常用操作

本节命令仅管理基础设施。需要同时管理后端和前端时，请使用仓库根目录的 `mdop.cmd`。

查看三个服务最近 100 行日志：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml logs --tail 100 mysql rabbitmq redis
```

持续查看单个服务日志：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml logs -f mysql
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml logs -f rabbitmq
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml logs -f redis
```

停止全部服务，但保留容器和数据：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml stop
```

重新启动已经停止且仍然存在的容器：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml start
```

重启单个服务：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml restart redis
```

`restart` 只重启当前容器，不负责应用新的 Compose 配置。

修改 Compose 或环境变量后，应使用以下形式应用变更：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml up -d redis
```

删除容器和网络，但保留命名数据卷：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml down
```

执行 `down` 后不能使用 `start` 恢复已经被删除的容器，应重新执行：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml up -d
```

## 8. 数据持久化与清理

本地数据保存在以下命名数据卷中：

| 服务 | 命名数据卷 | 容器目录 |
|---|---|---|
| MySQL | `mdop-local_mysql-data` | `/var/lib/mysql` |
| RabbitMQ | `mdop-local_rabbitmq-data` | `/var/lib/rabbitmq` |
| Redis | `mdop-local_redis-data` | `/data` |

执行普通的 `stop`、`restart` 或 `down` 后，命名数据卷仍然保留。

Redis 当前启用：

```text
appendonly yes
appendfsync everysec
```

这表示 Redis 使用 AOF 记录写操作，并以约每秒一次的策略同步到磁盘。即使启用了持久化，Redis 仍然不是核心业务事实的最终数据源。

RabbitMQ 数据卷能够保存节点元数据和符合持久化条件的数据，但业务消息能否持久化还取决于后续队列、交换机和消息投递配置。

以下命令会删除容器、网络和三个命名数据卷：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml down -v
```

该操作会永久清空本地 MySQL、RabbitMQ 和 Redis 数据，只有在确认所有本地数据都不需要保留时才能执行。

初始化配置注意事项：

- MySQL 的初始化数据库、账号和密码只在空数据卷首次启动时生效。
- RabbitMQ 的默认用户、密码和虚拟主机只在空节点首次初始化时生效。
- 修改已有 MySQL 或 RabbitMQ 初始化变量，不会自动修改数据卷中的已有账号。
- Redis 密码通过启动参数应用，修改后需要使用 `up -d redis` 重新创建 Redis 容器。

不要为了处理普通配置问题直接执行 `down -v`。

## 9. 安全约束

本地端口只绑定到 `127.0.0.1`：

| 服务 | 本地端口 | 用途 |
|---|---:|---|
| MySQL | `3306` | 数据库连接 |
| RabbitMQ | `5672` | AMQP 消息连接 |
| RabbitMQ Management | `15672` | 本地管理界面 |
| Redis | `6379` | Redis 客户端连接 |

安全要求：

- Spring Boot 应用使用 MySQL 普通账号，不使用 `root`。
- MySQL 普通账号和 `root` 密码必须不同。
- RabbitMQ 和 Redis 使用独立密码，不与 MySQL 密码复用。
- `.env.local`、数据库备份和运行数据不得提交。
- RabbitMQ 本地管理员账号不得直接作为生产应用账号。
- Redis 本地默认用户密码方案不得直接作为生产权限方案。
- 本地环境变量不得直接作为生产密钥管理方案。
- 生产环境必须使用专门的 Secrets（密钥管理机制）、最小权限账号和网络访问控制。

## 10. 故障排查

Docker 服务无法连接时：

```powershell
docker version
docker context show
```

查看容器状态：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml ps
```

查看单个服务最近日志：

```powershell
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml logs --tail 100 mysql
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml logs --tail 100 rabbitmq
docker compose --env-file deploy/env/.env.local -f deploy/compose/compose.local.yml logs --tail 100 redis
```

检查本地端口占用：

```powershell
Get-NetTCPConnection -State Listen -LocalPort 3306,5672,6379,15672 -ErrorAction SilentlyContinue
```

如果端口被占用，应先查明占用程序，不要直接结束未知进程。确认确实需要更换端口后，只修改 `.env.local` 中对应的本地端口变量。

服务长时间处于 `starting` 或 `unhealthy` 时：

1. 查看对应服务日志。
2. 检查 `.env.local` 是否缺少必填变量。
3. 检查端口是否被占用。
4. 检查 Docker Desktop 的磁盘空间和内存。
5. 不要未经确认直接删除数据卷。
