/**
 * 应用层：收货到上架、质检、冲正与退货的用例编排。
 *
 * <p>职责：事务边界、单据状态推进、幂等键与载荷哈希、库存流水写入的调用顺序。
 * 加锁顺序固定为“仓库 → 到货通知 → 单据/余额”，不得调整。
 */
package io.github.acczff.mdop.wms.receiving.application;
