# ADR-0001：采用 Java 25 LTS 作为项目基线

- 状态：已接受
- 日期：2026-07-11

## 背景

Java 版本会影响编译、CI、容器和依赖兼容性

## 备选方案

- Java 21 LTS
- Java 25 LTS

## 决策

明确所有后端模块和运行环境统一使用 Java 25，保持一套构建基线

## 选择理由

- MDOP 是从零建设的新项目，没有旧系统 Java 版本兼容负担。
- Spring Boot 4.1 官方支持 Java 17 至 Java 26，能够正式运行在 Java 25 上。
- Java 25 是当前 LTS 版本，可以减少项目近期升级运行时的成本。
- 当前开发环境已经具备 Java 25，可以降低环境建设成本，但这不是唯一决策依据。

## 正面影响

- 本地开发、CI 和容器统一使用同一 Java 版本，减少环境差异。
- 项目从当前 LTS 版本起步，减少近期升级 Java 主版本的成本。
- 可以在经过评估和测试后使用 Java 25 提供的新语言及运行时能力。

## 风险和代价

- 部分第三方依赖和构建插件对 Java 25 的支持可能滞后。
- 使用 `release 25` 编译的字节码不能在 Java 21 运行环境中执行。
- 部分客户部署环境可能只允许使用 Java 21。
- 如果未来需要降级，已经使用的 Java 25 特性可能需要重写。

## 约束措施

当前已经落实：

- Maven Compiler 将 `release` 固定为 `25`。
- Maven Enforcer 将构建所需 Java 版本限制为 `[25,26)`。
- Maven Wrapper 固定 Maven 版本。
- 本地构建和 Pull Request 合并前验证使用 Java 25。

后续建立对应交付能力时必须落实：

- CI 明确安装 Eclipse Temurin JDK 25。
- 应用 Docker 镜像明确使用兼容的 JRE 25。

## 重新评估条件

- 核心依赖明确不支持 Java 25。
- 目标客户环境只能部署 Java 21。
- Java 25 出现影响生产稳定性的重大问题。
- 下一个 Java LTS 成熟，并带来明确的业务或工程收益。

## 参考资料

- [Spring Boot 4.1 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)
