package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;

abstract class PurchaseScenarioSupport extends InventoryScenarioSupport {
    static final String URL = "/api/v1/purchasing/documents";

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
}
