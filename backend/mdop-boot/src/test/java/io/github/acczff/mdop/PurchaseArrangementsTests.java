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
class PurchaseArrangementsTests extends InventoryScenarioSupport {
    static final String URL = "/api/v1/purchasing/documents";

    @Test
    void maximumReasonIsPreservedThroughArrangementDeliveryAndWithdrawal() throws Exception {
        var d = order();
        String reason = "验".repeat(500);
        var input = new HashMap<>(arrangement(d, "1"));
        input.put("reason", reason);
        d =
                postAs(
                        URL + "/" + d.get("id").asLong() + "/arrangements",
                        input,
                        operator("buyer", "purchasing:write"),
                        200);
        long a = d.get("arrangements").get(0).get("id").asLong();
        for (String operation : List.of("deliver", "withdraw")) {
            var body = new HashMap<>(command(d));
            body.put("reason", reason);
            d = postAs(path(d, a, operation), body, operator("buyer", "purchasing:write"), 200);
            assertThat(d.get("history").get(0).get("reason").asText()).isEqualTo(reason);
        }
    }

    JsonNode order() throws Exception {
        var d =
                postAs(
                        URL,
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "warehouseId",
                                warehouse,
                                "purpose",
                                "采购到货验收",
                                "neededDate",
                                "2026-11-01",
                                "lines",
                                List.of(Map.of("materialId", material, "quantity", "100"))),
                        operator("buyer", "purchasing:write"),
                        201);
        d = action(d, "submit", "buyer", "purchasing:write", 200);
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
        d = action(d, "submit", "buyer", "purchasing:write", 200);
        return action(d, "approve", "reviewer", "purchasing:review", 200);
    }

    Map<String, Object> command(JsonNode d) {
        return Map.of(
                "idempotencyKey", key(), "version", d.get("version").asLong(), "reason", "验收操作");
    }

    JsonNode action(JsonNode d, String action, String user, String permission, int expected)
            throws Exception {
        return postAs(
                URL + "/" + d.get("id").asLong() + "/actions/" + action,
                command(d),
                operator(user, permission),
                expected);
    }

    JsonNode getOrder(long id) throws Exception {
        return json.readTree(
                mvc.perform(get(URL + "/" + id).with(operator("reader", "purchasing:read")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    Map<String, Object> arrangement(JsonNode d, String qty) {
        return Map.of(
                "idempotencyKey",
                key(),
                "version",
                d.get("version").asLong(),
                "expectedDate",
                "2026-11-02",
                "reason",
                "分批到货",
                "lines",
                List.of(
                        Map.of(
                                "orderLineId",
                                d.get("lines").get(0).get("id").asLong(),
                                "quantity",
                                qty)));
    }

    JsonNode arrange(JsonNode d, String qty, int expected) throws Exception {
        return postAs(
                URL + "/" + d.get("id").asLong() + "/arrangements",
                arrangement(d, qty),
                operator("buyer", "purchasing:write"),
                expected);
    }

    String path(JsonNode d, long a, String action) {
        return URL + "/" + d.get("id").asLong() + "/arrangements/" + a + "/" + action;
    }

    JsonNode perform(JsonNode d, long a, String action, int expected) throws Exception {
        return postAs(
                path(d, a, action), command(d), operator("buyer", "purchasing:write"), expected);
    }

    JsonNode draft(JsonNode a, String qty, int expected) throws Exception {
        return postAs(
                "/api/v1/wms/arrival-notices/" + a.get("wms_arrival_id").asLong() + "/receipts",
                Map.of(
                        "idempotencyKey",
                        key(),
                        "items",
                        List.of(
                                Map.of(
                                        "arrivalItemId",
                                        a.get("lines").get(0).get("wms_arrival_item_id").asLong(),
                                        "locationId",
                                        location,
                                        "quantity",
                                        qty))),
                operator("warehouse", "wms:receipt:create"),
                expected);
    }

    @Test
    void sixtyPlusFortyFlowsThroughActualReceivingWithoutMoreAuthorization() throws Exception {
        var d = arrange(order(), "60", 200);
        long first = d.get("arrangements").get(0).get("id").asLong();
        d = arrange(d, "40", 200);
        long second = d.get("arrangements").get(0).get("id").asLong();
        arrange(d, "0.000001", 409);
        d = perform(d, first, "deliver", 200);
        d = perform(d, second, "deliver", 200);
        assertThat(d.get("status").asText()).isEqualTo("FULFILLING");
        for (var a : d.get("arrangements")) {
            var receipt = draft(a, a.get("lines").get(0).get("quantity").asText(), 201);
            postAs(
                    "/api/v1/wms/receipts/" + receipt.get("receipt").get("id").asLong() + "/submit",
                    Map.of("idempotencyKey", key(), "version", 0),
                    operator("warehouse", "wms:receipt:submit"),
                    200);
        }
        d = getOrder(d.get("id").asLong());
        for (var a : d.get("arrangements")) {
            assertThat(a.get("wms").get("status").asText()).isEqualTo("RECEIVED");
            assertThat(a.get("wms").get("lines").get(0).get("receivedQty").asText())
                    .isEqualTo(a.get("lines").get(0).get("quantity").asText());
            perform(d, a.get("id").asLong(), "withdraw", 409);
        }
        action(d, "cancel", "buyer", "purchasing:write", 409);
        assertThat(
                        db.queryForObject(
                                "SELECT SUM(on_hand_qty) FROM wms_inventory_balance WHERE warehouse_id=?",
                                java.math.BigDecimal.class,
                                warehouse))
                .isEqualByComparingTo("100");
    }

    @Test
    void pendingWithdrawalReleasesQuotaAndDeliveredWithdrawalBlocksFutureDrafts() throws Exception {
        var d = arrange(order(), "100", 200);
        long a = d.get("arrangements").get(0).get("id").asLong();
        action(d, "cancel", "buyer", "purchasing:write", 409);
        d = perform(d, a, "withdraw", 200);
        d = arrange(d, "100", 200);
        a = d.get("arrangements").get(0).get("id").asLong();
        d = perform(d, a, "deliver", 200);
        var delivered = d.get("arrangements").get(0);
        d = perform(d, a, "withdraw", 200);
        draft(delivered, "1", 409);
        assertThat(d.get("arrangements").get(0).get("wms").get("status").asText())
                .isEqualTo("WITHDRAWN");
        d = action(d, "cancel", "buyer", "purchasing:write", 200);
        assertThat(getOrder(d.get("request_id").asLong()).get("status").asText())
                .isEqualTo("APPROVED");
    }

    @Test
    void failedDeliveryRetainsQuotaAndSameRequestCanRetryAfterCatalogRecovery() throws Exception {
        var d = arrange(order(), "100", 200);
        long a = d.get("arrangements").get(0).get("id").asLong();
        var body = command(d);
        String path = path(d, a, "deliver");
        db.update("UPDATE mdm_material SET status='DISABLED' WHERE id=?", material);
        postAs(path, body, operator("buyer", "purchasing:write"), 409);
        d = getOrder(d.get("id").asLong());
        assertThat(d.get("arrangements").get(0).get("last_error").asText()).isNotBlank();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_arrival_notice WHERE purchase_arrangement_id=?",
                                Long.class,
                                a))
                .isZero();
        action(d, "cancel", "buyer", "purchasing:write", 409);
        db.update("UPDATE mdm_material SET status='ENABLED',name='新名称' WHERE id=?", material);
        d = postAs(path, body, operator("buyer", "purchasing:write"), 200);
        var replay = postAs(path, body, operator("buyer", "purchasing:write"), 200);
        assertThat(replay.get("arrangements").get(0).get("wms_arrival_id"))
                .isEqualTo(d.get("arrangements").get(0).get("wms_arrival_id"));
        assertThat(
                        db.queryForObject(
                                "SELECT material_name FROM wms_arrival_notice_item WHERE purchase_arrangement_line_id=?",
                                String.class,
                                d.get("arrangements")
                                        .get(0)
                                        .get("lines")
                                        .get(0)
                                        .get("id")
                                        .asLong()))
                .isEqualTo("物料");
    }

    @Test
    void concurrentArrangementsAndDeliveryNeverExceedOrDuplicate() throws Exception {
        var d = order();
        String path = URL + "/" + d.get("id").asLong() + "/arrangements";
        var body = arrangement(d, "60");
        var another = arrangement(d, "60");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var x =
                    pool.submit(
                            () -> {
                                gate.await();
                                return raw(path, body, "purchasing:write");
                            });
            var y =
                    pool.submit(
                            () -> {
                                gate.await();
                                return raw(path, another, "purchasing:write");
                            });
            gate.countDown();
            assertThat(List.of(x.get(20, TimeUnit.SECONDS), y.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        d = getOrder(d.get("id").asLong());
        long a = d.get("arrangements").get(0).get("id").asLong();
        String deliver = path(d, a, "deliver");
        var input = command(d);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var x = pool.submit(() -> raw(deliver, input, "purchasing:write"));
            var y = pool.submit(() -> raw(deliver, input, "purchasing:write"));
            assertThat(x.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(y.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_arrival_notice WHERE purchase_arrangement_id=?",
                                Long.class,
                                a))
                .isEqualTo(1);
    }

    int raw(String path, Object body, String permission) throws Exception {
        return mvc.perform(
                        post(path)
                                .with(operator("buyer", permission))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(body)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void withdrawalAndDraftCreationRaceCannotBothSucceed() throws Exception {
        var d = arrange(order(), "60", 200);
        long a = d.get("arrangements").get(0).get("id").asLong();
        d = perform(d, a, "deliver", 200);
        var notice = d.get("arrangements").get(0);
        String withdrawal = path(d, a, "withdraw");
        var input = command(d);
        var receiptBody =
                Map.of(
                        "idempotencyKey",
                        key(),
                        "items",
                        List.of(
                                Map.of(
                                        "arrivalItemId",
                                        notice.get("lines")
                                                .get(0)
                                                .get("wms_arrival_item_id")
                                                .asLong(),
                                        "locationId",
                                        location,
                                        "quantity",
                                        "1")));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var x =
                    pool.submit(
                            () -> {
                                gate.await();
                                return raw(withdrawal, input, "purchasing:write");
                            });
            var y =
                    pool.submit(
                            () -> {
                                gate.await();
                                return raw(
                                        "/api/v1/wms/arrival-notices/"
                                                + notice.get("wms_arrival_id").asLong()
                                                + "/receipts",
                                        receiptBody,
                                        "wms:receipt:create");
                            });
            gate.countDown();
            int withdraw = x.get(20, TimeUnit.SECONDS), receive = y.get(20, TimeUnit.SECONDS);
            assertThat((withdraw == 200 && receive == 409) || (withdraw == 409 && receive == 201))
                    .isTrue();
        }
    }

    @Test
    void draftBlocksWithdrawalAndMissingPermissionOrForeignLinesAreRejected() throws Exception {
        var d = order();
        var body = arrangement(d, "1");
        postAs(
                URL + "/" + d.get("id").asLong() + "/arrangements",
                body,
                operator("reader", "purchasing:read"),
                403);
        var foreign = new HashMap<>(body);
        foreign.put("lines", List.of(Map.of("orderLineId", 999999, "quantity", "1")));
        postAs(
                URL + "/" + d.get("id").asLong() + "/arrangements",
                foreign,
                operator("buyer", "purchasing:write"),
                400);
        d = arrange(d, "1", 200);
        long a = d.get("arrangements").get(0).get("id").asLong();
        d = perform(d, a, "deliver", 200);
        draft(d.get("arrangements").get(0), "1", 201);
        perform(d, a, "withdraw", 409);
        assertThat(getOrder(d.get("id").asLong()).get("arrangements").get(0).get("status").asText())
                .isEqualTo("DELIVERED");
    }
}
