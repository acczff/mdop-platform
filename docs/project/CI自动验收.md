# CI 自动验收

CI 首版建立前后端持续集成验证，本轮补充同次构建的候选包。每个 PR 自动检查现有后端与前端，保留诊断结果并在两项检查通过后组合发布候选。不增加 WMS 业务规则，不发布 GitHub Release、不部署应用，不执行日常数据库迁移，也不自动合并 PR。

## 触发与完成条件

工作流位于 `.github/workflows/ci.yml`，名称为 `CI`：

- 任意目标分支的 PR 创建、更新或重新打开时运行，包括草稿 PR；不按文件路径跳过。
- 推送到 `main` 时再次验证合并结果；进入主干后支持 Actions 页面手动运行。
- 同一个 PR 更新时取消旧运行，只保留最新候选；`main` 的运行不主动取消。
- `Backend verify`、`Frontend verify` 和依赖二者的 `Release candidate` 三项检查都成功，才视为本次 CI 通过。取消、跳过、排队或没有运行都不算通过。

首次工作流通过 PR 提交后即可触发 `pull_request` 验证；手动入口需要工作流已进入默认分支。当前不修改分支保护策略，工作流本身不会阻止管理员绕过检查。若后续启用必须检查，使用上述三个固定检查名称，不将 CI 当成业务审批。

## 验证范围

| 检查 | 环境与命令 | 完成标准 |
|---|---|---|
| Backend verify | Ubuntu 24.04、Temurin Java 25、仓库 Maven Wrapper；`./backend/mvnw -B -ntp -f backend/pom.xml verify` | 版本与依赖约束、Java 格式、全部测试、打包和 JaCoCo 报告成功 |
| Frontend verify | Ubuntu 24.04、Node 24.18.0；pnpm 从 `frontend/package.json` 的 packageManager 读取；`pnpm install --frozen-lockfile`、`pnpm run verify` | 格式、ESLint、Vitest、类型检查与生产构建成功 |
| Release candidate | 两项验证成功后运行；Node 内置测试、下载本次运行的组件、生成清单并逐文件校验 | 发布工具回归通过，前后端提交及运行编号一致，无文件损坏或缺失，候选包成功上传 |

后端使用现有 Testcontainers 基座启动 MySQL、RabbitMQ 和 Redis，随机端口和临时凭据，不依赖本地 Compose 或 `.env.local`。Docker 不可用直接失败，不跳过数据库测试。后端超时 30 分钟，前端 15 分钟，缓存只用于依赖下载，不复用业务数据。

复用现有 `verify` 命令，不维护另一套业务测试列表。前端在 `CI` 环境额外输出 JUnit XML，本地默认控制台报告保持不变。日志经 `tee` 保存，显式 Bash shell 保留 `pipefail`，不能把失败命令因日志管道变成成功。

## 结果与失败处理

在 PR 的 Checks 或仓库 Actions 中查看相应提交的运行。安装依赖或验证失败后查看对应步骤日志，并从运行页面下载 artifact：

| 产物名 | 内容 | 保留期 |
|---|---|---|
| `backend-reports-运行ID-尝试次数` | Maven 日志、Surefire/Failsafe 报告、已生成的 JaCoCo 报告 | 14 天 |
| `frontend-reports-运行ID-尝试次数` | 依赖安装/验证日志、已生成的 JUnit XML | 14 天 |
| `frontend-dist-运行ID-尝试次数` | 成功验证后的前端生产构建 | 7 天 |
| `backend-component-运行ID-尝试次数` | 已验证的可执行 JAR、组件构建身份及文件哈希 | 7 天 |
| `frontend-component-运行ID-尝试次数` | 已验证的前端、组件构建身份及文件哈希 | 7 天 |
| `release-reports-运行ID-尝试次数` | 发布工具测试日志；组合或下载失败另查看相应步骤日志 | 14 天 |
| `release-candidate-运行ID-尝试次数` | 同次构建的 JAR、静态前端、manifest、SHA256SUMS、配置示例、校验/备份工具和操作手册 | 14 天 |

诊断上传使用 `always()`，前序验证失败仍会尝试保存已经生成的结果。若依赖安装、运行器启动失败、任务超时或取消，测试报告可能尚未生成；此时以 Actions 步骤日志为准，不能声称报告完整。构建产物只在前序步骤成功后上传，候选包不自动部署。

组合任务按精确产物名下载当前运行和尝试次数的两个组件，不重新编译或从其他运行取包。组件身份同时记录实际 checkout 的 `buildCommit`、PR 的 `sourceCommit`、运行编号和版本；PR 的实际构建可能是 GitHub 合并候选提交。下载和文件校验失败会阻断候选上传。只重跑组合任务时，当前尝试次数缺少组件会失败，须重跑全部任务以保持同次产物；不偷偷复用旧尝试。迁移清单记录文件名和 SHA-256，不把 Flyway 自身校验值替换成 SHA-256。正式主干合并后重新验证并生成主干候选，审批记录与构建记录分别保留。

代码问题修复后推送新提交，基础设施偶发错误可在确认原因后重新运行失败任务。以最新候选提交的检查结果为准，不能借用旧提交的绿色结果。

## 权限与版本

只授予 `contents: read`；不使用 `pull_request_target`，不向 PR 注入仓库密钥，不保存 Git 推送凭据。官方 Actions 与 pnpm Action 均固定到完整提交 SHA，旁注发布版本；升级时核对官方发布及输入变化。依赖按锁文件安装。

首次远端运行暴露原固定 pnpm `11.13.0` 被安装器以 `ERR_PNPM_BROKEN_PNPM_RELEASE` 拒绝：该版本的可执行包发布损坏。项目 packageManager、工作区 engines 与运行文档统一修正为 `11.13.1`，保留现有依赖锁文件；不通过跳过版本保护继续使用损坏发布。历史 I0 变更记录仍保留当时的版本号。

首次 CI 交付基线为后端 175 项、前端 68 项；IAM 候选已增加到后端 186 项、前端 75 项，本轮另有 13 项发布工具测试。数量不是写死的通过门槛，新增用例应自然进入对应验证入口。CI 只说明自动检查结果，不能代替浏览器业务验收、异人审批、数据恢复演练或真实系统联调。

参考：[GitHub Java 配置](https://github.com/actions/setup-java)、[pnpm 安装与版本读取](https://github.com/pnpm/action-setup)、[Vitest 报告](https://vitest.dev/guide/reporters.html)、[Actions 产物上传](https://github.com/actions/upload-artifact)。

## 交付记录

2026-10-08，PR [#30](https://github.com/acczff/mdop-platform/pull/30) 已合并 main，合并提交 `0d8e627`。PR 与[主干 push 运行](https://github.com/acczff/mdop-platform/actions/runs/37714241236)均通过；下载报告核对后端 175 项、前端 68 项，失败、错误和跳过均为 0。报告与前端构建产物齐全。该数量属于 CI 首次交付基线，后续迭代以当次运行报告为准。
