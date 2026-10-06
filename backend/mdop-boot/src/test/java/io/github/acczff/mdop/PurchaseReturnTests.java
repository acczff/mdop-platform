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

@SpringBootTest(
        properties = {"mdop.messaging.enabled=true", "mdop.messaging.initial-delay-ms=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PurchaseReturnTests extends MdopInfrastructureTestBase {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired CorrectionService corrections;
    @Autowired org.springframework.security.core.userdetails.UserDetailsService users;
    @Autowired io.github.acczff.mdop.integration.messaging.DeliveryService delivery;
    @Autowired io.github.acczff.mdop.integration.messaging.SimulatorListener simulator;

    @org.springframework.test.context.DynamicPropertySource
    static void rabbitProperties(
            org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

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

    long rejectedItem() throws Exception {
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
                        "质检不合格",
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
        return item;
    }

    JsonNode apply(long item, int quantity) throws Exception {
        return postAs(
                "/api/v1/wms/purchase-returns",
                Map.of(
                        "idempotencyKey",
                        key(),
                        "receiptItemId",
                        item,
                        "quantity",
                        quantity,
                        "reason",
                        "退回供应商"),
                operator("alice", "wms:return:create"),
                201);
    }

    Map<String, Object> decision(long version, String action) {
        return Map.of(
                "idempotencyKey",
                key(),
                "version",
                version,
                "decision",
                action,
                "reason",
                "核对检验结果");
    }

    JsonNode approve(long id) throws Exception {
        return postAs(
                "/api/v1/wms/purchase-returns/" + id + "/decision",
                decision(0, "APPROVE"),
                operator("bob", "wms:return:approve"),
                200);
    }

    JsonNode confirm(long id, Object body, int status) throws Exception {
        return postAs(
                "/api/v1/wms/purchase-returns/" + id + "/confirm",
                body,
                operator("alice", "wms:return:confirm"),
                status);
    }

    Map<String, Object> confirmation() {
        return Map.of("idempotencyKey", key(), "version", 1, "handoverNo", "交接-" + key());
    }

    BigDecimal stock() {
        return db.queryForObject(
                "SELECT SUM(on_hand_qty) FROM wms_inventory_balance WHERE warehouse_id=?",
                BigDecimal.class,
                warehouse);
    }

    int count(String sql, Object... args) {
        return db.queryForObject(sql, Integer.class, args);
    }

    @Test
    void partialReturnsOnlyDeductAfterConfirmationAndPublishTwoDistinctEvents() throws Exception {
        long item = rejectedItem();
        long first = apply(item, 12).get("id").asLong();
        long second = apply(item, 8).get("id").asLong();
        assertThat(stock()).isEqualByComparingTo("100");
        approve(first);
        approve(second);
        assertThat(stock()).isEqualByComparingTo("100");
        var input = confirmation();
        confirm(first, input, 200);
        confirm(first, input, 200);
        assertThat(stock()).isEqualByComparingTo("88");
        confirm(second, confirmation(), 200);
        assertThat(stock()).isEqualByComparingTo("80");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE receipt_item_id=? AND transaction_type='RETURN_OUT'",
                                item))
                .isEqualTo(2);
        long receipt =
                db.queryForObject(
                        "SELECT receipt_id FROM wms_receipt_item WHERE id=?", Long.class, item);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_outbox WHERE aggregate_id=? AND event_type='PurchaseReturnConfirmed'",
                                receipt))
                .isEqualTo(2);
        delivery.dispatch(
                io.github.acczff.mdop.integration.messaging.DeliveryService.Direction.OUTBOX);
        for (int attempt = 0;
                attempt < 50
                        && count(
                                        "SELECT COUNT(*) FROM integration_simulated_purchase_return WHERE receipt_id=?",
                                        receipt)
                                != 2;
                attempt++) Thread.sleep(100);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM integration_simulated_purchase_return WHERE receipt_id=?",
                                receipt))
                .isEqualTo(2);
        for (String message :
                db.queryForList(
                        "SELECT message_id FROM wms_outbox WHERE aggregate_id=? AND event_type='PurchaseReturnConfirmed'",
                        String.class,
                        receipt)) {
            simulator.erp(
                    new org.springframework.amqp.core.Message(
                            json.writeValueAsBytes(delivery.outgoing(message))));
        }
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM integration_simulated_purchase_return WHERE receipt_id=?",
                                receipt))
                .isEqualTo(2);
        assertThat(
                        db.queryForObject(
                                "SELECT received_qty FROM wms_arrival_notice_item WHERE id=?",
                                BigDecimal.class,
                                line))
                .isEqualByComparingTo("100");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM integration_simulated_result WHERE aggregate_id=? AND event_type='PurchaseReturnConfirmed'",
                                receipt))
                .isEqualTo(2);
        assertThat(
                        delivery.results().stream()
                                .filter(
                                        r ->
                                                r.get("event_type")
                                                                .equals("PurchaseReturnConfirmed")
                                                        && ((Number) r.get("aggregate_id"))
                                                                        .longValue()
                                                                == receipt))
                .allMatch(r -> r.get("downstream_state").equals("RETURNED"));
    }

    @Test
    void pendingAndApprovedRequestsReserveQuotaAndRejectionReleasesIt() throws Exception {
        long item = rejectedItem();
        long id = apply(item, 20).get("id").asLong();
        var body =
                Map.of(
                        "idempotencyKey",
                        key(),
                        "receiptItemId",
                        item,
                        "quantity",
                        1,
                        "reason",
                        "超过额度");
        postAs("/api/v1/wms/purchase-returns", body, admin(), 409);
        postAs(
                "/api/v1/wms/purchase-returns/" + id + "/decision",
                decision(0, "REJECT"),
                operator("bob", "wms:return:approve"),
                200);
        long next = apply(item, 20).get("id").asLong();
        approve(next);
        postAs("/api/v1/wms/purchase-returns", body, admin(), 409);
        var cancel = Map.of("idempotencyKey", key(), "version", 1, "reason", "暂缓退货");
        postAs(
                "/api/v1/wms/purchase-returns/" + next + "/cancel",
                cancel,
                operator("alice", "wms:return:create"),
                200);
        postAs(
                "/api/v1/wms/purchase-returns/" + next + "/cancel",
                cancel,
                operator("alice", "wms:return:create"),
                200);
        confirm(next, confirmation(), 409);
        apply(item, 20);
        assertThat(stock()).isEqualByComparingTo("100");
    }

    @Test
    void selfApprovalForeignWarehouseMissingPermissionAndCsrfAreDenied() throws Exception {
        long item = rejectedItem(), id = apply(item, 20).get("id").asLong();
        postAs(
                "/api/v1/wms/purchase-returns/" + id + "/decision",
                decision(0, "APPROVE"),
                operator("alice", "wms:return:approve"),
                403);
        postAs(
                "/api/v1/wms/purchase-returns/" + id + "/decision",
                decision(0, "APPROVE"),
                operator("bob", "wms:return:read"),
                403);
        postAs(
                "/api/v1/wms/purchase-returns/" + id + "/decision",
                decision(0, "APPROVE"),
                user("outsider").authorities(new SimpleGrantedAuthority("wms:return:approve")),
                403);
        mvc.perform(
                        get("/api/v1/wms/purchase-returns/" + id + "/history")
                                .with(
                                        user("outsider")
                                                .authorities(
                                                        new SimpleGrantedAuthority(
                                                                "wms:return:read"))))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/wms/purchase-returns/" + id + "/decision")
                                .with(admin())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(decision(0, "APPROVE"))))
                .andExpect(status().isForbidden());
        postAs(
                "/api/v1/wms/purchase-returns/" + id + "/cancel",
                Map.of("idempotencyKey", key(), "version", 0, "reason", "其他人撤销"),
                admin(),
                403);
        confirm(id, confirmation(), 409);
        assertThat(stock()).isEqualByComparingTo("100");
    }

    @Test
    void originalCreateReplaysButChangedContentAndStaleVersionAreRejected() throws Exception {
        long item = rejectedItem();
        String requestKey = key();
        var input =
                Map.of(
                        "idempotencyKey",
                        requestKey,
                        "receiptItemId",
                        item,
                        "quantity",
                        10,
                        "reason",
                        "退货");
        var first = postAs("/api/v1/wms/purchase-returns", input, admin(), 201);
        assertThat(postAs("/api/v1/wms/purchase-returns", input, admin(), 201).get("id"))
                .isEqualTo(first.get("id"));
        postAs(
                "/api/v1/wms/purchase-returns",
                Map.of(
                        "idempotencyKey",
                        requestKey,
                        "receiptItemId",
                        item,
                        "quantity",
                        11,
                        "reason",
                        "退货"),
                admin(),
                409);
        postAs(
                "/api/v1/wms/purchase-returns/" + first.get("id").asLong() + "/decision",
                decision(9, "APPROVE"),
                operator("bob", "wms:return:approve"),
                409);
    }

    @Test
    void outboxFailureRollsBackInventoryLedgerStatusAndAudit() throws Exception {
        long item = rejectedItem(), id = apply(item, 20).get("id").asLong();
        approve(id);
        db.execute(
                "ALTER TABLE wms_outbox ADD CONSTRAINT test_block_return CHECK(NOT(event_type='PurchaseReturnConfirmed' AND business_key='"
                        + id
                        + "'))");
        var input = confirmation();
        try {
            confirm(id, input, 503);
        } finally {
            db.execute("ALTER TABLE wms_outbox DROP CHECK test_block_return");
        }
        assertThat(stock()).isEqualByComparingTo("100");
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM wms_purchase_return WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("APPROVED");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE purchase_return_id=?",
                                id))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_purchase_return_audit WHERE return_id=? AND action='RETURNED'",
                                id))
                .isZero();
        confirm(id, input, 200);
        assertThat(stock()).isEqualByComparingTo("80");
    }

    @Test
    void concurrentApplicationsCannotExceedRejectedQuantity() throws Exception {
        long item = rejectedItem();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<Integer> task =
                    () -> {
                        gate.await();
                        return mvc.perform(
                                        post("/api/v1/wms/purchase-returns")
                                                .with(admin())
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "idempotencyKey",
                                                                        key(),
                                                                        "receiptItemId",
                                                                        item,
                                                                        "quantity",
                                                                        15,
                                                                        "reason",
                                                                        "并发申请"))))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var a = pool.submit(task);
            var b = pool.submit(task);
            gate.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
    }

    @Test
    void concurrentConfirmAndCancellationCannotBothSucceed() throws Exception {
        long item = rejectedItem(), id = apply(item, 20).get("id").asLong();
        approve(id);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var confirm =
                    pool.submit(
                            () -> {
                                gate.await();
                                return mvc.perform(
                                                post("/api/v1/wms/purchase-returns/"
                                                                + id
                                                                + "/confirm")
                                                        .with(
                                                                operator(
                                                                        "alice",
                                                                        "wms:return:confirm"))
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json.writeValueAsString(
                                                                        confirmation())))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var cancel =
                    pool.submit(
                            () -> {
                                gate.await();
                                return mvc.perform(
                                                post("/api/v1/wms/purchase-returns/"
                                                                + id
                                                                + "/cancel")
                                                        .with(
                                                                operator(
                                                                        "alice",
                                                                        "wms:return:create"))
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                json.writeValueAsString(
                                                                        Map.of(
                                                                                "idempotencyKey",
                                                                                key(),
                                                                                "version",
                                                                                1,
                                                                                "reason",
                                                                                "撤销"))))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            gate.countDown();
            assertThat(List.of(confirm.get(20, TimeUnit.SECONDS), cancel.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        String state =
                db.queryForObject(
                        "SELECT status FROM wms_purchase_return WHERE id=?", String.class, id);
        assertThat(stock()).isEqualByComparingTo(state.equals("RETURNED") ? "80" : "100");
    }

    @Test
    void uninspectedOrQualifiedOnlyStockCannotBeReturned() throws Exception {
        long receipt = receive(100);
        long item =
                db.queryForObject(
                        "SELECT id FROM wms_receipt_item WHERE receipt_id=?", Long.class, receipt);
        var input =
                Map.of(
                        "idempotencyKey",
                        key(),
                        "receiptItemId",
                        item,
                        "quantity",
                        1,
                        "reason",
                        "错误退货");
        postAs("/api/v1/wms/purchase-returns", input, admin(), 409);
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
                        "全部合格",
                        "items",
                        List.of(
                                Map.of(
                                        "receiptItemId",
                                        item,
                                        "qualifiedQty",
                                        100,
                                        "rejectedQty",
                                        0))),
                admin(),
                200);
        postAs("/api/v1/wms/purchase-returns", input, admin(), 409);
    }
}
