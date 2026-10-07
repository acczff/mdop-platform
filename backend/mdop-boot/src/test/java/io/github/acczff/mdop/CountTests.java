package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.acczff.mdop.test.support.MdopInfrastructureTestBase;
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

@SpringBootTest(
        properties = {
            "mdop.auth.operators[0].username=count-operator",
            "mdop.auth.operators[0].password=Inventory-Test-Only-2026!",
            "mdop.auth.operators[0].authorities=wms:inventory:read,wms:count:read,wms:count:create,wms:count:submit,wms:count:review,wms:count:cancel,wms:warehouse:1"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CountTests extends MdopInfrastructureTestBase {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
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

    long receive(Number quantity) throws Exception {
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
                                                "TRANSFER-BATCH",
                                                "dateCode",
                                                "DC-01",
                                                "productionDate",
                                                "2026-01-01",
                                                "expiryDate",
                                                "2099-12-31"))),
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

    long storage() throws Exception {
        return postAs(
                        "/api/master-data/locations",
                        Map.of(
                                "warehouseId",
                                warehouse,
                                "code",
                                "S" + key().substring(0, 8),
                                "name",
                                "存储位",
                                "areaType",
                                "STORAGE"),
                        admin(),
                        201)
                .get("id")
                .asLong();
    }

    long[] ready() throws Exception {
        long receipt = receive(100);
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
                        "移库测试",
                        "items",
                        List.of(
                                Map.of(
                                        "receiptItemId",
                                        item,
                                        "qualifiedQty",
                                        80,
                                        "rejectedQty",
                                        20))),
                admin(),
                200);
        long a = storage(), b = storage();
        postAs(
                "/api/v1/wms/quality/items/" + item + "/putaway",
                Map.of("idempotencyKey", key(), "locationId", a),
                admin(),
                200);
        long balance =
                db.queryForObject(
                        "SELECT id FROM wms_inventory_balance WHERE location_id=? AND quality_status='QUALIFIED'",
                        Long.class,
                        a);
        return new long[] {balance, a, b};
    }

    Map<String, Object> input(long source, long target, String quantity, String key) {
        return Map.of(
                "sourceBalanceId",
                source,
                "targetLocationId",
                target,
                "quantity",
                quantity,
                "idempotencyKey",
                key,
                "reason",
                "实际移库");
    }

    BigDecimal qty(long id) {
        return db.queryForObject(
                "SELECT on_hand_qty FROM wms_inventory_balance WHERE id=?", BigDecimal.class, id);
    }

    JsonNode read(String path) throws Exception {
        return json.readTree(
                mvc.perform(get(path).with(admin()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    long count(long balance) throws Exception {
        return postAs(
                        "/api/v1/wms/counts",
                        Map.of("balanceId", balance, "idempotencyKey", key()),
                        admin(),
                        201)
                .get("id")
                .asLong();
    }

    void submitCount(long id, String quantity) throws Exception {
        postAs(
                "/api/v1/wms/counts/" + id + "/submit",
                Map.of("quantity", quantity, "reason", "实盘差异"),
                admin(),
                200);
    }

    void approve(long id, int status) throws Exception {
        postAs(
                "/api/v1/wms/counts/" + id + "/approve",
                Map.of(),
                operator("reviewer", "wms:count:review"),
                status);
    }

    @Test
    void lossGainAndRetryKeepAudit() throws Exception {
        long balance = ready()[0];
        String requestKey = key();
        var body = Map.of("balanceId", balance, "idempotencyKey", requestKey);
        long id = postAs("/api/v1/wms/counts", body, admin(), 201).get("id").asLong();
        assertThat(postAs("/api/v1/wms/counts", body, admin(), 201).get("id").asLong())
                .isEqualTo(id);
        submitCount(id, "70.123456");
        submitCount(id, "70.123456");
        postAs(
                "/api/v1/wms/counts/" + id + "/submit",
                Map.of("quantity", "70", "reason", "实盘差异"),
                admin(),
                409);
        postAs("/api/v1/wms/counts/" + id + "/approve", Map.of(), admin(), 403);
        approve(id, 200);
        approve(id, 200);
        assertThat(qty(balance)).isEqualByComparingTo("70.123456");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE count_id=?",
                                Long.class,
                                id))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT transaction_type FROM wms_inventory_transaction WHERE count_id=?",
                                String.class,
                                id))
                .isEqualTo("COUNT_LOSS");
        long gain = count(balance);
        submitCount(gain, "82");
        approve(gain, 200);
        assertThat(qty(balance)).isEqualByComparingTo("82");
        long same = count(balance);
        submitCount(same, "82");
        approve(same, 200);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE count_id=?",
                                Long.class,
                                same))
                .isZero();
        assertThat(read("/api/v1/wms/stock/" + balance + "/transactions").get("items").toString())
                .contains("COUNT_GAIN", "count_id");
    }

    @Test
    void interveningMovementInvalidatesEvenRestoredBalance() throws Exception {
        var stock = ready();
        long id = count(stock[0]);
        submitCount(id, "79");
        var moved =
                postAs(
                        "/api/v1/wms/transfers",
                        input(stock[0], stock[2], "10", key()),
                        admin(),
                        201);
        postAs(
                "/api/v1/wms/transfers",
                input(moved.get("target_balance_id").asLong(), stock[1], "10", key()),
                admin(),
                201);
        assertThat(qty(stock[0])).isEqualByComparingTo("80");
        approve(id, 409);
        postAs("/api/v1/wms/counts/" + id + "/cancel", Map.of("reason", "重新盘点"), admin(), 200);
        approve(id, 409);
        long fresh = count(stock[0]);
        submitCount(fresh, "79");
        approve(fresh, 200);
    }

    @Test
    void permissionsValidationAndRejection() throws Exception {
        long balance = ready()[0];
        assertThat(users.loadUserByUsername("count-operator").getAuthorities())
                .extracting("authority")
                .contains("wms:count:review");
        postAs(
                "/api/v1/wms/counts",
                Map.of("balanceId", balance, "idempotencyKey", key()),
                user("reader").authorities(new SimpleGrantedAuthority("wms:count:read")),
                403);
        postAs(
                "/api/v1/wms/counts",
                Map.of("balanceId", balance, "idempotencyKey", key()),
                user("outside").authorities(new SimpleGrantedAuthority("wms:count:create")),
                403);
        mvc.perform(
                        post("/api/v1/wms/counts")
                                .with(admin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        long id = count(balance);
        postAs(
                "/api/v1/wms/counts/" + id + "/submit",
                Map.of("quantity", "-1", "reason", "原因"),
                admin(),
                400);
        postAs(
                "/api/v1/wms/counts/" + id + "/submit",
                Map.of("quantity", "1.1234567", "reason", "原因"),
                admin(),
                400);
        postAs(
                "/api/v1/wms/counts/" + id + "/submit",
                Map.of("quantity", "1", "reason", "原因"),
                operator("other", "wms:count:submit"),
                403);
        submitCount(id, "0");
        postAs(
                "/api/v1/wms/counts/" + id + "/reject",
                Map.of("reason", "复盘"),
                operator("reviewer", "wms:count:review"),
                200);
        approve(id, 409);
        assertThat(qty(balance)).isEqualByComparingTo("80");
        long rejected =
                db.queryForObject(
                        "SELECT id FROM wms_inventory_balance WHERE warehouse_id=? AND quality_status='REJECTED'",
                        Long.class,
                        warehouse);
        postAs(
                "/api/v1/wms/counts",
                Map.of("balanceId", rejected, "idempotencyKey", key()),
                admin(),
                409);
    }

    @Test
    void competingApprovalsOnlyApplyOneSnapshot() throws Exception {
        long balance = ready()[0], a = count(balance), b = count(balance);
        submitCount(a, "75");
        submitCount(b, "77");
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> jobs = List.of(() -> approveStatus(a), () -> approveStatus(b));
            var results = pool.invokeAll(jobs);
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ledgerFailureRollsBackBalanceAndApproval() throws Exception {
        long balance = ready()[0], id = count(balance);
        submitCount(id, "70");
        db.execute(
                "ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_test_count_failure CHECK (count_id IS NULL OR created_by<>'count-rollback')");
        try {
            postAs(
                    "/api/v1/wms/counts/" + id + "/approve",
                    Map.of(),
                    operator("count-rollback", "wms:count:review"),
                    503);
        } finally {
            db.execute("ALTER TABLE wms_inventory_transaction DROP CHECK ck_test_count_failure");
        }
        assertThat(qty(balance)).isEqualByComparingTo("80");
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM wms_stock_count WHERE id=?", String.class, id))
                .isEqualTo("PENDING");
        approve(id, 200);
        assertThat(qty(balance)).isEqualByComparingTo("70");
    }

    int approveStatus(long id) throws Exception {
        return mvc.perform(
                        post("/api/v1/wms/counts/" + id + "/approve")
                                .with(operator("reviewer", "wms:count:review"))
                                .with(csrf()))
                .andReturn()
                .getResponse()
                .getStatus();
    }
}
