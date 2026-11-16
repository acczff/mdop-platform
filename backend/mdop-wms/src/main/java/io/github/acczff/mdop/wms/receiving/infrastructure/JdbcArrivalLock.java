package io.github.acczff.mdop.wms.receiving.infrastructure;

import io.github.acczff.mdop.wms.receiving.ReceivingAccess;
import io.github.acczff.mdop.wms.receiving.domain.ArrivalLockPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@link ArrivalLockPort} 的 JDBC 实现。
 *
 * <p>正式采购（{@code MDOP_PURCHASING}）的到货必须先锁仓库行再动库存；模拟来源不加仓库锁，
 * 因此这里按 {@code source_system} 分支。该差异是刻意的，不要在正式路径复用无锁分支。
 */
@Component
public class JdbcArrivalLock implements ArrivalLockPort {

    private final JdbcClient db;
    private final ReceivingAccess access;

    public JdbcArrivalLock(JdbcClient db, ReceivingAccess access) {
        this.db = db;
        this.access = access;
    }

    @Override
    public void lockForArrival(long arrivalId) {
        var rows =
                db.sql("SELECT warehouse_id,source_system FROM wms_arrival_notice WHERE id=?")
                        .param(arrivalId)
                        .query()
                        .listOfRows();
        if (rows.isEmpty()) return; // 由调用方给出各自的“通知不存在”错误
        var row = rows.getFirst();
        long warehouse = ((Number) row.get("warehouse_id")).longValue();
        access.requireWarehouse(warehouse);
        if ("MDOP_PURCHASING".equals(row.get("source_system"))) {
            db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                    .param(warehouse)
                    .query(Long.class)
                    .single();
        }
    }
}
