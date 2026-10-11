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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompletionTests extends MaterialScenarioSupport {
    String url() {
        return MFG + "/orders/" + orderId + "/completions";
    }

    RequestPostProcessor recorder() {
        return completionUser("recorder", "report");
    }

    RequestPostProcessor reviewer() {
        return completionUser("checker", "review");
    }

    RequestPostProcessor completionUser(String name, String p) {
        return user(name)
                .authorities(
                        new SimpleGrantedAuthority("manufacturing:read"),
                        new SimpleGrantedAuthority("manufacturing:completion:" + p),
                        new SimpleGrantedAuthority("wms:warehouse:" + fg));
    }

    Map<String, Object> report(String qty) {
        return new HashMap<>(
                Map.of(
                        "idempotencyKey",
                        key(),
                        "reportNo",
                        key(),
                        "quantity",
                        qty,
                        "batchNo",
                        "B-" + key(),
                        "productionDate",
                        "2026-01-01",
                        "dateCode",
                        "",
                        "reason",
                        "实际完工"));
    }

    JsonNode last(JsonNode d) {
        return d.get("reports").get(0);
    }

    JsonNode action(JsonNode r, String a, RequestPostProcessor user, int status) throws Exception {
        return postAs(url() + "/" + r.get("id").asLong() + "/" + a, command(r), user, status);
    }

    long loc(String area) throws Exception {
        return postAs(
                        "/api/master-data/locations",
                        Map.of(
                                "warehouseId",
                                fg,
                                "code",
                                "L" + key().substring(0, 12),
                                "name",
                                area,
                                "areaType",
                                area),
                        admin(),
                        201)
                .get("id")
                .asLong();
    }

    @Test
    void sixPlusFourRequireIndependentReviewAndQualityBeforeAvailable() throws Exception {
        long inspection = loc("INSPECTION"), store = loc("STORAGE");
        long[] receipts = new long[2];
        int index = 0;
        for (String qty : List.of("6", "4")) {
            var in = report(qty);
            var r = last(postAs(url(), in, recorder(), 200));
            assertThat(r.get("production_date").asText()).isEqualTo("2026-01-01");
            assertThat(last(postAs(url(), in, recorder(), 200)).get("id")).isEqualTo(r.get("id"));
            assertThat(
                            db.queryForObject(
                                    "SELECT COUNT(*) FROM wms_finished_receipt WHERE production_order_id=?",
                                    Long.class,
                                    orderId))
                    .isEqualTo((long) index);
            action(r, "approve", completionUser("recorder", "review"), 403);
            var cmd = command(r);
            String approve = url() + "/" + r.get("id").asLong() + "/approve";
            var d = postAs(approve, cmd, reviewer(), 200);
            postAs(approve, cmd, reviewer(), 200);
            long id = last(d).get("receipt").get("id").asLong();
            var displayed = read("/api/v1/wms/finished-receipts/" + id);
            assertThat(displayed.get("completion_report_no").asText())
                    .isEqualTo(in.get("reportNo"));
            assertThat(displayed.get("demand_no").asText()).isNotEqualTo(in.get("reportNo"));
            receipts[index++] = id;
            postAs(
                    "/api/v1/wms/finished-receipts/" + id + "/putaway",
                    Map.of("locationId", store),
                    admin(),
                    409);
            var f =
                    postAs(
                            "/api/v1/wms/finished-receipts/" + id + "/receive",
                            Map.of("locationId", inspection),
                            admin(),
                            200);
            assertThat(
                            db.queryForObject(
                                    "SELECT available_qty FROM wms_inventory_balance WHERE id=?",
                                    BigDecimal.class,
                                    f.get("received_balance_id").asLong()))
                    .isZero();
            postAs(
                    "/api/local/finished-receipts/" + id + "/quality",
                    Map.of("eventNo", key(), "result", "QUALIFIED", "reason", "模拟质量判定"),
                    admin(),
                    200);
            f =
                    postAs(
                            "/api/v1/wms/finished-receipts/" + id + "/putaway",
                            Map.of("locationId", store),
                            admin(),
                            200);
            postAs(
                    "/api/v1/wms/finished-receipts/" + id + "/putaway",
                    Map.of("locationId", store),
                    admin(),
                    200);
            assertThat(
                            db.queryForObject(
                                    "SELECT available_qty FROM wms_inventory_balance WHERE id=?",
                                    BigDecimal.class,
                                    f.get("stored_balance_id").asLong()))
                    .isEqualByComparingTo(qty);
            assertThat(
                            db.queryForObject(
                                    "SELECT COUNT(*) FROM wms_inventory_transaction WHERE finished_receipt_id=?",
                                    Long.class,
                                    id))
                    .isEqualTo(5);
            assertThat(
                            db.queryForList(
                                    "SELECT event_type FROM wms_outbox WHERE aggregate_type='FinishedReceipt' AND aggregate_id=?",
                                    String.class,
                                    id))
                    .containsExactly("FinishedInspectionRequested");
        }
        postAs(url(), report("1"), recorder(), 409);
        productionAction("cancel", planner(), 409);
        assertThat(
                        db.queryForObject(
                                "SELECT SUM(quantity) FROM mfg_completion WHERE order_id=? AND status='APPROVED'",
                                BigDecimal.class,
                                orderId))
                .isEqualByComparingTo("10");
        for (var check :
                db.queryForList(
                        java.nio.file.Files.readString(
                                java.nio.file.Path.of("../../scripts/audit-inventory.sql"))))
            assertThat(((Number) check.values().toArray()[1]).longValue())
                    .as(check.toString())
                    .isZero();
    }

    @Test
    void concurrentPendingReportsReserveQuotaAndCancelRejectReleaseIt() throws Exception {
        var gate = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> jobs = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                jobs.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return mvc.perform(
                                                    post(url())
                                                            .with(recorder())
                                                            .with(csrf())
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(
                                                                    json.writeValueAsString(
                                                                            report("6"))))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            gate.countDown();
            for (var job : jobs) statuses.add(job.get(30, TimeUnit.SECONDS));
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        var r =
                last(
                        json.readTree(
                                mvc.perform(get(url()).with(recorder()))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString()));
        productionAction("cancel", planner(), 409);
        action(r, "cancel", completionUser("other", "report"), 403);
        action(r, "reject", reviewer(), 200);
        r = last(postAs(url(), report("10"), recorder(), 200));
        action(r, "cancel", recorder(), 200);
        productionAction("cancel", planner(), 200);
        postAs(url(), report("1"), recorder(), 409);
    }

    @Test
    void changedPayloadDatesRolesWarehouseAndCsrfFailWithoutStock() throws Exception {
        var in = report("6");
        postAs(url(), in, recorder(), 200);
        in.put("quantity", "7");
        postAs(url(), in, recorder(), 409);
        postAs(url(), report("1"), admin(), 403);
        postAs(url(), report("1"), planner(), 403);
        postAs(
                url(),
                report("1"),
                user("other")
                        .authorities(new SimpleGrantedAuthority("manufacturing:completion:report")),
                403);
        mvc.perform(
                        post(url())
                                .with(recorder())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(report("1"))))
                .andExpect(status().isForbidden());
        in = report("1");
        in.remove("productionDate");
        postAs(url(), in, recorder(), 400);
        in = report("1");
        in.put("productionDate", "2999-01-01");
        postAs(url(), in, recorder(), 409);
        in = report("1");
        in.put("batchNo", " ");
        postAs(url(), in, recorder(), 400);
        in = report("1");
        in.put("expiryDate", "2025-12-31");
        postAs(url(), in, recorder(), 409);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_finished_receipt WHERE production_order_id=?",
                                Long.class,
                                orderId))
                .isZero();
        var legacy =
                new HashMap<String, Object>(
                        Map.of(
                                "demandNo",
                                key(),
                                "workOrderNo",
                                production.get("order_no").asText(),
                                "warehouseId",
                                fg,
                                "materialId",
                                product,
                                "quantity",
                                "1",
                                "batchNo",
                                "X",
                                "productionDate",
                                "2026-01-01"));
        postAs("/api/local/finished-receipts", legacy, admin(), 409);
    }

    @Test
    void rejectedQualityAndExpiredProductStayIsolatedAndCannotBeCancelled() throws Exception {
        long inspection = loc("INSPECTION"), store = loc("STORAGE");
        for (boolean expired : List.of(false, true)) {
            var in = report("4");
            if (expired) in.put("expiryDate", "2026-01-02");
            var r = last(postAs(url(), in, recorder(), 200));
            r = last(action(r, "approve", reviewer(), 200));
            long id = r.get("receipt").get("id").asLong();
            postAs(
                    "/api/v1/wms/finished-receipts/" + id + "/receive",
                    Map.of("locationId", inspection),
                    admin(),
                    200);
            if (expired)
                postAs(
                        "/api/local/finished-receipts/" + id + "/quality",
                        Map.of("eventNo", key(), "result", "QUALIFIED", "reason", "过期"),
                        admin(),
                        409);
            postAs(
                    "/api/local/finished-receipts/" + id + "/quality",
                    Map.of("eventNo", key(), "result", "REJECTED", "reason", "不合格隔离"),
                    admin(),
                    200);
            postAs(
                    "/api/v1/wms/finished-receipts/" + id + "/putaway",
                    Map.of("locationId", store),
                    admin(),
                    409);
            action(r, "cancel", recorder(), 409);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT SUM(available_qty) FROM wms_inventory_balance WHERE warehouse_id=?",
                                BigDecimal.class,
                                fg))
                .isZero();
    }
}
