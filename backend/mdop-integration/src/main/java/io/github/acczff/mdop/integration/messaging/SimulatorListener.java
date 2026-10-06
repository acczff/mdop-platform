package io.github.acczff.mdop.integration.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile({"local", "test"})
@ConditionalOnProperty(name = "mdop.messaging.enabled", havingValue = "true")
public class SimulatorListener {
    private final JdbcClient db;
    private final ObjectMapper json;

    public SimulatorListener(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @RabbitListener(queues = MessagingConfiguration.ERP)
    @Transactional
    public void erp(Message message) {
        consume("ERP", "PurchaseReceiptConfirmed", message);
    }

    @RabbitListener(queues = MessagingConfiguration.QMS)
    @Transactional
    public void qms(Message message) {
        consume("QMS", "IncomingInspectionRequested", message);
    }

    private void consume(String target, String expected, Message message) {
        EventEnvelope event;
        try {
            event = json.readValue(message.getBody(), EventEnvelope.class);
            String compensation =
                    target.equals("ERP")
                            ? "PurchaseReceiptReversed"
                            : "IncomingInspectionCancelled";
            if (!(expected.equals(event.eventType())
                            || compensation.equals(event.eventType())
                            || (target.equals("ERP")
                                    && event.eventType().equals("PurchaseReturnConfirmed")))
                    || event.eventVersion() != 1
                    || !"WMS".equals(event.sourceSystem())) throw new IllegalArgumentException();
            Long.parseLong(event.aggregateId());
        } catch (Exception e) {
            throw new AmqpRejectAndDontRequeueException("INVALID_RESULT_EVENT");
        }
        if (event.eventType().equals("PurchaseReturnConfirmed")) {
            consumeReturn(event);
            return;
        }
        db.sql(
                        "INSERT INTO integration_simulated_result(target_system,message_id,event_type,aggregate_id,envelope) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE target_system=target_system")
                .params(
                        target,
                        event.messageId(),
                        event.eventType(),
                        Long.parseLong(event.aggregateId()),
                        json.writeValueAsString(event))
                .update();
        boolean reversed =
                event.eventType().equals("PurchaseReceiptReversed")
                        || event.eventType().equals("IncomingInspectionCancelled");
        db.sql(
                        "INSERT INTO integration_simulated_receipt_state(target_system,receipt_id,state) VALUES (?,?,?) ON DUPLICATE KEY UPDATE state=IF(state='REVERSED' OR ?='REVERSED','REVERSED','RECEIVED')")
                .params(
                        target,
                        Long.parseLong(event.aggregateId()),
                        reversed ? "REVERSED" : "RECEIVED",
                        reversed ? "REVERSED" : "RECEIVED")
                .update();
    }

    private void consumeReturn(EventEnvelope event) {
        long returnId, receiptId;
        java.math.BigDecimal quantity;
        String digest;
        try {
            returnId = event.payload().get("returnId").asLong();
            receiptId = event.payload().get("receiptId").asLong();
            quantity = new java.math.BigDecimal(event.payload().get("quantity").asText());
            if (returnId <= 0
                    || receiptId <= 0
                    || receiptId != Long.parseLong(event.aggregateId())
                    || !event.aggregateType().equals("Receipt")
                    || quantity.signum() <= 0
                    || quantity.scale() > 6
                    || quantity.compareTo(new java.math.BigDecimal("999999999999.999999")) > 0)
                throw new IllegalArgumentException();
            digest =
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(json.writeValueAsBytes(event.payload())));
        } catch (Exception e) {
            throw new AmqpRejectAndDontRequeueException("INVALID_RETURN_EVENT");
        }
        db.sql(
                        "INSERT INTO integration_simulated_purchase_return(return_id,receipt_id,quantity,payload_hash) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE return_id=return_id")
                .params(returnId, receiptId, quantity, digest)
                .update();
        var original =
                db.sql(
                                "SELECT payload_hash FROM integration_simulated_purchase_return WHERE return_id=? FOR UPDATE")
                        .param(returnId)
                        .query(String.class)
                        .single();
        if (!digest.equals(original))
            throw new AmqpRejectAndDontRequeueException("RETURN_ID_CONFLICT");
        db.sql(
                        "INSERT INTO integration_simulated_result(target_system,message_id,event_type,aggregate_id,envelope,business_key) VALUES ('ERP',?,?,?,?,?) ON DUPLICATE KEY UPDATE target_system=target_system")
                .params(
                        event.messageId(),
                        event.eventType(),
                        receiptId,
                        json.writeValueAsString(event),
                        String.valueOf(returnId))
                .update();
    }
}
