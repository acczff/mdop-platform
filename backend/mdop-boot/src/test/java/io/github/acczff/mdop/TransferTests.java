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
            "mdop.auth.operators[0].username=transfer-operator",
            "mdop.auth.operators[0].password=Inventory-Test-Only-2026!",
            "mdop.auth.operators[0].authorities=wms:inventory:read,wms:transfer:read,wms:transfer:confirm,wms:warehouse:1"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransferTests extends MdopInfrastructureTestBase {
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

    @Test
    void partialMoveRetryAndMoveBackPreserveTotalAndTrace() throws Exception {
        var s = ready();
        var request = input(s[0], s[2], "30.123456", key());
        var transfer = postAs("/api/v1/wms/transfers", request, admin(), 201);
        long destination = transfer.get("target_balance_id").asLong(),
                id = transfer.get("id").asLong();
        assertThat(qty(s[0])).isEqualByComparingTo("49.876544");
        assertThat(qty(destination)).isEqualByComparingTo("30.123456");
        var dimension = read("/api/v1/wms/stock/" + destination);
        assertThat(dimension.get("batch_no").asText()).isEqualTo("TRANSFER-BATCH");
        assertThat(dimension.get("date_code").asText()).isEqualTo("DC-01");
        assertThat(dimension.get("production_date").asText()).isEqualTo("2026-01-01");
        assertThat(dimension.get("expiry_date").asText()).isEqualTo("2099-12-31");
        assertThat(postAs("/api/v1/wms/transfers", request, admin(), 201).get("id").asLong())
                .isEqualTo(id);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE transfer_id=?",
                                Integer.class,
                                id))
                .isEqualTo(2);
        assertThat(
                        read("/api/v1/wms/stock/" + destination + "/transactions")
                                .get("items")
                                .get(0)
                                .get("transfer_id")
                                .asLong())
                .isEqualTo(id);
        var back =
                postAs(
                        "/api/v1/wms/transfers",
                        input(destination, s[1], "10.123456", key()),
                        admin(),
                        201);
        assertThat(back.get("target_balance_id").asLong()).isEqualTo(s[0]);
        assertThat(qty(s[0])).isEqualByComparingTo("60");
        assertThat(qty(destination)).isEqualByComparingTo("20");
        assertThat(
                        read("/api/v1/wms/transfers?warehouseId=" + warehouse)
                                .get("totalElements")
                                .asLong())
                .isEqualTo(2);
        postAs(
                "/api/v1/wms/transfers",
                input(s[0], s[2], "31", request.get("idempotencyKey").toString()),
                admin(),
                409);
        postAs("/api/v1/wms/transfers", request, operator("another", "wms:transfer:confirm"), 409);
    }

    @Test
    void rejectsInvalidStockAndDestinationsWithoutChangingBalances() throws Exception {
        var s = ready();
        postAs("/api/v1/wms/transfers", input(s[0], s[1], "1", key()), admin(), 409);
        postAs("/api/v1/wms/transfers", input(s[0], location, "1", key()), admin(), 409);
        postAs("/api/v1/wms/transfers", input(s[0], s[2], "81", key()), admin(), 409);
        postAs("/api/v1/wms/transfers", input(s[0], s[2], "0", key()), admin(), 400);
        postAs("/api/v1/wms/transfers", input(s[0], s[2], "1.0000001", key()), admin(), 400);
        long otherWarehouse =
                postAs(
                                "/api/master-data/warehouses",
                                Map.of(
                                        "code",
                                        "W" + key().substring(0, 8).toUpperCase(Locale.ROOT),
                                        "name",
                                        "其他仓",
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
        long otherLocation =
                postAs(
                                "/api/master-data/locations",
                                Map.of(
                                        "warehouseId",
                                        otherWarehouse,
                                        "code",
                                        "OTHER",
                                        "name",
                                        "其他仓存储位",
                                        "areaType",
                                        "STORAGE"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        postAs("/api/v1/wms/transfers", input(s[0], otherLocation, "1", key()), admin(), 409);
        long rejected =
                db.queryForObject(
                        "SELECT id FROM wms_inventory_balance WHERE warehouse_id=? AND quality_status='REJECTED'",
                        Long.class,
                        warehouse);
        postAs("/api/v1/wms/transfers", input(rejected, s[2], "1", key()), admin(), 409);
        long pending =
                db.queryForObject(
                        "SELECT id FROM wms_inventory_balance WHERE warehouse_id=? AND quality_status='PENDING_INSPECTION'",
                        Long.class,
                        warehouse);
        postAs("/api/v1/wms/transfers", input(pending, s[2], "1", key()), admin(), 409);
        db.update("UPDATE wms_inventory_balance SET expiry_date='2000-01-01' WHERE id=?", s[0]);
        postAs("/api/v1/wms/transfers", input(s[0], s[2], "1", key()), admin(), 409);
        assertThat(qty(s[0])).isEqualByComparingTo("80");
    }

    @Test
    void enforcesWarehousePermissionsCsrfAndDisabledWarehouse() throws Exception {
        var s = ready();
        var request = input(s[0], s[2], "1", key());
        postAs("/api/v1/wms/transfers", request, operator("reader", "wms:transfer:read"), 403);
        postAs(
                "/api/v1/wms/transfers",
                request,
                user("outsider").authorities(new SimpleGrantedAuthority("wms:transfer:confirm")),
                403);
        mvc.perform(
                        post("/api/v1/wms/transfers")
                                .with(admin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(request)))
                .andExpect(status().isForbidden());
        var transfer =
                postAs(
                        "/api/v1/wms/transfers",
                        request,
                        operator("mover", "wms:transfer:confirm"),
                        201);
        mvc.perform(
                        get("/api/v1/wms/transfers/" + transfer.get("id").asLong())
                                .with(
                                        user("outsider")
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "wms:transfer:read"))))
                .andExpect(status().isForbidden());
        db.update("UPDATE mdm_warehouse SET status='DISABLED' WHERE id=?", warehouse);
        postAs("/api/v1/wms/transfers", input(s[0], s[2], "1", key()), admin(), 409);
        assertThat(users.loadUserByUsername("transfer-operator").getAuthorities())
                .extracting(a -> a.getAuthority())
                .contains("wms:transfer:confirm")
                .doesNotContain("ROLE_ADMIN");
    }

    @Test
    void concurrentMovesCannotOverdraw() throws Exception {
        var s = ready();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Integer> move =
                    () -> {
                        gate.await();
                        return mvc.perform(
                                        post("/api/v1/wms/transfers")
                                                .with(admin())
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                input(s[0], s[2], "60", key()))))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var a = pool.submit(move);
            var b = pool.submit(move);
            gate.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(qty(s[0])).isEqualByComparingTo("20");
    }

    @Test
    void secondLedgerFailureRollsBackEntireMove() throws Exception {
        var s = ready();
        db.execute(
                "ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_test_transfer_failure CHECK (transaction_type<>'TRANSFER_IN' OR created_by<>'rollback-probe')");
        var request = input(s[0], s[2], "30", key());
        try {
            postAs("/api/v1/wms/transfers", request, user("rollback-probe").roles("ADMIN"), 503);
        } finally {
            db.execute("ALTER TABLE wms_inventory_transaction DROP CHECK ck_test_transfer_failure");
        }
        assertThat(qty(s[0])).isEqualByComparingTo("80");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_stock_transfer WHERE warehouse_id=?",
                                Integer.class,
                                warehouse))
                .isZero();
        postAs("/api/v1/wms/transfers", request, user("rollback-probe").roles("ADMIN"), 201);
        assertThat(qty(s[0])).isEqualByComparingTo("50");
    }
}
