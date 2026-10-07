package io.github.acczff.mdop.integration.messaging;

import io.github.acczff.mdop.common.BusinessException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.*;

@Service
public class WarehouseResultStore {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final Validator validator;

    public WarehouseResultStore(JdbcClient db, ObjectMapper json, Validator validator) {
        this.db = db;
        this.json = json;
        this.validator = validator;
    }

    @Transactional
    public void accept(EventEnvelope e) {
        long id;
        String target, digest;
        try {
            if (e == null
                    || !validator.validate(e).isEmpty()
                    || !e.sourceSystem().equals("WMS")
                    || !Set.of("FinishedReceipt", "SalesOrder").contains(e.aggregateType())
                    || !Set.of(
                                    "FinishedGoodsReceived",
                                    "FinishedInspectionRequested",
                                    "FinishedGoodsPutaway",
                                    "SalesOutboundConfirmed")
                            .contains(e.eventType())) throw new IllegalArgumentException();
            var p = e.payload();
            boolean sales = e.aggregateType().equals("SalesOrder");
            if (sales != e.eventType().equals("SalesOutboundConfirmed"))
                throw new IllegalArgumentException();
            id = number(p, sales ? "salesOrderId" : "receiptId");
            if (!String.valueOf(id).equals(e.aggregateId())) throw new IllegalArgumentException();
            var qty = new BigDecimal(text(p, "quantity", 32));
            if (qty.signum() <= 0
                    || qty.scale() > 6
                    || qty.compareTo(new BigDecimal("999999999999.999999")) > 0)
                throw new IllegalArgumentException();
            target =
                    sales
                            ? "ERP"
                            : e.eventType().equals("FinishedInspectionRequested") ? "QMS" : "MES";
            java.util.List<Object> canonical;
            if (sales) {
                canonical =
                        List.of(
                                e.aggregateType(),
                                id,
                                e.eventType(),
                                target,
                                text(p, "salesOrderNo", 64),
                                text(p, "customerReference", 128),
                                number(p, "warehouseId"),
                                number(p, "materialId"),
                                text(p, "batchNo", 64),
                                qty.stripTrailingZeros().toPlainString());
            } else {
                String quality = text(p, "qualityStatus", 32);
                if (!quality.equals(
                        e.eventType().equals("FinishedGoodsPutaway")
                                ? "QUALIFIED"
                                : "PENDING_INSPECTION")) throw new IllegalArgumentException();
                canonical =
                        List.of(
                                e.aggregateType(),
                                id,
                                e.eventType(),
                                target,
                                text(p, "workOrderNo", 64),
                                number(p, "warehouseId"),
                                number(p, "materialId"),
                                text(p, "batchNo", 64),
                                qty.stripTrailingZeros().toPlainString(),
                                quality);
            }
            digest =
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(json.writeValueAsBytes(canonical)));
        } catch (Exception ex) {
            throw new BusinessException(400, "INVALID_WAREHOUSE_RESULT", "仓储反馈契约不合法");
        }
        db.sql(
                        "INSERT INTO integration_warehouse_message(message_id,payload_hash) VALUES(?,?) ON DUPLICATE KEY UPDATE message_id=message_id")
                .params(e.messageId(), digest)
                .update();
        if (!digest.equals(
                db.sql(
                                "SELECT payload_hash FROM integration_warehouse_message WHERE message_id=? FOR UPDATE")
                        .param(e.messageId())
                        .query(String.class)
                        .single())) throw conflict();
        db.sql(
                        "INSERT INTO integration_warehouse_result(aggregate_type,aggregate_id,event_type,target_system,payload_hash) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE aggregate_id=aggregate_id")
                .params(e.aggregateType(), id, e.eventType(), target, digest)
                .update();
        if (!digest.equals(
                db.sql(
                                "SELECT payload_hash FROM integration_warehouse_result WHERE aggregate_type=? AND aggregate_id=? AND event_type=? AND target_system=? FOR UPDATE")
                        .params(e.aggregateType(), id, e.eventType(), target)
                        .query(String.class)
                        .single())) throw conflict();
        db.sql(
                        "INSERT INTO integration_simulated_result(target_system,message_id,event_type,aggregate_id,envelope) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE target_system=target_system")
                .params(target, e.messageId(), e.eventType(), id, json.writeValueAsString(e))
                .update();
    }

    private BusinessException conflict() {
        return new BusinessException(409, "WAREHOUSE_RESULT_CONFLICT", "仓储反馈编号或业务身份内容冲突");
    }

    private String text(JsonNode p, String key, int max) {
        var v = p.get(key);
        if (v == null || !v.isTextual() || v.asText().isBlank() || v.asText().length() > max)
            throw new IllegalArgumentException();
        return v.asText();
    }

    private long number(JsonNode p, String key) {
        var v = p.get(key);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() <= 0)
            throw new IllegalArgumentException();
        return v.asLong();
    }
}
