package io.github.acczff.mdop.wms.inventory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProductionEvents {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final Clock clock;

    public ProductionEvents(JdbcClient db, ObjectMapper json, Clock clock) {
        this.db = db;
        this.json = json;
        this.clock = clock;
    }

    // Called inside the inventory transaction; delivery is owned by the integration module.
    public void record(long issueId, String type, String key, BigDecimal quantity) {
        record(issueId, type, key, quantity, null, null);
    }

    public void reverse(
            long issueId,
            String type,
            String key,
            String originalType,
            String originalKey,
            BigDecimal quantity) {
        record(issueId, type, key, quantity, originalType, originalKey);
    }

    private void record(
            long issueId,
            String type,
            String key,
            BigDecimal quantity,
            String originalType,
            String originalKey) {
        var d =
                db.sql(
                                "SELECT d.*,b.batch_no FROM wms_material_issue d JOIN wms_inventory_balance b ON b.id=d.source_balance_id WHERE d.id=?")
                        .param(issueId)
                        .query()
                        .singleRow();
        var payload =
                Map.of(
                        "issueId",
                        issueId,
                        "workOrderNo",
                        d.get("work_order_no"),
                        "businessKey",
                        key,
                        "quantity",
                        quantity.toPlainString(),
                        "materialId",
                        d.get("material_id"),
                        "batchNo",
                        d.get("batch_no"),
                        "sourceWarehouseId",
                        d.get("warehouse_id"),
                        "lineWarehouseId",
                        d.get("target_warehouse_id"));
        var envelope = new java.util.LinkedHashMap<String, Object>(payload);
        if (originalType != null) {
            envelope.put("originalEventType", originalType);
            envelope.put("originalBusinessKey", originalKey);
        }
        db.sql(
                        "INSERT INTO wms_outbox(message_id,event_type,aggregate_type,aggregate_id,trace_id,payload,occurred_at,business_key) VALUES(?,?,'MaterialIssue',?,?,?,?,?)")
                .params(
                        UUID.randomUUID().toString(),
                        type,
                        issueId,
                        UUID.randomUUID().toString(),
                        json.writeValueAsString(envelope),
                        Timestamp.from(clock.instant()),
                        key)
                .update();
    }
}
