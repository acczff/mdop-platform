# 快速开始

本指南针对 Windows 上的**全新本地环境**，从仓库根目录执行命令。已有业务库先阅读[升级与恢复](../../deploy/release/RUNBOOK.md)，不要用重新建库解决普通启动问题。

## 1. 准备工具

| 工具 | 代码约束 / 用途 | 检查 |
|---|---|---|
| Git | 克隆和版本管理 | `git --version` |
| JDK | Java 25，构建与运行一致 | 对选定 JDK 的 `bin/java.exe` 执行 `--version` |
| Maven | Wrapper 固定发行版；构建要求 3.9.11 及以上的 3.x | 无需全局安装 Maven |
| Node.js | `>=24.18.0 <25` | `node --version` |
| Corepack / pnpm | Node 目录提供 `corepack.cmd`；pnpm 固定 11.13.1 | `corepack --version` |
| Docker | Engine 可连接，使用 Linux 容器；含 Compose 插件 | `docker version`、`docker compose version` |

Java 与 Node 可只对本项目设置，不修改系统全局版本。首次运行需联网下载 Maven、pnpm 依赖和容器镜像。Corepack 未安装时，先在所用 Node 环境安装 Corepack，再使用仓库脚本；不要用不同版本的全局 pnpm 替代锁定版本。

```powershell
git clone https://github.com/acczff/mdop-platform.git
cd mdop-platform
if (-not (Test-Path deploy/env/.env.local)) {
    Copy-Item deploy/env/.env.example deploy/env/.env.local
}
```

## 2. 编辑本地配置

编辑刚复制的文件；它已被 Git 忽略。模板见 [`.env.example`](../../deploy/env/.env.example)，Compose 使用关系见 [compose.local.yml](../../deploy/compose/compose.local.yml)。

| 配置 | 要填写的内容 |
|---|---|
| `MDOP_JAVA_HOME` | 本机 JDK 25 根目录，例如 `C:\Tools\jdk-25`，不要加 `bin` |
| `MDOP_NODE_HOME` | 含 `node.exe` 和 `corepack.cmd` 的目录，例如 `C:\Tools\node` |
| `MDOP_MYSQL_PASSWORD` / `MDOP_MYSQL_ROOT_PASSWORD` | 两个不同密码；应用使用普通账号 |
| `MDOP_RABBITMQ_PASSWORD` / `MDOP_REDIS_PASSWORD` | 各自独立密码 |
| `MDOP_ADMIN_USERNAME` / `MDOP_ADMIN_PASSWORD` | 首次初始化账号与至少 12 字符密码，使用随机生成值，不沿用模板占位符 |
| `MDOP_BACKEND_PORT` | 建议保留模板 `8081`；前端默认 `5173` |
| 基础设施端口 | 默认 MySQL 3306、AMQP 5672、管理界面 15672、Redis 6379；冲突时在此文件调整 |

每行 `KEY=VALUE`，不要写 PowerShell 的 `$env:` 前缀；不要在值后追加行尾注释。命令行 `-BackendPort` 优先于配置；没有配置才回退兼容默认 8080。

## 3. 启动与停止

```powershell
.\mdop.cmd start
.\mdop.cmd status
```

脚本检查环境、安装锁定依赖、打包后端、启动基础设施，再等待前后端就绪。首次启动应用时 Flyway 创建当前分支的表，不自动插入 WMS 演示业务数据。

- 管理端：<http://127.0.0.1:5173>
- 健康检查：<http://127.0.0.1:8081/actuator/health>（采用模板端口）
- 日志：本地 `logs/local/`；进程状态：`tmp/mdop-local-state.json`。

```powershell
.\mdop.cmd stop
```

停止会关闭脚本管理的应用及本项目三个基础设施服务，保留数据卷。它不会接管 IDEA 中手动启动的应用。`start` 是运行入口，不代替完整测试。

## 4. 首次账号与仓库初始化

