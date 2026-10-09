package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.acczff.mdop.system.identity.PermissionCatalog;
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
class PurchasingTests extends InventoryScenarioSupport {
    static final String URL = "/api/v1/purchasing/documents";

    Map<String, Object> draft(String requestKey) {
        return new HashMap<>(
                Map.of(
                        "idempotencyKey",
                        requestKey,
                        "warehouseId",
                        warehouse,
                        "purpose",
                        "生产备料",
                        "neededDate",
                        "2026-10-31",
                        "lines",
                        List.of(
                                Map.of("materialId", material, "quantity", "12.123456"),
                                Map.of("materialId", material, "quantity", "0.000001"))));
    }

    JsonNode create() throws Exception {
        return postAs(URL, draft(key()), operator("buyer", "purchasing:write"), 201);
    }

    JsonNode action(JsonNode d, String action, String name, String permission, int expected)
            throws Exception {
        return postAs(
                URL + "/" + d.get("id").asLong() + "/actions/" + action,
                Map.of(
                        "idempotencyKey",
                        key(),
                        "version",
                        d.get("version").asLong(),
                        "reason",
                        "验收操作"),
                operator(name, permission),
                expected);
    }

    JsonNode approved() throws Exception {
        return action(
                action(create(), "submit", "buyer", "purchasing:write", 200),
                "approve",
                "reviewer",
                "purchasing:review",
                200);
    }

    Map<String, Object> conversion(JsonNode d) {
        return Map.of(
                "idempotencyKey",
                key(),
                "version",
                d.get("version").asLong(),
                "supplierId",
                supplier,
                "neededDate",
                "2026-11-01",
                "reason",
                "按批准需求采购");
    }

