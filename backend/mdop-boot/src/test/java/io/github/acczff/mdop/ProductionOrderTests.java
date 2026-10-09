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
class ProductionOrderTests extends InventoryScenarioSupport {
    static final String BASE = "/api/v1/manufacturing";
    long fg, site, bom, component;

    @BeforeEach
    void prepare() throws Exception {
        fg =
                postAs(
                                "/api/master-data/warehouses",
                                Map.of(
                                        "code",
                                        "FG" + key().substring(0, 8).toUpperCase(Locale.ROOT),
                                        "name",
                                        "生产目标仓",
                                        "purpose",
                                        "FINISHED_GOODS",
                                        "form",
                                        "PHYSICAL",
                                        "managementCategory",
                                        "GENERAL"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        site =
                postAs(
                                "/api/master-data/production-sites",
                                Map.of("code", "P" + key().substring(0, 8), "name", "装配一线"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        component =
                postAs(
                                "/api/master-data/materials",
                                Map.of(
                                        "code",
                                        "C" + key().substring(0, 8),
                                        "name",
                                        "组件",
                                        "unit",
                                        "件",
                                        "trackingMode",
                                        "QUANTITY",
                                        "requireDateCode",
                                        false,
                                        "requireExpiry",
                                        false),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        bom = published("V1", "2");
    }

    RequestPostProcessor who(String name, String permission) {
        return user(name)
                .authorities(
                        new SimpleGrantedAuthority("manufacturing:read"),
                        new SimpleGrantedAuthority("manufacturing:" + permission),
                        new SimpleGrantedAuthority("wms:warehouse:" + fg));
    }

    RequestPostProcessor planner() {
        return who("planner", "write");
    }

    RequestPostProcessor reviewer() {
        return who("reviewer", "review");
    }

    RequestPostProcessor bommer() {
        return user("bom")
                .authorities(
                        new SimpleGrantedAuthority("bom:write"),
                        new SimpleGrantedAuthority("bom:read"));
    }

    long published(String label, String qty) throws Exception {
        var d =
                postAs(
                        BASE + "/boms",
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "productId",
                                material,
                                "versionLabel",
                                label,
                                "baseQuantity",
                                "1",
                                "description",
                                "装配",
                                "reason",
                                "初版",
                                "components",
                                List.of(Map.of("materialId", component, "quantity", qty))),
                        bommer(),
                        201);
        return postAs(
                        BASE + "/boms/" + d.get("id").asLong() + "/actions/publish",
                        command(d),
                        bommer(),
                        200)
                .get("id")
                .asLong();
    }

    Map<String, Object> demand() {
        return new HashMap<>(
                Map.of(
                        "idempotencyKey",
                        key(),
                        "warehouseId",
                        fg,
                        "sourceType",
                        "MANUAL",
                        "sourceReference",
                        key(),
                        "productId",
                        material,
                        "quantity",
                        "10",
                        "neededDate",
                        "2026-12-01",
                        "purpose",
                        "备库依据"));
    }

    Map<String, Object> command(JsonNode d) {
        return new HashMap<>(
                Map.of(
                        "idempotencyKey",
                        key(),
                        "version",
                        d.get("version").asLong(),
                        "reason",
                        "核对依据"));
    }

    JsonNode create() throws Exception {
        return postAs(BASE + "/demands", demand(), planner(), 201);
    }

    JsonNode read(long id) throws Exception {
        return json.readTree(
                mvc.perform(get(BASE + "/demands/" + id).with(planner()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    Map<String, Object> orderInput(JsonNode d) {
        var body = command(d);
        body.put("siteId", site);
        body.put("bomId", bom);
        body.put("plannedDate", "2026-11-20");
        return body;
    }

    JsonNode convert(JsonNode d) throws Exception {
        return postAs(
                BASE + "/demands/" + d.get("id").asLong() + "/orders",
                orderInput(d),
                planner(),
                201);
    }

    JsonNode o(JsonNode d) {
        return d.get("orders").get(0);
    }

    JsonNode action(JsonNode d, String a, RequestPostProcessor p, int expected) throws Exception {
        return postAs(
                BASE + "/orders/" + o(d).get("id").asLong() + "/actions/" + a,
                command(o(d)),
                p,
                expected);
    }

    @Test
    void snapshotSurvivesNewVersionDisableAndSafeCancellation() throws Exception {
        var d = convert(create());
        String snapshot = o(d).get("bom_snapshot").asText();
        assertThat(json.readTree(snapshot).get("components").get(0).get("quantity").asText())
                .isEqualTo("2.000000");
        d = action(d, "submit", planner(), 200);
        action(d, "approve", who("planner", "review"), 409);
        d = action(d, "approve", reviewer(), 200);
        assertThat(o(d).get("approved_by").asText()).isEqualTo("reviewer");
        published("V2", "3");
        postAs(
                BASE + "/boms/" + bom + "/actions/disable",
                Map.of("idempotencyKey", key(), "version", 1, "reason", "新版"),
                bommer(),
                200);
        assertThat(o(read(d.get("id").asLong())).get("bom_snapshot").asText()).isEqualTo(snapshot);
        mvc.perform(
                        put(BASE + "/orders/" + o(d).get("id").asLong())
                                .with(planner())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(orderInput(o(d)))))
                .andExpect(status().isConflict());
        db.update("UPDATE mdm_production_site SET status='DISABLED' WHERE id=?", site);
        d = action(d, "cancel", planner(), 200);
        assertThat(o(d).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(o(d).get("bom_snapshot").asText()).isEqualTo(snapshot);
        var c = command(d);
        postAs(BASE + "/demands/" + d.get("id").asLong() + "/cancel", c, planner(), 200);
        assertThat(
                        postAs(
                                        BASE + "/demands/" + d.get("id").asLong() + "/cancel",
                                        c,
                                        planner(),
                                        200)
                                .get("status")
                                .asText())
                .isEqualTo("CANCELLED");
    }

    @Test
    void concurrentConversionCreatesOneAuthorizationAndCancellationReleasesIt() throws Exception {
        var d = create();
        var body = orderInput(d);
        String path = BASE + "/demands/" + d.get("id").asLong() + "/orders";
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<JsonNode> work =
                    () -> {
                        gate.await();
                        return postAs(path, body, planner(), 201);
                    };
            var a = pool.submit(work);
            var b = pool.submit(work);
            gate.countDown();
            assertThat(o(a.get(30, TimeUnit.SECONDS)).get("id"))
                    .isEqualTo(o(b.get(30, TimeUnit.SECONDS)).get("id"));
        }
        d = read(d.get("id").asLong());
        postAs(path, orderInput(d), planner(), 409);
        postAs(BASE + "/demands/" + d.get("id").asLong() + "/cancel", command(d), planner(), 409);
        d = action(d, "cancel", planner(), 200);
        d = convert(d);
        assertThat(d.get("orders").size()).isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM mfg_order WHERE active_demand_id=?",
                                Integer.class,
                                d.get("id").asLong()))
                .isEqualTo(1);
    }

    @Test
    void revalidatesDisabledSiteBomAndMaterialsBeforeAuthorization() throws Exception {
        var d = convert(create());
        d = action(d, "submit", planner(), 200);
        db.update("UPDATE mdm_production_site SET status='DISABLED' WHERE id=?", site);
        action(d, "approve", reviewer(), 409);
        d = action(d, "reject", reviewer(), 200);
        assertThat(o(d).get("status").asText()).isEqualTo("REJECTED");
        db.update("UPDATE mdm_production_site SET status='ENABLED' WHERE id=?", site);
        db.update("UPDATE mdm_material SET status='DISABLED' WHERE id=?", component);
        action(d, "submit", planner(), 409);
        db.update("UPDATE mdm_material SET status='ENABLED' WHERE id=?", component);
        postAs(
                BASE + "/boms/" + bom + "/actions/disable",
                Map.of("idempotencyKey", key(), "version", 1, "reason", "失效"),
                bommer(),
                200);
        action(d, "submit", planner(), 409);
        action(d, "cancel", planner(), 200);
    }

    @Test
    void permissionsReplayPayloadVersionAndCreatorSeparation() throws Exception {
        var body = demand();
        var d = postAs(BASE + "/demands", body, planner(), 201);
        assertThat(postAs(BASE + "/demands", body, planner(), 201).get("id"))
                .isEqualTo(d.get("id"));
        postAs(BASE + "/demands", body, who("other", "write"), 409);
        body.put("quantity", "11");
        postAs(BASE + "/demands", body, planner(), 409);
        mvc.perform(
                        get(BASE + "/demands/" + d.get("id").asLong())
                                .with(
                                        user("outsider")
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "manufacturing:read"))))
                .andExpect(status().isForbidden());
        postAs(BASE + "/demands", demand(), admin(), 403);
        postAs(BASE + "/demands", demand(), reviewer(), 403);
        mvc.perform(
                        post(BASE + "/demands")
                                .with(planner())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(demand())))
                .andExpect(status().isForbidden());
        d =
                postAs(
                        BASE + "/demands/" + d.get("id").asLong() + "/orders",
                        orderInput(d),
                        who("different-planner", "write"),
                        201);
        var stale = command(o(d));
        d = action(d, "submit", who("different-planner", "write"), 200);
        action(d, "approve", who("planner", "review"), 409);
        postAs(
                BASE + "/orders/" + o(d).get("id").asLong() + "/actions/approve",
                stale,
                reviewer(),
                409);
        assertThatThrownBy(
                        () ->
                                io.github.acczff.mdop.system.identity.PermissionCatalog.resolve(
                                        Set.of("PRODUCTION_PLANNER", "PRODUCTION_REVIEWER"),
                                        Set.of(fg)))
                .hasMessageContaining("不同账号");
    }

    @Test
    void invalidDemandDoesNotLeavePartialRowsAndBomMustMatchProduct() throws Exception {
        var body = demand();
        body.put("quantity", "0");
        postAs(BASE + "/demands", body, planner(), 400);
        body = demand();
        body.remove("neededDate");
        postAs(BASE + "/demands", body, planner(), 400);
        body = demand();
        body.put("quantity", "0.1234567");
        postAs(BASE + "/demands", body, planner(), 400);
        var d = create();
        var input = orderInput(d);
        input.put("siteId", Long.MAX_VALUE);
        postAs(BASE + "/demands/" + d.get("id").asLong() + "/orders", input, planner(), 404);
        assertThat(read(d.get("id").asLong()).get("orders").isEmpty()).isTrue();
        body = demand();
        body.put("productId", component);
        d = postAs(BASE + "/demands", body, planner(), 201);
        postAs(
                BASE + "/demands/" + d.get("id").asLong() + "/orders",
                orderInput(d),
                planner(),
                409);
    }

    RequestPostProcessor sales(String name, String p) {
        return user(name)
                .authorities(
                        new SimpleGrantedAuthority("sales:read"),
                        new SimpleGrantedAuthority("sales:" + p),
                        new SimpleGrantedAuthority("wms:warehouse:" + fg));
    }

    @Test
    void salesTenConvertsOnceAndUpstreamCancellationWaitsForProduction() throws Exception {
        String sal = "/api/v1/sales/documents";
        long customer =
                postAs(
                                "/api/master-data/customers",
                                Map.of("code", "C" + key().substring(0, 8), "name", "客户"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        var s =
                postAs(
                        sal,
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "warehouseId",
                                fg,
                                "customerId",
                                customer,
                                "purpose",
                                "销售制造",
                                "neededDate",
                                "2026-12-01",
                                "lines",
                                List.of(Map.of("materialId", material, "quantity", "10"))),
                        sales("seller", "write"),
                        201);
        long sid = s.get("id").asLong();
        var source =
                new HashMap<String, Object>(
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "warehouseId",
                                fg,
                                "sourceType",
                                "SALES",
                                "salesLineId",
                                s.get("lines").get(0).get("id").asLong(),
                                "purpose",
                                "销售转生产"));
        postAs(BASE + "/demands", source, planner(), 409);
        s = postAs(sal + "/" + sid + "/actions/submit", command(s), sales("seller", "write"), 200);
        s =
                postAs(
                        sal + "/" + sid + "/actions/approve",
                        command(s),
                        sales("sales-reviewer", "review"),
                        200);
        var d = postAs(BASE + "/demands", source, planner(), 201);
        assertThat(d.get("quantity").asText()).isEqualTo("10.000000");
        source.put("idempotencyKey", key());
        postAs(BASE + "/demands", source, planner(), 409);
        postAs(sal + "/" + sid + "/actions/cancel", command(s), sales("seller", "write"), 409);
        d = convert(d);
        d = action(d, "submit", planner(), 200);
        d = action(d, "approve", reviewer(), 200);
        assertThat(d.get("quantity").asText()).isEqualTo("10.000000");
        d = action(d, "cancel", planner(), 200);
        postAs(BASE + "/demands/" + d.get("id").asLong() + "/cancel", command(d), planner(), 200);
        postAs(sal + "/" + sid + "/actions/cancel", command(s), sales("seller", "write"), 200);
    }

    @Test
    void differentRequestKeysRaceForOneDemandAndRejectedOrderCanBeCorrected() throws Exception {
        var d = create();
        String path = BASE + "/demands/" + d.get("id").asLong() + "/orders";
        var first = orderInput(d);
        var second = orderInput(d);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            java.util.function.Function<Map<String, Object>, Callable<Integer>> task =
                    body ->
                            () -> {
                                gate.await();
                                return mvc.perform(
                                                post(path)
                                                        .with(planner())
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(json.writeValueAsString(body)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            };
            var a = pool.submit(task.apply(first));
            var b = pool.submit(task.apply(second));
            gate.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        d = read(d.get("id").asLong());
        d = action(d, "submit", planner(), 200);
        d = action(d, "reject", reviewer(), 200);
        var edit = orderInput(o(d));
        edit.put("plannedDate", "2026-12-05");
        d =
                json.readTree(
                        mvc.perform(
                                        put(BASE + "/orders/" + o(d).get("id").asLong())
                                                .with(who("editor", "write"))
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(json.writeValueAsString(edit)))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        d = action(d, "submit", planner(), 200);
        action(d, "approve", who("editor", "review"), 409);
        d = action(d, "approve", reviewer(), 200);
        assertThat(o(d).get("planned_date").asText()).isEqualTo("2026-12-05");
    }

    @Test
    void legacyMesCannotCreateExecutionFactsForErpOrderNumbers() throws Exception {
        var d = convert(create());
        String no = o(d).get("order_no").asText();
        postAs(
                "/api/local/mes-demands",
                Map.of(
                        "demandNo",
                        key(),
                        "workOrderNo",
                        no,
                        "warehouseId",
                        warehouse,
                        "targetWarehouseId",
                        fg,
                        "materialId",
                        component,
                        "quantity",
                        "2"),
                admin(),
                409);
        postAs(
                "/api/local/finished-receipts",
                Map.of(
                        "demandNo",
                        key(),
                        "workOrderNo",
                        no,
                        "warehouseId",
                        fg,
                        "materialId",
                        material,
                        "quantity",
                        "1",
                        "batchNo",
                        "B1",
                        "productionDate",
                        "2026-10-09"),
                admin(),
                409);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_material_issue WHERE work_order_no=?",
                                Integer.class,
                                no))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_finished_receipt WHERE work_order_no=?",
                                Integer.class,
                                no))
                .isZero();
    }
}