**系统管理员只有账号管理权限。** 初始登录默认进入“我的账号”，看不到收货页面是正常的职责隔离。

1. 用初始化管理员登录“用户与权限”，创建基础资料账号，角色选“基础资料管理员”；此时可以暂不分配仓库。
2. 退出，使用基础资料账号创建一个启用的原料仓；在“收货基础资料”创建供应商、物料、待检库位与正式存储库位。保存界面返回的实际仓库，不假设 ID 为 1。
3. 退出，管理员重新登录，创建“仓库作业员”和“业务审批员”两个独立账号，分别分配刚建立的仓库范围。
4. 仅在本地演练需要模拟输入时，为作业员额外分配“模拟接口与消息管理员”；也可以建立独立模拟接口账号。系统管理员不能混合业务角色，作业与审批角色不能同时授予同一账号。
5. 用作业员开始[采购收货演练](wms-walkthrough.md)，需要审批的地方换用审批员。改变账号权限会使原会话下次请求失效，重新登录即可。

账号初始化只执行一次。修改环境变量不会重置数据库中的密码、角色或停用状态；通过界面维护。基础资料是全局目录，业务仓库范围用于 WMS 作业授权，不能视为租户隔离。

## 5. 开发与验证

统一入口在仓库根目录使用；首次验证前安装前端依赖：

```powershell
$env:MDOP_NODE_HOME = 'C:\Tools\node' # 换成本机目录，仅影响当前终端
$env:Path = "$PWD\scripts;$env:MDOP_NODE_HOME;$env:Path"
.\scripts\pnpm.cmd install --frozen-lockfile
.\mdop.cmd verify
node scripts/docs/check.mjs
node --test scripts/docs/check.test.mjs
node --test scripts/release/release.test.mjs
```

`mdop verify` 读取本地工具链配置，后端测试使用 Testcontainers 临时数据库，前端使用组件测试。它不运行文档/发布工具测试，后三条补齐相应检查。后端测试需要 Docker，但无需先运行 `mdop start`。

已有后端单独调试前端时：

```powershell
$env:MDOP_BACKEND_URL = 'http://127.0.0.1:8081'
.\scripts\pnpm.cmd --filter @mdop/admin run dev --host 127.0.0.1 --port 5174 --strictPort
```

该命令不会启动后端；在独立终端保持运行，用 Ctrl+C 停止。不要同时在 5173 启动两份前端。`.\scripts\pnpm.cmd` 不自行读取 `.env.local`，独立使用时设置 `MDOP_NODE_HOME` 和当前终端 PATH。

## 6. 常见问题

| 现象 | 检查与处理 |
|---|---|
| 找不到 `docker`，或只有 Client 没有 Server | 启动 Docker Desktop，确认 `docker version` 能返回 Server；安装后重开 IDEA/终端刷新 PATH |
| 要求 Java 25 / pnpm 版本不匹配 | 核对 `.env.local` 工具目录；嵌套 pnpm 需要 `scripts` 在当前 PATH 前部 |
| 端口占用 | 查看 `Get-NetTCPConnection -State Listen`；更换项目端口，不停止不明进程 |
| 管理员没有仓库业务菜单 | 按第 4 节创建分职责账号并授权仓库 |
| 401 / 403 | 分别检查会话失效，以及角色、仓库范围和 CSRF；不要通过关闭鉴权处理 |
| Flyway 校验失败 | 核对应用版本和迁移历史；保留日志，不修改历史迁移或删除数据卷 |
| 修改密码变量后无法登录 | 已初始化账号以数据库为准；环境变量不会覆盖已有账号 |
| 页面刷新 404 或 `/api` 失败 | Vite 开发代理与生产静态代理不同；部署使用包内 Nginx 配置及同源 `/api` |

更多基础设施诊断见[部署说明](../../deploy/README.md)，发布环境参数以[包内操作手册](../../deploy/release/RUNBOOK.md)为准。`dev`/`prod` profile 只提供部分日志配置，并不是完整独立部署配置。
