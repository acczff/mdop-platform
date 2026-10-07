package io.github.acczff.mdop.integration.messaging;

import io.github.acczff.mdop.wms.inventory.FinishedGoodsService;
import jakarta.validation.constraints.Positive;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/integration/finished-receipts")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:finished:read')")
public class FinishedFeedbackController {
    private final FinishedGoodsService service;
    private final JdbcClient db;

    public FinishedFeedbackController(FinishedGoodsService service, JdbcClient db) {
        this.service = service;
        this.db = db;
    }

    @GetMapping("/{id}/feedback")
    public List<Map<String, Object>> feedback(@PathVariable @Positive long id) {
        service.detail(id);
        return db.sql(
                        "SELECT o.message_id,o.event_type,o.status,o.last_error,r.target_system,r.received_at FROM wms_outbox o LEFT JOIN integration_simulated_result r ON r.message_id=o.message_id WHERE o.aggregate_type='FinishedReceipt' AND o.aggregate_id=? ORDER BY o.occurred_at,o.event_type")
                .param(id)
                .query()
                .listOfRows();
    }
}
