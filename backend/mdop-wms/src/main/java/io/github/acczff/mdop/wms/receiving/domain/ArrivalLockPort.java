package io.github.acczff.mdop.wms.receiving.domain;

/**
 * 到货通知的加锁端口。
 *
 * <p>收货、质检、冲正与退货在写库存前都必须先按“仓库 → 到货通知 → 余额”的顺序取锁，
 * 否则会与收货提交形成环路死锁。端口把这条顺序约束暴露给应用层，
 * 具体实现（通知行、仓库行）留在基础设施层。
 *
 * <p>实现约定：通知不存在时静默返回，由调用方给出各自的未找到错误。
 */
public interface ArrivalLockPort {

    /** 校验仓库范围，并按固定顺序取得到货通知与所属仓库的行锁。 */
    void lockForArrival(long arrivalId);
}
