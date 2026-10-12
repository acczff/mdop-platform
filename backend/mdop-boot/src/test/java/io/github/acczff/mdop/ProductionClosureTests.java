package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
class ProductionClosureTests extends MaterialScenarioSupport {
    long source, store, issueId, consumptionId;
    JsonNode plan;
    Map<String, Object> consumedRequest;

    String endpoint() {
        return MFG + "/orders/" + orderId + "/closure";
    }

    RequestPostProcessor scoped(String name, String permission) {
        return user(name)
                .authorities(
                        new SimpleGrantedAuthority("manufacturing:read"),
                        new SimpleGrantedAuthority("manufacturing:" + permission),
                        new SimpleGrantedAuthority("wms:warehouse:" + fg),
                        new SimpleGrantedAuthority("wms:warehouse:" + warehouse),
                        new SimpleGrantedAuthority("wms:warehouse:" + targetWarehouse));
    }

    @BeforeEach
    void execution() throws Exception {
        lineSide();
        plan = postAs(path() + "/calculate", calc(0), scoped("planner", "write"), 200);
    }

    JsonNode check() throws Exception {
        return json.readTree(
                mvc.perform(get(endpoint()).with(scoped("closer", "review")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    Map<String, Object> closeInput(JsonNode d) {
        return new HashMap<>(
                Map.of(
                        "idempotencyKey",
                        key(),
                        "version",
                        d.get("orderVersion").asLong(),
                        "factHash",
                        d.get("factHash").asText(),
                        "reason",
                        "材料成品及供给均已核对",
                        "varianceReason",
                        "节约材料，剩余实物已退回"));
    }

    void material(boolean confirmReturn) throws Exception {
        source = putaway(receive(20), 20);
        store =
                db.queryForObject(
                        "SELECT location_id FROM wms_inventory_balance WHERE id=?",
                        Long.class,
                        source);

        var d =
                postAs(
                        path() + "/issues",
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "planVersion",
                                1,
                                "lineId",
                                plan.get("lines").get(0).get("id").asLong(),
                                "targetWarehouseId",
                                targetWarehouse,
                                "quantity",
                                "20",
                                "reason",
                                "按固定BOM领料"),
                        scoped("planner", "write"),
                        200);
        issueId = d.get("issues").get(0).get("id").asLong();
        reserve(issueId, source, 200);
        confirmIssue(issueId, 200);
        consumedRequest =
                Map.of(
                        "consumption",
                        Map.of(
                                "eventNo",
                                key(),
                                "issueId",
                                issueId,
                                "workOrderNo",
                                production.get("order_no").asText(),
                                "quantity",
                                "19"),
                        "reason",
                        "实际消耗");
        consumptionId =
                postAs(
                                "/api/v1/wms/production/consumptions",
                                consumedRequest,
                                both("recorder", "wms:production:report"),
                                200)
                        .get("id")
                        .asLong();
        var r =
                postAs(
                        "/api/v1/wms/production/returns",
                        Map.of(
                                "eventNo",
                                key(),
                                "issueId",
                                issueId,
                                "workOrderNo",
                                production.get("order_no").asText(),
                                "quantity",
                                "1",
                                "qualityStatus",
                                "QUALIFIED",
                                "targetLocationId",
                                store,
                                "reason",
                                "节约退料"),
                        both("recorder", "wms:production:report"),
                        200);
        if (confirmReturn)
            postAs(
                    "/api/v1/wms/production/returns/" + r.get("id").asLong() + "/confirm",
                    Map.of(),
                    admin(),
                    200);
    }

    long loc(String type) throws Exception {
        return postAs(
                        "/api/master-data/locations",
                        Map.of(
                                "warehouseId",
                                fg,
                                "code",
                                "L" + key().substring(0, 10),
                                "name",
                                type,
                                "areaType",
                                type),
                        admin(),
                        201)
                .get("id")
                .asLong();
    }

    long complete(String qty, boolean putaway) throws Exception {
        String base = MFG + "/orders/" + orderId + "/completions";
        var r =
                postAs(
                                base,
                                Map.of(
                                        "idempotencyKey",
                                        key(),
                                        "reportNo",
                                        key(),
                                        "quantity",
                                        qty,
                                        "batchNo",
                                        key(),
                                        "productionDate",
                                        "2026-01-01",
                                        "reason",
                                        "实际产量"),
                                scoped("recorder", "completion:report"),
                                200)
                        .get("reports")
                        .get(0);
        var approved =
                postAs(
                        base + "/" + r.get("id").asLong() + "/approve",
                        command(r),
                        scoped("checker", "completion:review"),
                        200);
        long receipt = approved.get("reports").get(0).get("receipt").get("id").asLong();
        String url = "/api/v1/wms/finished-receipts/" + receipt;
        postAs(url + "/receive", Map.of("locationId", loc("INSPECTION")), admin(), 200);
        if (putaway) {
            postAs(
                    "/api/local/finished-receipts/" + receipt + "/quality",
                    Map.of("eventNo", key(), "result", "QUALIFIED", "reason", "模拟质量判定"),
                    admin(),
                    200);
            postAs(url + "/putaway", Map.of("locationId", loc("STORAGE")), admin(), 200);
        }
        return receipt;
    }

    void readyToClose() throws Exception {
        material(true);
        complete("6", true);
        complete("4", true);
    }

    @Test
    void completesOnlyAfterRealFactsAndPreservesIdempotencyAndHistory() throws Exception {
        var initial = check();
        assertThat(initial.get("canClose").asBoolean()).isFalse();
        material(true);
        complete("6", true);
        complete("4", true);
        var d = check();
        assertThat(d.get("canClose").asBoolean()).isTrue();
        number(d.get("facts").get("materials").get(0), "variance", "-1");
        var in = closeInput(d);
        var noReason = new HashMap<>(in);
        noReason.put("varianceReason", "");
        postAs(endpoint(), noReason, scoped("closer", "review"), 409);
        postAs(endpoint(), in, scoped("planner", "review"), 403);
        postAs(endpoint(), in, scoped("recorder", "review"), 403);
        postAs(endpoint(), closeInput(initial), scoped("closer", "review"), 409);
        var closed = postAs(endpoint(), in, scoped("closer", "review"), 200);
        assertThat(closed.get("orderStatus").asText()).isEqualTo("COMPLETED");
        assertThat(closed.get("closureMatches").asBoolean()).isTrue();
        postAs(endpoint(), in, scoped("closer", "review"), 200);
        in.put("reason", "篡改请求");
        postAs(endpoint(), in, scoped("closer", "review"), 409);
        postAs(path() + "/calculate", calc(1), scoped("planner", "write"), 409);
        postAs(
                "/api/v1/wms/production/consumptions",
                consumedRequest,
                both("recorder", "wms:production:report"),
                200);
        postAs(
                "/api/v1/wms/production-reversals",
                Map.of(
                        "requestKey",
                        key(),
                        "kind",
                        "CONSUMPTION",
                        "sourceId",
                        consumptionId,
                        "externalReference",
                        "纠错",
                        "reason",
                        "测试结束保护"),
                both("recorder", "wms:production:reverse"),
                409);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM mfg_order_audit WHERE order_id=? AND action='CLOSE'",
                                Long.class,
                                orderId))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT active_demand_id FROM mfg_order WHERE id=?",
                                Long.class,
                                orderId))
                .isNotNull();
        assertThat(stock(source, "on_hand_qty")).isEqualByComparingTo("1");
    }

    @Test
    void blocksLineRemainderPendingReturnAndUnqualifiedFinishedGoods() throws Exception {
        material(false);
        long receipt = complete("10", false);
        var d = check();
        String blockers = d.get("facts").get("blockers").toString();
        assertThat(blockers).contains("线边余料", "待确认退料", "尚未合格上架");
        postAs(endpoint(), closeInput(d), scoped("closer", "review"), 409);
        postAs(
                "/api/local/finished-receipts/" + receipt + "/quality",
                Map.of("eventNo", key(), "result", "REJECTED", "reason", "模拟不合格"),
                admin(),
                200);
        assertThat(check().get("canClose").asBoolean()).isFalse();
    }

    @Test
    void pendingProcurementMustBeCancelledOrClosedAndOldFactsAreRejected() throws Exception {
        var p = postAs(path() + "/confirm", confirm(plan), scoped("planner", "write"), 200);
        long request = requestId(p);
        readyToClose();
        var before = check();
        assertThat(before.get("facts").get("blockers").toString()).contains("采购需求");
        var doc = getOrder(request);
        action(doc, "cancel", "buyer", "purchasing:write", 200);
        assertThat(check().get("canClose").asBoolean()).isTrue();
        postAs(endpoint(), closeInput(before), scoped("closer", "review"), 409);
        postAs(endpoint(), closeInput(check()), scoped("closer", "review"), 200);
    }

    @Test
    void competingCloseCommandsWriteOnlyOneImmutableSnapshot() throws Exception {
        readyToClose();
        var d = check();
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                var in = closeInput(d);
                tasks.add(
                        pool.submit(
                                () -> {
                                    latch.await();
                                    return mvc.perform(
                                                    post(endpoint())
                                                            .with(scoped("closer", "review"))
                                                            .with(csrf())
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(json.writeValueAsString(in)))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            }
            latch.countDown();
            assertThat(
                            List.of(
                                    tasks.get(0).get(30, TimeUnit.SECONDS),
                                    tasks.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM mfg_order_closure WHERE order_id=?",
                                Long.class,
                                orderId))
                .isEqualTo(1);
    }

    @Test
    void pendingReversalMustBeResolvedAndRacingNewReversalCannotChangeClosedFacts()
            throws Exception {
        readyToClose();
        var input =
                Map.of(
                        "requestKey",
                        key(),
                        "kind",
                        "CONSUMPTION",
                        "sourceId",
                        consumptionId,
                        "externalReference",
                        "现场核对",
                        "reason",
                        "疑似误报");
        var reverse =
                postAs(
                        "/api/v1/wms/production-reversals",
                        input,
                        both("recorder", "wms:production:reverse"),
                        201);
        assertThat(check().get("facts").get("blockers").toString()).contains("待审冲正");
        postAs(endpoint(), closeInput(check()), scoped("closer", "review"), 409);
        postAs(
                "/api/v1/wms/production-reversals/" + reverse.get("id").asLong() + "/cancel",
                Map.of("reason", "原消耗正确"),
                both("recorder", "wms:production:reverse"),
                200);
        var close = closeInput(check());
        var next = new HashMap<>(input);
        next.put("requestKey", key());
        var latch = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                latch.await();
                                return mvc.perform(
                                                post(endpoint())
                                                        .with(scoped("closer", "review"))
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(json.writeValueAsString(close)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var b =
                    pool.submit(
                            () -> {
                                latch.await();
                                return mvc.perform(
                                                post("/api/v1/wms/production-reversals")
                                                        .with(
                                                                both(
                                                                        "recorder",
                                                                        "wms:production:reverse"))
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(json.writeValueAsString(next)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            latch.countDown();
            var codes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(codes).isIn(List.of(200, 409), List.of(409, 201));
        }
    }

    @Test
    void mismatchedLedgerBlocksEvenIfDocumentQuantitiesAgree() throws Exception {
        readyToClose();
        db.update(
                "UPDATE wms_inventory_transaction SET change_qty=-18,after_qty=before_qty-18 WHERE consumption_id=?",
                consumptionId);
        assertThat(check().get("facts").get("blockers").toString()).contains("库存流水");
        postAs(endpoint(), closeInput(check()), scoped("closer", "review"), 409);
        db.update(
                "UPDATE wms_inventory_transaction SET change_qty=-19,after_qty=before_qty-19 WHERE consumption_id=?",
                consumptionId);
        assertThat(check().get("canClose").asBoolean()).isTrue();
    }

    @Test
    void permissionsWarehouseScopeAndCsrfAreEnforced() throws Exception {
        var in = closeInput(check());
        postAs(endpoint(), in, scoped("reader", "read"), 403);
        mvc.perform(get(endpoint()).with(person("limited", "read", false)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(endpoint())
                                .with(scoped("closer", "review"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(in)))
                .andExpect(status().isForbidden());
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM mfg_order_closure WHERE order_id=?",
                                Long.class,
                                orderId))
                .isZero();
    }

    @Test
    void sourceMismatchBlocksClosingAndLaterMismatchDoesNotRewriteSnapshot() throws Exception {
        readyToClose();
        db.update("UPDATE wms_material_issue SET consumed_qty=18 WHERE id=?", issueId);
        assertThat(check().get("facts").get("blockers").toString()).contains("来源不一致");
        db.update("UPDATE wms_material_issue SET consumed_qty=19 WHERE id=?", issueId);
        postAs(endpoint(), closeInput(check()), scoped("closer", "review"), 200);
        String original =
                db.queryForObject(
                        "SELECT snapshot FROM mfg_order_closure WHERE order_id=?",
                        String.class,
                        orderId);
        db.update("UPDATE wms_material_issue SET consumed_qty=18 WHERE id=?", issueId);
        assertThat(check().get("closureMatches").asBoolean()).isFalse();
        assertThat(
                        db.queryForObject(
                                "SELECT snapshot FROM mfg_order_closure WHERE order_id=?",
                                String.class,
                                orderId))
                .isEqualTo(original);
        db.update("UPDATE wms_material_issue SET consumed_qty=19 WHERE id=?", issueId);
    }
}
