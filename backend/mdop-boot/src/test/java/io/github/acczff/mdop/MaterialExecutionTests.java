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
class MaterialExecutionTests extends MaterialScenarioSupport {
    long source, store;
    JsonNode plan;

    @BeforeEach
    void execution() throws Exception {
        lineSide();
        source = putaway(receive(20), 20);
        store =
                db.queryForObject(
                        "SELECT location_id FROM wms_inventory_balance WHERE id=?",
                        Long.class,
                        source);
        plan = postAs(path() + "/calculate", calc(0), executionPlanner(), 200);
    }

    RequestPostProcessor executionPlanner() {
        return user("planner")
                .authorities(
                        new SimpleGrantedAuthority("manufacturing:read"),
                        new SimpleGrantedAuthority("manufacturing:write"),
                        new SimpleGrantedAuthority("wms:warehouse:" + fg),
                        new SimpleGrantedAuthority("wms:warehouse:" + warehouse),
                        new SimpleGrantedAuthority("wms:warehouse:" + targetWarehouse));
    }

    RequestPostProcessor recorder(String name) {
        return both(name, "wms:production:report");
    }

    Map<String, Object> issueInput(String qty) {
        return new HashMap<>(
                Map.of(
                        "idempotencyKey",
                        key(),
                        "planVersion",
                        plan.get("planVersion").asLong(),
                        "lineId",
                        plan.get("lines").get(0).get("id").asLong(),
                        "targetWarehouseId",
                        targetWarehouse,
                        "quantity",
                        qty,
                        "reason",
                        "按计划分次领料"));
    }

    long issue(String qty) throws Exception {
        var result = postAs(path() + "/issues", issueInput(qty), executionPlanner(), 200);
        return result.get("issues").get(result.get("issues").size() - 1).get("id").asLong();
    }

    void send(long id) throws Exception {
        reserve(id, source, 200);
        confirmIssue(id, 200);
    }

    Map<String, Object> consumption(long id, String qty) {
        return Map.of(
                "eventNo",
                key(),
                "issueId",
                id,
                "workOrderNo",
                production.get("order_no").asText(),
                "quantity",
                qty);
    }

    JsonNode report(long id, String qty) throws Exception {
        return postAs(
                "/api/v1/wms/production/consumptions",
                Map.of("consumption", consumption(id, qty), "reason", "实际投料"),
                recorder("recorder"),
                200);
    }

    JsonNode back(long id, String qty) throws Exception {
        var body = new HashMap<>(consumption(id, qty));
        body.put("qualityStatus", "QUALIFIED");
        body.put("targetLocationId", store);
        body.put("reason", "余料退回");
        return postAs("/api/v1/wms/production/returns", body, recorder("recorder"), 200);
    }

