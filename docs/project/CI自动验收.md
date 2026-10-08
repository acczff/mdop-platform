# CI 自动验收

本阶段只建立持续集成验证：每个 PR 自动检查现有后端与前端，保留可下载的诊断结果。不增加 WMS 业务规则，不发布应用，不执行日常数据库迁移，也不自动合并 PR。

## 触发与完成条件

工作流位于 `.github/workflows/ci.yml`，名称为 `CI`：

- 任意目标分支的 PR 创建、更新或重新打开时运行，包括草稿 PR；不按文件路径跳过。
- 推送到 `main` 时再次验证合并结果；进入主干后支持 Actions 页面手动运行。
- 同一个 PR 更新时取消旧运行，只保留最新候选；`main` 的运行不主动取消。
- 两个独立检查 `Backend verify`、`Frontend verify` 都成功，才视为本次 CI 通过。取消、跳过、排队或没有运行都不算通过。

首次工作流通过 PR 提交后即可触发 `pull_request` 验证；手动入口需要工作流已进入默认分支。当前不修改分支保护策略，工作流本身不会阻止管理员绕过检查。若后续启用必须检查，使用上述两个固定检查名称，不将 CI 当成业务审批。

## 验证范围

| 检查 | 环境与命令 | 完成标准 |
|---|---|---|
| Backend verify | Ubuntu 24.04、Temurin Java 25、仓库 Maven Wrapper；`./backend/mvnw -B -ntp -f backend/pom.xml verify` | 版本与依赖约束、Java 格式、全部测试、打包和 JaCoCo 报告成功 |
| Frontend verify | Ubuntu 24.04、Node 24.18.0；pnpm 从 `frontend/package.json` 的 packageManager 读取；`pnpm install --frozen-lockfile`、`pnpm run verify` | 格式、ESLint、Vitest、类型检查与生产构建成功 |

后端使用现有 Testcontainers 基座启动 MySQL、RabbitMQ 和 Redis，随机端口和临时凭据，不依赖本地 Compose 或 `.env.local`。Docker 不可用直接失败，不跳过数据库测试。后端超时 30 分钟，前端 15 分钟，缓存只用于依赖下载，不复用业务数据。

复用现有 `verify` 命令，不维护另一套业务测试列表。前端在 `CI` 环境额外输出 JUnit XML，本地默认控制台报告保持不变。日志经 `tee` 保存，显式 Bash shell 保留 `pipefail`，不能把失败命令因日志管道变成成功。

## 结果与失败处理

在 PR 的 Checks 或仓库 Actions 中查看相应提交的运行。安装依赖或验证失败后查看对应步骤日志，并从运行页面下载 artifact：

| 产物名 | 内容 | 保留期 |
|---|---|---|
| `backend-reports-运行ID-尝试次数` | Maven 日志、Surefire/Failsafe 报告、已生成的 JaCoCo 报告 | 14 天 |
| `frontend-reports-运行ID-尝试次数` | 依赖安装/验证日志、已生成的 JUnit XML | 14 天 |
| `frontend-dist-运行ID-尝试次数` | 成功验证后的前端生产构建 | 7 天 |

诊断上传使用 `always()`，前序验证失败仍会尝试保存已经生成的结果。若依赖安装、运行器启动失败、任务超时或取消，测试报告可能尚未生成；此时以 Actions 步骤日志为准，不能声称报告完整。构建产物只在前序步骤成功后上传，不作为自动部署包。

代码问题修复后推送新提交，基础设施偶发错误可在确认原因后重新运行失败任务。以最新候选提交的检查结果为准，不能借用旧提交的绿色结果。

## 权限与版本

只授予 `contents: read`；不使用 `pull_request_target`，不向 PR 注入仓库密钥，不保存 Git 推送凭据。官方 Actions 与 pnpm Action 均固定到完整提交 SHA，旁注发布版本；升级时核对官方发布及输入变化。依赖按锁文件安装。

首次远端运行暴露原固定 pnpm `11.13.0` 被安装器以 `ERR_PNPM_BROKEN_PNPM_RELEASE` 拒绝：该版本的可执行包发布损坏。项目 packageManager、工作区 engines 与运行文档统一修正为 `11.13.1`，保留现有依赖锁文件；不通过跳过版本保护继续使用损坏发布。历史 I0 变更记录仍保留当时的版本号。

仓库现有本地验收基线为后端 175 项、前端 68 项；这不是写死的通过门槛，后续新增用例应自然进入同一验证入口。CI 只说明自动检查结果，不能代替浏览器业务验收、异人审批、数据恢复演练或真实系统联调。

参考：[GitHub Java 配置](https://github.com/actions/setup-java)、[pnpm 安装与版本读取](https://github.com/pnpm/action-setup)、[Vitest 报告](https://vitest.dev/guide/reporters.html)、[Actions 产物上传](https://github.com/actions/upload-artifact)。
