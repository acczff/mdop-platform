/**
 * 接口层：把 HTTP 请求转换为应用层调用。
 *
 * <p>职责：路径与状态码、参数校验、功能权限注解、异常到 ProblemDetail 的映射。
 * 不写业务规则，不直接访问数据库，也不依赖基础设施实现。
 */
package io.github.acczff.mdop.purchasing.api;
