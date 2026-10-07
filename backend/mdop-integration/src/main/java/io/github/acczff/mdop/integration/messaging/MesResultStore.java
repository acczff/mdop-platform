package io.github.acczff.mdop.integration.messaging;

import io.github.acczff.mdop.common.BusinessException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class MesResultStore {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final Validator validator;

    public MesResultStore(JdbcClient db, ObjectMapper json, Validator validator) {
        this.db = db;
        this.json = json;
        this.validator = validator;
    }

    @Transactional
    public void accept(EventEnvelope event) {
        String digest, key, workOrder;
        long issue;
        BigDecimal quantity;
        try {
            if (event == null
                    || !validator.validate(event).isEmpty()
                    || !Set.of(
                                    "MaterialIssued",
                                    "ProductionConsumed",
                                    "ProductionMaterialReturned",
                                    "ProductionConsumptionReversed",
                                    "ProductionReturnReversed")
                            .contains(event.eventType())
                    || !"WMS".equals(event.sourceSystem())
                    || !"MaterialIssue".equals(event.aggregateType()))
                throw new IllegalArgumentException();
            var p = event.payload();
            issue = number(p, "issueId");
            if (!String.valueOf(issue).equals(event.aggregateId()))
                throw new IllegalArgumentException();
            key = p.get("businessKey").asText();
            workOrder = text(p, "workOrderNo", 64);
            if (!p.get("businessKey").isTextual()
                    || (event.eventType().equals("MaterialIssued")
                            ? !key.isEmpty()
                            : !key.matches("[1-9][0-9]{0,18}")))
                throw new IllegalArgumentException();
            quantity = new BigDecimal(text(p, "quantity", 32));
            if (quantity.signum() <= 0
                    || quantity.scale() > 6
                    || quantity.compareTo(new BigDecimal("999999999999.999999")) > 0)
                throw new IllegalArgumentException();
            long source = number(p, "sourceWarehouseId"), line = number(p, "lineWarehouseId");
            if (source == line) throw new IllegalArgumentException();
            String originalType = "", originalKey = "";
            if (event.eventType().endsWith("Reversed")) {
                originalType = text(p, "originalEventType", 64);
                originalKey = text(p, "originalBusinessKey", 64);
                if (!originalKey.matches("[1-9][0-9]{0,18}")
                        || !originalType.equals(
                                event.eventType().equals("ProductionConsumptionReversed")
                                        ? "ProductionConsumed"
                                        : "ProductionMaterialReturned"))
                    throw new IllegalArgumentException();
            }
            var canonicalFields =
                    new ArrayList<Object>(
                            List.of(
                                    event.eventType(),
                                    issue,
                                    key,
                                    workOrder,
                                    quantity.stripTrailingZeros().toPlainString(),
                                    number(p, "materialId"),
                                    batch(p),
                                    source,
                                    line));
            if (!originalType.isEmpty()) {
                canonicalFields.add(originalType);
                canonicalFields.add(originalKey);
            }
            String canonical = json.writeValueAsString(canonicalFields);
            digest =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException(400, "INVALID_MES_RESULT", "MES 反馈契约不合法");
        }
        db.sql(
                        "INSERT INTO integration_mes_message(message_id,payload_hash) VALUES(?,?) ON DUPLICATE KEY UPDATE message_id=message_id")
                .params(event.messageId(), digest)
                .update();
        String previous =
                db.sql(
                                "SELECT payload_hash FROM integration_mes_message WHERE message_id=? FOR UPDATE")
                        .param(event.messageId())
                        .query(String.class)
                        .single();
        if (!digest.equals(previous))
            throw new BusinessException(409, "MES_MESSAGE_CONFLICT", "相同反馈消息编号内容不一致");
        db.sql(
                        "INSERT INTO integration_mes_result(event_type,issue_id,business_key,payload_hash,quantity,work_order_no) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE issue_id=issue_id")
                .params(event.eventType(), issue, key, digest, quantity, workOrder)
                .update();
        previous =
                db.sql(
                                "SELECT payload_hash FROM integration_mes_result WHERE event_type=? AND issue_id=? AND business_key=? FOR UPDATE")
                        .params(event.eventType(), issue, key)
                        .query(String.class)
                        .single();
        if (!digest.equals(previous))
            throw new BusinessException(409, "MES_RESULT_CONFLICT", "相同反馈业务身份内容不一致");
        db.sql(
                        "INSERT INTO integration_simulated_result(target_system,message_id,event_type,aggregate_id,envelope,business_key) VALUES('MES',?,?,?,?,?) ON DUPLICATE KEY UPDATE target_system=target_system")
                .params(
                        event.messageId(),
                        event.eventType(),
                        issue,
                        json.writeValueAsString(event),
                        key)
                .update();
    }

    private String batch(JsonNode p) {
        var value = p.get("batchNo");
        if (value == null || !value.isTextual() || value.asText().length() > 64)
            throw new IllegalArgumentException();
        return value.asText();
    }

    private long number(JsonNode p, String key) {
        var v = p.get(key);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() <= 0)
            throw new IllegalArgumentException();
        return v.asLong();
    }

    private String text(JsonNode p, String key, int max) {
        var v = p.get(key);
        if (v == null || !v.isTextual() || v.asText().isBlank() || v.asText().length() > max)
            throw new IllegalArgumentException();
        return v.asText();
    }
}
