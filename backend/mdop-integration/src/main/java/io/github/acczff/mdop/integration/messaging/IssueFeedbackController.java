package io.github.acczff.mdop.integration.messaging;

import io.github.acczff.mdop.wms.inventory.IssueService;
import jakarta.validation.constraints.Positive;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/integration/material-issues")
@PreAuthorize(
        "hasRole('ADMIN') or hasAuthority('wms:issue:read') or hasAuthority('wms:production:read')")
public class IssueFeedbackController {
    private final IssueService issues;
    private final JdbcClient db;

    public IssueFeedbackController(IssueService issues, JdbcClient db) {
        this.issues = issues;
        this.db = db;
    }

    @GetMapping("/{id}/feedback")
    public List<Map<String, Object>> feedback(@PathVariable @Positive long id) {
        issues.detail(id);
        return db.sql(
                        "SELECT o.message_id,o.event_type,o.business_key,o.status,o.attempts,o.last_error,r.received_at FROM wms_outbox o LEFT JOIN integration_simulated_result r ON r.message_id=o.message_id AND r.target_system='MES' WHERE o.aggregate_type='MaterialIssue' AND o.aggregate_id=? ORDER BY o.occurred_at,o.message_id")
                .param(id)
                .query()
                .listOfRows();
    }
}
