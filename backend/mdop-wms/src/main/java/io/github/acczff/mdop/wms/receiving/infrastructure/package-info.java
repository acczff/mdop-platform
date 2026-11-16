/**
 * 基础设施层：收货域的 SQL、行映射与加锁实现。
 *
 * <p>职责：查询与加锁语句、外部适配。此处可以依赖 Spring 与数据库，
 * 但不决定事务边界，也不写业务分支。
 */
package io.github.acczff.mdop.wms.receiving.infrastructure;
