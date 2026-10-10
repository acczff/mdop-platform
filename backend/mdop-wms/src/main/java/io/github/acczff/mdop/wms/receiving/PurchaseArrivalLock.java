package io.github.acczff.mdop.wms.receiving;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Formal procurement facts and closure serialize warehouse first, then notice/receipt. */
final class PurchaseArrivalLock {
    private PurchaseArrivalLock() {}

    static void acquire(JdbcClient db, ReceivingAccess access, long arrivalId) {
        var rows =
                db.sql("SELECT warehouse_id,source_system FROM wms_arrival_notice WHERE id=?")
                        .param(arrivalId)
                        .query()
                        .listOfRows();
        if (rows.isEmpty()) return; // The owning service reports its normal not-found error.
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
