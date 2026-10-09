package io.github.acczff.mdop.wms.inventory;

import io.github.acczff.mdop.common.manufacturing.MaterialStockReference;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class MaterialStockReferenceAdapter implements MaterialStockReference {
    private final JdbcClient db;
    private final Clock clock;

    public MaterialStockReferenceAdapter(JdbcClient db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public List<Map<String, Object>> read(long warehouse, List<Long> materials) {
        if (materials.isEmpty()) return List.of();
        var rows =
                db.sql(
                                "SELECT b.id,b.material_id,b.available_qty,b.reservation_version,b.active_freeze_id,b.expiry_date FROM wms_inventory_balance b JOIN mdm_location l ON l.id=b.location_id WHERE b.warehouse_id=:warehouse AND b.material_id IN (:materials) AND b.quality_status='QUALIFIED' AND l.area_type='STORAGE' AND b.origin_type='PURCHASE' AND b.supplier_id IS NOT NULL AND b.owner_type='ENTERPRISE' AND b.active_freeze_id IS NULL AND (b.expiry_date IS NULL OR b.expiry_date>=:today) AND b.available_qty>0 ORDER BY b.id")
                        .param("warehouse", warehouse)
                        .param("materials", materials)
                        .param("today", LocalDate.now(clock))
                        .query()
                        .listOfRows();
        rows.forEach(
                r ->
                        r.replaceAll(
                                (k, v) ->
                                        v instanceof BigDecimal || v instanceof java.sql.Date
                                                ? v.toString()
                                                : v));
        return rows;
    }
}
