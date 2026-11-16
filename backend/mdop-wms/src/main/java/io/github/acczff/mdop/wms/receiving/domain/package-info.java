/**
 * 领域层：收货与质检的规则抽象。
 *
 * <p>职责：仓储接口、状态与数量规则。保持纯 Java，不依赖 Spring、JDBC 或 Web。
 * 当前阶段先承载只读查询与加锁所需的仓储契约。
 */
package io.github.acczff.mdop.wms.receiving.domain;
