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
            "mdop.auth.operators[0].username=issue-operator",
            "mdop.auth.operators[0].password=Inventory-Test-Only-2026!",
            "mdop.auth.operators[0].authorities=wms:inventory:read,wms:issue:read,wms:issue:reserve,wms:issue:cancel,wms:issue:confirm,wms:warehouse:1"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IssueTests extends MdopInfrastructureTestBase {
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

    long targetWarehouse, targetLocation;

    void lineSide() throws Exception {
        targetWarehouse =
                postAs(
                                "/api/master-data/warehouses",
                                Map.of(
                                        "code",
                                        "LS" + key().substring(0, 8).toUpperCase(Locale.ROOT),
                                        "name",
                                        "线边仓",
                                        "purpose",
                                        "LINE_SIDE",
                                        "form",
                                        "PHYSICAL",
                                        "managementCategory",
                                        "GENERAL"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        targetLocation =
                postAs(
                                "/api/master-data/locations",
                                Map.of(
                                        "warehouseId",
                                        targetWarehouse,
                                        "code",
                                        "ST",
                                        "name",
                                        "线边存储位",
                                        "areaType",
                                        "STORAGE"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
    }

    Map<String, Object> demandBody(String no, String quantity) {
        return Map.of(
                "demandNo",
                no,
                "workOrderNo",
                "MO-001",
                "warehouseId",
                warehouse,
                "targetWarehouseId",
                targetWarehouse,
                "materialId",
                material,
                "quantity",
                quantity);
    }

    long demand(String quantity) throws Exception {
        return postAs("/api/local/mes-demands", demandBody(key(), quantity), admin(), 201)
                .get("id")
                .asLong();
    }

    JsonNode reserve(long id, long balance, int status) throws Exception {
        return postAs(
                "/api/v1/wms/issues/" + id + "/reserve",
                Map.of("balanceId", balance, "targetLocationId", targetLocation),
                admin(),
                status);
    }

    void confirmIssue(long id, int status) throws Exception {
        postAs("/api/v1/wms/issues/" + id + "/confirm", Map.of(), admin(), status);
    }

    void cancelIssue(long id, int status) throws Exception {
        postAs("/api/v1/wms/issues/" + id + "/cancel", Map.of("reason", "需求取消"), admin(), status);
    }

    BigDecimal stock(long id, String column) {
        return db.queryForObject(
                "SELECT " + column + " FROM wms_inventory_balance WHERE id=?",
                BigDecimal.class,
                id);
    }

    RequestPostProcessor both(String name, String permission) {
        return user(name)
                .authorities(
                        new SimpleGrantedAuthority(permission),
                        new SimpleGrantedAuthority("wms:warehouse:" + warehouse),
                        new SimpleGrantedAuthority("wms:warehouse:" + targetWarehouse));
    }

    @Test
    void reservationIssueAndRetriesPreserveTotalAndDimensions() throws Exception {
        long balance = ready()[0];
        lineSide();
        String no = key();
        var input = demandBody(no, "12.123456");
        long id = postAs("/api/local/mes-demands", input, admin(), 201).get("id").asLong();
        assertThat(postAs("/api/local/mes-demands", input, admin(), 201).get("id").asLong())
                .isEqualTo(id);
        postAs("/api/local/mes-demands", demandBody(no, "13"), admin(), 409);
        confirmIssue(id, 409);
        reserve(id, balance, 200);
        reserve(id, balance, 200);
        assertThat(qty(balance)).isEqualByComparingTo("80");
        assertThat(stock(balance, "available_qty")).isEqualByComparingTo("67.876544");
        assertThat(stock(balance, "reserved_qty")).isEqualByComparingTo("12.123456");
        confirmIssue(id, 200);
        confirmIssue(id, 200);
        reserve(id, balance, 200);
        cancelIssue(id, 409);
        var row = read("/api/v1/wms/issues/" + id);
        long target = row.get("target_balance_id").asLong();
        assertThat(qty(balance).add(qty(target))).isEqualByComparingTo("80");
        assertThat(stock(balance, "reserved_qty")).isZero();
        assertThat(qty(target)).isEqualByComparingTo("12.123456");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE issue_id=?",
                                Long.class,
                                id))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT SUM(change_qty) FROM wms_inventory_transaction WHERE issue_id=?",
                                BigDecimal.class,
                                id))
                .isZero();
        assertThat(
                        db.queryForMap(
                                "SELECT material_id,supplier_id,batch_no,date_code,production_date,expiry_date,owner_type,owner_id FROM wms_inventory_balance WHERE id=?",
                                target))
                .isEqualTo(
                        db.queryForMap(
                                "SELECT material_id,supplier_id,batch_no,date_code,production_date,expiry_date,owner_type,owner_id FROM wms_inventory_balance WHERE id=?",
                                balance));
        assertThat(read("/api/v1/wms/issues/" + id + "/events").toString())
                .contains("RESERVE", "CONSUME");
        assertThat(read("/api/v1/wms/stock/" + balance + "/transactions").get("items").toString())
                .contains("ISSUE_OUT", "issue_id");
    }

    @Test
    void cancellationReleasesOnceAndInvalidatesCountSnapshot() throws Exception {
        long balance = ready()[0];
        lineSide();
        long count = count(balance);
        submitCount(count, "79");
        long id = demand("20");
        reserve(id, balance, 200);
        postAs(
                "/api/v1/wms/counts",
                Map.of("balanceId", balance, "idempotencyKey", key()),
                admin(),
                409);
        cancelIssue(id, 200);
        cancelIssue(id, 200);
        confirmIssue(id, 409);
        reserve(id, balance, 409);
        assertThat(qty(balance)).isEqualByComparingTo("80");
        assertThat(stock(balance, "available_qty")).isEqualByComparingTo("80");
        assertThat(stock(balance, "reserved_qty")).isZero();
        approve(count, 409);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_reservation_event WHERE issue_id=?",
                                Long.class,
                                id))
                .isEqualTo(2);
        postAs("/api/v1/wms/issues/" + id + "/cancel", Map.of("reason", "变更原因"), admin(), 409);
        long open = demand("10");
        cancelIssue(open, 200);
        assertThat(read("/api/v1/wms/issues/" + open + "/events").size()).isZero();
    }

    @Test
    void concurrentReservationsAndOrdinaryTransferCannotSpendReservedStock() throws Exception {
        var b = ready();
        lineSide();
        long a = demand("50"), c = demand("50");
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tasks =
                    List.of(() -> reserveStatus(a, b[0]), () -> reserveStatus(c, b[0]));
            var results = pool.invokeAll(tasks);
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stock(b[0], "reserved_qty")).isEqualByComparingTo("50");
        postAs("/api/v1/wms/transfers", input(b[0], b[2], "31", key()), admin(), 409);
        postAs("/api/v1/wms/transfers", input(b[0], b[2], "30", key()), admin(), 201);
        long winner =
                db.queryForObject(
                        "SELECT id FROM wms_material_issue WHERE warehouse_id=? AND status='RESERVED'",
                        Long.class,
                        warehouse);
        confirmIssue(winner, 200);
        assertThat(qty(b[0])).isZero();
    }

    int reserveStatus(long id, long balance) throws Exception {
        return mvc.perform(
                        post("/api/v1/wms/issues/" + id + "/reserve")
                                .with(admin())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "balanceId",
                                                        balance,
                                                        "targetLocationId",
                                                        targetLocation))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void permissionsScopesValidationAndTargetRules() throws Exception {
        long balance = ready()[0];
        lineSide();
        long id = demand("10");
        assertThat(users.loadUserByUsername("issue-operator").getAuthorities())
                .extracting("authority")
                .contains("wms:issue:confirm");
        mvc.perform(get("/api/v1/wms/issues/" + id).with(operator("source-only", "wms:issue:read")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/v1/wms/issues")
                                .param("warehouseId", "" + warehouse)
                                .param("targetWarehouseId", "" + targetWarehouse)
                                .with(operator("source-only", "wms:issue:read")))
                .andExpect(status().isForbidden());
        postAs(
                "/api/local/mes-demands",
                demandBody(key(), "10"),
                both("operator", "wms:issue:reserve"),
                403);
        postAs(
                "/api/v1/wms/issues/" + id + "/reserve",
                Map.of("balanceId", balance, "targetLocationId", targetLocation),
                operator("source-only", "wms:issue:reserve"),
                403);
        postAs(
                "/api/v1/wms/issues/" + id + "/reserve",
                Map.of("balanceId", balance, "targetLocationId", location),
                admin(),
                409);
        postAs("/api/local/mes-demands", demandBody(key(), "0"), admin(), 400);
        postAs("/api/local/mes-demands", demandBody(key(), "1.1234567"), admin(), 400);
        mvc.perform(post("/api/v1/wms/issues/" + id + "/confirm").with(admin()))
                .andExpect(status().isForbidden());
        postAs(
                "/api/v1/wms/issues/" + id + "/reserve",
                Map.of("balanceId", balance, "targetLocationId", targetLocation),
                both("storekeeper", "wms:issue:reserve"),
                200);
        postAs(
                "/api/v1/wms/issues/" + id + "/confirm",
                Map.of(),
                both("reader", "wms:issue:read"),
                403);
        postAs(
                "/api/v1/wms/issues/" + id + "/confirm",
                Map.of(),
                both("storekeeper", "wms:issue:confirm"),
                200);
    }

    @Test
    void expiredRejectedAndDisabledStockCannotIssueButCanRelease() throws Exception {
        long balance = ready()[0];
        lineSide();
        long id = demand("10");
        long rejected =
                db.queryForObject(
                        "SELECT id FROM wms_inventory_balance WHERE warehouse_id=? AND quality_status='REJECTED'",
                        Long.class,
                        warehouse);
        reserve(id, rejected, 409);
        reserve(id, balance, 200);
        db.update("UPDATE wms_inventory_balance SET expiry_date='2000-01-01' WHERE id=?", balance);
        confirmIssue(id, 409);
        db.update("UPDATE mdm_warehouse SET status='DISABLED' WHERE id=?", warehouse);
        cancelIssue(id, 200);
        assertThat(stock(balance, "reserved_qty")).isZero();
        assertThat(stock(balance, "available_qty")).isEqualByComparingTo("80");
    }

    @Test
    void secondLedgerFailureRollsBackBothBalancesAndReservation() throws Exception {
        long balance = ready()[0];
        lineSide();
        long id = demand("20");
        reserve(id, balance, 200);
        db.execute(
                "ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_test_issue_failure CHECK(transaction_type<>'ISSUE_IN' OR issue_id<>"
                        + id
                        + ")");
        try {
            confirmIssue(id, 503);
        } finally {
            db.execute("ALTER TABLE wms_inventory_transaction DROP CHECK ck_test_issue_failure");
        }
        assertThat(qty(balance)).isEqualByComparingTo("80");
        assertThat(stock(balance, "reserved_qty")).isEqualByComparingTo("20");
        assertThat(read("/api/v1/wms/issues/" + id).get("status").asText()).isEqualTo("RESERVED");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE issue_id=?",
                                Long.class,
                                id))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_balance WHERE warehouse_id=?",
                                Long.class,
                                targetWarehouse))
                .isZero();
        assertThat(read("/api/v1/wms/issues/" + id + "/events").size()).isEqualTo(1);
        confirmIssue(id, 200);
    }

    @Test
    void multipleReservationsShareBalanceAndReuseLineSideDimension() throws Exception {
        long balance = ready()[0];
        lineSide();
        long a = demand("10"), b = demand("20"), c = demand("5");
        reserve(a, balance, 200);
        reserve(b, balance, 200);
        reserve(c, balance, 200);
        assertThat(stock(balance, "reserved_qty")).isEqualByComparingTo("35");
        confirmIssue(a, 200);
        cancelIssue(b, 200);
        confirmIssue(c, 200);
        assertThat(qty(balance)).isEqualByComparingTo("65");
        assertThat(stock(balance, "available_qty")).isEqualByComparingTo("65");
        assertThat(stock(balance, "reserved_qty")).isZero();
        long target = read("/api/v1/wms/issues/" + a).get("target_balance_id").asLong();
        assertThat(read("/api/v1/wms/issues/" + c).get("target_balance_id").asLong())
                .isEqualTo(target);
        assertThat(qty(target)).isEqualByComparingTo("15");
    }

    @Test
    void cancelRacingConfirmHasExactlyOneTerminalOutcome() throws Exception {
        long balance = ready()[0];
        lineSide();
        long id = demand("20");
        reserve(id, balance, 200);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tasks =
                    List.of(
                            () -> terminalStatus(id, "cancel"),
                            () -> terminalStatus(id, "confirm"));
            var results = pool.invokeAll(tasks);
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stock(balance, "reserved_qty")).isZero();
        String status = read("/api/v1/wms/issues/" + id).get("status").asText();
        assertThat(qty(balance)).isEqualByComparingTo(status.equals("ISSUED") ? "60" : "80");
        assertThat(read("/api/v1/wms/issues/" + id + "/events").size()).isEqualTo(2);
    }

    int terminalStatus(long id, String action) throws Exception {
        return mvc.perform(
                        post("/api/v1/wms/issues/" + id + "/" + action)
                                .with(admin())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("reason", "需求取消"))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void demandStorageFailureCanRetryOriginalNumber() throws Exception {
        lineSide();
        String no = "FAIL-" + key();
        var body = demandBody(no, "10");
        db.execute(
                "ALTER TABLE wms_material_issue ADD CONSTRAINT ck_test_demand_failure CHECK(demand_no<>'"
                        + no
                        + "')");
        try {
            postAs("/api/local/mes-demands", body, admin(), 503);
        } finally {
            db.execute("ALTER TABLE wms_material_issue DROP CHECK ck_test_demand_failure");
        }
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_material_issue WHERE demand_no=?",
                                Long.class,
                                no))
                .isZero();
        postAs("/api/local/mes-demands", body, admin(), 201);
        assertThat(read("/api/local/mes-demands/capabilities").get("enabled").asBoolean()).isTrue();
    }
}
