# Scripts

本目录提供 MDOP 仓库级操作脚本。Windows 用户从仓库根目录通过 `mdop.cmd` 调用，不需要直接执行 `scripts/mdop.ps1`。

## 目录结构

| 路径 | 作用 |
|---|---|
| `mdop.ps1`、`pnpm.cmd` | 入口：启停/验证主脚本，以及前端包管理包装 |
| `checks/docs/` | 文档相对链接、标题锚点、源码导航校验 |
| `checks/architecture/` | 模块边界与分层规则、存量例外基线 |
| `checks/audit-inventory.sql` | 只读库存对账（47 项） |
| `docker/` | Docker 启动前检查、自测、陈旧 socket 恢复 |
| `release/` | 发布候选打包、数据库备份与空库恢复 |

命名约定：库脚本与自测成对出现（`check.mjs` + `check.test.mjs`、`startup-checks.ps1` + `startup-checks.test.ps1`），自测导入库脚本而不复制逻辑。入口脚本保留在目录根部，避免 `mdop.cmd` 与文档中的命令失效。

文档工具 `node scripts/checks/docs/check.mjs` 校验所有纳入 Git 的 Markdown 相对链接、标题锚点及源码导航同步；`--write-index` 更新模块、页面、控制器和迁移清单。工具回归为 `node --test scripts/checks/docs/check.test.mjs`，CI 使用 `Documentation verify`，失败会阻止发布候选。它不检查外部网站或证明接口行为。

架构边界检查 `node scripts/checks/architecture/check.mjs` 执行六类规则：跨模块 import 必须在 pom 中显式声明、控制器不得直接依赖其它模块的 Service、迁移只能改本模块前缀的表、迁移命名格式、已分层模块的层间依赖方向（`api` 与 `application` 不引用 `infrastructure`，`domain` 不依赖 Spring/JDBC）、库存余额只能经 `InventoryLedger` 写入（`BALANCE_WRITE`）。存量例外冻结在 `scripts/checks/architecture/baseline.json`，每条必须写明原因；修复后检查会提示删除对应条目。`--report` 打印全部命中，`--write-baseline` 重建基线。规则回归为 `node --test scripts/checks/architecture/check.test.mjs`，CI 在 `Documentation verify` 中执行。

库存切片测试 `InventorySliceTests`（4 项：余额按仓库隔离与限量、余额维度唯一、加锁的仓库范围校验、通知缺失静默返回）只启动 MySQL 与 Flyway，随 `Backend verify` 执行。新增直写 `wms_inventory_balance` 会被 `BALANCE_WRITE` 规则拦截；迁移一条写入就从 `baseline.json` 删除对应条目。

`release/release.mjs` 负责 CI 组件标记、候选包组合与完整性校验；`release/database.mjs` 提供停写后的数据库备份校验和仅空目标恢复。它们不调用 `mdop start`，不自动升级日常数据库；参数和失败处理见[发布包操作手册](../deploy/release/RUNBOOK.md)。工具回归使用 `node --test scripts/release/*.test.mjs`，CI 的 `Release candidate` 检查自动执行。

## 命令

```powershell
.\mdop.cmd verify
.\mdop.cmd start
.\mdop.cmd start -BackendPort 8081
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
- Corepack；`scripts/pnpm.cmd` 在前端工作区执行锁定版本的 pnpm，不依赖全局 pnpm 或临时适配文件；
- `start`、`status` 和 `stop` 使用 `deploy/env/.env.local`；
- Docker Desktop 已启动，Docker Engine 可访问；
- 配置的后端端口和前端端口 `5173` 未被其他程序占用。模板使用 `MDOP_BACKEND_PORT=8081`，命令行 `-BackendPort` 优先，没有任何配置时保留兼容默认值 `8080`。

`start` 与 `verify` 统一使用 `MDOP_JAVA_HOME`（未配置则使用 `JAVA_HOME`）和 `MDOP_NODE_HOME`（未配置则发现 PATH 中的 Node）。检查 Java 25、Node 24.18+ 与 packageManager 中的 pnpm 精确版本；设置只影响当前进程与子进程。存在 `.env.local` 时读取其中的工具链路径，但测试数据库仍由 Testcontainers 创建。

启动需要 `MDOP_ADMIN_PASSWORD`。前端代理目标自动继承实际后端端口，手动启动前端时可设置 `MDOP_BACKEND_URL`，默认目标为 `http://127.0.0.1:8081`。

