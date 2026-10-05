package io.github.acczff.mdop.integration.messaging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class EventPublisher {
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;

    public EventPublisher(RabbitTemplate rabbit, ObjectMapper json) {
        this.rabbit = rabbit;
        this.json = json;
    }

    public void publish(EventEnvelope event) {
        var correlation = new CorrelationData(UUID.randomUUID().toString());
        var message =
                MessageBuilder.withBody(
                                json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .setMessageId(event.messageId())
                        .build();
        try {
            rabbit.send(MessagingConfiguration.EXCHANGE, event.eventType(), message, correlation);
            var confirm = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (!confirm.ack() || correlation.getReturned() != null)
                throw new IllegalStateException("BROKER_REJECTED_OR_UNROUTABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PUBLISH_INTERRUPTED", e);
        } catch (Exception e) {
            throw new IllegalStateException("PUBLISH_NOT_CONFIRMED", e);
        }
    }
}