    @Test
    void twentyIssuedNineteenConsumedOneReturnedAndNoSimulatedFeedback() throws Exception {
        long a = issue("12"), b = issue("8");
        send(a);
        send(b);
        report(a, "12");
        report(b, "7");
        var r = back(b, "1");
        postAs(
                "/api/v1/wms/production/consumptions",
                Map.of("consumption", consumption(b, "1"), "reason", "待退不能消耗"),
                recorder("recorder"),
                409);
        postAs(
                "/api/v1/wms/production/returns/" + r.get("id").asLong() + "/confirm",
                Map.of(),
                admin(),
                200);
        postAs(
                "/api/v1/wms/production/returns/" + r.get("id").asLong() + "/confirm",
                Map.of(),
                admin(),
                200);
        for (long id : List.of(a, b))
            number(read("/api/v1/wms/production/issues/" + id), "remaining_qty", "0");
        assertThat(qty(source)).isEqualByComparingTo("1");
        assertThat(
                        db.queryForObject(
                                "SELECT SUM(consumed_qty) FROM wms_material_issue WHERE production_order_id=?",
                                BigDecimal.class,
                                orderId))
                .isEqualByComparingTo("19");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_outbox WHERE aggregate_type='MaterialIssue' AND aggregate_id IN (?,?)",
                                Long.class,
                                a,
                                b))
                .isZero();
        plan = postAs(path() + "/calculate", calc(1), executionPlanner(), 200);
        number(facts(plan), "committed", "19");
        number(facts(plan), "gap", "0");
        postAs(path() + "/issues", issueInput("1"), executionPlanner(), 409);
        productionAction("cancel", planner(), 409);
    }

    @Test
    void concurrentAuthorizationCannotExceedBomAndReplayRetainsOneDocument() throws Exception {
        var one = issueInput("12");
        var two = issueInput("12");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var jobs = new ArrayList<Future<Integer>>();
            for (var body : List.of(one, two))
                jobs.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return mvc.perform(
                                                    post(path() + "/issues")
                                                            .with(executionPlanner())
                                                            .with(csrf())
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(json.writeValueAsString(body)))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            gate.countDown();
            assertThat(
                            List.of(
                                    jobs.get(0).get(30, TimeUnit.SECONDS),
                                    jobs.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT SUM(quantity) FROM wms_material_issue WHERE production_order_id=?",
                                BigDecimal.class,
                                orderId))
                .isEqualByComparingTo("12");
        var rest = issueInput("8");
        var first = postAs(path() + "/issues", rest, executionPlanner(), 200);
        var again = postAs(path() + "/issues", rest, executionPlanner(), 200);
        assertThat(again.get("issues")).isEqualTo(first.get("issues"));
        rest.put("reason", "改变请求");
        postAs(path() + "/issues", rest, executionPlanner(), 409);
    }

    @Test
    void cancellationFreesAuthorizationAndReservationDoesNotCreateProcurementGap()
            throws Exception {
        long id = issue("20");
        reserve(id, source, 200);
        plan = postAs(path() + "/calculate", calc(1), executionPlanner(), 200);
        number(facts(plan), "committed", "20");
        number(facts(plan), "gap", "0");
        productionAction("cancel", planner(), 409);
        postAs("/api/v1/wms/issues/" + id + "/cancel", Map.of("reason", "取消未发料"), admin(), 200);
        long other = issue("20");
        postAs("/api/v1/wms/issues/" + other + "/cancel", Map.of("reason", "撤销计划"), admin(), 200);
        productionAction("cancel", planner(), 200);
        postAs(path() + "/issues", issueInput("1"), executionPlanner(), 409);
        reserve(other, source, 409);
    }

    @Test
    void sourcePermissionsStalePlanAndManualReplayAreEnforced() throws Exception {
        var stale = issueInput("1");
        plan = postAs(path() + "/calculate", calc(1), executionPlanner(), 200);
        postAs(path() + "/issues", stale, executionPlanner(), 409);
        postAs(path() + "/issues", issueInput("1"), planner(), 403);
        mvc.perform(
                        post(path() + "/issues")
                                .with(executionPlanner())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(issueInput("1"))))
                .andExpect(status().isForbidden());
        long id = issue("20");
        send(id);
        var body = consumption(id, "5");
        postAs("/api/local/mes-production/consumptions", body, admin(), 409);
        var manual = Map.of("consumption", body, "reason", "记录实际消耗");
        postAs("/api/v1/wms/production/consumptions", manual, admin(), 403);
        postAs("/api/v1/wms/production/consumptions", manual, recorder("recorder"), 200);
        postAs("/api/v1/wms/production/consumptions", manual, recorder("recorder"), 200);
        postAs("/api/v1/wms/production/consumptions", manual, recorder("other"), 409);
        number(read("/api/v1/wms/production/issues/" + id), "consumed_qty", "5");
        assertThat(
                        io.github.acczff.mdop.system.identity.PermissionCatalog.resolve(
                                Set.of("WAREHOUSE_OPERATOR"), Set.of(warehouse)))
                .doesNotContain("wms:production:report");
        assertThat(
                        io.github.acczff.mdop.system.identity.PermissionCatalog.resolve(
                                Set.of("PRODUCTION_RECORDER"), Set.of(warehouse)))
                .contains("wms:production:report")
                .doesNotContain("wms:production:confirm", "manufacturing:write");
    }

    @Test
    void formalReversalUsesTwoPeopleAndDoesNotReopenIssueQuota() throws Exception {
        long id = issue("20");
        send(id);
        long consumed = report(id, "19").get("id").asLong();
        var cmd =
                Map.of(
                        "kind",
                        "CONSUMPTION",
                        "sourceId",
                        consumed,
                        "requestKey",
                        key(),
                        "externalReference",
                        key(),
                        "reason",
                        "登记错误");
        long correction =
                postAs(
                                "/api/v1/wms/production-reversals",
                                cmd,
                                both("requester", "wms:production:reverse"),
                                201)
                        .get("id")
                        .asLong();
        String url = "/api/v1/wms/production-reversals/" + correction + "/approve";
        postAs(url, Map.of("reason", "核对"), both("requester", "wms:production:review"), 403);
        postAs(url, Map.of("reason", "核对"), both("reviewer", "wms:production:review"), 200);
        postAs(url, Map.of("reason", "核对"), both("reviewer", "wms:production:review"), 200);
        number(read("/api/v1/wms/production/issues/" + id), "consumed_qty", "0");
        postAs(path() + "/issues", issueInput("1"), executionPlanner(), 409);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE production_reversal_id=?",
                                Long.class,
                                correction))
                .isEqualTo(1);
    }
}
