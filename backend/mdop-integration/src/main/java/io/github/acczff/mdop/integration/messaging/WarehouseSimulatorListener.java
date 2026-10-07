package io.github.acczff.mdop.integration.messaging;

import io.github.acczff.mdop.common.BusinessException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@Profile({"local", "test"})
@ConditionalOnProperty(name = "mdop.messaging.enabled", havingValue = "true")
public class WarehouseSimulatorListener {
    private final WarehouseResultStore store;
    private final DeliveryService delivery;
    private final ObjectMapper json;

    public WarehouseSimulatorListener(
            WarehouseResultStore store, DeliveryService delivery, ObjectMapper json) {
        this.store = store;
        this.delivery = delivery;
        this.json = json;
    }

    @RabbitListener(queues = MessagingConfiguration.OPERATIONS)
    public void consume(Message message) {
        EventEnvelope event;
        try {
            event = json.readValue(message.getBody(), EventEnvelope.class);
        } catch (Exception e) {
            reject(message, "INVALID_WAREHOUSE_JSON");
            return;
        }
        try {
            store.accept(event);
        } catch (BusinessException e) {
            reject(message, e.getCode());
        }
    }

    private void reject(Message m, String reason) {
        delivery.reject(m.getBody(), reason);
        throw new AmqpRejectAndDontRequeueException(reason);
    }
}
