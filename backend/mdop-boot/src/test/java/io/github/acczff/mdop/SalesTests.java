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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(
        properties = {"mdop.messaging.enabled=true", "mdop.messaging.initial-delay-ms=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SalesTests extends InventoryScenarioSupport {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean java.time.Clock clock;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"2026-10-06T16:00:00Z", "2026-10-06T23:59:59Z", "2026-10-07T00:00:00Z"})
    void expiredAtBusinessMidnightCannotBeReservedOrShipped(String instant) throws Exception {
        finished();
        db.update("UPDATE wms_inventory_balance SET expiry_date='2026-10-06' WHERE id=?", stockId);
        org.mockito.Mockito.doReturn(java.time.Instant.parse("2026-10-06T15:59:59Z"))
                .when(clock)
                .instant();
        long id = sale("2");
        reserveSale(id, 200);
        long p = pick(id, "2");
        postAs(path(id, "review"), reviewing(p, true), reviewer(), 200);
        org.mockito.Mockito.doReturn(java.time.Instant.parse(instant)).when(clock).instant();
        reserveSale(sale("1"), 409);
        ship(id, 409);
        assertThat(read("/api/v1/wms/sales-orders/" + id).get("status").asText())
                .isEqualTo("VERIFIED");
        assertThat(qty(stockId)).isEqualByComparingTo("10.123456");
        assertThat(stock(stockId, "reserved_qty")).isEqualByComparingTo("2");
        assertThat(read(path(id, "reservations"))).hasSize(1);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE sales_order_id=?",
                                Long.class,
                                id))
                .isZero();
    }

    @org.springframework.test.context.DynamicPropertySource
    static void broker(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", RABBITMQ::getHost);
        r.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

    @org.springframework.beans.factory.annotation.Autowired
    io.github.acczff.mdop.integration.messaging.DeliveryService delivery;

    @org.springframework.beans.factory.annotation.Autowired
    io.github.acczff.mdop.integration.messaging.WarehouseResultStore store;

    long fg, stockId, storage;

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
                                        "10.123456",
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

    Map<String, Object> demandBody(String amount) {
        return Map.of(
                "demandNo",
                key(),
                "salesOrderNo",
                "SO1",
                "customerReference",
                "客户A",
                "warehouseId",
                fg,
                "materialId",
                material,
                "quantity",
                amount);
    }

    long sale(String amount) throws Exception {
        return postAs("/api/local/sales-orders", demandBody(amount), admin(), 201)
                .get("id")
                .asLong();
    }

    void reserveSale(long id, int status) throws Exception {
        postAs(path(id, "reserve"), Map.of("balanceId", stockId), admin(), status);
    }

    String path(long id, String action) {
        return "/api/v1/wms/sales-orders/" + id + "/" + action;
    }

    RequestPostProcessor reviewer() {
        return user("reviewer")
                .authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "wms:sales:review"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "wms:warehouse:" + fg));
    }

    Map<String, Object> picking(String amount) {
        return Map.of("requestKey", key(), "batchNo", "B1", "quantity", amount);
    }

    long pick(long id, String amount) throws Exception {
        return postAs(path(id, "pick"), picking(amount), admin(), 200).get("pick_id").asLong();
    }

    Map<String, Object> reviewing(long pick, boolean approved) {
        return Map.of(
                "requestKey",
                key(),
                "pickId",
                pick,
                "approved",
                approved,
                "reason",
                approved ? "实物复核一致" : "重新拣货核对");
    }

    void ship(long id, int status) throws Exception {
        postAs(path(id, "ship"), Map.of(), admin(), status);
    }

    @Test
    void completeFlowOnlyDeductsPhysicalStockAtShippingAndPublishesOnce() throws Exception {
        finished();
        var body = demandBody("3.123456");
        long id = postAs("/api/local/sales-orders", body, admin(), 201).get("id").asLong();
        assertThat(postAs("/api/local/sales-orders", body, admin(), 201).get("id").asLong())
                .isEqualTo(id);
        reserveSale(id, 200);
        reserveSale(id, 200);
        assertThat(qty(stockId)).isEqualByComparingTo("10.123456");
        assertThat(stock(stockId, "available_qty")).isEqualByComparingTo("7");
        long p = pick(id, "3.123456");
        postAs(path(id, "review"), reviewing(p, true), reviewer(), 200);
        assertThat(qty(stockId)).isEqualByComparingTo("10.123456");
        ship(id, 200);
        ship(id, 200);
        assertThat(qty(stockId)).isEqualByComparingTo("7");
        assertThat(stock(stockId, "reserved_qty")).isZero();
        assertThat(read(path(id, "reservations"))).hasSize(2);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE sales_order_id=?",
                                Long.class,
                                id))
                .isEqualTo(1);
        delivery.dispatch(
                io.github.acczff.mdop.integration.messaging.DeliveryService.Direction.OUTBOX);
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                db.queryForObject(
                                                        "SELECT COUNT(*) FROM integration_warehouse_result WHERE aggregate_type='SalesOrder' AND aggregate_id=?",
                                                        Long.class,
                                                        id))
                                        .isEqualTo(1));
        String message =
                db.queryForObject(
                        "SELECT message_id FROM wms_outbox WHERE aggregate_type='SalesOrder' AND aggregate_id=?",
                        String.class,
                        id);
        var e = delivery.outgoing(message);
        store.accept(e);
        var changed = e.payload().deepCopy();
        ((tools.jackson.databind.node.ObjectNode) changed).put("customerReference", "不同客户");
        var bad =
                new io.github.acczff.mdop.integration.messaging.EventEnvelope(
                        key(),
                        e.eventType(),
                        1,
                        "WMS",
                        e.occurredAt(),
                        e.traceId(),
                        e.aggregateType(),
                        e.aggregateId(),
                        changed);
        assertThatThrownBy(() -> store.accept(bad))
                .isInstanceOf(io.github.acczff.mdop.common.BusinessException.class);
        assertThat(
                        read("/api/integration/sales-orders/" + id + "/feedback")
                                .get(0)
                                .get("received_at")
                                .isNull())
                .isFalse();
        postAs(path(id, "cancel"), Map.of("reason", "已发货不可取消"), admin(), 409);
    }

    @Test
    void rejectRequiresRepickAndOldRequestCannotReopenOrApproveNewPick() throws Exception {
        finished();
        long id = sale("2");
        reserveSale(id, 200);
        ship(id, 409);
        postAs(path(id, "pick"), picking("3"), admin(), 409);
        var pick = picking("2");
        long p = postAs(path(id, "pick"), pick, admin(), 200).get("pick_id").asLong();
        postAs(path(id, "review"), reviewing(p, true), admin(), 409);
        var reject = reviewing(p, false);
        postAs(path(id, "review"), reject, reviewer(), 200);
        postAs(path(id, "pick"), pick, admin(), 200);
        assertThat(read("/api/v1/wms/sales-orders/" + id).get("status").asText())
                .isEqualTo("RESERVED");
        long next = pick(id, "2");
        postAs(path(id, "review"), reviewing(p, true), reviewer(), 409);
        postAs(path(id, "review"), reject, reviewer(), 200);
        assertThat(read("/api/v1/wms/sales-orders/" + id).get("status").asText())
                .isEqualTo("PICKED");
        postAs(path(id, "review"), reviewing(next, true), reviewer(), 200);
        assertThat(read(path(id, "history"))).hasSize(4);
    }

    @Test
    void cancellationReleasesReservationsAndInvalidatesCountEvenWithoutPhysicalChange()
            throws Exception {
        finished();
        long c = count(stockId);
        long id = sale("2");
        reserveSale(id, 200);
        pick(id, "2");
        postAs(path(id, "cancel"), Map.of("reason", "实物已归还原库位"), admin(), 200);
        postAs(path(id, "cancel"), Map.of("reason", "实物已归还原库位"), admin(), 200);
        assertThat(stock(stockId, "reserved_qty")).isZero();
        assertThat(qty(stockId)).isEqualByComparingTo("10.123456");
        assertThat(stock(stockId, "available_qty")).isEqualByComparingTo("10.123456");
        submitCount(c, "9");
        postAs(
                "/api/v1/wms/counts/" + c + "/approve",
                Map.of(),
                user("counter").roles("ADMIN"),
                409);
        reserveSale(id, 409);
    }

    @Test
    void stockScopeExpiryQualityAndPermissionAreEnforced() throws Exception {
        finished();
        long id = sale("1");
        postAs(
                path(id, "reserve"),
                Map.of("balanceId", stockId),
                operator("outside", "wms:sales:reserve"),
                403);
        postAs(
                path(id, "reserve"),
                Map.of("balanceId", stockId),
                user("reader")
                        .authorities(
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("wms:sales:read"),
                                new org.springframework.security.core.authority
                                        .SimpleGrantedAuthority("wms:warehouse:" + fg)),
                403);
        mvc.perform(post(path(id, "reserve")).with(admin())).andExpect(status().isForbidden());
        db.update("UPDATE wms_inventory_balance SET expiry_date='2000-01-01' WHERE id=?", stockId);
        reserveSale(id, 409);
        db.update("UPDATE wms_inventory_balance SET expiry_date='2099-01-01' WHERE id=?", stockId);
        reserveSale(id, 200);
        long p = pick(id, "1");
        postAs(path(id, "review"), reviewing(p, true), reviewer(), 200);
        db.update("UPDATE wms_inventory_balance SET expiry_date='2000-01-01' WHERE id=?", stockId);
        ship(id, 409);
        assertThat(qty(stockId)).isEqualByComparingTo("10.123456");
        postAs(path(id, "cancel"), Map.of("reason", "过期取消"), admin(), 200);
    }

    @Test
    void simultaneousOrdersCannotOverreserveAndConcurrentShipmentDeductsOnce() throws Exception {
        finished();
        long a = sale("8"), b = sale("8");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> jobs = new ArrayList<>();
            for (long id : List.of(a, b))
                jobs.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return mvc.perform(
                                                    post(path(id, "reserve"))
                                                            .with(admin())
                                                            .with(csrf())
                                                            .contentType(MediaType.APPLICATION_JSON)
                                                            .content(
                                                                    json.writeValueAsString(
                                                                            Map.of(
                                                                                    "balanceId",
                                                                                    stockId))))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            gate.countDown();
            assertThat(
                            List.of(
                                    jobs.get(0).get(30, TimeUnit.SECONDS),
                                    jobs.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        long win =
                read("/api/v1/wms/sales-orders/" + a).get("status").asText().equals("RESERVED")
                        ? a
                        : b;
        long p = pick(win, "8");
        postAs(path(win, "review"), reviewing(p, true), reviewer(), 200);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var j1 =
                    pool.submit(
                            () -> {
                                ship(win, 200);
                                return true;
                            });
            var j2 =
                    pool.submit(
                            () -> {
                                ship(win, 200);
                                return true;
                            });
            assertThat(j1.get(30, TimeUnit.SECONDS) && j2.get(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(qty(stockId)).isEqualByComparingTo("2.123456");
    }

    @Test
    void outboxFailureRollsBackInventoryReservationAndState() throws Exception {
        finished();
        long id = sale("1");
        reserveSale(id, 200);
        long p = pick(id, "1");
        postAs(path(id, "review"), reviewing(p, true), reviewer(), 200);
        db.execute(
                "ALTER TABLE wms_outbox ADD CONSTRAINT test_sales_failure CHECK(aggregate_type<>'SalesOrder' OR aggregate_id<>"
                        + id
                        + ")");
        try {
            ship(id, 503);
        } finally {
            db.execute("ALTER TABLE wms_outbox DROP CHECK test_sales_failure");
        }
        assertThat(qty(stockId)).isEqualByComparingTo("10.123456");
        assertThat(stock(stockId, "reserved_qty")).isEqualByComparingTo("1");
        assertThat(read("/api/v1/wms/sales-orders/" + id).get("status").asText())
                .isEqualTo("VERIFIED");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE sales_order_id=?",
                                Long.class,
                                id))
                .isZero();
        ship(id, 200);
    }

    @Test
    void changedDemandWrongStockAndChangedActionKeysCannotBeAccepted() throws Exception {
        finished();
        var body = new HashMap<>(demandBody("1"));
        long id = postAs("/api/local/sales-orders", body, admin(), 201).get("id").asLong();
        body.put("customerReference", "客户B");
        postAs("/api/local/sales-orders", body, admin(), 409);
        body = new HashMap<>(demandBody("1"));
        body.put("warehouseId", warehouse);
        postAs("/api/local/sales-orders", body, admin(), 409);
        long raw = ready()[0];
        postAs(path(id, "reserve"), Map.of("balanceId", raw), admin(), 409);
        reserveSale(id, 200);
        var pick = new HashMap<>(picking("1"));
        postAs(path(id, "pick"), pick, admin(), 200);
        pick.put("quantity", "2");
        postAs(path(id, "pick"), pick, admin(), 409);
    }

    @Test
    void changingWarehousePurposeDoesNotTurnSelfProducedGoodsIntoPurchasedRawMaterial()
            throws Exception {
        finished();
        db.update("UPDATE mdm_warehouse SET purpose='RAW_MATERIAL' WHERE id=?", fg);
        warehouse = fg;
        lineSide();
        long id = demand("1");
        reserve(id, stockId, 409);
        assertThat(stock(stockId, "reserved_qty")).isZero();
    }
}
