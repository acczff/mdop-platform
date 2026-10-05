package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.acczff.mdop.test.support.MdopInfrastructureTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReceivingTests extends MdopInfrastructureTestBase {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    long warehouse, location, material, supplier, arrival, line;

    @BeforeEach
    void setup() throws Exception {
        db.execute("DROP TRIGGER IF EXISTS fail_ledger");
        for (String table :
                List.of(
                        "wms_outbox",
                        "wms_receiving_audit",
                        "wms_inventory_transaction",
                        "wms_inventory_balance",
                        "wms_receipt_item",
                        "wms_receipt",
                        "wms_arrival_notice_item",
                        "wms_arrival_notice",
                        "mdm_location",
                        "mdm_material",
                        "mdm_supplier",
                        "mdm_warehouse")) db.update("DELETE FROM " + table);
        warehouse =
                created(
                                "/api/master-data/warehouses",
                                Map.of(
                                        "code",
                                        "WH-RECEIVE",
                                        "name",
                                        "收货仓",
                                        "purpose",
                                        "RAW_MATERIAL",
                                        "form",
                                        "PHYSICAL",
                                        "managementCategory",
                                        "GENERAL"))
                        .get("id")
                        .asLong();
        location =
                created(
                                "/api/master-data/locations",
                                Map.of(
                                        "warehouseId",
                                        warehouse,
                                        "code",
                                        "LOC-01",
                                        "name",
                                        "待检区",
                                        "areaType",
                                        "INSPECTION"))
                        .get("id")
                        .asLong();
        material =
                created(
                                "/api/master-data/materials",
                                Map.of(
                                        "code",
                                        "MAT-01",
                                        "name",
                                        "元器件",
                                        "unit",
                                        "件",
                                        "trackingMode",
                                        "BATCH",
                                        "requireDateCode",
                                        false,
                                        "requireExpiry",
                                        false))
                        .get("id")
                        .asLong();
        supplier =
                created("/api/master-data/suppliers", Map.of("code", "SUP-01", "name", "供应商"))
                        .get("id")
                        .asLong();
        var result =
                created(
                        "/api/local/erp-arrivals",
                        Map.of(
                                "externalNoticeNo",
                                "ERP-01",
                                "purchaseOrderNo",
                                "PO-01",
                                "supplierId",
                                supplier,
                                "warehouseId",
                                warehouse,
                                "items",
                                List.of(Map.of("materialId", material, "quantity", 100))));
        arrival = result.get("arrival").get("id").asLong();
        line = result.get("items").get(0).get("id").asLong();
    }

    @Test
    void partialReceiptAndRetryShouldProduceExactlyOneStockEffect() throws Exception {
        long first = draft(60);
        assertThat(count("wms_inventory_balance")).isZero();
        String key = UUID.randomUUID().toString();
        submit(first, key, 200);
        submit(first, key, 200);
        assertThat(stock()).isEqualByComparingTo("60");
        assertThat(count("wms_inventory_transaction")).isEqualTo(1);
        assertThat(count("wms_outbox")).isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM wms_arrival_notice WHERE id=?",
                                String.class,
                                arrival))
                .isEqualTo("PARTIALLY_RECEIVED");
        submit(draft(40), UUID.randomUUID().toString(), 200);
        assertThat(stock()).isEqualByComparingTo("100");
        assertThat(
                        db.queryForObject(
                                "SELECT SUM(available_qty) FROM wms_inventory_balance",
                                BigDecimal.class))
                .isEqualByComparingTo("0");
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM wms_arrival_notice WHERE id=?",
                                String.class,
                                arrival))
                .isEqualTo("RECEIVED");
        mvc.perform(
                        admin(put("/api/v1/wms/receipts/{id}", first))
                                .content(
                                        json.writeValueAsString(
                                                Map.of("version", 1, "items", List.of(item(1))))))
                .andExpect(status().isConflict());
    }

    @Test
    void quantitiesMustRoundTripWithoutBrowserPrecisionLoss() throws Exception {
        String exact = "999999999999.999999";
        db.update(
                "UPDATE wms_arrival_notice_item SET notice_qty=? WHERE id=?",
                new BigDecimal(exact),
                line);
        var body =
                Map.of(
                        "idempotencyKey",
                        UUID.randomUUID().toString(),
                        "items",
                        List.of(
                                Map.of(
                                        "arrivalItemId",
                                        line,
                                        "locationId",
                                        location,
                                        "quantity",
                                        exact,
                                        "batchNo",
                                        "B-EXACT")));
        var result = created("/api/v1/wms/arrival-notices/" + arrival + "/receipts", body);
        assertThat(result.get("items").get(0).get("quantity").isString()).isTrue();
        assertThat(result.get("items").get(0).get("quantity").asText()).isEqualTo(exact);
        submit(result.get("receipt").get("id").asLong(), UUID.randomUUID().toString(), 200);
        mvc.perform(
                        get("/api/v1/wms/inventory")
                                .param("warehouseId", Long.toString(warehouse))
                                .with(user("receiver").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].onHandQty").value(exact));
        assertThat(stock()).isEqualByComparingTo(exact);
    }

    @Test
    void overReceiptRollsBackAllEffects() throws Exception {
        long id = draft(101);
        submit(id, UUID.randomUUID().toString(), 409);
        assertThat(count("wms_inventory_transaction")).isZero();
        assertThat(count("wms_outbox")).isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT received_qty FROM wms_arrival_notice_item WHERE id=?",
                                BigDecimal.class,
                                line))
                .isEqualByComparingTo("0");
        assertThat(db.queryForObject("SELECT status FROM wms_receipt WHERE id=?", String.class, id))
                .isEqualTo("DRAFT");
    }

    @Test
    void missingBatchWrongLocationAndWrongSourceLineAreRejected() throws Exception {
        var missing = Map.of("arrivalItemId", line, "locationId", location, "quantity", 1);
        mvc.perform(
                        admin(post("/api/v1/wms/arrival-notices/{id}/receipts", arrival))
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString(),
                                                        "items",
                                                        List.of(missing)))))
                .andExpect(status().isBadRequest());
        long storage =
                created(
                                "/api/master-data/locations",
                                Map.of(
                                        "warehouseId",
                                        warehouse,
                                        "code",
                                        "LOC-02",
                                        "name",
                                        "存储区",
                                        "areaType",
                                        "STORAGE"))
                        .get("id")
                        .asLong();
        var wrong =
                Map.of(
                        "arrivalItemId",
                        line,
                        "locationId",
                        storage,
                        "quantity",
                        1,
                        "batchNo",
                        "B-01");
        mvc.perform(
                        admin(post("/api/v1/wms/arrival-notices/{id}/receipts", arrival))
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString(),
                                                        "items",
                                                        List.of(wrong)))))
                .andExpect(status().isConflict());
        var other =
                Map.of(
                        "arrivalItemId",
                        Long.MAX_VALUE,
                        "locationId",
                        location,
                        "quantity",
                        1,
                        "batchNo",
                        "B-01");
        mvc.perform(
                        admin(post("/api/v1/wms/arrival-notices/{id}/receipts", arrival))
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString(),
                                                        "items",
                                                        List.of(other)))))
                .andExpect(status().isBadRequest());
        assertThat(count("wms_receipt")).isZero();
    }

    @Test
    void disabledWarehouseAndUnauthorizedWarehouseCannotReceive() throws Exception {
        long id = draft(1);
        mvc.perform(
                        post("/api/v1/wms/receipts/{id}/submit", id)
                                .with(user("reader"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        0,
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/v1/wms/arrival-notices/{id}", arrival)
                                .with(user("receiver").authorities(() -> "wms:arrival:read")))
                .andExpect(status().isForbidden());
        db.update("UPDATE mdm_warehouse SET status='DISABLED' WHERE id=?", warehouse);
        submit(id, UUID.randomUUID().toString(), 409);
        assertThat(count("wms_inventory_transaction")).isZero();
    }

    @Test
    void draftUpdatesAreVersionedAndIdempotencyCannotBeRepurposed() throws Exception {
        String key = UUID.randomUUID().toString();
        var original = Map.of("idempotencyKey", key, "items", List.of(item(10)));
        long id =
                created("/api/v1/wms/arrival-notices/" + arrival + "/receipts", original)
                        .get("receipt")
                        .get("id")
                        .asLong();
        assertThat(
                        created("/api/v1/wms/arrival-notices/" + arrival + "/receipts", original)
                                .get("receipt")
                                .get("id")
                                .asLong())
                .isEqualTo(id);
        mvc.perform(
                        admin(post("/api/v1/wms/arrival-notices/{id}/receipts", arrival))
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "idempotencyKey",
                                                        key,
                                                        "items",
                                                        List.of(item(11))))))
                .andExpect(status().isConflict());
        mvc.perform(
                        admin(put("/api/v1/wms/receipts/{id}", id))
                                .content(
                                        json.writeValueAsString(
                                                Map.of("version", 0, "items", List.of(item(20))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.version").value(1));
        submit(id, UUID.randomUUID().toString(), 409);
        assertThat(count("wms_inventory_balance")).isZero();
    }

    @Test
    void ledgerFailureRollsBackStockCountsStatusAndOutbox() throws Exception {
        long id = draft(50);
        db.execute(
                "ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_test_fail_ledger CHECK (change_qty < 0)");
        try {
            assertThatThrownBy(() -> submit(id, UUID.randomUUID().toString(), 200))
                    .hasStackTraceContaining("ck_test_fail_ledger");
            assertThat(count("wms_inventory_balance")).isZero();
            assertThat(count("wms_outbox")).isZero();
            assertThat(
                            db.queryForObject(
                                    "SELECT received_qty FROM wms_arrival_notice_item WHERE id=?",
                                    BigDecimal.class,
                                    line))
                    .isEqualByComparingTo("0");
            assertThat(
                            db.queryForObject(
                                    "SELECT status FROM wms_receipt WHERE id=?", String.class, id))
                    .isEqualTo("DRAFT");
        } finally {
            db.execute("ALTER TABLE wms_inventory_transaction DROP CHECK ck_test_fail_ledger");
        }
    }

    @Test
    void concurrentReceiptsCannotOverReceive() throws Exception {
        long first = draft(70), second = draft(70);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a =
                    executor.submit(
                            () -> concurrentSubmit(first, UUID.randomUUID().toString(), start));
            var b =
                    executor.submit(
                            () -> concurrentSubmit(second, UUID.randomUUID().toString(), start));
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(stock()).isEqualByComparingTo("70");
        assertThat(count("wms_inventory_transaction")).isEqualTo(1);
    }

    @Test
    void simultaneousDuplicateSubmissionOnlyChangesStockOnce() throws Exception {
        long id = draft(20);
        String key = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> concurrentSubmit(id, key, start));
            var b = executor.submit(() -> concurrentSubmit(id, key, start));
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsOnly(200);
        }
        assertThat(stock()).isEqualByComparingTo("20");
        assertThat(count("wms_inventory_transaction")).isEqualTo(1);
    }

    private int concurrentSubmit(long id, String key, CountDownLatch start) throws Exception {
        start.await();
        return mvc.perform(
                        admin(post("/api/v1/wms/receipts/{id}/submit", id))
                                .content(
                                        json.writeValueAsString(
                                                Map.of("version", 0, "idempotencyKey", key))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private void submit(long id, String key, int expected) throws Exception {
        mvc.perform(
                        admin(post("/api/v1/wms/receipts/{id}/submit", id))
                                .content(
                                        json.writeValueAsString(
                                                Map.of("version", 0, "idempotencyKey", key))))
                .andExpect(status().is(expected));
    }

    private long draft(int qty) throws Exception {
        return created(
                        "/api/v1/wms/arrival-notices/" + arrival + "/receipts",
                        Map.of(
                                "idempotencyKey",
                                UUID.randomUUID().toString(),
                                "items",
                                List.of(item(qty))))
                .get("receipt")
                .get("id")
                .asLong();
    }

    private Map<String, Object> item(int qty) {
        return Map.of(
                "arrivalItemId", line, "locationId", location, "quantity", qty, "batchNo", "B-01");
    }

    private JsonNode created(String url, Object body) throws Exception {
        return json.readTree(
                mvc.perform(admin(post(url)).content(json.writeValueAsString(body)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return builder.with(user("receiver").roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private BigDecimal stock() {
        return db.queryForObject(
                "SELECT SUM(on_hand_qty) FROM wms_inventory_balance", BigDecimal.class);
    }
}
