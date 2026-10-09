# MDOP Platform

**以仓储公共流程为起点的制造业数字化运营平台。**

MDOP（Manufacturing Digital Operations Platform）使用 Java 25、Spring Boot 和 Vue 3，采用模块化单体架构。当前重点实现 WMS：从采购收货、质检上架，到生产领料、消耗退料、成品出入库和库存运营，保留权限、审计、库存流水及可靠消息。

[快速开始](docs/guides/quickstart.md) · [业务演练](docs/guides/wms-walkthrough.md) · [文档中心](docs/README.md) · [架构与源码](docs/reference/architecture.md) · [贡献指南](CONTRIBUTING.md)

## 适用范围与成熟度

当前为 `0.1` 开发阶段，用于业务流程验证、工程实践和设计交流。本分支已建立 ERP 主数据、采购需求/订单审批和分批到货至 WMS 收货的衔接；采购履约归集与结案尚未完成。原 ERP 模拟到货仍兼容，MES/QMS 对接使用本地模拟输入或消费者，并非完整子系统。长期 MES、QMS、EAM、IoT、BI 愿景见[总体蓝图](docs/project/项目总体蓝图.md)，不应当作现有功能清单。

文档描述**所在分支的代码**。主干、待审 PR、测试证据与部署状态分别记录在[版本与验证记录](docs/project/status.md)；CI 成功不代表生产准入。许可证暂未选择，尚未授予开源复用许可；本轮不添加 LICENSE。

## 当前能力

| 业务 | 已实现闭环 | 主要边界 |
|---|---|---|
| 基础资料 | 客户/供应商、物料、单位、单组织维护，版本/审计与启停，业务引用锁及单据快照 | 一物料一个基本单位；客户订单、生产地点、换算及多组织留后续轮次 |
| 采购需求与订单 | 手工需求、异人审核、整单转订单、分批到货、WMS 送达/撤回、来源和审计 | [P1](docs/erp/采购P1实现与验收.md) / [P2 候选实现](docs/erp/采购P2实现与验收.md)；履约结案待 P3，不含金额税额、付款、拆单或汇单 |
| 采购入库 | 模拟到货、草稿、分批收货、质检、上架、差异与冲正、不合格采购退货 | 不含退款、财务、序列号、复检、部分上架 |
| 生产物料协同 | 模拟 MES 需求、预占、发料到线边、实际消耗、余料退回、异人审批冲正 | 发料保留总库存，消耗才扣减；不实现 MES 排程/报工 |
| 成品出入库 | 模拟完工需求、待检、判定、上架；销售预占、拣货、复核、出库 | 不含客户退货、真实 ERP/MES/QMS 适配 |
| 库存运营 | 库存及流水查询、同仓移库、快照盘点、冻结/异人解冻、跨仓分批实收 | 不含运输短少核销、调拨冲正、部分数量冻结 |
| 用户与消息 | 数据库账号、固定角色、仓库范围、会话失效、审计；Inbox/Outbox、重放、模拟对账 | 不含多租户、组织树、SSO、自定义角色和动态审批流 |
| 工程交付 | 回归 CI、同次构建候选包、文件校验、停写备份及空库恢复工具 | 不自动部署；隔离恢复演练不等于任意生产库升级保证 |

公共流程及基本异常的逐项标准见 [WMS 验收清单](docs/project/WMS公共流程验收清单.md)。企业差异化规则按具体工作环境另行确认。

## 工程特点

- 草稿与库存记账分离，确认实际业务后才写余额、流水及消息；冲正保留原始记录。
- 数量使用 `DECIMAL(18,6)`，接口以十进制字符串返回，前端以六位定点整数计算。
- 本地事务、行锁/版本检查、幂等键与 Inbox/Outbox 共同保护库存和消息一致性。
- 审批与作业账号分离，页面控制之外由后端独立校验权限、仓库范围和禁止自审。
- Testcontainers 使用真实 MySQL/RabbitMQ/Redis、随机端口及临时凭据进行隔离回归。

## 快速运行（Windows）

准备 Git、JDK 25、Node.js `>=24.18.0 <25`、Corepack 及可用的 Docker Engine/Compose。pnpm 版本固定在 [frontend/package.json](frontend/package.json)，由仓库入口调用。

```powershell
git clone https://github.com/acczff/mdop-platform.git
cd mdop-platform
if (-not (Test-Path deploy/env/.env.local)) {
    Copy-Item deploy/env/.env.example deploy/env/.env.local
}
```

**启动前编辑** `deploy/env/.env.local`：填写本机 JDK/Node 目录、各服务独立密码、管理员初始化密码；保留模板后端端口 `8081`。路径和密码示例、初始化顺序与排障见[快速开始](docs/guides/quickstart.md)。

```powershell
.\mdop.cmd start
.\mdop.cmd status
```

访问前端 <http://127.0.0.1:5173>，后端健康地址为 <http://127.0.0.1:8081/actuator/health>。首次管理员仅管理账号：先创建基础资料员，创建仓库，再由管理员为作业员、审批员分配实际仓库范围。不要跳过这一步直接开始收货。

`start` 会构建应用并执行尚未应用的 Flyway 迁移。已有数据库升级先按[部署与恢复手册](deploy/release/RUNBOOK.md)备份并验证；空库首次体验与已有库升级是两种操作。

```powershell
.\mdop.cmd verify   # 后端 + 前端，需先安装前端依赖，后端测试需要 Docker
.\mdop.cmd stop     # 停止受管应用和本项目基础设施，保留数据卷
```

Windows 一键启动由仓库脚本提供；Linux CI 使用 Maven Wrapper/Corepack 验证入口，候选包部署见 [RUNBOOK](deploy/release/RUNBOOK.md)。目前没有跨平台一键启动脚本。

## 仓库导航

| 路径 | 内容 |
|---|---|
| [backend](backend/README.md) | 9 个 Maven 模块：公共契约、安全、账号、主数据、采购、WMS、集成、测试基座与装配 |
| [frontend](frontend/README.md) | Vue 管理端、统一 API 客户端、数量运算与组件测试 |
| [deploy](deploy/README.md) | 本地 Compose、环境模板、发布配置与恢复手册 |
| [scripts](scripts/README.md) | 启停、验证、库存对账、发布/备份和文档检查 |
| [docs](docs/README.md) | 入门、架构/接口、业务设计、验收记录、ADR 与长期规划 |
| [.github](.github/workflows/ci.yml) | CI、Issue 与 PR 模板 |

从 [源码导航](docs/reference/source-index.md) 查找页面、控制器及全部迁移；从 [代码阅读指南](docs/reference/architecture.md) 理解调用链与修改边界。

## 参与与反馈

缺陷或建议请使用 [Issues](https://github.com/acczff/mdop-platform/issues)，注明提交版本和复现步骤。贡献流程、检查命令和中文提交格式见 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题按 [SECURITY.md](SECURITY.md) 私下报告，不公开凭据或可利用细节。

先完善现有公共流程、文档与交付质量；运输短少核销等复杂业务暂缓。完整规划与现实差距见[项目整理报告](docs/project/repository-review.md)。
