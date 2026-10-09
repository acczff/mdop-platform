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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseFulfillmentTests extends PurchaseScenarioSupport {
    JsonNode delivered() throws Exception {
        var d = arrange(order(), "100", 200);
        return perform(d, d.get("arrangements").get(0).get("id").asLong(), "deliver", 200);
    }

    JsonNode receive(JsonNode d, String qty) throws Exception {
        var receipt = draft(d.get("arrangements").get(0), qty, 201);
        return postAs(
                "/api/v1/wms/receipts/" + receipt.get("receipt").get("id").asLong() + "/submit",
                Map.of("idempotencyKey", key(), "version", 0),
                operator("warehouse", "wms:receipt:submit"),
                200);
    }

    long inspect(JsonNode receipt, int good, int bad) throws Exception {
        long id = receipt.get("receipt").get("id").asLong();
        long item =
                db.queryForObject(
                        "SELECT id FROM wms_receipt_item WHERE receipt_id=?", Long.class, id);
        postAs(
                "/api/local/qms-results",
                Map.of(
                        "idempotencyKey",
                        key(),
                        "receiptId",
                        id,
                        "version",
                        1,
                        "referenceNo",
                        key(),
                        "reason",
                        "采购验收",
                        "items",
                        List.of(
                                Map.of(
                                        "receiptItemId",
                                        item,
                                        "qualifiedQty",
                                        good,
                                        "rejectedQty",
                                        bad))),
                admin(),
                200);
        return item;
    }

    void putaway(long item) throws Exception {
        long storage =
                postAs(
                                "/api/master-data/locations",
                                Map.of(
                                        "warehouseId",
                                        warehouse,
                                        "code",
                                        "S" + key().substring(0, 8),
                                        "name",
                                        "正式库位",
                                        "areaType",
                                        "STORAGE"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        postAs(
                "/api/v1/wms/quality/items/" + item + "/putaway",
                Map.of("idempotencyKey", key(), "locationId", storage),
                operator("warehouse", "wms:putaway:confirm"),
                200);
    }

    JsonNode close(JsonNode d, int expected) throws Exception {
        return action(
                getOrder(d.get("id").asLong()), "close", "buyer", "purchasing:write", expected);
    }

    JsonNode quantities(JsonNode d) throws Exception {
        return getOrder(d.get("id").asLong()).get("fulfillment").get("lines").get(0);
    }

    @Test
    void partialInspectionPutawayAndStockConsumptionHaveDistinctMeaning() throws Exception {
        var d = delivered();
        close(d, 409);
        var first = receive(d, "60");
        assertThat(quantities(d).get("remaining").asText()).isEqualTo("40.000000");
        assertThat(quantities(d).get("pendingInspection").asText()).isEqualTo("60.000000");
        close(d, 409);
        long item = inspect(first, 60, 0);
        assertThat(quantities(d).get("pendingPutaway").asText()).isEqualTo("60.000000");
        close(d, 409);
        putaway(item);
        putaway(inspect(receive(d, "40"), 40, 0));
        // Simulate stock having been consumed after putaway: procurement uses source facts.
        db.update(
                "UPDATE wms_inventory_balance SET on_hand_qty=0,available_qty=0 WHERE warehouse_id=?",
                warehouse);
        var closed = close(d, 200);
        assertThat(closed.get("closure").get("outcome").asText()).isEqualTo("QUALIFIED");
        assertThat(closed.get("closureMatches").asBoolean()).isTrue();
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        arrange(closed, "1", 409);
        action(closed, "cancel", "buyer", "purchasing:write", 409);
    }

    @Test
    void rejectedQuantityRequiresActualHandoverAndNeverReopensAuthorization() throws Exception {
        var d = delivered();
        long item = inspect(receive(d, "100"), 80, 20);
        putaway(item);
        close(d, 409);
        var ret =
                postAs(
                        "/api/v1/wms/purchase-returns",
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "receiptItemId",
                                item,
                                "quantity",
                                20,
                                "reason",
                                "不合格退供"),
                        operator("warehouse", "wms:return:create"),
                        201);
        long id = ret.get("id").asLong();
        postAs(
                "/api/v1/wms/purchase-returns/" + id + "/decision",
                Map.of(
                        "idempotencyKey",
                        key(),
                        "version",
                        0,
                        "decision",
                        "APPROVE",
                        "reason",
                        "同意退供"),
                operator("reviewer", "wms:return:approve"),
                200);
        assertThat(quantities(d).get("rejectedPendingReturn").asText()).isEqualTo("20.000000");
        close(d, 409);
        var body = Map.of("idempotencyKey", key(), "version", 1, "handoverNo", "HANDOVER-" + key());
        var replacement = replacement(d.get("id").asLong());
        postAs(URL, replacement, operator("buyer", "purchasing:write"), 409);
        postAs(
                "/api/v1/wms/purchase-returns/" + id + "/confirm",
                body,
                operator("warehouse", "wms:return:confirm"),
                200);
        postAs(
                "/api/v1/wms/purchase-returns/" + id + "/confirm",
                body,
                operator("warehouse", "wms:return:confirm"),
                200);
        assertThat(quantities(d).get("netReceived").asText()).isEqualTo("100.000000");
        assertThat(quantities(d).get("returned").asText()).isEqualTo("20.000000");
        arrange(getOrder(d.get("id").asLong()), "1", 409);
        assertThat(close(d, 200).get("closure").get("outcome").asText()).isEqualTo("WITH_RETURNS");
        var original = getOrder(d.get("id").asLong());
        var request = postAs(URL, replacement, operator("buyer", "purchasing:write"), 201);
        var replay = postAs(URL, replacement, operator("buyer", "purchasing:write"), 201);
        assertThat(replay.get("id")).isEqualTo(request.get("id"));
        assertThat(request.get("original_order_id").asLong()).isEqualTo(d.get("id").asLong());
        assertThat(request.get("status").asText()).isEqualTo("DRAFT");
        assertThat(request.get("related").toString()).contains(d.get("document_no").asText());
        assertThat(getOrder(d.get("id").asLong()).get("related").toString())
                .contains(request.get("document_no").asText());
        var edit = new HashMap<String, Object>(replacement);
        edit.put("idempotencyKey", key());
        edit.put("version", 0);
        edit.put("reason", "保留来源修改用途");
        edit.put("originalOrderId", request.get("id").asLong());
        mvc.perform(
                        put(URL + "/" + request.get("id").asLong())
                                .with(operator("buyer", "purchasing:write"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(edit)))
                .andExpect(status().isConflict());
        edit.remove("originalOrderId"); // Older clients must not clear the immutable reference.
        mvc.perform(
                        put(URL + "/" + request.get("id").asLong())
                                .with(operator("buyer", "purchasing:write"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(edit)))
                .andExpect(status().isOk());
        request = getOrder(request.get("id").asLong());
        assertThat(request.get("original_order_id").asLong()).isEqualTo(d.get("id").asLong());
        request = action(request, "submit", "buyer", "purchasing:write", 200);
        action(request, "approve", "buyer", "purchasing:review", 409);
        request = action(request, "approve", "reviewer", "purchasing:review", 200);
        var converted =
                postAs(
                        URL + "/" + request.get("id").asLong() + "/convert",
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "version",
                                request.get("version").asLong(),
                                "supplierId",
                                supplier,
                                "neededDate",
                                "2026-11-03",
                                "reason",
                                "独立审批补货"),
                        operator("buyer", "purchasing:write"),
                        200);
        assertThat(converted.get("original_order_id").asLong()).isEqualTo(d.get("id").asLong());
        assertThat(converted.get("status").asText()).isEqualTo("DRAFT");
        assertThat(getOrder(d.get("id").asLong()).get("closure"))
                .isEqualTo(original.get("closure"));
        assertThat(getOrder(d.get("id").asLong()).get("version"))
                .isEqualTo(original.get("version"));
    }

    Map<String, Object> replacement(long original) {
        return Map.of(
                "idempotencyKey",
                key(),
                "warehouseId",
                warehouse,
                "originalOrderId",
                original,
                "purpose",
                "退供后人工补货",
                "neededDate",
                "2026-11-03",
                "lines",
                List.of(Map.of("materialId", material, "quantity", "20")));
    }

    @Test
    void replacementRejectsInvalidSourceAndCrossWarehouseReferences() throws Exception {
        var d = order();
        postAs(URL, replacement(d.get("id").asLong()), operator("buyer", "purchasing:write"), 409);
        postAs(
                URL,
                replacement(d.get("request_id").asLong()),
                operator("buyer", "purchasing:write"),
                409);
        postAs(URL, replacement(Long.MAX_VALUE), operator("buyer", "purchasing:write"), 404);
        postAs(URL, replacement(d.get("id").asLong()), operator("reader", "purchasing:read"), 403);
        long other = warehouse;
        setup(); // New authorized target warehouse; original belongs to the previous scope.
        postAs(URL, replacement(d.get("id").asLong()), operator("buyer", "purchasing:write"), 403);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM pur_document WHERE original_order_id=?",
                                Long.class,
                                d.get("id").asLong()))
                .isZero();
        assertThat(other).isNotEqualTo(warehouse);
    }

    @Test
    void reversalSubtractsOnlyOnceAndSameNoticeMayReceiveReplacement() throws Exception {
        var d = delivered();
        var r = receive(d, "20");
        long id = r.get("receipt").get("id").asLong();
        var c =
                postAs(
                        "/api/v1/wms/corrections/reversals",
                        Map.of("idempotencyKey", key(), "receiptId", id, "reason", "录入错误"),
                        operator("warehouse", "wms:correction:create"),
                        201);
        var body =
                Map.of(
                        "idempotencyKey",
                        key(),
                        "version",
                        0,
                        "decision",
                        "APPROVE",
                        "reason",
                        "批准冲正");
        String url = "/api/v1/wms/corrections/" + c.get("id").asLong() + "/decision";
        postAs(url, body, operator("reviewer", "wms:correction:approve"), 200);
        postAs(url, body, operator("reviewer", "wms:correction:approve"), 200);
        assertThat(quantities(d).get("netReceived").asText()).isEqualTo("0.000000");
        assertThat(quantities(d).get("reversed").asText()).isEqualTo("20.000000");
        close(d, 409);
        putaway(inspect(receive(d, "100"), 100, 0));
        assertThat(close(d, 200).get("closureMatches").asBoolean()).isTrue();
    }

    JsonNode difference(JsonNode d) throws Exception {
        var a = d.get("arrangements").get(0);
        return postAs(
                "/api/v1/wms/corrections/differences",
                Map.of(
                        "idempotencyKey",
                        key(),
                        "arrivalId",
                        a.get("wms_arrival_id").asLong(),
                        "arrivalItemId",
                        a.get("lines").get(0).get("wms_arrival_item_id").asLong(),
                        "type",
                        "SHORT",
                        "quantity",
                        1,
                        "reason",
                        "现场待核对"),
                operator("warehouse", "wms:correction:create"),
                201);
    }

    @Test
    void pendingDifferenceBlocksCloseAndLateFactsPreserveOriginalSnapshot() throws Exception {
        var d = delivered();
        putaway(inspect(receive(d, "100"), 100, 0));
        var c = difference(d);
        close(d, 409);
        postAs(
                "/api/v1/wms/corrections/" + c.get("id").asLong() + "/decision",
                Map.of(
                        "idempotencyKey",
                        key(),
                        "version",
                        0,
                        "decision",
                        "REJECT",
                        "reason",
                        "核对已收齐"),
                operator("reviewer", "wms:correction:approve"),
                200);
        var closed = close(d, 200);
        String original = closed.get("closure").get("snapshot").asText();
        var late = difference(d);
        var changed = getOrder(d.get("id").asLong());
        assertThat(changed.get("closureMatches").asBoolean()).isFalse();
        assertThat(changed.get("closure").get("snapshot").asText()).isEqualTo(original);
        assertThat(changed.get("status").asText()).isEqualTo("CLOSED");
        close(d, 409);
        postAs(
                "/api/v1/wms/corrections/" + late.get("id").asLong() + "/decision",
                Map.of(
                        "idempotencyKey",
                        key(),
                        "version",
                        0,
                        "decision",
                        "REJECT",
                        "reason",
                        "现场复核为误报，原实物结果不变"),
                operator("reviewer", "wms:correction:approve"),
                200);
        var checked = getOrder(d.get("id").asLong());
        assertThat(checked.get("closureMatches").asBoolean()).isTrue();
        assertThat(checked.get("closure").get("snapshot").asText()).isEqualTo(original);
    }

    @Test
    void closeIsPermissionScopedVersionedAndIdempotentUnderConcurrency() throws Exception {
        var d = delivered();
        putaway(inspect(receive(d, "100"), 100, 0));
        String path = URL + "/" + d.get("id").asLong() + "/actions/close";
        var body = command(d);
        postAs(path, body, operator("reader", "purchasing:read"), 403);
        postAs(
                path,
                body,
                user("foreign")
                        .authorities(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("purchasing:write")),
                403);
        var stale = new HashMap<>(body);
        stale.put("version", 0);
        postAs(path, stale, operator("buyer", "purchasing:write"), 409);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Integer> send =
                    () -> {
                        gate.await();
                        return mvc.perform(
                                        post(path)
                                                .with(operator("buyer", "purchasing:write"))
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(json.writeValueAsString(body)))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var a = pool.submit(send);
            var b = pool.submit(send);
            gate.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM pur_closure WHERE document_id=?",
                                Integer.class,
                                d.get("id").asLong()))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM pur_audit WHERE document_id=? AND action='CLOSE'",
                                Integer.class,
                                d.get("id").asLong()))
                .isEqualTo(1);
        var changed = new HashMap<>(body);
        changed.put("reason", "改变原载荷");
        postAs(path, changed, operator("buyer", "purchasing:write"), 409);
    }

    @Test
    void mismatchedNoticeTotalsCannotBeClosedAndRecoveryNeedsNoMessageReplay() throws Exception {
        var d = delivered();
        putaway(inspect(receive(d, "100"), 100, 0));
        long item =
                d.get("arrangements")
                        .get(0)
                        .get("lines")
                        .get(0)
                        .get("wms_arrival_item_id")
                        .asLong();
        db.update("UPDATE wms_arrival_notice_item SET received_qty=99 WHERE id=?", item);
        close(d, 409);
        assertThat(getOrder(d.get("id").asLong()).get("fulfillment").get("blockers").toString())
                .contains("通知累计与净收货事实不一致");
        db.update("UPDATE wms_arrival_notice_item SET received_qty=100 WHERE id=?", item);
        close(d, 200);
    }
}
