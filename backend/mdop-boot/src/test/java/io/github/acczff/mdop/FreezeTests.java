package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FreezeTests extends InventoryScenarioSupport {
    static final String URL = "/api/v1/wms/freezes";

    Map<String, Object> freezing(long balance) {
        return Map.of("balanceId", balance, "idempotencyKey", key(), "reason", "批次异常调查");
    }

    Map<String, Object> releasing() {
        return Map.of("idempotencyKey", key(), "reason", "已复核批次符合使用条件");
    }

    long freeze(long balance) throws Exception {
        return postAs(URL, freezing(balance), admin(), 201).get("id").asLong();
    }

    JsonNode release(long id) throws Exception {
        return postAs(
                URL + "/" + id + "/release",
                releasing(),
                operator("reviewer", "wms:freeze:review"),
                200);
    }

    void audit() throws Exception {
        for (var check :
                db.queryForList(
                        java.nio.file.Files.readString(
                                java.nio.file.Path.of("../../scripts/audit-inventory.sql"))))
            assertThat(((Number) check.get("mismatch_count")).longValue())
                    .as(check.get("check_name").toString())
                    .isZero();
    }

    @Test
    void preservesPhysicalStockReservationsAndSeparateReviewer() throws Exception {
        long b = ready()[0];
        lineSide();
        long issue = demand("12.123456");
        reserve(issue, b, 200);
        long f = freeze(b);
        assertThat(qty(b)).isEqualByComparingTo("80");
        assertThat(stock(b, "available_qty")).isZero();
        assertThat(stock(b, "reserved_qty")).isEqualByComparingTo("12.123456");
        confirmIssue(issue, 409);
        reserve(demand("1"), b, 409);
        postAs(URL + "/" + f + "/release", releasing(), admin(), 409);
        audit();
        var r = release(f);
        assertThat(r.get("released_available").asText()).isEqualTo("67.876544");
        confirmIssue(issue, 200);
        audit();
        long lineStock = read("/api/v1/wms/issues/" + issue).get("target_balance_id").asLong();
        postAs(URL, freezing(lineStock), admin(), 409);
    }

    @Test
    void cancellationDuringFreezeDoesNotRestoreAvailability() throws Exception {
        long b = ready()[0];
        lineSide();
        long issue = demand("20");
        reserve(issue, b, 200);
        long f = freeze(b);
        cancelIssue(issue, 200);
        assertThat(stock(b, "available_qty")).isZero();
        assertThat(stock(b, "reserved_qty")).isZero();
        audit();
        release(f);
        assertThat(stock(b, "available_qty")).isEqualByComparingTo("80");
        audit();
    }

    @Test
    void inboundMergeRemainsFrozenAndUnfreezingUsesCurrentQuantity() throws Exception {
        var s = ready();
        long target =
                postAs("/api/v1/wms/transfers", input(s[0], s[2], "5.123456", key()), admin(), 201)
                        .get("target_balance_id")
                        .asLong();
        long f = freeze(target);
        postAs("/api/v1/wms/transfers", input(s[0], s[2], "2", key()), admin(), 201);
        assertThat(qty(target)).isEqualByComparingTo("7.123456");
        assertThat(stock(target, "available_qty")).isZero();
        postAs("/api/v1/wms/transfers", input(target, s[1], "1", key()), admin(), 409);
        audit();
        release(f);
        assertThat(stock(target, "available_qty")).isEqualByComparingTo("7.123456");
        audit();
    }

    @Test
    void idempotencyCannotReleaseNewFreezeAndCountsAreInvalidated() throws Exception {
        long b = ready()[0];
        long count = count(b);
        submitCount(count, "80");
        var body = freezing(b);
        long f = postAs(URL, body, admin(), 201).get("id").asLong();
        assertThat(postAs(URL, body, admin(), 201).get("id").asLong()).isEqualTo(f);
        var altered = new HashMap<>(body);
        altered.put("reason", "另一原因");
        postAs(URL, altered, admin(), 409);
        postAs(URL, body, operator("other", "wms:freeze:create"), 409);
        var unfreeze = releasing();
        postAs(
                URL + "/" + f + "/release",
                unfreeze,
                operator("reviewer", "wms:freeze:review"),
                200);
        approve(count, 409);
        long next = freeze(b);
        postAs(
                URL + "/" + f + "/release",
                unfreeze,
                operator("reviewer", "wms:freeze:review"),
                200);
        postAs(URL + "/" + f + "/release", unfreeze, operator("other", "wms:freeze:review"), 409);
        postAs(URL, body, admin(), 201);
        assertThat(stock(b, "active_freeze_id")).isEqualByComparingTo(String.valueOf(next));
        assertThat(stock(b, "available_qty")).isZero();
        audit();
    }

    @Test
    void pendingAndRejectedStayUnavailableAfterReleaseAndQualityCannotMoveFrozenStock()
            throws Exception {
        long receipt = receive(100);
        long b =
                db.queryForObject(
                        "SELECT id FROM wms_inventory_balance WHERE warehouse_id=?",
                        Long.class,
                        warehouse);
        long item =
                db.queryForObject(
                        "SELECT id FROM wms_receipt_item WHERE receipt_id=?", Long.class, receipt);
        var quality =
                Map.of(
                        "idempotencyKey",
                        key(),
                        "receiptId",
                        receipt,
                        "version",
                        1,
                        "referenceNo",
                        key(),
                        "reason",
                        "质检",
                        "items",
                        List.of(
                                Map.of(
                                        "receiptItemId",
                                        item,
                                        "qualifiedQty",
                                        80,
                                        "rejectedQty",
                                        20)));
        long f = freeze(b);
        postAs("/api/local/qms-results", quality, admin(), 409);
        assertThat(qty(b)).isEqualByComparingTo("100");
        release(f);
        assertThat(stock(b, "available_qty")).isZero();
        postAs("/api/local/qms-results", quality, admin(), 200);
        long rejected =
                db.queryForObject(
                        "SELECT id FROM wms_inventory_balance WHERE warehouse_id=? AND quality_status='REJECTED'",
                        Long.class,
                        warehouse);
        release(freeze(rejected));
        assertThat(stock(rejected, "available_qty")).isZero();
        postAs(URL, freezing(b), admin(), 409);
        audit();
    }

    @Test
    void permissionsScopeCsrfAndReasonAreEnforced() throws Exception {
        long b = ready()[0];
        var body = freezing(b);
        postAs(URL, body, operator("reader", "wms:freeze:read"), 403);
        postAs(
                URL,
                body,
                user("outsider")
                        .authorities(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("wms:freeze:create")),
                403);
        mvc.perform(
                        post(URL)
                                .with(admin())
                                .contentType("application/json")
                                .content(json.writeValueAsString(body)))
                .andExpect(status().isForbidden());
        postAs(URL, Map.of("balanceId", b, "idempotencyKey", key(), "reason", "  "), admin(), 400);
        long f = freeze(b);
        mvc.perform(
                        get(URL + "/" + f)
                                .with(
                                        user("outsider")
                                                .authorities(
                                                        new org.springframework.security.core
                                                                .authority.SimpleGrantedAuthority(
                                                                "wms:freeze:read"))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get(URL).param("warehouseId", String.valueOf(warehouse))
                                .with(operator("reader", "wms:freeze:read")))
                .andExpect(status().isOk());
        postAs(URL + "/" + f + "/release", releasing(), operator("reader", "wms:freeze:read"), 403);
    }

    @Test
    void auditFailureRollsBackStockAndAllowsOriginalRequestRetry() throws Exception {
        long b = ready()[0];
        long f = freeze(b);
        var body = releasing();
        db.execute(
                "ALTER TABLE wms_stock_freeze ADD CONSTRAINT ck_freeze_test CHECK(balance_id<>"
                        + b
                        + " OR status='FROZEN')");
        try {
            postAs(
                    URL + "/" + f + "/release",
                    body,
                    operator("reviewer", "wms:freeze:review"),
                    503);
        } finally {
            db.execute("ALTER TABLE wms_stock_freeze DROP CHECK ck_freeze_test");
        }
        assertThat(stock(b, "available_qty")).isZero();
        assertThat(read(URL + "/" + f).get("status").asText()).isEqualTo("FROZEN");
        postAs(URL + "/" + f + "/release", body, operator("reviewer", "wms:freeze:review"), 200);
        audit();
    }

    @Test
    void concurrentDuplicateFreezeAndReleaseApplyOnce() throws Exception {
        long b = ready()[0];
        var body = freezing(b);
        long before =
                db.queryForObject(
                        "SELECT COUNT(*) FROM wms_inventory_transaction WHERE balance_id=?",
                        Long.class,
                        b);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var jobs = new ArrayList<Future<JsonNode>>();
            for (int i = 0; i < 2; i++)
                jobs.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return postAs(URL, body, admin(), 201);
                                }));
            gate.countDown();
            long f = jobs.get(0).get(20, TimeUnit.SECONDS).get("id").asLong();
            assertThat(jobs.get(1).get(20, TimeUnit.SECONDS).get("id").asLong()).isEqualTo(f);
            var releaseBody = releasing();
            jobs.clear();
            for (int i = 0; i < 2; i++)
                jobs.add(
                        pool.submit(
                                () ->
                                        postAs(
                                                URL + "/" + f + "/release",
                                                releaseBody,
                                                operator("reviewer", "wms:freeze:review"),
                                                200)));
            for (var job : jobs)
                assertThat(job.get(20, TimeUnit.SECONDS).get("status").asText())
                        .isEqualTo("RELEASED");
        }
        assertThat(stock(b, "reservation_version")).isEqualByComparingTo("2");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE balance_id=?",
                                Long.class,
                                b))
                .isEqualTo(before);
        audit();
    }
}
