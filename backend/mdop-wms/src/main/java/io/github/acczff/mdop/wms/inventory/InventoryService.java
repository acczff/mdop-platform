package io.github.acczff.mdop.wms.inventory;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.wms.receiving.ReceivingAccess;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InventoryService {
    private final JdbcClient db;
    private final ReceivingAccess access;

    public InventoryService(JdbcClient db, ReceivingAccess access) {
        this.db = db;
        this.access = access;
    }

    public record Page(
            List<Map<String, Object>> items,
            int page,
            int size,
            long totalElements,
            long totalPages) {}

    private static final String FROM =
            """
        FROM wms_inventory_balance b
        JOIN mdm_material m ON m.id=b.material_id
        JOIN mdm_location l ON l.id=b.location_id
        JOIN mdm_supplier s ON s.id=b.supplier_id
        JOIN mdm_warehouse w ON w.id=b.warehouse_id
        """;
    private static final String COLUMNS =
            """
        SELECT b.id,b.warehouse_id,w.name AS warehouse_name,b.location_id,l.code AS location_code,
               l.name AS location_name,b.material_id,m.code AS material_code,m.name AS material_name,
               m.unit,b.supplier_id,s.name AS supplier_name,b.batch_no,b.date_code,
               CAST(b.production_date AS CHAR) AS production_date,
               CAST(b.expiry_date AS CHAR) AS expiry_date,b.quality_status,b.owner_type,b.owner_id,
               CAST(b.on_hand_qty AS CHAR) AS on_hand_qty,
               CAST(b.available_qty AS CHAR) AS available_qty
        """;

    public Page search(
            long warehouseId,
            String keyword,
            String batch,
            String quality,
            Long locationId,
            boolean includeZero,
            int page,
            int size) {
        access.requireWarehouse(warehouseId);
        StringBuilder where = new StringBuilder(" WHERE b.warehouse_id=:warehouseId");
        Map<String, Object> params = new HashMap<>();
        params.put("warehouseId", warehouseId);
        if (!keyword.isBlank()) {
            // LOCATE treats percent and underscore as literal text, not SQL wildcard operators.
            where.append(" AND (LOCATE(:keyword,m.code)>0 OR LOCATE(:keyword,m.name)>0)");
            params.put("keyword", keyword.trim());
        }
        if (!batch.isBlank()) {
            where.append(" AND b.batch_no=:batch");
            params.put("batch", batch.trim());
        }
        if (!quality.isEmpty()) {
            where.append(" AND b.quality_status=:quality");
            params.put("quality", quality);
        }
        if (locationId != null) {
            where.append(" AND b.location_id=:locationId");
            params.put("locationId", locationId);
        }
        if (!includeZero) where.append(" AND b.on_hand_qty>0");
        long total =
                db.sql("SELECT COUNT(*) " + FROM + where).params(params).query(Long.class).single();
        params.put("size", size);
        params.put("offset", (long) page * size);
        var items =
                db.sql(COLUMNS + FROM + where + " ORDER BY b.id DESC LIMIT :size OFFSET :offset")
                        .params(params)
                        .query()
                        .listOfRows();
        return new Page(items, page, size, total, (total + size - 1) / size);
    }

    public Map<String, Object> detail(long id) {
        var row =
                db.sql(COLUMNS + FROM + " WHERE b.id=?").param(id).query().listOfRows().stream()
                        .findFirst()
                        .orElseThrow(
                                () -> new BusinessException(404, "STOCK_NOT_FOUND", "库存记录不存在"));
        access.requireWarehouse(((Number) row.get("warehouse_id")).longValue());
        return row;
    }

    public Page transactions(long id, int page, int size) {
        detail(id);
        long total =
                db.sql("SELECT COUNT(*) FROM wms_inventory_transaction WHERE balance_id=?")
                        .param(id)
                        .query(Long.class)
                        .single();
        var rows =
                db.sql(
                                """
            SELECT t.id,t.transaction_type,t.receipt_id,r.receipt_no,t.receipt_item_id,
                   a.external_notice_no,a.purchase_order_no,i.material_code,i.material_name,i.unit,
                   CAST(t.before_qty AS CHAR) AS before_qty,CAST(t.change_qty AS CHAR) AS change_qty,
                   CAST(t.after_qty AS CHAR) AS after_qty,t.reversed_transaction_id,t.purchase_return_id,
                   t.created_by,t.created_at,t.transfer_id,t.count_id,
                   x.source_balance_id,x.target_balance_id
            FROM wms_inventory_transaction t LEFT JOIN wms_receipt r ON r.id=t.receipt_id
            LEFT JOIN wms_receipt_item ri ON ri.id=t.receipt_item_id
            LEFT JOIN wms_arrival_notice_item i ON i.id=ri.arrival_item_id
            LEFT JOIN wms_arrival_notice a ON a.id=r.arrival_id
            LEFT JOIN wms_stock_transfer x ON x.id=t.transfer_id
            WHERE t.balance_id=? ORDER BY t.id DESC LIMIT ? OFFSET ?
            """)
                        .params(id, size, (long) page * size)
                        .query()
                        .listOfRows();
        return new Page(rows, page, size, total, (total + size - 1) / size);
    }
}
