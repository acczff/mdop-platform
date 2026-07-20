# MDOP Platform

MDOP（Manufacturing Digital Operations Platform，制造业数字化运营平台）是一个面向电子制造企业的企业级信息化项目集合体。本项目以真实企业软件的分析、设计、实现和交付链路为标准，采用一个持续演进的模块化平台承载 WMS、MES、QMS、ERP 协同、EAM、IoT 和 BI 等业务能力。

## 当前状态

- 当前版本：`V0.1 工程基线版`
- 当前迭代：`I0 新项目工程基线`
- 当前成果：Java 25 后端多模块工程、多环境配置、MySQL／RabbitMQ／Redis 本地编排、Flyway 空库初始化与迁移校验、共享 Testcontainers 测试基座、Spring Security 最小安全基线、前端 pnpm 工作区和 admin 应用基线、后端与前端质量门禁，以及仓库级统一启动和验证命令
- 当前策略：新项目从零建设；旧项目只作业务和技术参考，不复制旧代码和旧数据

当前仓库尚未进入业务功能实现阶段。I0 已完成工程骨架、运行基线、自动化测试、仓库级统一命令和本地验收；进入下一阶段前必须先确认业务范围、最小实施切片和验收标准。

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
pnpm.cmd --version
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

### 3. 一条命令启动

```powershell
.\mdop.cmd start
```

该命令会安装锁文件指定的前端依赖、打包后端、等待 MySQL／RabbitMQ／Redis 健康，再启动后端和前端。就绪后访问：

- 后端健康检查：`http://127.0.0.1:8080/actuator/health`
- 前端管理端：`http://127.0.0.1:5173`

查看状态和停止全部仓库服务：

```powershell
.\mdop.cmd status
.\mdop.cmd stop
```

`stop` 保留本地容器和命名数据卷。应用日志写入被 Git 忽略的 `logs/local/`；详细的进程管理和失败清理规则见[仓库级脚本说明](scripts/README.md)。

### 4. 一条命令验证

```powershell
.\mdop.cmd verify
```

该命令依次执行后端 Maven `verify`（完整验证）和前端 pnpm `verify`，任一阶段失败都会返回非零退出码。不传动作时，`.\mdop.cmd` 默认执行 `verify`。

后端验证会运行共享 Testcontainers 回归，因此需要 Docker Engine，但不依赖 `deploy/env/.env.local` 或本机固定 Compose 容器。测试继续使用随机宿主机端口和运行时临时凭据。

## 文档导航

- [项目总体蓝图](docs/project/项目总体蓝图.md)
- [I0 工程基线方案](docs/project/I0工程基线方案.md)
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

1. 基于已验收的 I0 工程基线确认下一迭代范围和验收标准。
2. 按确认后的最小范围建设身份权限、审计与核心主数据能力。
3. 按 WMS 全链路设计和重新确认的业务规则实现第一个可验收业务闭环。
4. 根据真实业务证据逐步扩展生产、质量、供应链、设备和工业互联能力。

## 协作

贡献前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。`main` 分支始终保持可构建、可测试；功能通过短生命周期分支和 Pull Request 合并。

## 许可说明

仓库当前公开用于学习、设计评审和作品展示，尚未选择开源许可证。正式开放复用前将补充明确的许可证文件。
