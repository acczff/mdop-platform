/**
 * 应用层：编排一次完整的业务动作。
 *
 * <p>职责：事务边界、状态机推进、幂等键校验、审计记录、跨模块契约调用。
 * 只依赖领域接口与 common 契约，不直接编写 SQL。
 */
package io.github.acczff.mdop.purchasing.application;
