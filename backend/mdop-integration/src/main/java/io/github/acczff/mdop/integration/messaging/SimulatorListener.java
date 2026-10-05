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
            if (!expected.equals(event.eventType())
                    || event.eventVersion() != 1
                    || !"WMS".equals(event.sourceSystem())) throw new IllegalArgumentException();
            Long.parseLong(event.aggregateId());
        } catch (Exception e) {
            throw new AmqpRejectAndDontRequeueException("INVALID_RESULT_EVENT");
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
    }
}
