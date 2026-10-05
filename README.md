# MDOP Platform

MDOP（Manufacturing Digital Operations Platform，制造业数字化运营平台）是一个面向电子制造企业的企业级信息化项目集合体。本项目以真实企业软件的分析、设计、实现和交付链路为标准，采用一个持续演进的模块化平台承载 WMS、MES、QMS、ERP 协同、EAM、IoT 和 BI 等业务能力。

## 当前状态

- 当前版本：`V0.1 迭代开发版`
- 当前迭代：采购收货消息接入与可靠反馈
- 当前成果：仓库主数据、会话登录、管理页面、收货基础资料、到货通知本地模拟、收货草稿、分批提交、待检库存、流水和事务 Outbox
- 当前策略：新项目从零建设；旧项目只作业务和技术参考，不复制旧代码和旧数据

当前版本可验证“模拟 ERP 消息 → Inbox → 到货通知 → 草稿 → 收货提交 → 待检库存 → Outbox → RabbitMQ → ERP/QMS 模拟接收”链路，含失败重试、死信、重放与消息管理页面。尚未接入真实 ERP/QMS，也未实现完整 IAM、序列号管理、差异审批、收货冲正和质检放行；不能把待检入库视为可用库存或生产交付完成。

## 项目目标

1. 建立能够持续扩展多个制造业系统的统一技术平台。
2. 用完整业务链路驱动计算机基础、框架原理和工程实践学习。
3. 形成从业务分析、领域设计、技术设计、实现、测试到交付运维的闭环。
4. 逐步达到可维护、可测试、可观测、可部署和可演进的企业级质量标准。

## 仓库结构

```text
mdop-platform
├─ mdop.cmd          Windows 仓库级命令入口
├─ backend/          后端模块化单体工程
├─ frontend/         前端工作区与多终端应用
├─ deploy/           本地及各环境部署资源
├─ docs/             总体规划、系统设计与模板
├─ scripts/          统一启动、验证和运维脚本
└─ .github/          GitHub 协作模板
```

## 本地快速开始

### 1. 前置条件

当前仓库级命令面向 Windows 与 Windows PowerShell（Windows 命令行脚本环境），需要：

- Java 25；
- Node.js 24，最低 `24.18.0` 且低于 25；
- 由 Corepack（Node.js 包管理器代理）管理的 pnpm `11.13.0`；
- 已启动的 Docker Desktop（Docker 桌面程序）和可用的 Docker Engine（Docker 容器引擎）。

在仓库根目录确认工具版本：

```powershell
java -version
node --version
corepack --version
.\scripts\pnpm.cmd --version
docker version
docker context show
```

使用 Docker Desktop 时，Docker Context（Docker 运行环境上下文）通常应为 `desktop-linux`。

### 2. 创建本地配置

仅当本地配置不存在时，从安全模板创建：

```powershell
if (-not (Test-Path deploy/env/.env.local)) {
    Copy-Item deploy/env/.env.example deploy/env/.env.local
}
```

然后编辑 `deploy/env/.env.local`，把占位值替换为仅供本机开发的独立凭据。该文件已被 Git 忽略，不得提交、粘贴到日志或作为 Testcontainers（测试容器）凭据来源。

同时填写 `MDOP_JAVA_HOME`（本机 JDK 25 路径）、`MDOP_NODE_HOME`（Node 安装目录）、`MDOP_BACKEND_PORT=8081`、`MDOP_ADMIN_USERNAME` 和至少 12 位随机的 `MDOP_ADMIN_PASSWORD`。脚本只在子进程中配置工具链，不修改系统环境。构建与运行使用同一个 JDK；所有 pnpm 调用都通过受版本约束的 Corepack 入口。

首次升级此切片会执行两个新增 Flyway 迁移，创建供应商、物料、库位与收货相关表，保留原有仓库表及数据。已有本地数据请先备份；不要修改已经应用过的历史迁移文件。

### 3. 一条命令启动

```powershell
.\mdop.cmd start
```

如果本机 8080 已被其他项目占用，可指定后端端口：

```powershell
.\mdop.cmd start -BackendPort 8081
```

此时后端健康检查地址为 `http://127.0.0.1:8081/actuator/health`，前端仍使用 5173。

该命令会安装锁文件指定的前端依赖、打包后端、等待 MySQL／RabbitMQ／Redis 健康，再启动后端和前端。就绪后访问：

