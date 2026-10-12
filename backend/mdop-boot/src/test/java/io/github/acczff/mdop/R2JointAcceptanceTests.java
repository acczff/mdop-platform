package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class R2JointAcceptanceTests extends MaterialScenarioSupport {
    static final String SALES = "/api/v1/sales/documents";

    RequestPostProcessor mfg(String name, String permission) {
        return user(name)
                .authorities(
                        new SimpleGrantedAuthority("manufacturing:read"),
                        new SimpleGrantedAuthority("manufacturing:" + permission),
                        new SimpleGrantedAuthority("wms:warehouse:" + fg),
                        new SimpleGrantedAuthority("wms:warehouse:" + warehouse),
                        new SimpleGrantedAuthority("wms:warehouse:" + targetWarehouse));
    }

    RequestPostProcessor sales(String name, String permission) {
        return user(name)
                .authorities(
                        new SimpleGrantedAuthority("sales:read"),
                        new SimpleGrantedAuthority("sales:" + permission),
                        new SimpleGrantedAuthority("wms:warehouse:" + fg));
    }

    JsonNode salesAction(JsonNode d, String action, String name, String permission)
            throws Exception {
        return postAs(
                SALES + "/" + d.get("id").asLong() + "/actions/" + action,
                command(d),
                sales(name, permission),
                200);
    }

    JsonNode closure() throws Exception {
        return json.readTree(
                mvc.perform(
                                get(MFG + "/orders/" + orderId + "/closure")
                                        .with(mfg("closer", "review")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    long use(JsonNode plan, long stock, String issued, String consumed) throws Exception {
        var d =
                postAs(
                        path() + "/issues",
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
                                issued,
                                "reason",
                                "同源工单领料"),
                        mfg("planner", "write"),
                        200);
        long issue = d.get("issues").get(d.get("issues").size() - 1).get("id").asLong();
        // References are ordered by id; each request adds one new issue.
        reserve(issue, stock, 200);
        confirmIssue(issue, 200);
        postAs(
                "/api/v1/wms/production/consumptions",
                Map.of(
                        "consumption",
                        Map.of(
                                "eventNo",
                                key(),
                                "issueId",
                                issue,
                                "workOrderNo",
                                production.get("order_no").asText(),
                                "quantity",
                                consumed),
                        "reason",
                        "实际消耗"),
                both("recorder", "wms:production:report"),
                200);
        return issue;
    }

    long finished(String qty) throws Exception {
        String cp = MFG + "/orders/" + orderId + "/completions";
        var c =
                postAs(
                        cp,
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
                                "实际完工"),
                        mfg("recorder", "completion:report"),
                        200);
        var report = c.get("reports").get(0);
        c =
                postAs(
                        cp + "/" + report.get("id").asLong() + "/approve",
                        command(report),
                        mfg("reviewer", "completion:review"),
                        200);
        long receipt = c.get("reports").get(0).get("receipt").get("id").asLong();
        var inspection =
                postAs(
                        "/api/master-data/locations",
                        Map.of(
                                "warehouseId",
                                fg,
                                "code",
                                "IN" + key().substring(0, 8),
                                "name",
                                "待检",
                                "areaType",
                                "INSPECTION"),
                        admin(),
                        201);
        var storage =
                postAs(
                        "/api/master-data/locations",
                        Map.of(
                                "warehouseId",
                                fg,
                                "code",
                                "ST" + key().substring(0, 8),
                                "name",
                                "成品库位",
                                "areaType",
                                "STORAGE"),
                        admin(),
                        201);
        postAs(
                "/api/v1/wms/finished-receipts/" + receipt + "/receive",
                Map.of("locationId", inspection.get("id").asLong()),
                admin(),
                200);
        assertThat(closure().get("canClose").asBoolean()).isFalse();
        postAs(
                "/api/local/finished-receipts/" + receipt + "/quality",
                Map.of("eventNo", key(), "result", "QUALIFIED", "reason", "明确模拟QMS"),
                admin(),
                200);
        postAs(
                "/api/v1/wms/finished-receipts/" + receipt + "/putaway",
                Map.of("locationId", storage.get("id").asLong()),
                admin(),
                200);
        return receipt;
    }

    @Test
    void sameSalesSourceFlowsThroughProcurementProductionAndShipmentWithoutRewritingHistory()
            throws Exception {
        lineSide();
        long bom = production.get("bom_id").asLong();
        long customer =
                postAs(
                                "/api/master-data/customers",
                                Map.of("code", "C" + key().substring(0, 8), "name", "联合验收客户"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        var sale =
                postAs(
                        SALES,
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "warehouseId",
                                fg,
                                "customerId",
                                customer,
                                "purpose",
                                "同源R2验收",
                                "neededDate",
                                "2026-12-01",
                                "lines",
                                List.of(Map.of("materialId", product, "quantity", "10"))),
                        sales("seller", "write"),
                        201);
        sale = salesAction(sale, "submit", "seller", "write");
        sale = salesAction(sale, "approve", "sales-reviewer", "review");
        long saleLine = sale.get("lines").get(0).get("id").asLong();
        var demandBody =
                Map.of(
                        "idempotencyKey",
                        key(),
                        "warehouseId",
                        fg,
                        "sourceType",
                        "SALES",
                        "salesLineId",
                        saleLine,
                        "purpose",
                        "按销售10件生产");
        var d = postAs(MFG + "/demands", demandBody, mfg("planner", "write"), 201);
        assertThat(postAs(MFG + "/demands", demandBody, mfg("planner", "write"), 201).get("id"))
                .isEqualTo(d.get("id"));
        d =
                postAs(
                        MFG + "/demands/" + d.get("id").asLong() + "/orders",
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "version",
                                d.get("version").asLong(),
                                "siteId",
                                site,
                                "bomId",
                                bom,
                                "plannedDate",
                                "2026-11-20",
                                "reason",
                                "固定生产授权"),
                        mfg("planner", "write"),
                        201);
        production = d.get("orders").get(0);
        orderId = production.get("id").asLong();
        production = productionAction("submit", mfg("planner", "write"), 200).get("orders").get(0);
        production =
                productionAction("approve", mfg("reviewer", "review"), 200).get("orders").get(0);
        postAs(
                SALES + "/" + sale.get("id").asLong() + "/actions/cancel",
                command(sale),
                sales("seller", "write"),
                409);
        var plan = calculate(0);
        number(facts(plan), "gap", "16");
        var confirmation = confirm(plan);
        plan = postAs(path() + "/confirm", confirmation, planner(), 200);
        assertThat(
                        postAs(path() + "/confirm", confirmation, planner(), 200)
                                .get("lines")
                                .get(0)
                                .get("purchase_request_id"))
                .isEqualTo(plan.get("lines").get(0).get("purchase_request_id"));
        var purchase = getOrder(requestId(plan));
        purchase = action(purchase, "submit", "buyer", "purchasing:write", 200);
        purchase = action(purchase, "approve", "buyer-reviewer", "purchasing:review", 200);
        purchase =
                postAs(
                        URL + "/" + purchase.get("id").asLong() + "/convert",
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "version",
                                purchase.get("version").asLong(),
                                "supplierId",
                                supplier,
                                "neededDate",
                                "2026-11-01",
                                "reason",
                                "工单缺口采购"),
                        operator("buyer", "purchasing:write"),
                        200);
        purchase = action(purchase, "submit", "buyer", "purchasing:write", 200);
        purchase = action(purchase, "approve", "buyer-reviewer", "purchasing:review", 200);
        purchase = arrange(purchase, "16", 200);
        long arrangementId = purchase.get("arrangements").get(0).get("id").asLong();
        purchase = perform(purchase, arrangementId, "deliver", 200);
        var receipt = draft(purchase.get("arrangements").get(0), "16", 201);
        long receiptId = receipt.get("receipt").get("id").asLong();
        postAs(
                "/api/v1/wms/receipts/" + receiptId + "/submit",
                Map.of("idempotencyKey", key(), "version", 0),
                admin(),
                200);
        long replenished = putaway(receiptId, 16);
        purchase =
                action(
                        getOrder(purchase.get("id").asLong()),
                        "close",
                        "buyer",
                        "purchasing:write",
                        200);
        assertThat(purchase.get("closureMatches").asBoolean()).isTrue();
        use(plan, balance, "4", "4");
        long finalIssue = use(plan, replenished, "16", "15");
        long returnLocation =
                db.queryForObject(
                        "SELECT location_id FROM wms_inventory_balance WHERE id=?",
                        Long.class,
                        replenished);
        var ret =
                postAs(
                        "/api/v1/wms/production/returns",
                        Map.of(
                                "eventNo",
                                key(),
                                "issueId",
                                finalIssue,
                                "workOrderNo",
                                production.get("order_no").asText(),
                                "quantity",
                                "1",
                                "qualityStatus",
                                "QUALIFIED",
                                "targetLocationId",
                                returnLocation,
                                "reason",
                                "实际余料退回"),
                        both("recorder", "wms:production:report"),
                        200);
        postAs(
                "/api/v1/wms/production/returns/" + ret.get("id").asLong() + "/confirm",
                Map.of(),
                admin(),
                200);
        long first = finished("6"), second = finished("4");
        var before = closure();
        assertThat(before.get("canClose").asBoolean()).isTrue();
        var closed =
                postAs(
                        MFG + "/orders/" + orderId + "/closure",
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "version",
                                before.get("orderVersion").asLong(),
                                "factHash",
                                before.get("factHash").asText(),
                                "reason",
                                "同源事实已核对",
                                "varianceReason",
                                "19消耗与1件实物退回"),
                        mfg("closer", "review"),
                        200);
        String snapshot = closed.get("closure").get("snapshot").asText();
        for (long finishedId : List.of(first, second)) {
            var stock =
                    db.queryForMap(
                            "SELECT stored_balance_id,batch_no,quantity FROM wms_finished_receipt WHERE id=?",
                            finishedId);
            sale =
                    postAs(
                            SALES + "/" + sale.get("id").asLong() + "/arrangements",
                            Map.of(
                                    "idempotencyKey",
                                    key(),
                                    "version",
                                    sale.get("version").asLong(),
                                    "orderLineId",
                                    saleLine,
                                    "quantity",
                                    stock.get("quantity"),
                                    "expectedDate",
                                    "2026-12-01",
                                    "reason",
                                    "发出对应工单成品"),
                            sales("seller", "write"),
                            200);
            JsonNode ar = null;
            for (var row : sale.get("arrangements"))
                if (row.get("status").asText().equals("PENDING")) ar = row;
            assertThat(ar).isNotNull();
            long arrangement = ar.get("id").asLong();
            sale =
                    postAs(
                            SALES
                                    + "/"
                                    + sale.get("id").asLong()
                                    + "/arrangements/"
                                    + arrangement
                                    + "/deliver",
                            command(sale),
                            sales("seller", "write"),
                            200);
            for (var row : sale.get("arrangements"))
                if (row.get("id").asLong() == arrangement) ar = row;
            String wp = "/api/v1/wms/sales-orders/" + ar.get("wms_sales_id").asLong();
            postAs(
                    wp + "/reserve",
                    Map.of("balanceId", stock.get("stored_balance_id")),
                    user("shipper").roles("ADMIN"),
                    200);
            var pick =
                    postAs(
                            wp + "/pick",
                            Map.of(
                                    "requestKey",
                                    key(),
                                    "batchNo",
                                    stock.get("batch_no"),
                                    "quantity",
                                    stock.get("quantity")),
                            user("shipper").roles("ADMIN"),
                            200);
            postAs(
                    wp + "/review",
                    Map.of(
                            "requestKey",
                            key(),
                            "pickId",
                            pick.get("pick_id").asLong(),
                            "approved",
                            true,
                            "reason",
                            "异人核对"),
                    user("shipping-checker").roles("ADMIN"),
                    200);
            postAs(wp + "/ship", Map.of(), user("shipper").roles("ADMIN"), 200);
            postAs(wp + "/ship", Map.of(), user("shipper").roles("ADMIN"), 200);
            assertThat(stock(((Number) stock.get("stored_balance_id")).longValue(), "on_hand_qty"))
                    .isZero();
        }
        sale = salesAction(sale, "close", "seller", "write");
        assertThat(sale.get("closureMatches").asBoolean()).isTrue();
        var after = closure();
        assertThat(after.get("closureMatches").asBoolean()).isTrue();
        assertThat(after.get("factHash")).isEqualTo(before.get("factHash"));
        assertThat(after.get("closure").get("snapshot").asText()).isEqualTo(snapshot);
        assertThat(getOrder(purchase.get("id").asLong()).get("closureMatches").asBoolean())
                .isTrue();
        number(after.get("facts").get("materials").get(0), "consumed", "19");
        number(after.get("facts").get("materials").get(0), "returned", "1");
        assertThat(stock(replenished, "on_hand_qty")).isEqualByComparingTo("1");
        for (var audit :
                db.queryForList(
                        java.nio.file.Files.readString(
                                java.nio.file.Path.of("../../scripts/audit-inventory.sql"))))
            assertThat(((Number) audit.get("mismatch_count")).longValue())
                    .as(audit.get("check_name").toString())
                    .isZero();
    }
}
