package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.acczff.mdop.test.support.MdopInfrastructureTestBase;
import io.github.acczff.mdop.wms.receiving.CorrectionService;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QualityTests extends MdopInfrastructureTestBase {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired CorrectionService corrections;
    @Autowired org.springframework.security.core.userdetails.UserDetailsService users;
    long warehouse, location, material, supplier, arrival, line;

    @BeforeEach
    void setup() throws Exception {
        String code = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        warehouse =
                postAs(
                                "/api/master-data/warehouses",
                                Map.of(
                                        "code",
                                        "W" + code,
                                        "name",
                                        "冲正验收仓",
                                        "purpose",
                                        "RAW_MATERIAL",
                                        "form",
                                        "PHYSICAL",
                                        "managementCategory",
                                        "GENERAL"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        supplier =
                postAs(
                                "/api/master-data/suppliers",
                                Map.of("code", "S" + code, "name", "供应商"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        material =
                postAs(
                                "/api/master-data/materials",
                                Map.of(
                                        "code",
                                        "M" + code,
                                        "name",
                                        "物料",
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
        location =
                postAs(
                                "/api/master-data/locations",
                                Map.of(
                                        "warehouseId",
                                        warehouse,
                                        "code",
                                        "L" + code,
                                        "name",
                                        "待检",
                                        "areaType",
                                        "INSPECTION"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        var notice =
                postAs(
                        "/api/local/erp-arrivals",
                        Map.of(
                                "externalNoticeNo",
                                code,
                                "purchaseOrderNo",
                                "P" + code,
                                "supplierId",
                                supplier,
                                "warehouseId",
                                warehouse,
                                "items",
                                List.of(Map.of("materialId", material, "quantity", 100))),
                        admin(),
                        201);
        arrival = notice.get("arrival").get("id").asLong();
        line = notice.get("items").get(0).get("id").asLong();
    }

    RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    RequestPostProcessor operator(String name, String permission) {
        return user(name)
                .authorities(
                        new SimpleGrantedAuthority(permission),
                        new SimpleGrantedAuthority("wms:warehouse:" + warehouse));
    }

    JsonNode postAs(String path, Object body, RequestPostProcessor principal, int expected)
            throws Exception {
        String content =
                mvc.perform(
                                post(path)
                                        .with(principal)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().is(expected))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return content.isBlank() ? json.nullNode() : json.readTree(content);
    }

    String key() {
        return UUID.randomUUID().toString();
    }

    long receive(int quantity) throws Exception {
        var draft =
                postAs(
                        "/api/v1/wms/arrival-notices/" + arrival + "/receipts",
                        Map.of(
                                "idempotencyKey",
                                key(),
                                "items",
                                List.of(
                                        Map.of(
                                                "arrivalItemId",
                                                line,
                                                "locationId",
                                                location,
                                                "quantity",
                                                quantity,
                                                "batchNo",
                                                "",
                                                "dateCode",
                                                ""))),
                        admin(),
                        201);
        long id = draft.get("receipt").get("id").asLong();
        postAs(
                "/api/v1/wms/receipts/" + id + "/submit",
                Map.of("idempotencyKey", key(), "version", 0),
                admin(),
                200);
        return id;
    }

    @Test
    void concurrentPutawayOnlyMovesStockOnce() throws Exception {
        long receipt = receive(100);
        long item =
                postAs("/api/local/qms-results", result(receipt, 80, 20), admin(), 200)
                        .get(0)
                        .get("id")
                        .asLong();
        long destination = storage();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Integer> task =
                    () -> {
                        gate.await();
                        return mvc.perform(
                                        post("/api/v1/wms/quality/items/" + item + "/putaway")
                                                .with(admin())
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "idempotencyKey",
                                                                        key(),
                                                                        "locationId",
                                                                        destination))))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var first = pool.submit(task);
            var second = pool.submit(task);
            gate.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(sum("on_hand_qty")).isEqualByComparingTo("100");
        assertThat(sum("available_qty")).isEqualByComparingTo("80");
    }

    @Test
    void qualityAndReversalApprovalCannotBothSucceed() throws Exception {
        long receipt = receive(100);
        long request =
                postAs(
                                "/api/v1/wms/corrections/reversals",
                                Map.of(
                                        "idempotencyKey",
                                        key(),
                                        "receiptId",
                                        receipt,
                                        "reason",
                                        "错误收货"),
                                operator("alice", "wms:correction:create"),
                                201)
                        .get("id")
                        .asLong();
        var input = result(receipt, 80, 20);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var inspection =
                    pool.submit(
                            () -> {
                                gate.await();
                                return mvc.perform(
                                                post("/api/local/qms-results")
                                                        .with(admin())
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(json.writeValueAsString(input)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var correction =
                    pool.submit(
                            () -> {
                                gate.await();
                                return mvc.perform(
                                                post("/api/v1/wms/corrections/"
                                                                + request
                                                                + "/decision")
                                                        .with(
                                                                operator(
                                                                        "bob",
                                                                        "wms:correction:approve"))
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json.writeValueAsString(
                                                                        Map.of(
                                                                                "idempotencyKey",
                                                                                key(),
                                                                                "version",
                                                                                0,
                                                                                "decision",
                                                                                "APPROVE",
                                                                                "reason",
                                                                                "确认冲正"))))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            gate.countDown();
            assertThat(
                            List.of(
                                    inspection.get(20, TimeUnit.SECONDS),
                                    correction.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        String status =
                db.queryForObject(
                        "SELECT correction_status FROM wms_receipt WHERE id=?",
                        String.class,
                        receipt);
        assertThat(sum("on_hand_qty"))
                .isEqualByComparingTo(status.equals("REVERSED") ? "0" : "100");
    }

    Map<String, Object> result(long receipt, int good, int bad) {
        long item =
                db.queryForObject(
                        "SELECT id FROM wms_receipt_item WHERE receipt_id=?", Long.class, receipt);
        return Map.of(
                "idempotencyKey",
                key(),
                "receiptId",
                receipt,
                "version",
                1,
                "referenceNo",
                key(),
                "reason",
                "模拟检验结果",
                "items",
                List.of(Map.of("receiptItemId", item, "qualifiedQty", good, "rejectedQty", bad)));
    }

    long storage() throws Exception {
        return postAs(
                        "/api/master-data/locations",
                        Map.of(
                                "warehouseId",
                                warehouse,
                                "code",
                                "ST" + key().substring(0, 8),
                                "name",
                                "正式库位",
                                "areaType",
                                "STORAGE"),
                        admin(),
                        201)
                .get("id")
                .asLong();
    }

    BigDecimal sum(String column) {
        return db.queryForObject(
                "SELECT SUM(" + column + ") FROM wms_inventory_balance WHERE warehouse_id=?",
                BigDecimal.class,
                warehouse);
    }

    @Test
    void splitAndPutawayConserveStockAndRetriesAreIdempotent() throws Exception {
        long receipt = receive(100);
        var input = result(receipt, 80, 20);
        var lines = postAs("/api/local/qms-results", input, admin(), 200);
        postAs("/api/local/qms-results", input, admin(), 200);
        assertThat(sum("on_hand_qty")).isEqualByComparingTo("100");
        assertThat(sum("available_qty")).isEqualByComparingTo("0");
        long item = lines.get(0).get("id").asLong();
        var putaway = Map.of("idempotencyKey", key(), "locationId", storage());
        postAs("/api/v1/wms/quality/items/" + item + "/putaway", putaway, admin(), 200);
        postAs("/api/v1/wms/quality/items/" + item + "/putaway", putaway, admin(), 200);
        assertThat(sum("on_hand_qty")).isEqualByComparingTo("100");
        assertThat(sum("available_qty")).isEqualByComparingTo("80");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE receipt_id=?",
                                Integer.class,
                                receipt))
                .isEqualTo(6);
        postAs(
                "/api/v1/wms/corrections/reversals",
                Map.of("idempotencyKey", key(), "receiptId", receipt, "reason", "已检后不可冲正"),
                admin(),
                409);
        postAs("/api/local/qms-results", result(receipt, 100, 0), admin(), 409);
    }

    @Test
    void invalidTotalAndUnauthorizedUserCannotChangeStock() throws Exception {
        long receipt = receive(100);
        postAs("/api/local/qms-results", result(receipt, 80, 10), admin(), 400);
        postAs(
                "/api/local/qms-results",
                result(receipt, 80, 20),
                operator("unauthorized", "wms:quality:read"),
                403);
        mvc.perform(
                        get("/api/v1/wms/quality")
                                .param("warehouseId", String.valueOf(warehouse))
                                .with(
                                        user("wrong")
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "wms:quality:read"))))
                .andExpect(status().isForbidden());
        assertThat(sum("on_hand_qty")).isEqualByComparingTo("100");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_quality_result WHERE receipt_id=?",
                                Integer.class,
                                receipt))
                .isZero();
    }

    @Test
    void rejectedStockAndReceivingLocationsCannotBePutAway() throws Exception {
        long receipt = receive(60);
        long item =
                postAs("/api/local/qms-results", result(receipt, 0, 60), admin(), 200)
                        .get(0)
                        .get("id")
                        .asLong();
        postAs(
                "/api/v1/wms/quality/items/" + item + "/putaway",
                Map.of("idempotencyKey", key(), "locationId", storage()),
                admin(),
                409);
        long second = receive(40);
        item =
                postAs("/api/local/qms-results", result(second, 40, 0), admin(), 200)
                        .get(0)
                        .get("id")
                        .asLong();
        postAs(
                "/api/v1/wms/quality/items/" + item + "/putaway",
                Map.of("idempotencyKey", key(), "locationId", location),
                admin(),
                400);
        assertThat(sum("available_qty")).isEqualByComparingTo("0");
    }

    @Test
    void inventoryFailureRollsBackResultAndMarker() throws Exception {
        long receipt = receive(100);
        db.update(
                "UPDATE wms_inventory_balance SET on_hand_qty=50 WHERE warehouse_id=?", warehouse);
        postAs("/api/local/qms-results", result(receipt, 80, 20), admin(), 409);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_quality_result WHERE receipt_id=?",
                                Integer.class,
                                receipt))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT downstream_stage FROM wms_receipt WHERE id=?",
                                String.class,
                                receipt))
                .isEqualTo("NONE");
        assertThat(sum("on_hand_qty")).isEqualByComparingTo("50");
    }
}