    JsonNode getDocument(long id) throws Exception {
        return json.readTree(
                mvc.perform(get(URL + "/" + id).with(operator("reader", "purchasing:read")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    @Test
    void completeRequestOrderApprovalAndCancellationPreserveQuantitiesAndAudit() throws Exception {
        var req = approved();
        assertThat(req.get("needed_date").asText()).isEqualTo("2026-10-31");
        assertThat(req.get("document_no").asText()).matches("PR-[0-9]{8,}");
        mvc.perform(
                        get(URL).param("warehouseId", String.valueOf(warehouse))
                                .param("kind", "REQUEST")
                                .with(operator("reader", "purchasing:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].needed_date").value("2026-10-31"));
        assertThat(req.get("lines").size()).isEqualTo(1);
        assertThat(req.get("lines").get(0).get("quantity").asText()).isEqualTo("12.123457");
        var input = conversion(req);
        String convert = URL + "/" + req.get("id").asLong() + "/convert";
        var order = postAs(convert, input, operator("buyer", "purchasing:write"), 200);
        var again = postAs(convert, input, operator("buyer", "purchasing:write"), 200);
        assertThat(again.get("id")).isEqualTo(order.get("id"));
        assertThat(order.get("lines").get(0).get("source_line_id"))
                .isEqualTo(req.get("lines").get(0).get("id"));
        order = action(order, "submit", "buyer", "purchasing:write", 200);
        action(order, "approve", "buyer", "purchasing:review", 409);
        order = action(order, "approve", "reviewer", "purchasing:review", 200);
        assertThat(order.get("status").asText()).isEqualTo("APPROVED");
        order = action(order, "cancel", "buyer", "purchasing:write", 200);
        assertThat(order.get("status").asText()).isEqualTo("CANCELLED");
        req = getDocument(req.get("id").asLong());
        assertThat(req.get("status").asText()).isEqualTo("APPROVED");
        var newOrder = postAs(convert, conversion(req), operator("buyer", "purchasing:write"), 200);
        assertThat(newOrder.get("id")).isNotEqualTo(order.get("id"));
        assertThat(
                        postAs(convert, input, operator("buyer", "purchasing:write"), 200)
                                .get("status")
                                .asText())
                .isEqualTo("CANCELLED");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_balance WHERE warehouse_id=?",
                                Long.class,
                                warehouse))
                .isZero();
        assertThat(order.get("history").size()).isEqualTo(4);
    }

    @Test
    void approvalRejectsUnitDriftWithoutChangingSnapshotOrStatus() throws Exception {
        var req = action(create(), "submit", "buyer", "purchasing:write", 200);
        var original = req.get("lines").get(0).get("unit").asText();
        db.update("UPDATE mdm_material SET unit=? WHERE id=?", "变化后的单位", material);
        action(req, "approve", "reviewer", "purchasing:review", 409);
        var unchanged = getDocument(req.get("id").asLong());
        assertThat(unchanged.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(unchanged.get("lines").get(0).get("unit").asText()).isEqualTo(original);
        action(req, "reject", "reviewer", "purchasing:review", 200);
    }

    @Test
    void rejectionAllowsEditingButSubmissionVersionAndOriginalApplicantRemainProtected()
            throws Exception {
        var req = action(create(), "submit", "buyer", "purchasing:write", 200);
        action(req, "approve", "buyer", "purchasing:review", 409);
        req = action(req, "reject", "reviewer", "purchasing:review", 200);
        var body = draft(key());
        body.put("version", req.get("version").asLong());
        body.put("purpose", "更正用途");
        body.put("reason", "修正需求");
        long id = req.get("id").asLong();
        req =
                json.readTree(
                        mvc.perform(
                                        put(URL + "/" + id)
                                                .with(operator("editor", "purchasing:write"))
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(json.writeValueAsString(body)))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        req = action(req, "submit", "submitter", "purchasing:write", 200);
        for (String name : List.of("buyer", "editor", "submitter"))
            action(req, "approve", name, "purchasing:review", 409);
        action(req, "approve", "reviewer", "purchasing:review", 200);
        action(req, "cancel", "buyer", "purchasing:write", 409);
    }

    @Test
    void concurrentConversionAndRequestReplayCreateExactlyOneResult() throws Exception {
        var req = approved();
        var body = conversion(req);
        String path = URL + "/" + req.get("id").asLong() + "/convert";
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Long> call =
                    () -> {
                        gate.await();
                        return postAs(path, body, operator("buyer", "purchasing:write"), 200)
                                .get("id")
                                .asLong();
                    };
            var first = pool.submit(call);
            var second = pool.submit(call);
            gate.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM pur_document WHERE request_id=?",
                                Long.class,
                                req.get("id").asLong()))
                .isEqualTo(1);
        postAs(path, conversion(req), operator("buyer", "purchasing:write"), 409);
        var changed = new HashMap<>(body);
        changed.put("reason", "另一种载荷");
        postAs(path, changed, operator("buyer", "purchasing:write"), 409);
        action(getDocument(req.get("id").asLong()), "cancel", "buyer", "purchasing:write", 409);
    }

    @Test
    void disabledDataAndOverflowRollbackWithoutOrphanDocumentsOrCommands() throws Exception {
        var body = draft(key());
        body.put(
                "lines",
                List.of(
                        Map.of("materialId", material, "quantity", "999999999999.999999"),
                        Map.of("materialId", material, "quantity", "1")));
        postAs(URL, body, operator("buyer", "purchasing:write"), 400);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM pur_document WHERE warehouse_id=?",
                                Long.class,
                                warehouse))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM pur_command WHERE request_key=?",
                                Long.class,
                                body.get("idempotencyKey")))
                .isZero();
        var req = action(create(), "submit", "buyer", "purchasing:write", 200);
        db.update("UPDATE mdm_material SET status='DISABLED' WHERE id=?", material);
        action(req, "approve", "reviewer", "purchasing:review", 409);
        assertThat(getDocument(req.get("id").asLong()).get("status").asText())
                .isEqualTo("SUBMITTED");
        action(req, "reject", "reviewer", "purchasing:review", 200);
    }

    @Test
    void approvedRequestSnapshotDoesNotChangeWhenCatalogIsRenamedAndOrderCannotChangeQuantity()
            throws Exception {
        var req = approved();
        db.update("UPDATE mdm_material SET name='新名称' WHERE id=?", material);
        var order =
                postAs(
                        URL + "/" + req.get("id").asLong() + "/convert",
                        conversion(req),
                        operator("buyer", "purchasing:write"),
                        200);
        assertThat(order.get("lines").get(0).get("material_name").asText())
                .isEqualTo(req.get("lines").get(0).get("material_name").asText());
        var body = draft(key());
        body.put("version", 0);
        body.put("supplierId", supplier);
        body.put("reason", "改动数量");
        body.put("lines", List.of(Map.of("materialId", material, "quantity", "1")));
        mvc.perform(
                        put(URL + "/" + order.get("id").asLong())
                                .with(operator("buyer", "purchasing:write"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(body)))
                .andExpect(status().isConflict());
        db.update("UPDATE mdm_supplier SET status='DISABLED' WHERE id=?", supplier);
        action(order, "submit", "buyer", "purchasing:write", 409);
        action(order, "cancel", "buyer", "purchasing:write", 200);
    }

    @Test
    void permissionsWarehouseScopeCsrfAndRolesCannotGrantAccidentalPurchasingAuthority()
            throws Exception {
        var req = create();
        mvc.perform(
                        get(URL + "/" + req.get("id").asLong())
                                .with(
                                        user("other")
                                                .authorities(
                                                        new org.springframework.security.core
                                                                .authority.SimpleGrantedAuthority(
                                                                "purchasing:read"))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(URL)
                                .with(operator("buyer", "purchasing:write"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(draft(key()))))
                .andExpect(status().isForbidden());
        postAs(URL, draft(key()), admin(), 403);
        postAs(URL, draft(key()), operator("reader", "purchasing:read"), 403);
        action(req, "approve", "buyer", "purchasing:write", 403);
        assertThat(PermissionCatalog.resolve(Set.of("WAREHOUSE_OPERATOR"), Set.of(warehouse)))
                .doesNotContain("purchasing:write", "purchasing:review");
        assertThatThrownBy(
                        () ->
                                PermissionCatalog.resolve(
                                        Set.of("PURCHASE_OPERATOR", "PURCHASE_REVIEWER"),
                                        Set.of(warehouse)))
                .hasMessageContaining("不同账号");
        assertThat(PermissionCatalog.resolve(Set.of("READER"), Set.of(warehouse)))
                .contains("purchasing:read");
    }

    @Test
    void newRequestRetriesDoNotDuplicateAndDifferentBodySameKeyConflicts() throws Exception {
        var body = draft(key());
        var first = postAs(URL, body, operator("buyer", "purchasing:write"), 201);
        var second = postAs(URL, body, operator("buyer", "purchasing:write"), 201);
        assertThat(first.get("id")).isEqualTo(second.get("id"));
        body.put("purpose", "不同用途");
        postAs(URL, body, operator("buyer", "purchasing:write"), 409);
        assertThat(getDocument(first.get("id").asLong()).get("history").size()).isEqualTo(1);
    }
}
