package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionReversalTests extends InventoryScenarioSupport {
    long source, store, issue, target;

    void issued() throws Exception {
        var s = ready();
        source = s[0];
        store = s[1];
        lineSide();
        issue = demand("30");
        reserve(issue, source, 200);
        confirmIssue(issue, 200);
        target = read("/api/v1/wms/issues/" + issue).get("target_balance_id").asLong();
    }

    long consume() throws Exception {
        return postAs(
                        "/api/local/mes-production/consumptions",
                        Map.of(
                                "eventNo",
                                key(),
                                "issueId",
                                issue,
                                "workOrderNo",
                                "MO-001",
                                "quantity",
                                "10"),
                        admin(),
                        201)
                .get("id")
                .asLong();
    }

    long returned(String quality) throws Exception {
        long r =
                postAs(
                                "/api/local/mes-production/returns",
                                Map.of(
                                        "eventNo",
                                        key(),
                                        "issueId",
                                        issue,
                                        "workOrderNo",
                                        "MO-001",
                                        "quantity",
                                        "10",
                                        "qualityStatus",
                                        quality,
                                        "targetLocationId",
                                        quality.equals("QUALIFIED") ? store : location,
                                        "reason",
                                        "余料退回"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        postAs("/api/v1/wms/production/returns/" + r + "/confirm", Map.of(), admin(), 200);
        return r;
    }

    Map<String, Object> input(String kind, long sourceId, String key) {
        return Map.of(
                "kind",
                kind,
                "sourceId",
                sourceId,
                "requestKey",
                key,
                "externalReference",
                "MES-CORRECTION",
                "reason",
                "原单记账有误，已核对实物");
    }

    long request(String kind, long sourceId) throws Exception {
        return postAs(
                        "/api/v1/wms/production-reversals",
                        input(kind, sourceId, key()),
                        admin(),
                        201)
                .get("id")
                .asLong();
    }

    void decide(long id, String action, int status) throws Exception {
        postAs(
                "/api/v1/wms/production-reversals/" + id + "/" + action,
                Map.of("reason", "已核对纠错依据"),
                both("reviewer", "wms:production:review"),
                status);
    }

    @Test
    void consumptionReverseIsAuditedIdempotentAndCannotSelfApprove() throws Exception {
        issued();
        long c = consume(), r = request("CONSUMPTION", c);
        postAs(
                "/api/v1/wms/production-reversals/" + r + "/approve",
                Map.of("reason", "自审"),
                admin(),
                403);
        decide(r, "approve", 200);
        decide(r, "approve", 200);
        assertThat(qty(target)).isEqualByComparingTo("30");
        assertThat(stock(target, "production_qty")).isEqualByComparingTo("30");
        assertThat(read("/api/v1/wms/production/issues/" + issue).get("consumed_qty").asText())
                .isEqualTo("0.000000");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE production_reversal_id=?",
                                Long.class,
                                r))
                .isEqualTo(1);
        postAs("/api/v1/wms/production-reversals", input("CONSUMPTION", c, key()), admin(), 409);
        assertThat(
                        db.queryForObject(
                                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'$.originalBusinessKey')) FROM wms_outbox WHERE event_type='ProductionConsumptionReversed' AND aggregate_id=?",
                                String.class,
                                issue))
                .isEqualTo(String.valueOf(c));
    }

    @Test
    void qualifiedAndRejectedReturnReverseRestoreOriginalAllocation() throws Exception {
        for (String quality : List.of("QUALIFIED", "REJECTED")) {
            if (quality.equals("REJECTED")) setup();
            issued();
            long p = returned(quality), r = request("RETURN", p);
            decide(r, "approve", 200);
            assertThat(qty(target)).isEqualByComparingTo("30");
            assertThat(read("/api/v1/wms/production/issues/" + issue).get("returned_qty").asText())
                    .isEqualTo("0.000000");
            assertThat(
                            db.queryForObject(
                                    "SELECT SUM(change_qty) FROM wms_inventory_transaction WHERE production_reversal_id=?",
                                    java.math.BigDecimal.class,
                                    r))
                    .isZero();
        }
    }

    @Test
    void downstreamMovementPreventsReturnReverse() throws Exception {
        issued();
        long p = returned("QUALIFIED"), r = request("RETURN", p);
        long newDemand = demand("1");
        reserve(newDemand, source, 200);
        confirmIssue(newDemand, 200);
        decide(r, "approve", 409);
        assertThat(
                        read("/api/v1/wms/production-reversals?issueId=" + issue)
                                .get("items")
                                .get(0)
                                .get("status")
                                .asText())
                .isEqualTo("PENDING");
    }

    @Test
    void activeReservationPreventsReturnReverse() throws Exception {
        issued();
        long p = returned("QUALIFIED"), r = request("RETURN", p);
        reserve(demand("1"), source, 200);
        decide(r, "approve", 409);
    }

    @Test
    void rejectAndCancelKeepInventoryAndAllowNewApplication() throws Exception {
        issued();
        long c = consume(), r = request("CONSUMPTION", c);
        decide(r, "reject", 200);
        long second = request("CONSUMPTION", c);
        postAs(
                "/api/v1/wms/production-reversals/" + second + "/cancel",
                Map.of("reason", "撤销错误申请"),
                admin(),
                200);
        assertThat(qty(target)).isEqualByComparingTo("20");
        request("CONSUMPTION", c);
    }

    @Test
    void feedbackFailureRollsBackWholeReversal() throws Exception {
        issued();
        long c = consume(), r = request("CONSUMPTION", c);
        db.execute(
                "ALTER TABLE wms_outbox ADD CONSTRAINT test_reversal_failure CHECK(event_type<>'ProductionConsumptionReversed' OR aggregate_id<>"
                        + issue
                        + ")");
        try {
            decide(r, "approve", 503);
        } finally {
            db.execute("ALTER TABLE wms_outbox DROP CHECK test_reversal_failure");
        }
        assertThat(qty(target)).isEqualByComparingTo("20");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE production_reversal_id=?",
                                Long.class,
                                r))
                .isZero();
        decide(r, "approve", 200);
    }

    @Test
    void requestConflictsAndWarehouseScopeAreEnforced() throws Exception {
        issued();
        long c = consume();
        var body = input("CONSUMPTION", c, key());
        long r = postAs("/api/v1/wms/production-reversals", body, admin(), 201).get("id").asLong();
        assertThat(
                        postAs("/api/v1/wms/production-reversals", body, admin(), 201)
                                .get("id")
                                .asLong())
                .isEqualTo(r);
        var changed = new HashMap<>(body);
        changed.put("reason", "不同原因");
        postAs("/api/v1/wms/production-reversals", changed, admin(), 409);
        postAs(
                "/api/v1/wms/production-reversals/" + r + "/approve",
                Map.of("reason", "越权"),
                operator("outside", "wms:production:review"),
                403);
    }
}
