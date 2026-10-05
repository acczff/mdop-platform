package io.github.acczff.mdop.integration.local;

import io.github.acczff.mdop.integration.messaging.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"local", "test"})
@ConditionalOnProperty(name = "mdop.messaging.enabled", havingValue = "true")
@RequestMapping("/api/local/erp-messages")
@PreAuthorize("hasRole('ADMIN')")
public class ErpMessageController {
    private final EventPublisher publisher;
    private final DeliveryService service;
    private final JdbcClient db;

    public ErpMessageController(EventPublisher publisher, DeliveryService service, JdbcClient db) {
        this.publisher = publisher;
        this.service = service;
        this.db = db;
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Map<String, String> send(@Valid @RequestBody EventEnvelope event) {
        service.validateArrival(event);
        try {
            publisher.publish(event);
        } catch (IllegalStateException failure) {
            throw new io.github.acczff.mdop.common.BusinessException(
                    503, "PUBLISH_NOT_CONFIRMED", "消息发送尚未确认，请核对消息记录后使用同一消息编号重试");
        }
        return Map.of("messageId", event.messageId(), "status", "ACCEPTED");
    }

    @GetMapping("/results")
    public List<Map<String, Object>> results() {
        return db.sql(
                        "SELECT target_system,message_id,event_type,aggregate_id,received_at FROM integration_simulated_result ORDER BY received_at DESC LIMIT 100")
                .query()
                .listOfRows();
    }
}
