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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.*;

@SpringBootTest(
        properties = {
            "mdop.auth.operators[0].username=reviewer",
            "mdop.auth.operators[0].password=test-reviewer-password",
            "mdop.auth.operators[0].authorities[0]=wms:correction:approve",
            "mdop.auth.operators[0].authorities[1]=wms:warehouse:1"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorrectionTests extends MdopInfrastructureTestBase {
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

    JsonNode request(long receipt) throws Exception {
        return postAs(
                "/api/v1/wms/corrections/reversals",
                Map.of("idempotencyKey", key(), "receiptId", receipt, "reason", "登记错误，需要冲正"),
                operator("alice", "wms:correction:create"),
                201);
    }

    Map<String, Object> decision(String action) {
        return Map.of(
                "idempotencyKey", key(), "version", 0, "decision", action, "reason", "核对原始凭证后处理");
    }

    JsonNode decide(long id, Object decision, RequestPostProcessor principal, int status)
            throws Exception {
        return postAs("/api/v1/wms/corrections/" + id + "/decision", decision, principal, status);
    }

    BigDecimal stock() {
        return db.queryForObject(
                "SELECT SUM(on_hand_qty) FROM wms_inventory_balance WHERE warehouse_id=?",
                BigDecimal.class,
                warehouse);
    }

    int count(String query, Object... args) {
        return db.queryForObject(query, Integer.class, args);
    }

    @Test
    void approvalReversesOnlyOriginalReceiptAndKeepsImmutableHistory() throws Exception {
        long first = receive(60);
        receive(40);
        var application = request(first);
        long id = application.get("id").asLong();
        assertThat(stock()).isEqualByComparingTo("100");
        var input = decision("APPROVE");
        decide(id, input, operator("bob", "wms:correction:approve"), 200);
        decide(id, input, operator("bob", "wms:correction:approve"), 200);
        assertThat(stock()).isEqualByComparingTo("40");
        assertThat(
                        db.queryForObject(
                                "SELECT received_qty FROM wms_arrival_notice_item WHERE id=?",
                                BigDecimal.class,
                                line))
                .isEqualByComparingTo("40");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE receipt_id=?",
                                first))
                .isEqualTo(2);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE receipt_id=? AND change_qty=-60 AND reversed_transaction_id IS NOT NULL",
                                first))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM wms_outbox WHERE aggregate_id=?", first))
                .isEqualTo(4);
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM wms_receipt WHERE id=?", String.class, first))
                .isEqualTo("SUBMITTED");
        assertThat(
                        db.queryForObject(
                                "SELECT correction_status FROM wms_receipt WHERE id=?",
                                String.class,
                                first))
                .isEqualTo("REVERSED");
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM wms_arrival_notice WHERE id=?",
                                String.class,
                                arrival))
                .isEqualTo("PARTIALLY_RECEIVED");
        receive(60);
        assertThat(stock()).isEqualByComparingTo("100");
    }

    @Test
    void selfApprovalWrongWarehouseAndMissingPermissionAreRejected() throws Exception {
        long id = request(receive(10)).get("id").asLong();
        decide(id, decision("APPROVE"), user("alice").roles("ADMIN"), 403);
        decide(id, decision("APPROVE"), operator("bob", "wms:correction:create"), 403);
        decide(
                id,
                decision("APPROVE"),
                user("bob")
                        .authorities(
                                new SimpleGrantedAuthority("wms:correction:approve"),
                                new SimpleGrantedAuthority("wms:warehouse:999999")),
                403);
        mvc.perform(
                        post("/api/v1/wms/corrections/" + id + "/decision")
                                .with(admin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(decision("APPROVE"))))
                .andExpect(status().isForbidden());
        assertThat(stock()).isEqualByComparingTo("10");
    }

    @Test
    void rejectionAllowsNewApplicationButNotDuplicatePendingReversal() throws Exception {
        long receipt = receive(10);
        var body = Map.of("idempotencyKey", key(), "receiptId", receipt, "reason", "误收");
        var first =
                postAs(
                        "/api/v1/wms/corrections/reversals",
                        body,
                        operator("alice", "wms:correction:create"),
                        201);
        assertThat(
                        postAs(
                                        "/api/v1/wms/corrections/reversals",
                                        body,
                                        operator("alice", "wms:correction:create"),
                                        201)
                                .get("id"))
                .isEqualTo(first.get("id"));
        postAs(
                "/api/v1/wms/corrections/reversals",
                Map.of("idempotencyKey", key(), "receiptId", receipt, "reason", "重复申请"),
                operator("alice", "wms:correction:create"),
                409);
        decide(
                first.get("id").asLong(),
                decision("REJECT"),
                operator("bob", "wms:correction:approve"),
                200);
        assertThat(request(receipt).get("id").asLong()).isNotEqualTo(first.get("id").asLong());
        assertThat(stock()).isEqualByComparingTo("10");
    }

    @Test
    void inspectionOrPutawayPreventsApprovalEvenIfStartedAfterApplication() throws Exception {
        long receipt = receive(10);
        long id = request(receipt).get("id").asLong();
        for (String stage : List.of("INSPECTION", "PUTAWAY")) {
            db.update("UPDATE wms_receipt SET downstream_stage=? WHERE id=?", stage, receipt);
            decide(id, decision("APPROVE"), operator("bob", "wms:correction:approve"), 409);
        }
        assertThat(stock()).isEqualByComparingTo("10");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE receipt_id=?",
                                receipt))
                .isEqualTo(1);
    }

    @Test
    void ledgerFailureRollsBackInventoryCountsApprovalAndCompensation() throws Exception {
        long receipt = receive(10);
        long id = request(receipt).get("id").asLong();
        db.execute(
                "ALTER TABLE wms_inventory_transaction ADD CONSTRAINT ck_test_no_reversal CHECK (transaction_type<>'REVERSAL' OR receipt_id<>"
                        + receipt
                        + ")");
        try {
            decide(id, decision("APPROVE"), operator("bob", "wms:correction:approve"), 503);
        } finally {
            db.execute("ALTER TABLE wms_inventory_transaction DROP CHECK ck_test_no_reversal");
        }
        assertThat(stock()).isEqualByComparingTo("10");
        assertThat(count("SELECT COUNT(*) FROM wms_outbox WHERE aggregate_id=?", receipt))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM wms_receiving_case WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("PENDING");
        assertThat(
                        db.queryForObject(
                                "SELECT received_qty FROM wms_arrival_notice_item WHERE id=?",
                                BigDecimal.class,
                                line))
                .isEqualByComparingTo("10");
        decide(id, decision("APPROVE"), operator("bob", "wms:correction:approve"), 200);
        assertThat(stock()).isZero();
    }

    @Test
    void concurrentApproversProduceOneReversal() throws Exception {
        long receipt = receive(10);
        long id = request(receipt).get("id").asLong();
        var a = decision("APPROVE");
        var b = decision("APPROVE");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var first =
                    pool.submit(
                            () -> {
                                gate.await();
                                return responseCode(id, a, "bob");
                            });
            var second =
                    pool.submit(
                            () -> {
                                gate.await();
                                return responseCode(id, b, "carol");
                            });
            gate.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(stock()).isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE receipt_id=? AND transaction_type='REVERSAL'",
                                receipt))
                .isEqualTo(1);
    }

    int responseCode(long id, Object input, String name) throws Exception {
        return mvc.perform(
                        post("/api/v1/wms/corrections/" + id + "/decision")
                                .with(operator(name, "wms:correction:approve"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(input)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void differenceApprovalDoesNotChangeInventoryOrAuthorizeOverReceipt() throws Exception {
        receive(60);
        for (String type : List.of("SHORT", "OVER", "DAMAGED", "WRONG_MATERIAL")) {
            var input =
                    Map.of(
                            "idempotencyKey",
                            key(),
                            "arrivalId",
                            arrival,
                            "arrivalItemId",
                            line,
                            "type",
                            type,
                            "observedMaterial",
                            "错误物料X",
                            "quantity",
                            5,
                            "reason",
                            "核对供应商到货异常");
            var item =
                    postAs(
                            "/api/v1/wms/corrections/differences",
                            input,
                            operator("alice", "wms:correction:create"),
                            201);
            decide(
                    item.get("id").asLong(),
                    decision("APPROVE"),
                    operator("bob", "wms:correction:approve"),
                    200);
        }
        assertThat(stock()).isEqualByComparingTo("60");
        assertThat(
                        db.queryForObject(
                                "SELECT notice_qty FROM wms_arrival_notice_item WHERE id=?",
                                BigDecimal.class,
                                line))
                .isEqualByComparingTo("100");
    }

    @Test
    void wrongMaterialRequiresIdentityAndForeignArrivalLineIsRejected() throws Exception {
        postAs(
                "/api/v1/wms/corrections/differences",
                Map.of(
                        "idempotencyKey",
                        key(),
                        "arrivalId",
                        arrival,
                        "arrivalItemId",
                        line,
                        "type",
                        "WRONG_MATERIAL",
                        "observedMaterial",
                        "",
                        "quantity",
                        1,
                        "reason",
                        "错料"),
                admin(),
                400);
        postAs(
                "/api/v1/wms/corrections/differences",
                Map.of(
                        "idempotencyKey",
                        key(),
                        "arrivalId",
                        arrival,
                        "arrivalItemId",
                        999999,
                        "type",
                        "SHORT",
                        "quantity",
                        1,
                        "reason",
                        "短收"),
                admin(),
                400);
    }

    @Test
    void configuredReviewerDoesNotReceiveAdminOrSubmitAuthority() {
        var reviewer = users.loadUserByUsername("reviewer");
        assertThat(reviewer.getAuthorities())
                .extracting("authority")
                .contains("wms:correction:approve")
                .doesNotContain("ROLE_ADMIN", "wms:receipt:submit", "wms:correction:create");
    }

    @Test
    void inspectionAndApprovalRaceCannotBothSucceed() throws Exception {
        long receipt = receive(10);
        long id = request(receipt).get("id").asLong();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var review =
                    pool.submit(
                            () -> {
                                gate.await();
                                return responseCode(id, decision("APPROVE"), "bob");
                            });
            var inspection =
                    pool.submit(
                            () -> {
                                gate.await();
                                var context = SecurityContextHolder.createEmptyContext();
                                context.setAuthentication(
                                        new UsernamePasswordAuthenticationToken(
                                                "integration:QMS",
                                                null,
                                                List.of(
                                                        new SimpleGrantedAuthority(
                                                                "wms:warehouse:" + warehouse))));
                                SecurityContextHolder.setContext(context);
                                try {
                                    corrections.markDownstreamStarted(receipt, "INSPECTION");
                                    return 200;
                                } catch (io.github.acczff.mdop.common.BusinessException failure) {
                                    return failure.getStatus();
                                } finally {
                                    SecurityContextHolder.clearContext();
                                }
                            });
            gate.countDown();
            assertThat(
                            List.of(
                                    review.get(15, TimeUnit.SECONDS),
                                    inspection.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        String stage =
                db.queryForObject(
                        "SELECT downstream_stage FROM wms_receipt WHERE id=?",
                        String.class,
                        receipt);
        assertThat(stock()).isEqualByComparingTo(stage.equals("NONE") ? "0" : "10");
    }

    @Test
    void staleApprovalAndChangedStockAreRejected() throws Exception {
        long receipt = receive(10);
        long id = request(receipt).get("id").asLong();
        decide(
                id,
                Map.of(
                        "idempotencyKey",
                        key(),
                        "version",
                        99,
                        "decision",
                        "APPROVE",
                        "reason",
                        "过期界面"),
                operator("bob", "wms:correction:approve"),
                409);
        db.update("UPDATE wms_inventory_balance SET on_hand_qty=5 WHERE warehouse_id=?", warehouse);
        decide(id, decision("APPROVE"), operator("bob", "wms:correction:approve"), 409);
        assertThat(stock()).isEqualByComparingTo("5");
        assertThat(count("SELECT COUNT(*) FROM wms_outbox WHERE aggregate_id=?", receipt))
                .isEqualTo(2);
    }
}
