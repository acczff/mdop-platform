package io.github.acczff.mdop.integration.messaging;

import io.github.acczff.mdop.wms.inventory.SalesService;
import jakarta.validation.constraints.Positive;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/integration/sales-orders")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:sales:read')")
public class SalesFeedbackController {
    private final SalesService service;
    private final JdbcClient db;

    public SalesFeedbackController(SalesService service, JdbcClient db) {
        this.service = service;
        this.db = db;
    }

    @GetMapping("/{id}/feedback")
    public List<Map<String, Object>> feedback(@PathVariable @Positive long id) {
        service.detail(id);
        return db.sql(
                        "SELECT o.message_id,o.event_type,o.status,o.last_error,r.target_system,r.received_at FROM wms_outbox o LEFT JOIN integration_simulated_result r ON r.message_id=o.message_id WHERE o.aggregate_type='SalesOrder' AND o.aggregate_id=? ORDER BY o.occurred_at,o.event_type")
                .param(id)
                .query()
                .listOfRows();
    }
}
