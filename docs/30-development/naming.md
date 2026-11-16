# 命名规范

> 类型：规范 | 状态：稳定 | 适用：0.1 开发分支 | 责任：平台组

统一命名的目的不是好看，而是**让人和工具都能从名字判断归属**：这是哪一层、属于哪个域、是不是历史记录。
新增文件前对照本页；改名只改名字，不改内容。

## 一、总则

1. **一个名字只表达一件事**：归属（模块/域/层）优先于描述。
2. **ASCII 优先**：代码、脚本、SQL、通用文档一律 ASCII；业务域文档与归档可使用中文。
3. **同目录同风格**：同一目录内不允许中英混排（历史归档除外，见第四节）。
4. **编号只用于有序集合**：迭代、批次、业务域规格用编号；普通文档不用。
5. **改名必须同步改引用**，并跑 `node scripts/checks/docs/check.mjs`。

## 二、代码与配置

| 类型 | 规则 | 示例 |
|---|---|---|
| Java 类 / 记录 / 枚举 | `PascalCase` | `ReceivingService`、`ArrivalLockPort` |
| Java 包 | 全小写，按层或域 | `wms.receiving.application` |
| Java 包说明 | 固定 `package-info.java` | — |
| 测试类 | 被测对象 + `Tests` | `ReceivingTests`、`LoginThrottleTests` |
| Vue 组件 | `PascalCase.vue`，后缀表达角色 | `ReceivingView.vue`、`CompletionPanel.vue` |
| 前端测试 | 被测对象 + `.spec.ts` | `Inventory.spec.ts` |
| 前端工具/模块 | `camelCase.ts` | `quantity.ts`、`businessPresentation.ts` |
| 配置 | 小写，Spring 约定 | `application-local.yml` |

## 三、脚本与 SQL

| 类型 | 规则 | 示例 |
|---|---|---|
| 入口脚本 | 保持稳定短名，不随目录调整 | `mdop.ps1`、`pnpm.cmd` |
| 工具脚本 | 小写 kebab-case，动词或对象开头 | `startup-checks.ps1`、`start-desktop.ps1` |
| Node 工具 | 小写 kebab-case | `check.mjs`、`release.mjs` |
| 工具自测 | 与被测同名 + `.test` | `check.test.mjs`、`startup-checks.test.ps1` |
| SQL 迁移 | `V<12 或 14 位时间戳>__snake_case.sql` | `V202610050002__receiving_core.sql` |
| 运维 SQL | 小写 kebab-case | `audit-inventory.sql` |

## 四、文档

### 4.1 现行通用文档（索引、指南、规范）

全小写 ASCII kebab-case，目录已表达分类，文件名不再重复模块名。

| 目录 | 规则 | 示例 |
|---|---|---|
| `docs/` 根 | 固定大写 | `README.md`、`INDEX.md` |
| `00-start` / `10-architecture` / `30-development` / `40-operations` | `lower-kebab.md` | `quickstart.md`、`code-reading.md`、`module-template.md` |
| 每个分类入口 | 固定 `README.md` | `docs/30-development/README.md` |

### 4.2 业务域文档（`20-domains/`）

`NN-<中文主题>.md`，两位序号在同一子域内连续，便于按顺序阅读。

| 目录 | 示例 |
|---|---|
| `20-domains/erp/` | `01-主数据.md` … `07-查询与控制.md` |
| `20-domains/wms/` | `01-WMS全链路设计.md`、`02-库存冻结与解冻首版.md` |

### 4.3 归档（`90-archive/`）

保留入库时的编号与中文标题，**不再改名**：编号即历史坐标。

| 子目录 | 规则 | 示例 |
|---|---|---|
| `iterations/` | 迭代号 + 中文标题 | `I0工程基线方案.md`、`I2-I4消息接入与可靠反馈.md` |
| `acceptance/` | 批次号或主题 + 中文标题 | `R1采购销售联合验收.md`、`采购入库阶段验收.md` |
| `acceptance/erp/` | 切片名 + `实现与验收` | `采购P1实现与验收.md` |
| `planning/` | 中文标题 | `项目总体蓝图.md`、`整理报告.md` |

### 4.4 决策记录

`ADR-<四位序号>-<中文标题>.md`，序号只增不减。

## 五、新文件检查清单

- [ ] 类型明确：代码 / 脚本 / SQL / 现行文档 / 业务域文档 / 归档
- [ ] 目录正确，同目录风格一致
- [ ] 代码遵循语言惯例，测试与被测同名
- [ ] SQL 迁移时间戳唯一，表前缀属于本模块
- [ ] 文档已加四行元信息（类型、状态、适用版本、责任）
- [ ] 改名已同步全部引用，`node scripts/checks/docs/check.mjs` 通过

## 六、已知例外

| 文件 | 原因 |
|---|---|
| `backend/**/package-info.java` | Java 语言约定，固定小写 |
| `docs/50-decisions/ADR-*.md` | 序号大写 + 中文标题，便于按编号检索 |
| 中文标题中的技术缩写（`WMS`、`BOM`、`ERP`、`Java25`） | 保留原写法，不转小写 |
