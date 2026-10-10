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
class MaterialPlanTests extends PurchaseScenarioSupport {
    static final String MFG = "/api/v1/manufacturing";
    long fg, product, site, orderId, balance;
    JsonNode production;

    RequestPostProcessor planner() {
        return person("planner", "write", true);
    }

    RequestPostProcessor person(String name, String permission, boolean raw) {
        var auth = new ArrayList<SimpleGrantedAuthority>();
        for (String p :
                List.of("manufacturing:read", "manufacturing:" + permission, "wms:warehouse:" + fg))
            auth.add(new SimpleGrantedAuthority(p));
        if (raw) auth.add(new SimpleGrantedAuthority("wms:warehouse:" + warehouse));
        return user(name).authorities(auth);
    }

    @BeforeEach
    void prepare() throws Exception {
        fg =
                postAs(
                                "/api/master-data/warehouses",
                                Map.of(
                                        "code",
                                        "FG" + key().substring(0, 8).toUpperCase(Locale.ROOT),
                                        "name",
                                        "成品仓",
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
                                Map.of("code", "P" + key().substring(0, 8), "name", "装配线"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        product =
                postAs(
                                "/api/master-data/materials",
                                Map.of(
                                        "code",
                                        "FG" + key().substring(0, 8),
                                        "name",
                                        "成品",
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
        approved("1", "2", "10");
        balance = putaway(receive(4), 4);
    }

    void approved(String base, String component, String output) throws Exception {
        var bp =
                user("bom")
                        .authorities(
                                new SimpleGrantedAuthority("bom:read"),
                                new SimpleGrantedAuthority("bom:write"));
        var b =
                postAs(
                        MFG + "/boms",
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "productId",
                                product,
                                "versionLabel",
                                key().substring(0, 8),
                                "baseQuantity",
                                base,
                                "description",
                                "装配",
                                "reason",
                                "计划",
                                "components",
                                List.of(Map.of("materialId", material, "quantity", component))),
                        bp,
                        201);
        b = postAs(MFG + "/boms/" + b.get("id").asLong() + "/actions/publish", command(b), bp, 200);
        var d =
                postAs(
                        MFG + "/demands",
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
                                product,
                                "quantity",
                                output,
                                "neededDate",
                                "2026-12-01",
                                "purpose",
                                "备库"),
                        planner(),
                        201);
        var in = new HashMap<String, Object>(command(d));
        in.put("siteId", site);
        in.put("bomId", b.get("id").asLong());
        in.put("plannedDate", "2026-11-20");
        d = postAs(MFG + "/demands/" + d.get("id").asLong() + "/orders", in, planner(), 201);
        production = d.get("orders").get(0);
        orderId = production.get("id").asLong();
        production = productionAction("submit", planner(), 200).get("orders").get(0);
        production =
                productionAction("approve", person("reviewer", "review", true), 200)
                        .get("orders")
                        .get(0);
    }

    JsonNode productionAction(String a, RequestPostProcessor p, int expected) throws Exception {
        return postAs(
                MFG + "/orders/" + orderId + "/actions/" + a, command(production), p, expected);
    }

    String path() {
        return MFG + "/orders/" + orderId + "/materials";
    }

    Map<String, Object> calc(long version) {
        return new HashMap<>(
                Map.of(
                        "idempotencyKey",
                        key(),
                        "warehouseId",
                        warehouse,
                        "orderVersion",
                        production.get("version").asLong(),
                        "planVersion",
                        version,
                        "reason",
                        "核对材料"));
    }

    JsonNode calculate(long version) throws Exception {
        return postAs(path() + "/calculate", calc(version), planner(), 200);
    }

    Map<String, Object> confirm(JsonNode p) {
        return new HashMap<>(
                Map.of(
                        "idempotencyKey",
                        key(),
                        "planVersion",
                        p.get("planVersion").asLong(),
                        "lineId",
                        p.get("lines").get(0).get("id").asLong(),
                        "reason",
                        "确认缺口采购"));
    }

    JsonNode facts(JsonNode p) {
        return json.readTree(p.get("plan").get("snapshot").asText()).get("lines").get(0);
    }

    void number(JsonNode l, String key, String value) {
        assertThat(new BigDecimal(l.get(key).asText())).isEqualByComparingTo(value);
    }

    long requestId(JsonNode p) {
        return p.get("lines").get(0).get("purchase_request_id").asLong();
    }

    long putaway(long receipt, int quantity) throws Exception {
        long item =
                db.queryForObject(
                        "SELECT id FROM wms_receipt_item WHERE receipt_id=?", Long.class, receipt);
        postAs(
                "/api/local/qms-results",
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
                        "检验",
                        "items",
                        List.of(
                                Map.of(
                                        "receiptItemId",
                                        item,
                                        "qualifiedQty",
                                        quantity,
                                        "rejectedQty",
                                        0))),
                admin(),
                200);
        long loc = storage();
        postAs(
                "/api/v1/wms/quality/items/" + item + "/putaway",
                Map.of("idempotencyKey", key(), "locationId", loc),
                admin(),
                200);
        return db.queryForObject(
                "SELECT id FROM wms_inventory_balance WHERE location_id=? AND quality_status='QUALIFIED'",
                Long.class,
                loc);
    }

    @Test
    void exactGapDraftReplayAndCancellationChain() throws Exception {
        var p = calculate(0);
        var l = facts(p);
        number(l, "required", "20");
        number(l, "available", "4");
        number(l, "gap", "16");
        var cmd = confirm(p);
        p = postAs(path() + "/confirm", cmd, planner(), 200);
        long id = requestId(p);
        assertThat(requestId(postAs(path() + "/confirm", cmd, planner(), 200))).isEqualTo(id);
        postAs(path() + "/confirm", cmd, person("other", "write", true), 409);
        var req = getOrder(id);
        assertThat(req.get("source_type").asText()).isEqualTo("MANUFACTURING");
        assertThat(req.get("status").asText()).isEqualTo("DRAFT");
        number(req.get("lines").get(0), "quantity", "16");
        assertThat(qty(balance)).isEqualByComparingTo("4");
        productionAction("cancel", planner(), 409);
        p = calculate(1);
        number(facts(p), "outstanding", "16");
        number(facts(p), "gap", "0");
        postAs(path() + "/confirm", confirm(p), planner(), 409);
        action(req, "cancel", "buyer", "purchasing:write", 200);
        production = productionAction("cancel", planner(), 200).get("orders").get(0);
        postAs(path() + "/calculate", calc(2), planner(), 409);
    }

    @Test
    void partialPutawayIsNotCountedTwiceAndSourceQuantityCannotBeEdited() throws Exception {
        var p = calculate(0);
        p = postAs(path() + "/confirm", confirm(p), planner(), 200);
        var d = getOrder(requestId(p));
        var edit = new HashMap<String, Object>(command(d));
        edit.put("warehouseId", warehouse);
        edit.put("neededDate", "2026-12-01");
        edit.put("purpose", "扩大采购");
        edit.put("lines", List.of(Map.of("materialId", material, "quantity", "99")));
        mvc.perform(
                        put(URL + "/" + d.get("id").asLong())
                                .with(operator("buyer", "purchasing:write"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(edit)))
                .andExpect(status().isConflict());
        d = action(d, "submit", "buyer", "purchasing:write", 200);
        action(d, "approve", "planner", "purchasing:review", 409);
        d = action(d, "approve", "reviewer", "purchasing:review", 200);
        d =
                postAs(
                        URL + "/" + d.get("id").asLong() + "/convert",
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "version",
                                d.get("version").asLong(),
                                "supplierId",
                                supplier,
                                "neededDate",
                                "2026-11-02",
                                "reason",
                                "采购"),
                        operator("buyer", "purchasing:write"),
                        200);
        assertThat(d.get("production_order_no").asText())
                .isEqualTo(production.get("order_no").asText());
        d = action(d, "submit", "buyer", "purchasing:write", 200);
        d = action(d, "approve", "reviewer", "purchasing:review", 200);
        long po = d.get("id").asLong();
        var arranged = arrange(d, "8", 200);
        long aid = arranged.get("arrangements").get(0).get("id").asLong();
        d = perform(arranged, aid, "deliver", 200);
        var receipt = draft(d.get("arrangements").get(0), "8", 201);
        long rid = receipt.get("receipt").get("id").asLong();
        postAs(
                "/api/v1/wms/receipts/" + rid + "/submit",
                Map.of("idempotencyKey", key(), "version", 0),
                operator("warehouse", "wms:receipt:submit"),
                200);
        putaway(rid, 8);
        p = calculate(1);
        var l = facts(p);
        number(l, "available", "12");
        number(l, "linked", "16");
        number(l, "putaway", "8");
        number(l, "outstanding", "8");
        number(l, "gap", "0");
        // Existing supply does not silently generate a second purchase after stock is used
        // elsewhere.
        db.update(
                "UPDATE wms_inventory_balance SET available_qty=0,reserved_qty=on_hand_qty,reservation_version=reservation_version+1 WHERE id=?",
                balance);
        p = calculate(2);
        number(facts(p), "gap", "4");
        number(facts(p), "suggested", "0");
        productionAction("cancel", planner(), 409);
        assertThat(getOrder(po).get("kind").asText()).isEqualTo("ORDER");
    }

    @Test
    void staleInventoryPlanAndWarehouseScopeBlockConfirmation() throws Exception {
        var p = calculate(0);
        var cmd = confirm(p);
        db.update("UPDATE wms_inventory_balance SET expiry_date='2000-01-01' WHERE id=?", balance);
        postAs(path() + "/confirm", cmd, planner(), 409);
        p = calculate(1);
        number(facts(p), "available", "0");
        number(facts(p), "suggested", "20");
        postAs(path() + "/confirm", cmd, planner(), 409);
        postAs(path() + "/confirm", confirm(p), person("noscope", "write", false), 403);
        mvc.perform(get(path()).with(person("noscope", "read", false)))
                .andExpect(status().isForbidden());
        postAs(path() + "/calculate", calc(2), admin(), 403);
        postAs(path() + "/calculate", calc(2), person("reader", "read", true), 403);
        mvc.perform(
                        post(path() + "/calculate")
                                .with(planner())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(calc(2))))
                .andExpect(status().isForbidden());
        db.update("UPDATE mdm_material SET status='DISABLED' WHERE id=?", material);
        postAs(path() + "/confirm", confirm(p), planner(), 409);
    }

    @Test
    void concurrentConfirmationCreatesOneRequestAndChangedPayloadIsRejected() throws Exception {
        var p = calculate(0);
        var cmd = confirm(p);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<JsonNode> work =
                    () -> {
                        gate.await();
                        return postAs(path() + "/confirm", cmd, planner(), 200);
                    };
            var a = pool.submit(work);
            var b = pool.submit(work);
            gate.countDown();
            assertThat(requestId(a.get(30, TimeUnit.SECONDS)))
                    .isEqualTo(requestId(b.get(30, TimeUnit.SECONDS)));
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM pur_document WHERE production_order_id=?",
                                Integer.class,
                                orderId))
                .isEqualTo(1);
        cmd.put("reason", "不同载荷");
        postAs(path() + "/confirm", cmd, planner(), 409);
        postAs(path() + "/confirm", confirm(p), planner(), 409);
    }

    @Test
    void repeatingDecimalRoundsUpWithExactRationalEvidenceAndOverflowRollsBack() throws Exception {
        approved("3", "1", "1");
        var p = calculate(0);
        var l = facts(p);
        number(l, "required", "0.333334");
        number(l, "roundingNumerator", "0.000002");
        number(l, "roundingDenominator", "3");
        approved("0.000001", "999999999999", "999999999999");
        postAs(path() + "/calculate", calc(0), planner(), 409);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM mfg_material_plan WHERE order_id=?",
                                Integer.class,
                                orderId))
                .isZero();
    }

    @Test
    void cancelledSuggestionCanBeReplacedButCalculationWarehouseIsFixed() throws Exception {
        var p = calculate(0);
        p = postAs(path() + "/confirm", confirm(p), planner(), 200);
        action(getOrder(requestId(p)), "cancel", "buyer", "purchasing:write", 200);
        p = calculate(1);
        number(facts(p), "suggested", "16");
        p = postAs(path() + "/confirm", confirm(p), planner(), 200);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM pur_document WHERE production_order_id=? AND status<>'CANCELLED'",
                                Integer.class,
                                orderId))
                .isEqualTo(1);
        var change = calc(2);
        change.put("warehouseId", fg);
        postAs(path() + "/calculate", change, planner(), 409);
    }
}
