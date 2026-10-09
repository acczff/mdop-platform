package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ErpSalesTests extends InventoryScenarioSupport {
    static final String BASE = "/api/v1/sales/documents";
    long fg, stockId, storage, customer;

    @BeforeEach
    void salesSetup() throws Exception {
        finished();
        customer =
                postAs(
                                "/api/master-data/customers",
                                Map.of("code", "C" + key().substring(0, 8), "name", "客户"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
    }

    RequestPostProcessor sales(String name, String permission) {
        return user(name)
                .authorities(
                        new SimpleGrantedAuthority("sales:read"),
                        new SimpleGrantedAuthority("sales:" + permission),
                        new SimpleGrantedAuthority("wms:warehouse:" + fg));
    }

    RequestPostProcessor seller() {
        return sales("seller", "write");
    }

    RequestPostProcessor salesReviewer() {
        return sales("sales-reviewer", "review");
    }

    RequestPostProcessor pickerReview() {
        return user("warehouse-reviewer")
                .authorities(
                        new SimpleGrantedAuthority("wms:sales:review"),
                        new SimpleGrantedAuthority("wms:warehouse:" + fg));
    }

    JsonNode order(long id) throws Exception {
        return json.readTree(
                mvc.perform(get(BASE + "/" + id).with(seller()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    Map<String, Object> draft(String quantity) {
        var d = new HashMap<String, Object>();
        d.put("idempotencyKey", key());
        d.put("warehouseId", fg);
        d.put("customerId", customer);
        d.put("purpose", "客户采购成品");
        d.put("neededDate", "2026-12-01");
        d.put("customerReference", "CUSTOMER-001");
        d.put("lines", List.of(Map.of("materialId", material, "quantity", quantity)));
        return d;
    }

    long create(String quantity) throws Exception {
        return postAs(BASE, draft(quantity), seller(), 201).get("id").asLong();
    }

    Map<String, Object> command(long id) throws Exception {
        return Map.of(
                "idempotencyKey",
                key(),
                "version",
                order(id).get("version").asLong(),
                "reason",
                "业务核对");
    }

    JsonNode action(long id, String action, RequestPostProcessor actor, int status)
            throws Exception {
        return postAs(BASE + "/" + id + "/actions/" + action, command(id), actor, status);
    }

    long approved(String qty) throws Exception {
        long id = create(qty);
        action(id, "submit", seller(), 200);
        action(id, "approve", salesReviewer(), 200);
        return id;
    }

    Map<String, Object> arranging(long id, String qty) throws Exception {
        var d = new HashMap<>(command(id));
        d.put("orderLineId", order(id).get("lines").get(0).get("id").asLong());
        d.put("quantity", qty);
        d.put("expectedDate", "2026-12-01");
        return d;
    }

    long arrange(long id, String qty) throws Exception {
        var d = postAs(BASE + "/" + id + "/arrangements", arranging(id, qty), seller(), 200);
        return d.get("arrangements").get(d.get("arrangements").size() - 1).get("id").asLong();
    }

    String route(long id, long arrangement, String action) {
        return BASE + "/" + id + "/arrangements/" + arrangement + "/" + action;
    }

    long deliver(long id, long a) throws Exception {
        postAs(route(id, a, "deliver"), command(id), seller(), 200);
        return db.queryForObject(
                "SELECT wms_sales_id FROM sal_arrangement WHERE id=?", Long.class, a);
    }

    String wms(long id, String action) {
        return "/api/v1/wms/sales-orders/" + id + "/" + action;
    }

    void reserve(long id, int status) throws Exception {
        postAs(wms(id, "reserve"), Map.of("balanceId", stockId), admin(), status);
    }

    long pick(long id, String quantity) throws Exception {
        return postAs(
                        wms(id, "pick"),
                        Map.of("requestKey", key(), "batchNo", "B1", "quantity", quantity),
                        admin(),
                        200)
                .get("pick_id")
                .asLong();
    }

    void review(long id, long pick, boolean approved) throws Exception {
        postAs(
                wms(id, "review"),
                Map.of(
                        "requestKey",
                        key(),
                        "pickId",
                        pick,
                        "approved",
                        approved,
                        "reason",
                        "独立核对实物"),
                pickerReview(),
                200);
    }

    void ship(long id, int status) throws Exception {
        postAs(wms(id, "ship"), Map.of(), admin(), status);
    }

    void execute(long id, String qty) throws Exception {
        reserve(id, 200);
        review(id, pick(id, qty), true);
        ship(id, 200);
    }

    @Test
    void auditSeparatesFormalFactsFromSimulatorFeedback() throws Exception {
        long id = approved("10"), a = arrange(id, "10"), w = deliver(id, a);
        execute(w, "10");
        long baseline = salesFeedbackMismatches();
        assertThat(baseline).isZero();
        String message = key();
        try {
            db.update(
                    "INSERT INTO wms_outbox(message_id,event_type,aggregate_type,aggregate_id,trace_id,payload,occurred_at) VALUES(?,'SalesOutboundConfirmed','SalesOrder',?,?,'{}',NOW(6))",
                    message,
                    w,
                    key());
            assertThat(salesFeedbackMismatches()).isEqualTo(baseline + 1);
        } finally {
            db.update("DELETE FROM wms_outbox WHERE message_id=?", message);
        }
        assertThat(salesFeedbackMismatches()).isEqualTo(baseline);
    }

    @Test
    void partialShipmentsCloseOnlyActualQuantityAndPreserveSnapshot() throws Exception {
        long id = approved("100");
        long a = arrange(id, "60"), b = arrange(id, "40");
        postAs(BASE + "/" + id + "/arrangements", arranging(id, "0.000001"), seller(), 409);
        var send = command(id);
        var first = postAs(route(id, a, "deliver"), send, seller(), 200);
        long wa = first.get("arrangements").get(0).get("wms_sales_id").asLong();
        postAs(route(id, a, "deliver"), send, seller(), 200);
        long wb = deliver(id, b);
        reserve(wa, 200);
        long p = pick(wa, "60");
        postAs(
                wms(wa, "review"),
                Map.of("requestKey", key(), "pickId", p, "approved", true, "reason", "不能自审"),
                admin(),
                409);
        review(wa, p, false);
        review(wa, pick(wa, "60"), true);
        assertThat(order(id).get("fulfillment").get("lines").get(0).get("shipped").asText())
                .isEqualTo("0.000000");
        action(id, "close", seller(), 409);
        ship(wa, 200);
        ship(wa, 200);
        assertThat(qty(stockId)).isEqualByComparingTo("40");
        assertThat(order(id).get("fulfillment").get("lines").get(0).get("remaining").asText())
                .isEqualTo("40.000000");
        action(id, "close", seller(), 409);
        execute(wb, "40");
        var closing = command(id);
        var closed = postAs(BASE + "/" + id + "/actions/close", closing, seller(), 200);
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("closureMatches").asBoolean()).isTrue();
        postAs(BASE + "/" + id + "/actions/close", closing, seller(), 200);
        assertThat(qty(stockId)).isZero();
        assertThat(stock(stockId, "reserved_qty")).isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM sal_closure WHERE document_id=?",
                                Long.class,
                                id))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE sales_order_id IN (?,?)",
                                Long.class,
                                wa,
                                wb))
                .isEqualTo(2);
        var before = closed.get("closure").get("snapshot").asText();
        db.update("UPDATE mdm_customer SET name='改名客户' WHERE id=?", customer);
        assertThat(order(id).get("customer_name").asText()).isEqualTo("客户");
        assertThat(order(id).get("closure").get("snapshot").asText()).isEqualTo(before);
        action(id, "cancel", seller(), 409);
        postAs(route(id, a, "withdraw"), command(id), seller(), 409);
    }

    @Test
    void cancelRequiresConfirmedWithdrawalAndReleasesReservationOnce() throws Exception {
        long id = approved("20"), a = arrange(id, "20"), w = deliver(id, a);
        reserve(w, 200);
        action(id, "cancel", seller(), 409);
        var withdrawal = command(id);
        postAs(route(id, a, "withdraw"), withdrawal, seller(), 200);
        postAs(route(id, a, "withdraw"), withdrawal, seller(), 200);
        assertThat(stock(stockId, "reserved_qty")).isZero();
        assertThat(stock(stockId, "available_qty")).isEqualByComparingTo("100");
        assertThat(order(id).get("status").asText()).isEqualTo("APPROVED");
        long retry = arrange(id, "20");
        postAs(route(id, retry, "withdraw"), command(id), seller(), 200);
        action(id, "cancel", seller(), 200);
        assertThat(qty(stockId)).isEqualByComparingTo("100");
    }

    @Test
    void warehouseCancellationMustBeAcknowledgedBeforeRearranging() throws Exception {
        long id = approved("20"), a = arrange(id, "20"), w = deliver(id, a);
        reserve(w, 200);
        postAs(wms(w, "cancel"), Map.of("reason", "仓库发现发货信息有误"), admin(), 200);
        postAs(BASE + "/" + id + "/arrangements", arranging(id, "1"), seller(), 409);
        assertThat(order(id).get("fulfillment").get("blockers").toString()).contains("请确认撤回安排");
        postAs(route(id, a, "withdraw"), command(id), seller(), 200);
        arrange(id, "20");
    }

    @Test
    void permissionsSelfApprovalVersionsCsrfAndIdempotencyAreEnforced() throws Exception {
        var body = draft("5");
        long id = postAs(BASE, body, seller(), 201).get("id").asLong();
        assertThat(postAs(BASE, body, seller(), 201).get("id").asLong()).isEqualTo(id);
        body.put("purpose", "请求键换载荷");
        postAs(BASE, body, seller(), 409);
        mvc.perform(
                        post(BASE)
                                .with(seller())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(draft("1"))))
                .andExpect(status().isForbidden());
        postAs(BASE, draft("1"), sales("reader", "read"), 403);
        mvc.perform(
                        get(BASE + "/" + id)
                                .with(
                                        user("wrong-warehouse")
                                                .authorities(
                                                        new SimpleGrantedAuthority("sales:read"))))
                .andExpect(status().isForbidden());
        var stale = command(id);
        action(id, "submit", seller(), 200);
        action(id, "approve", sales("seller", "review"), 409);
        postAs(BASE + "/" + id + "/actions/approve", stale, salesReviewer(), 409);
        action(id, "reject", salesReviewer(), 200);
        var edit = draft("6");
        edit.put("version", order(id).get("version").asLong());
        edit.put("reason", "调整数量");
        mvc.perform(
                        put(BASE + "/" + id)
                                .with(seller())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(edit)))
                .andExpect(status().isOk());
        action(id, "submit", seller(), 200);
        action(id, "approve", salesReviewer(), 200);
        edit.put("idempotencyKey", key());
        mvc.perform(
                        put(BASE + "/" + id)
                                .with(seller())
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(edit)))
                .andExpect(status().isConflict());
    }

    @Test
    void deliveryFailureRetainsAllocationAndAllowsSameRequestRetry() throws Exception {
        long id = approved("5"), a = arrange(id, "5");
        var send = command(id);
        db.update("UPDATE mdm_customer SET status='DISABLED' WHERE id=?", customer);
        postAs(route(id, a, "deliver"), send, seller(), 409);
        var state = order(id);
        assertThat(state.get("arrangements").get(0).get("last_error").isNull()).isFalse();
        assertThat(state.get("arrangements").get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_sales_order WHERE sales_arrangement_id=?",
                                Long.class,
                                a))
                .isZero();
        postAs(BASE + "/" + id + "/arrangements", arranging(id, "1"), seller(), 409);
        db.update("UPDATE mdm_customer SET status='ENABLED' WHERE id=?", customer);
        postAs(route(id, a, "deliver"), send, seller(), 200);
        postAs(route(id, a, "deliver"), send, seller(), 200);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_sales_order WHERE sales_arrangement_id=?",
                                Long.class,
                                a))
                .isEqualTo(1);
    }

    @Test
    void shortageFreezeAndExpiryDoNotAdvanceFulfillment() throws Exception {
        long id = approved("101"), a = arrange(id, "101"), w = deliver(id, a);
        reserve(w, 409);
        postAs(route(id, a, "withdraw"), command(id), seller(), 200);
        long b = arrange(id, "10"), wb = deliver(id, b);
        reserve(wb, 200);
        review(wb, pick(wb, "10"), true);
        long freeze =
                postAs(
                                "/api/v1/wms/freezes",
                                Map.of(
                                        "balanceId",
                                        stockId,
                                        "idempotencyKey",
                                        key(),
                                        "reason",
                                        "批次调查"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        ship(wb, 409);
        assertThat(qty(stockId)).isEqualByComparingTo("100");
        postAs(
                "/api/v1/wms/freezes/" + freeze + "/release",
                Map.of("idempotencyKey", key(), "reason", "调查完成"),
                user("other").roles("ADMIN"),
                200);
        db.update("UPDATE wms_inventory_balance SET expiry_date='2000-01-01' WHERE id=?", stockId);
        ship(wb, 409);
        assertThat(order(id).get("fulfillment").get("lines").get(0).get("shipped").asText())
                .isEqualTo("0.000000");
        action(id, "close", seller(), 409);
        postAs(route(id, b, "withdraw"), command(id), seller(), 200);
    }

    @Test
    void concurrentArrangementsAndDeliveryCannotExceedAuthorizationOrDuplicateExecution()
            throws Exception {
        long id = approved("10");
        var one = arranging(id, "7");
        var two = new HashMap<>(one);
        two.put("idempotencyKey", key());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var tasks = new ArrayList<Future<Integer>>();
            for (var input : List.of(one, two))
                tasks.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return mvc.perform(
                                                    post(BASE + "/" + id + "/arrangements")
                                                            .with(seller())
                                                            .with(csrf())
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(
                                                                    json.writeValueAsString(input)))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            gate.countDown();
            assertThat(
                            List.of(
                                    tasks.get(0).get(30, TimeUnit.SECONDS),
                                    tasks.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            long a = order(id).get("arrangements").get(0).get("id").asLong();
            var send = command(id);
            var sendGate = new CountDownLatch(1);
            tasks.clear();
            for (int i = 0; i < 2; i++)
                tasks.add(
                        pool.submit(
                                () -> {
                                    sendGate.await();
                                    return mvc.perform(
                                                    post(route(id, a, "deliver"))
                                                            .with(seller())
                                                            .with(csrf())
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(json.writeValueAsString(send)))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            sendGate.countDown();
            assertThat(
                            List.of(
                                    tasks.get(0).get(30, TimeUnit.SECONDS),
                                    tasks.get(1).get(30, TimeUnit.SECONDS)))
                    .containsOnly(200);
            assertThat(
                            db.queryForObject(
                                    "SELECT COUNT(*) FROM wms_sales_order WHERE sales_arrangement_id=?",
                                    Long.class,
                                    a))
                    .isEqualTo(1);
        }
    }

    @Test
    void simulatorCannotOverwriteFormalSourceAndClosedFactsMismatchIsVisible() throws Exception {
        postAs(
                "/api/local/sales-orders",
                Map.of(
                        "demandNo",
                        "mdop-sales-future-id",
                        "salesOrderNo",
                        "fake",
                        "customerReference",
                        "模拟不能抢占正式前缀",
                        "warehouseId",
                        fg,
                        "materialId",
                        material,
                        "quantity",
                        "1"),
                admin(),
                409);
        long id = approved("1"), a = arrange(id, "1"), w = deliver(id, a);
        postAs(
                "/api/local/sales-orders",
                Map.of(
                        "demandNo",
                        "MDOP-SALES-" + a,
                        "salesOrderNo",
                        order(id).get("document_no").asText(),
                        "customerReference",
                        "伪造来源",
                        "warehouseId",
                        fg,
                        "materialId",
                        material,
                        "quantity",
                        "1"),
                admin(),
                409);
        execute(w, "1");
        action(id, "close", seller(), 200);
        db.update(
                "UPDATE wms_inventory_transaction SET change_qty=-0.5,after_qty=before_qty-0.5 WHERE sales_order_id=?",
                w);
        assertThat(order(id).get("closureMatches").asBoolean()).isFalse();
        assertThat(order(id).get("fulfillment").get("blockers").toString()).contains("流水不一致");
    }

    @Test
    void disabledMastersAndRawWarehouseCannotAuthorizeNewOrders() throws Exception {
        var body = draft("1");
        body.put("warehouseId", warehouse);
        postAs(
                BASE,
                body,
                user("seller")
                        .authorities(
                                new SimpleGrantedAuthority("sales:write"),
                                new SimpleGrantedAuthority("wms:warehouse:" + warehouse)),
                409);
        db.update("UPDATE mdm_customer SET status='DISABLED' WHERE id=?", customer);
        postAs(BASE, draft("1"), seller(), 409);
        db.update("UPDATE mdm_customer SET status='ENABLED' WHERE id=?", customer);
        long id = create("1");
        db.update("UPDATE mdm_material SET status='DISABLED' WHERE id=?", material);
        action(id, "submit", seller(), 409);
    }

    @Test
    void racingWithdrawalAndActualShipmentHasOnlyOnePhysicalOutcome() throws Exception {
        long id = approved("10"), a = arrange(id, "10"), w = deliver(id, a);
        reserve(w, 200);
        review(w, pick(w, "10"), true);
        var withdraw = command(id);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var shipped =
                    pool.submit(
                            () -> {
                                gate.await();
                                return mvc.perform(
                                                post(wms(w, "ship"))
                                                        .with(admin())
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content("{}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            var withdrawn =
                    pool.submit(
                            () -> {
                                gate.await();
                                return mvc.perform(
                                                post(route(id, a, "withdraw"))
                                                        .with(seller())
                                                        .with(csrf())
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(json.writeValueAsString(withdraw)))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            });
            gate.countDown();
            int shipStatus = shipped.get(30, TimeUnit.SECONDS),
                    withdrawStatus = withdrawn.get(30, TimeUnit.SECONDS);
            assertThat(List.of(shipStatus, withdrawStatus)).containsExactlyInAnyOrder(200, 409);
            assertThat(stock(stockId, "reserved_qty")).isZero();
            assertThat(qty(stockId)).isEqualByComparingTo(shipStatus == 200 ? "90" : "100");
            assertThat(order(id).get("fulfillment").get("lines").get(0).get("shipped").asText())
                    .isEqualTo(shipStatus == 200 ? "10.000000" : "0.000000");
        }
    }

    @Test
    void shippedStockSourceMismatchBlocksClosureAndFormalShipmentHasNoSimulatorOutbox()
            throws Exception {
        long id = approved("1"), a = arrange(id, "1"), w = deliver(id, a);
        execute(w, "1");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_outbox WHERE aggregate_type='SalesOrder' AND aggregate_id=?",
                                Long.class,
                                w))
                .isZero();
        // A second genuine balance allows a source mismatch without violating stock foreign keys.
        long originalStock = stockId;
        finished();
        db.update(
                "UPDATE wms_inventory_transaction SET balance_id=? WHERE sales_order_id=?",
                stockId,
                w);
        fg = db.queryForObject("SELECT warehouse_id FROM sal_document WHERE id=?", Long.class, id);
        action(id, "close", seller(), 409);
        assertThat(order(id).get("fulfillment").get("blockers").toString()).contains("流水不一致");
        db.update(
                "UPDATE wms_inventory_transaction SET balance_id=? WHERE sales_order_id=?",
                originalStock,
                w);
        action(id, "close", seller(), 200);
    }

    @Test
    void dispatchKeepsApprovedNamesEvenWhenCatalogIsRenamed() throws Exception {
        String longName = "客".repeat(100);
        db.update("UPDATE mdm_customer SET name=? WHERE id=?", longName, customer);
        long id = approved("1");
        db.update("UPDATE mdm_customer SET name='新名称' WHERE id=?", customer);
        db.update("UPDATE mdm_material SET name='新物料名称' WHERE id=?", material);
        long a = arrange(id, "1"), w = deliver(id, a);
        assertThat(
                        db.queryForObject(
                                "SELECT customer_reference FROM wms_sales_order WHERE id=?",
                                String.class,
                                w))
                .isEqualTo(longName);
        assertThat(
                        db.queryForObject(
                                "SELECT material_name FROM wms_sales_order WHERE id=?",
                                String.class,
                                w))
                .isEqualTo("物料");
    }

    @Test
    void mergedLinesRemainIndependentlyAuthorizedAndCannotBorrowAnotherOrderLine()
            throws Exception {
        long second =
                postAs(
                                "/api/master-data/materials",
                                Map.of(
                                        "code",
                                        "M" + key().substring(0, 8),
                                        "name",
                                        "第二种成品",
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
        var body = draft("1");
        body.put(
                "lines",
                List.of(
                        Map.of("materialId", material, "quantity", "0.5"),
                        Map.of("materialId", second, "quantity", "2"),
                        Map.of("materialId", material, "quantity", "0.5")));
        long id = postAs(BASE, body, seller(), 201).get("id").asLong();
        action(id, "submit", seller(), 200);
        action(id, "approve", salesReviewer(), 200);
        assertThat(order(id).get("lines")).hasSize(2);
        assertThat(order(id).get("lines").get(0).get("quantity").asText()).isEqualTo("1.000000");
        long a = arrange(id, "1"), w = deliver(id, a);
        execute(w, "1");
        action(id, "close", seller(), 409);
        assertThat(order(id).get("fulfillment").get("lines").get(1).get("remaining").asText())
                .isEqualTo("2.000000");
        long other = approved("1");
        var foreign = arranging(other, "1");
        foreign.put("version", order(id).get("version").asLong());
        postAs(BASE + "/" + id + "/arrangements", foreign, seller(), 400);
    }

    void finished() throws Exception {
        fg =
                postAs(
                                "/api/master-data/warehouses",
                                Map.of(
                                        "code",
                                        "FG" + key().substring(0, 8).toUpperCase(Locale.ROOT),
                                        "name",
                                        "销售验收仓",
                                        "purpose",
                                        "FINISHED_GOODS",
                                        "form",
                                        "PHYSICAL",
                                        "managementCategory",
                                        "GENERAL"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        long inspection =
                postAs(
                                "/api/master-data/locations",
                                Map.of(
                                        "warehouseId",
                                        fg,
                                        "code",
                                        "IN",
                                        "name",
                                        "待检",
                                        "areaType",
                                        "INSPECTION"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        storage =
                postAs(
                                "/api/master-data/locations",
                                Map.of(
                                        "warehouseId",
                                        fg,
                                        "code",
                                        "ST",
                                        "name",
                                        "存储",
                                        "areaType",
                                        "STORAGE"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        long f =
                postAs(
                                "/api/local/finished-receipts",
                                Map.of(
                                        "demandNo",
                                        key(),
                                        "workOrderNo",
                                        "WO1",
                                        "warehouseId",
                                        fg,
                                        "materialId",
                                        material,
                                        "quantity",
                                        "100",
                                        "batchNo",
                                        "B1",
                                        "productionDate",
                                        java.time.LocalDate.now().toString(),
                                        "expiryDate",
                                        "2099-01-01"),
                                admin(),
                                201)
                        .get("id")
                        .asLong();
        postAs(
                "/api/v1/wms/finished-receipts/" + f + "/receive",
                Map.of("locationId", inspection),
                admin(),
                200);
        postAs(
                "/api/local/finished-receipts/" + f + "/quality",
                Map.of("eventNo", key(), "result", "QUALIFIED", "reason", "合格"),
                admin(),
                200);
        stockId =
                postAs(
                                "/api/v1/wms/finished-receipts/" + f + "/putaway",
                                Map.of("locationId", storage),
                                admin(),
                                200)
                        .get("stored_balance_id")
                        .asLong();
    }
}
