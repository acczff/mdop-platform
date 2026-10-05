package io.github.acczff.mdop.integration.messaging;

import io.github.acczff.mdop.common.BusinessException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "mdop.messaging.enabled", havingValue = "true")
public class ArrivalListener {
    private final DeliveryService service;
    private final ObjectMapper json;

    public ArrivalListener(DeliveryService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @RabbitListener(queues = MessagingConfiguration.ARRIVALS)
    public void receive(Message message) {
        EventEnvelope event;
        try {
            if (message.getBody().length > 262144)
                throw new IllegalArgumentException("EVENT_TOO_LARGE");
            event = json.readValue(message.getBody(), EventEnvelope.class);
            service.validateArrival(event);
        } catch (Exception invalid) {
            service.reject(message.getBody(), "INVALID_EVENT");
            throw new AmqpRejectAndDontRequeueException("INVALID_EVENT");
        }
        try {
            service.accept(event);
        } catch (BusinessException conflict) {
            service.reject(message.getBody(), "MESSAGE_ID_CONFLICT");
            throw new AmqpRejectAndDontRequeueException("MESSAGE_ID_CONFLICT");
        }
        // Container ACK follows a committed durable Inbox write. Business work is retried from
        // Inbox.
    }
}
