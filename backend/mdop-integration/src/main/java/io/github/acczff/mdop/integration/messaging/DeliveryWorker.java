package io.github.acczff.mdop.integration.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mdop.messaging.enabled", havingValue = "true")
public class DeliveryWorker {
    private final DeliveryService service;

    public DeliveryWorker(DeliveryService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${mdop.messaging.poll-ms:2000}",
            initialDelayString = "${mdop.messaging.initial-delay-ms:5000}")
    public void tick() {
        service.dispatch(DeliveryService.Direction.INBOX);
        service.dispatch(DeliveryService.Direction.OUTBOX);
    }
}