- 后端健康检查：`http://127.0.0.1:8081/actuator/health`（采用模板中的端口配置时）
- 前端管理端：`http://127.0.0.1:5173`

查看状态和停止全部仓库服务：

```powershell
.\mdop.cmd status
.\mdop.cmd stop
```

`stop` 保留本地容器和命名数据卷。应用日志写入被 Git 忽略的 `logs/local/`；详细的进程管理和失败清理规则见[仓库级脚本说明](scripts/README.md)。

前端通过同源 `/api` 代理连接实际后端端口，登录使用本地配置中的账号与密码。密码不写入前端代码或浏览器存储；会话采用 HttpOnly Cookie，写操作保留 CSRF 校验。会话重启后失效。

### 业务验收顺序

1. 登录后，在“仓库管理”创建仓库，验证查询、修改、启停与停用后结构调整。
2. 在“收货基础资料”创建供应商、物料和收货暂存/待检库位。物料支持数量或批次管理，可要求 Date Code、有效期。
3. 在“采购收货”选择仓库，通过“本地模拟 ERP 到货”发送 RabbitMQ 通知，稍后刷新查询到货单。模拟入口与模拟消费者仅存在于 `local`/`test` 配置，生产不可用。
4. 登记本次收货并保存草稿，确认库存没有变化；重新打开草稿，确认提交后检查待检库存和流水。
5. 通知 100 件时先收 60 件，再收 40 件，状态应从部分收货变为全部收货，可用库存始终为 0；重复提交不能重复增加库存，超收必须拒绝。

6. 在“消息管理”确认 Inbox 已处理、两类收货反馈已发布、ERP/QMS 模拟端已接收；失败消息填写原因后重放，核对处理记录和库存不重复增加。

本阶段发布真实 RabbitMQ 消息，但下游为本项目内的模拟消费者，不自动放行质量状态。基础资料列表最多 1000 条，到货通知与库存列表显示最近 100 条；消息列表每页50条，对账/隔离记录显示最近100条。契约、故障恢复与边界见[消息接入与可靠反馈](docs/project/I2-I4消息接入与可靠反馈.md)。

### 4. 一条命令验证

```powershell
.\mdop.cmd verify
```

该命令依次执行后端 Maven `verify`（完整验证）和前端 pnpm `verify`，任一阶段失败都会返回非零退出码。不传动作时，`.\mdop.cmd` 默认执行 `verify`。

后端验证会运行共享 Testcontainers 回归，因此需要 Docker Engine，但不依赖 `deploy/env/.env.local` 或本机固定 Compose 容器。测试继续使用随机宿主机端口和运行时临时凭据。

## 文档导航

- [项目总体蓝图](docs/project/项目总体蓝图.md)
- [I0 工程基线方案](docs/project/I0工程基线方案.md)
- [I1.1 仓库主数据方案](docs/project/I1.1仓库主数据方案.md)
- [后端工程说明](backend/README.md)
- [前端工作区说明](frontend/README.md)
- [仓库级脚本说明](scripts/README.md)
- [本地部署说明](deploy/README.md)
- [WMS 全链路设计](docs/wms/WMS全链路设计.md)
- [单系统全链路模板](docs/templates/单系统全链路模板.md)

## 建设原则

- 业务先行：先明确业务问题、范围、流程、规则和验收标准，再选择技术实现。
- 正式演进：每个阶段都按照正式项目标准建设，不维护一次性演示分支。
- 模块化单体优先：先控制模块边界和依赖方向，满足明确条件后再评估微服务拆分。
- 文档与实现同步：实践中出现变更时，同步更新蓝图、设计、代码、测试和交付说明。
- 安全默认：仓库只使用模拟数据，不提交真实企业数据、个人信息、密码、密钥和生产配置。

## 近期路线

1. 完成本切片的本地业务验收，确认仓库管理与采购收货正常链路。
2. 验收 Inbox、Outbox 发布与 ERP/QMS 模拟消费者、失败重试及对账；真实 ERP/QMS 联调须另行确认凭据、主数据映射和业务契约。
3. 补充差异处理、序列号、冲正审批与质检衔接，并扩展多用户及仓库授权管理。
4. 根据真实业务证据逐步扩展生产、质量、供应链、设备和工业互联能力。

## 协作

贡献前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。`main` 分支始终保持可构建、可测试；功能通过短生命周期分支和 Pull Request 合并。

## 许可说明

仓库当前公开用于学习、设计评审和作品展示，尚未选择开源许可证。正式开放复用前将补充明确的许可证文件。