## `verify` 验证边界

`verify` 复用两个已经存在的质量入口，不维护第二套检查逻辑：

```powershell
.\backend\mvnw.cmd -f .\backend\pom.xml verify
.\scripts\pnpm.cmd run verify
```

后端完整验证包含共享 Testcontainers 测试。测试容器使用随机宿主机端口和运行时临时凭据，不读取 `.env.local`，也不要求预先启动本机固定 Compose 容器。

## `start` 启动范围

`start` 启动后端时会应用目标库中尚未执行的迁移，不能将 Git 合并理解为已经部署。当前代码与历史主干的迁移区别见[版本记录](../docs/40-operations/status.md)；已有库升级先按 [部署与恢复流程](../deploy/README.md#11-wms-主干升级与恢复)备份验证，本轮未自动启动日常环境。

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
| 后端地址 | `http://127.0.0.1:<配置端口>` |
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

`scripts/checks/audit-inventory.sql` 为只读库存对账入口，当前包含 47 项检查，覆盖余额/流水、质量可用量、预占、生产归属、销售、调拨在途/分批收货及冻结状态。应在已确认的目标数据库执行，全部差异为零后再结合业务验收判断，不以单次健康响应代替完整验收。

销售反馈按来源检查：`ERP_SIMULATOR` 已出库必须恰好一条模拟反馈；`MDOP_SALES` 正式来源不应发送模拟反馈，履约通过 ERP 详情中的 WMS 事实和结案快照核对。脚本要求使用匹配当前应用的数据库迁移；不要将新版 SQL 用于尚未升级的旧库。采购/销售联合验收及恢复记录见 [R1 验收报告](../docs/90-archive/acceptance/R1采购销售联合验收.md)。

- 提示端口被占用：先查明 `8080` 或 `5173` 的监听程序，不要结束未知进程。
- 提示 Docker Engine 不可用：启动 Docker Desktop，并检查 `docker version` 与 `docker context show`。
- Windows Docker 启动异常：先在开始菜单打开 Docker Desktop，再检查 `docker info` 是否返回服务端版本和 `OSType: linux`。只有客户端版本或命令退出码为零，不足以证明引擎已就绪。
- 若桌面提示安装登记缺失，或 WSL 挂载 `docker_data.vhdx` 报 `ERROR_PATH_NOT_FOUND`，但从某个应用终端查看文件却存在，先核对是否受 [MSIX 文件和注册表重定向](https://learn.microsoft.com/en-us/windows/msix/desktop/desktop-to-uwp-behind-the-scenes)影响。不要从 Codex 等打包应用的子进程安装、修复或启动 Docker；这可能使配置和磁盘落入应用私有 `LocalCache`，普通桌面进程和 WSL 无法通过同一路径访问。应从正常 Windows 用户会话检查安装登记、实际磁盘路径和 WSL `BasePath`，停止 Docker 后备份并校验磁盘，再修复安装和迁移路径。启动脚本会在读写 Docker 配置前拒绝打包进程环境；不会自动迁移或重置数据。
- 安装与数据路径已确认正常，仅遇到 `sailor-ingest.sock` 等陈旧 socket 报错时，可在**从开始菜单单独打开的 PowerShell** 中执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\docker\start-desktop.ps1 -RecoverStaleSockets -NoDashboard`。脚本固定正确的启动工作目录；引擎已就绪时复用，不重启。只有 Docker 进程与 WSL 均已停止、运行目录仅含零字节 socket 文件时，才将目录原样改名保留后重建；不处理容器、镜像、数据卷和 WSL 磁盘。其他文件或正在运行的进程会阻止恢复。该脚本不能修复安装缺失或磁盘路径错误，不应将这些问题统称为 Docker 上游缺陷。
- 启动检查的 Windows 回归测试：`powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\docker\startup-checks.test.ps1`。测试使用临时模拟 CLI，覆盖空响应、仅客户端信息、缺失服务端版本、错误输出、超时及正常响应，不启动 Docker。
- 应用未能就绪：查看错误信息列出的 `logs/local/` 日志文件。
- 提示已有应用正在运行：先执行 `.\mdop.cmd status`，确认后使用 `.\mdop.cmd stop`。
- 仅需操作基础设施时：使用 [deploy/README.md](../deploy/README.md) 中的底层 Compose 命令。
