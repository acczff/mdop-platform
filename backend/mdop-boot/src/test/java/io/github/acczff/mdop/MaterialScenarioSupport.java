package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

abstract class MaterialScenarioSupport extends PurchaseScenarioSupport {
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
}
