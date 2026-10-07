package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.*;

@SpringBootTest(
        properties = {
            "mdop.auth.operators[0].username=production-reader",
            "mdop.auth.operators[0].password=Production-Test-Only-2026!",
            "mdop.auth.operators[0].authorities=wms:production:read,wms:production:confirm,wms:production:cancel,wms:warehouse:1"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductionTests extends InventoryScenarioSupport {
    long source, storageLocation, issue, target;

    void issuedStock() throws Exception {
        var stock = ready();
        source = stock[0];
        storageLocation = stock[1];
        lineSide();
        issue = demand("50");
        reserve(issue, source, 200);
        confirmIssue(issue, 200);
        target = read("/api/v1/wms/issues/" + issue).get("target_balance_id").asLong();
    }

    Map<String, Object> consumeInput(long id, String amount, String event) {
        return Map.of("eventNo", event, "issueId", id, "workOrderNo", "MO-001", "quantity", amount);
    }

    JsonNode consume(long id, String amount, String event, int status) throws Exception {
        return postAs(
                "/api/local/mes-production/consumptions",
                consumeInput(id, amount, event),
                admin(),
                status);
    }

    Map<String, Object> returnInput(
            long id, String amount, String quality, long location, String event) {
        return Map.of(
                "eventNo",
                event,
                "issueId",
                id,
                "workOrderNo",
                "MO-001",
                "quantity",
                amount,
                "qualityStatus",
                quality,
                "targetLocationId",
                location,
                "reason",
                "余料退回");
    }

    long requestReturn(String amount, String quality, long location) throws Exception {
        return postAs(
                        "/api/local/mes-production/returns",
                        returnInput(issue, amount, quality, location, key()),
                        admin(),
                        201)
                .get("id")
                .asLong();
    }

    void returnConfirm(long id, int status) throws Exception {
        postAs("/api/v1/wms/production/returns/" + id + "/confirm", Map.of(), admin(), status);
    }

    void returnCancel(long id, int status) throws Exception {
        postAs(
                "/api/v1/wms/production/returns/" + id + "/cancel",
                Map.of("reason", "撤销退料"),
                admin(),
                status);
    }

    JsonNode usage() throws Exception {
        return read("/api/v1/wms/production/issues/" + issue);
    }

    @Test
    void consumptionAndQualifiedReturnRetainQuotaLedgerAndFeedback() throws Exception {
        issuedStock();
        String event = key();
        long c = consume(issue, "12.123456", event, 201).get("id").asLong();
        consume(issue, "12.123456", event, 201);
        consume(issue, "13", event, 409);
        long r = requestReturn("10", "QUALIFIED", storageLocation);
        returnConfirm(r, 200);
        returnConfirm(r, 200);
        returnCancel(r, 409);
        assertThat(qty(source)).isEqualByComparingTo("40");
        assertThat(qty(target)).isEqualByComparingTo("27.876544");
        assertThat(stock(target, "available_qty")).isZero();
        assertThat(stock(target, "production_qty")).isEqualByComparingTo("27.876544");
        assertThat(usage().get("consumed_qty").asText()).isEqualTo("12.123456");
        assertThat(usage().get("returned_qty").asText()).isEqualTo("10.000000");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE consumption_id=?",
                                Long.class,
                                c))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT SUM(change_qty) FROM wms_inventory_transaction WHERE production_return_id=?",
                                BigDecimal.class,
                                r))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_outbox WHERE aggregate_type='MaterialIssue' AND aggregate_id=?",
                                Long.class,
                                issue))
                .isEqualTo(3);
        assertThat(read("/api/v1/wms/stock/" + target + "/transactions").toString())
                .contains("CONSUMPTION", "PROD_RETURN_OUT");
        assertThat(
                        read("/api/v1/wms/production/issues/" + issue + "/records?returns=true")
                                .get("items")
                                .get(0)
                                .get("status")
                                .asText())
                .isEqualTo("RETURNED");
    }

    @Test
    void pendingReturnReservesQuotaAndCancelReleasesWithoutPhysicalChange() throws Exception {
        issuedStock();
        long r = requestReturn("40", "QUALIFIED", storageLocation);
        consume(issue, "11", key(), 409);
        consume(issue, "10", key(), 201);
        assertThat(usage().get("consumable_qty").asText()).isEqualTo("0.000000");
        returnCancel(r, 200);
        returnCancel(r, 200);
        returnConfirm(r, 409);
        assertThat(qty(target)).isEqualByComparingTo("40");
        assertThat(usage().get("consumable_qty").asText()).isEqualTo("40.000000");
        consume(issue, "40", key(), 201);
        assertThat(qty(target)).isZero();
        postAs(
                "/api/local/mes-production/returns",
                returnInput(issue, "1", "QUALIFIED", storageLocation, key()),
                admin(),
                409);
    }

    @Test
    void rejectedAndExpiredReturnsStayUnavailableAndPreserveDimensions() throws Exception {
        issuedStock();
        postAs(
                "/api/local/mes-production/returns",
                returnInput(issue, "5", "REJECTED", storageLocation, key()),
                admin(),
                409);
        db.update("UPDATE wms_inventory_balance SET expiry_date='2000-01-01' WHERE id=?", target);
        postAs(
                "/api/local/mes-production/returns",
                returnInput(issue, "5", "QUALIFIED", storageLocation, key()),
                admin(),
                409);
        long r = requestReturn("5", "REJECTED", location);
        returnConfirm(r, 200);
        long balance =
                db.queryForObject(
                        "SELECT target_balance_id FROM wms_production_return WHERE id=?",
                        Long.class,
                        r);
        assertThat(stock(balance, "available_qty")).isZero();
        assertThat(qty(balance)).isEqualByComparingTo("5");
        assertThat(
                        db.queryForObject(
                                "SELECT quality_status FROM wms_inventory_balance WHERE id=?",
                                String.class,
                                balance))
                .isEqualTo("REJECTED");
        assertThat(
                        db.queryForObject(
                                "SELECT batch_no FROM wms_inventory_balance WHERE id=?",
                                String.class,
                                balance))
                .isEqualTo("TRANSFER-BATCH");
    }

    @Test
    void expiredAfterRequestCannotReturnAsQualifiedAndCanCancel() throws Exception {
        issuedStock();
        long r = requestReturn("5", "QUALIFIED", storageLocation);
        db.update("UPDATE wms_inventory_balance SET expiry_date='2000-01-01' WHERE id=?", target);
        returnConfirm(r, 409);
        returnCancel(r, 200);
        assertThat(qty(target)).isEqualByComparingTo("50");
    }

    @Test
    void workOrdersAndIssueAllocationsCannotSpendEachOthersStock() throws Exception {
        issuedStock();
        long second = demand("20");
        reserve(second, source, 200);
        confirmIssue(second, 200);
        assertThat(read("/api/v1/wms/issues/" + second).get("target_balance_id").asLong())
                .isEqualTo(target);
        consume(issue, "51", key(), 409);
        consume(issue, "50", key(), 201);
        consume(issue, "1", key(), 409);
        assertThat(qty(target)).isEqualByComparingTo("20");
        consume(second, "20", key(), 201);
        var bad = new HashMap<>(consumeInput(issue, "1", key()));
        bad.put("workOrderNo", "WRONG");
        postAs("/api/local/mes-production/consumptions", bad, admin(), 409);
    }

    @Test
    void ordinaryMovementAndCountsCannotSpendProductionAllocation() throws Exception {
        issuedStock();
        long other =
                postAs(
                                "/api/master-data/locations",
                                Map.of(
                                        "warehouseId",
                                        targetWarehouse,
                                        "code",
                                        "OTHER",
                                        "name",
                                        "其他位",
                                        "areaType",
                                        "STORAGE"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        postAs("/api/v1/wms/transfers", input(target, other, "1", key()), admin(), 409);
        postAs(
                "/api/v1/wms/counts",
                Map.of("balanceId", target, "idempotencyKey", key()),
                admin(),
                409);
        assertThat(stock(target, "available_qty")).isZero();
    }

    @Test
    void consumptionRacingReturnCannotOverallocate() throws Exception {
        issuedStock();
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> jobs =
                    List.of(
                            () ->
                                    postStatus(
                                            "/api/local/mes-production/consumptions",
                                            consumeInput(issue, "40", key())),
                            () ->
                                    postStatus(
                                            "/api/local/mes-production/returns",
                                            returnInput(
                                                    issue,
                                                    "40",
                                                    "QUALIFIED",
                                                    storageLocation,
                                                    key())));
            var results = pool.invokeAll(jobs);
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(new BigDecimal(usage().get("consumable_qty").asText()))
                .isEqualByComparingTo("10");
    }

    @Test
    void ledgerAndOutboxFailuresRollBackConsumptionAndReturn() throws Exception {
        issuedStock();
        String event = key();
        db.execute(
                "ALTER TABLE wms_outbox ADD CONSTRAINT ck_test_prod_feedback CHECK(event_type<>'ProductionConsumed' OR aggregate_id<>"
                        + issue
                        + ")");
        try {
            consume(issue, "10", event, 503);
        } finally {
            db.execute("ALTER TABLE wms_outbox DROP CHECK ck_test_prod_feedback");
        }
        assertThat(qty(target)).isEqualByComparingTo("50");
        assertThat(usage().get("consumed_qty").asText()).isEqualTo("0.000000");
        consume(issue, "10", event, 201);
        long r = requestReturn("5", "QUALIFIED", storageLocation);
        db.execute(
                "ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_test_prod_return CHECK(transaction_type<>'PROD_RETURN_IN' OR production_return_id<>"
                        + r
                        + ")");
        try {
            returnConfirm(r, 503);
        } finally {
            db.execute("ALTER TABLE wms_inventory_transaction DROP CHECK ck_test_prod_return");
        }
        assertThat(qty(target)).isEqualByComparingTo("40");
        assertThat(qty(source)).isEqualByComparingTo("30");
        assertThat(usage().get("pending_return_qty").asText()).isEqualTo("5.000000");
        returnConfirm(r, 200);
    }

    @Test
    void productionPermissionsAndDualWarehouseScopeApplyToReadAndWrite() throws Exception {
        issuedStock();
        assertThat(users.loadUserByUsername("production-reader").getAuthorities())
                .extracting("authority")
                .contains("wms:production:confirm");
        mvc.perform(
                        get("/api/v1/wms/production/issues/" + issue)
                                .with(operator("outside", "wms:production:read")))
                .andExpect(status().isForbidden());
        postAs(
                "/api/local/mes-production/consumptions",
                consumeInput(issue, "1", key()),
                both("storekeeper", "wms:production:confirm"),
                403);
        long r = requestReturn("5", "QUALIFIED", storageLocation);
        postAs(
                "/api/v1/wms/production/returns/" + r + "/confirm",
                Map.of(),
                operator("outside", "wms:production:confirm"),
                403);
        postAs(
                "/api/v1/wms/production/returns/" + r + "/confirm",
                Map.of(),
                both("reader", "wms:production:read"),
                403);
        postAs(
                "/api/v1/wms/production/returns/" + r + "/confirm",
                Map.of(),
                both("storekeeper", "wms:production:confirm"),
                200);
        consume(issue, "-1", key(), 400);
        consume(issue, "1.1234567", key(), 400);
        mvc.perform(post("/api/v1/wms/production/returns/" + r + "/confirm").with(admin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void returnEventRetriesAreImmutableAndConcurrentConfirmCancelAreExclusive() throws Exception {
        issuedStock();
        String no = key();
        var body = returnInput(issue, "10", "QUALIFIED", storageLocation, no);
        long r = postAs("/api/local/mes-production/returns", body, admin(), 201).get("id").asLong();
        assertThat(
                        postAs("/api/local/mes-production/returns", body, admin(), 201)
                                .get("id")
                                .asLong())
                .isEqualTo(r);
        postAs(
                "/api/local/mes-production/returns",
                returnInput(issue, "11", "QUALIFIED", storageLocation, no),
                admin(),
                409);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> jobs =
                    List.of(
                            () ->
                                    postStatus(
                                            "/api/v1/wms/production/returns/" + r + "/confirm",
                                            Map.of()),
                            () ->
                                    postStatus(
                                            "/api/v1/wms/production/returns/" + r + "/cancel",
                                            Map.of("reason", "撤销退料")));
            var results = pool.invokeAll(jobs);
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(usage().get("pending_return_qty").asText()).isEqualTo("0.000000");
    }

    int postStatus(String path, Object body) throws Exception {
        return mvc.perform(
                        post(path)
                                .with(admin())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(body)))
                .andReturn()
                .getResponse()
                .getStatus();
    }
}
