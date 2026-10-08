# MDOP 候选发布包操作手册

适用 Java 25、Node 24.18—24.x（校验/备份工具）、MySQL 8.4.10 和同源静态反向代理。包不携带数据库、凭据或生产配置，不自动部署。演练需要 Docker；备份工具仅适用于配置了 `MYSQL_USER`、`MYSQL_PASSWORD` 的 MySQL 容器。其他数据库托管方式应使用其备份机制，不能照搬容器命令。

## 1. 核对版本

从已批准的 GitHub Actions 运行下载 `release-candidate-运行ID-尝试次数`，解压到新目录。核对该运行的所有检查成功，并对照可信的运行记录检查 `manifest.json` 中的 `buildCommit`、`sourceCommit`、`runId`、`runAttempt`。PR 构建通常是临时合并提交；正式交付使用合并后 main 的成功运行。

```powershell
node .\tools\release.mjs verify . <完整构建提交SHA>
```

校验失败停止部署，重新获取可信产物。`SHA256SUMS` 能发现损坏或混包，不能防止攻击者同时改文件和校验表，不能替代可信下载来源、审批或代码审查。所有配置复制到包外后修改；不要修改原包。前后端来自同一次构建，不能单独换旧版文件。

## 2. 停写与备份

记录旧版前后端、外部配置、迁移清单及消息处理状态。关闭入口并停止全部旧应用和消息消费/发布，确认没有其他写入者。确认停写后，在旧应用 JAR 与数据库相匹配的前提下执行：

```powershell
node .\tools\database.mjs backup <源MySQL容器> <数据库名> <旧版JAR路径> <新的备份目录> --writes-stopped
node .\tools\database.mjs verify <备份目录> <旧版JAR路径>
```

工具使用容器内普通账号，密码不出现在宿主机命令行；生成 SQL、其校验值、旧 JAR 校验值、迁移清单和数据指纹。完成标记为 `snapshot.json`。命令失败或缺标记的目录不作为有效备份；不要覆盖重用该目录。`--writes-stopped` 是操作者声明，脚本不能代替停写或消除备份后的数据丢失风险。备份含业务和账号数据，应限制文件访问并存入受控备份介质，不能提交 Git。

## 3. 隔离升级

新建独立 MySQL 8.4.10 容器和空数据库，不映射日常服务端口、不挂载日常数据卷。容器配置 `MYSQL_DATABASE`、独立 `MYSQL_USER`/`MYSQL_PASSWORD` 和不同的 root 密码。将旧备份恢复到空库：

```powershell
node .\tools\database.mjs restore <隔离MySQL容器> <空数据库名> <旧版JAR路径> <备份目录> --empty-target
```

工具会拒绝非空目标、损坏备份及不匹配的 JAR。导入失败可能留下部分数据，此时保持应用停止，保留证据并新建另一个空目标重试，不自动删表或覆盖。

将 `config/application-release.yml` 复制到包外，设置当前终端/进程的环境变量 `MDOP_JDBC_URL`、`MDOP_DB_USER`、`MDOP_DB_PASSWORD`、`MDOP_ADMIN_USERNAME`、`MDOP_ADMIN_PASSWORD` 和独立的 `MDOP_BACKEND_PORT`。管理员初始密码至少 12 字符，不能写入仓库；原操作员需在外部配置的 `mdop.auth.operators` 列表保留原名称、密码及权限供首次导入。初始化后配置不能重置已保存账号。

隔离演练保持 `MDOP_MESSAGING_ENABLED=false`，不连接原 RabbitMQ；不用 `local` 或 `test` profile。仅本机 HTTP 演练可设置当前进程 `MDOP_COOKIE_SECURE=false`；网络部署必须使用 HTTPS 和 Secure Cookie。

```powershell
java -jar .\backend\mdop.jar --spring.config.additional-location=file:<包外application-release.yml的绝对路径>
```

启动会执行缺失的 Flyway 迁移，不能在未经确认的数据库上试启动。检查后端健康 `/actuator/health` 为 `UP`、日志无迁移/初始化错误、已执行版本与清单一致。仅 Java 进程存在或端口打开不代表就绪。

静态代理参考 `config/nginx.conf`，在包外调整 root 和后端地址，执行 `nginx -t` 后由部署环境的服务管理器启动。前端必须与 `/api/` 同源，保留 Cookie/CSRF，不使用 Vite 开发服务器代替部署。默认示例只监听本机 HTTP；TLS、域名、服务自启与实际生产网络配置需由目标环境决定。

## 4. 验收与开放作业

- 浏览器登录，刷新深层路由；核对静态版本 `/release.json` 与包清单。普通账号不能管理用户，系统管理员不自动拥有业务全权限。
- 核对旧操作员规范名称及仓库范围；分配独立仓管和审批账号，确认旧权限变更后会话失效及禁止自审。
- 在 MySQL 客户端执行包内只读 `tools/audit-inventory.sql`，47 项差异应均为 0；核对历史单据和业务数量，不仅看空库测试。
- 核对后端健康、账号启停状态、审计和迁移。按既有 WMS 清单验收主流程与基本异常。
- 演练通过并经目标环境批准后，才安排正式停写窗口。网络环境还须明确 TLS、密钥管理、数据库权限、日志及备份留存、消息目标和恢复责任人；候选包不声明已满足生产准入。

## 5. 失败与恢复

| 情况 | 处理 |
|---|---|
| 包损坏或混版本 | 不启动，重新下载正确候选 |
| 迁移、初始化或健康检查失败 | 保持入口关闭，停止新应用并保存日志；不删 Flyway 记录，不改历史 SQL |
| 登录失败 | 核对初始化状态、角色和用户名，不通过改环境变量强行重建账号 |
| 对账差异 | 暂停开放作业，保留原单和流水，定位差异，不直接改库存 |
| 需要回退 | 停止所有新版本写入，在另一个空库恢复升级前备份，使用匹配的旧版前后端和外部配置，验证健康、历史数量、权限及 47 项对账后再切换入口 |

禁止只回退 JAR 继续使用已升级数据库。首次 IAM 升级前的旧版仍采用配置式全权限管理员，恢复旧版会恢复旧权限模型；新建 IAM 账号不会出现在升级前备份中。恢复已有 IAM 版本时，账号状态和审计应一并恢复，不因重启被配置覆盖。

数据库备份不包含 RabbitMQ 队列或 ERP/MES/QMS 的外部事实。恢复副本始终关闭消息，真实事故需单独核对 Outbox/Inbox、队列及下游已处理结果后再恢复投递，不能承诺全系统时间倒退。已开放作业后的回退可能丢失备份后业务，必须先评估并处理这些变更，不能自动覆盖。
