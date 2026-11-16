/**
 * 接口层：收货、质检、冲正与采购退货的 HTTP 入口。
 *
 * <p>职责：路径与状态码、参数校验、功能权限注解、异常映射。
 * 不写业务规则，不直接访问数据库。仓库范围的判定统一通过
 * {@link io.github.acczff.mdop.wms.receiving.ReceivingAccess} 完成。
 */
package io.github.acczff.mdop.wms.receiving.api;
