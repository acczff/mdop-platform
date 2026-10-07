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
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        properties = {"mdop.messaging.enabled=true", "mdop.messaging.initial-delay-ms=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinishedGoodsTests extends InventoryScenarioSupport {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean java.time.Clock clock;

    long fg, inspection, storage;

    private <T> List<T> concurrentRetry(Callable<T> operation) throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<T>> jobs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                jobs.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    return operation.call();
                                }));
            }
            gate.countDown();
            return List.of(
                    jobs.get(0).get(30, TimeUnit.SECONDS), jobs.get(1).get(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentRetriesAtEveryStepKeepOneReceiptAndOneSetOfLedgers() throws Exception {
        finishedWarehouse();
        var body = demand();
        var ids =
                concurrentRetry(
                        () ->
                                postAs("/api/local/finished-receipts", body, admin(), 201)
                                        .get("id")
                                        .asLong());
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        long id = ids.get(0);
        String event = key();
        for (String step : List.of("receive", "quality", "putaway")) {
            concurrentRetry(
                    () -> {
                        switch (step) {
                            case "receive" -> receive(id, 200);
                            case "quality" -> quality(id, "QUALIFIED", event, 200);
                            default -> putaway(id, 200);
                        }
                        return true;
                    });
        }
        var receipt = read("/api/v1/wms/finished-receipts/" + id);
        assertThat(receipt.get("status").asText()).isEqualTo("STORED");
        assertThat(qty(receipt.get("received_balance_id").asLong())).isZero();
        assertThat(qty(receipt.get("quality_balance_id").asLong())).isZero();
        assertThat(qty(receipt.get("stored_balance_id").asLong()))
                .isEqualByComparingTo("10.123456");
        assertThat(stock(receipt.get("stored_balance_id").asLong(), "available_qty"))
                .isEqualByComparingTo("10.123456");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE finished_receipt_id=?",
                                Long.class,
                                id))
                .isEqualTo(5);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_outbox WHERE aggregate_type='FinishedReceipt' AND aggregate_id=?",
                                Long.class,
                                id))
                .isEqualTo(3);
    }

    @Test
    void shanghaiMidnightAcceptsTodayButRejectsTomorrowAndExpiredQualification() throws Exception {
        org.mockito.Mockito.doReturn(java.time.Instant.parse("2026-10-06T16:00:00Z"))
                .when(clock)
                .instant();
        finishedWarehouse();
        var body = new HashMap<>(demand());
        body.put("productionDate", "2026-10-07");
        postAs("/api/local/finished-receipts", body, admin(), 201);
        body.put("demandNo", key());
        body.put("productionDate", "2026-10-08");
        postAs("/api/local/finished-receipts", body, admin(), 409);
        body.put("productionDate", "2026-10-06");
        body.put("expiryDate", "2026-10-06");
        long id = postAs("/api/local/finished-receipts", body, admin(), 201).get("id").asLong();
        receive(id, 200);
        quality(id, "QUALIFIED", key(), 409);
        assertThat(read("/api/v1/wms/finished-receipts/" + id).get("status").asText())
                .isEqualTo("RECEIVED");
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
    io.github.acczff.mdop.integration.messaging.WarehouseResultStore resultStore;

    @Test
    void feedbackUsesActualBrokerAndRejectsChangedReplay() throws Exception {
        finishedWarehouse();
        long id = create();
        receive(id, 200);
        quality(id, "QUALIFIED", key(), 200);
        putaway(id, 200);
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            // The scheduler is disabled in this test. Simulate successive batches
                            // so earlier scenarios cannot starve this receipt beyond the 20-row
                            // limit.
                            delivery.dispatch(
                                    io.github.acczff.mdop.integration.messaging.DeliveryService
                                            .Direction.OUTBOX);
                            assertThat(
                                            db.queryForObject(
                                                    "SELECT COUNT(*) FROM integration_warehouse_result WHERE aggregate_type='FinishedReceipt' AND aggregate_id=?",
                                                    Long.class,
                                                    id))
                                    .isEqualTo(3);
                        });
        String message =
                db.queryForObject(
                        "SELECT message_id FROM wms_outbox WHERE aggregate_type='FinishedReceipt' AND aggregate_id=? AND event_type='FinishedGoodsPutaway'",
                        String.class,
                        id);
        var e = delivery.outgoing(message);
        resultStore.accept(e);
        var clone =
                new io.github.acczff.mdop.integration.messaging.EventEnvelope(
                        key(),
                        e.eventType(),
                        1,
                        "WMS",
                        e.occurredAt(),
                        e.traceId(),
                        e.aggregateType(),
                        e.aggregateId(),
                        e.payload());
        resultStore.accept(clone);
        var changed = e.payload().deepCopy();
        ((tools.jackson.databind.node.ObjectNode) changed).put("quantity", "99");
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
        assertThatThrownBy(() -> resultStore.accept(bad))
                .isInstanceOf(io.github.acczff.mdop.common.BusinessException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM integration_warehouse_message WHERE message_id=?",
                                Long.class,
                                bad.messageId()))
                .isZero();
        assertThat(read("/api/integration/finished-receipts/" + id + "/feedback")).hasSize(3);
    }

    void finishedWarehouse() throws Exception {
        fg =
                postAs(
                                "/api/master-data/warehouses",
                                Map.of(
                                        "code",
                                        "FG" + key().substring(0, 8).toUpperCase(Locale.ROOT),
                                        "name",
                                        "成品仓",
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
        inspection = location("INSPECTION");
        storage = location("STORAGE");
    }

    long location(String area) throws Exception {
        return postAs(
                        "/api/master-data/locations",
                        Map.of(
                                "warehouseId",
                                fg,
                                "code",
                                area + key().substring(0, 5),
                                "name",
                                area,
                                "areaType",
                                area),
                        admin(),
                        201)
                .get("id")
                .asLong();
    }

    Map<String, Object> demand() {
        return Map.of(
                "demandNo",
                key(),
                "workOrderNo",
                "WO-FINISHED",
                "warehouseId",
                fg,
                "materialId",
                material,
                "quantity",
                "10.123456",
                "batchNo",
                "FG-BATCH",
                "dateCode",
                "DC2026",
                "productionDate",
                java.time.LocalDate.now().toString(),
                "expiryDate",
                "2099-01-01");
    }

    long create() throws Exception {
        return postAs("/api/local/finished-receipts", demand(), admin(), 201).get("id").asLong();
    }

    void receive(long id, int status) throws Exception {
        postAs(
                "/api/v1/wms/finished-receipts/" + id + "/receive",
                Map.of("locationId", inspection),
                admin(),
                status);
    }

    void quality(long id, String result, String event, int status) throws Exception {
        postAs(
                "/api/local/finished-receipts/" + id + "/quality",
                Map.of("eventNo", event, "result", result, "reason", "QMS 完工检验"),
                admin(),
                status);
    }

    void putaway(long id, int status) throws Exception {
        postAs(
                "/api/v1/wms/finished-receipts/" + id + "/putaway",
                Map.of("locationId", storage),
                admin(),
                status);
    }

    @Test
    void receiptQualityAndPutawayPreserveProductionOriginAndLedger() throws Exception {
        finishedWarehouse();
        var body = demand();
        long id = postAs("/api/local/finished-receipts", body, admin(), 201).get("id").asLong();
        assertThat(postAs("/api/local/finished-receipts", body, admin(), 201).get("id").asLong())
                .isEqualTo(id);
        receive(id, 200);
        receive(id, 200);
        long pending =
                read("/api/v1/wms/finished-receipts/" + id).get("received_balance_id").asLong();
        assertThat(stock(pending, "available_qty")).isZero();
        String event = key();
        quality(id, "QUALIFIED", event, 200);
        quality(id, "QUALIFIED", event, 200);
        putaway(id, 200);
        putaway(id, 200);
        long balance =
                read("/api/v1/wms/finished-receipts/" + id).get("stored_balance_id").asLong();
        assertThat(qty(balance)).isEqualByComparingTo("10.123456");
        assertThat(stock(balance, "available_qty")).isEqualByComparingTo("10.123456");
        assertThat(read("/api/v1/wms/stock/" + balance).get("supplier_id").isNull()).isTrue();
        assertThat(read("/api/v1/wms/stock/" + balance).get("origin_type").asText())
                .isEqualTo("PRODUCTION");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE finished_receipt_id=?",
                                Long.class,
                                id))
                .isEqualTo(5);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_outbox WHERE aggregate_type='FinishedReceipt' AND aggregate_id=?",
                                Long.class,
                                id))
                .isEqualTo(3);
        long other = location("STORAGE");
        postAs("/api/v1/wms/transfers", input(balance, other, "1", key()), admin(), 201);
        assertThat(qty(balance)).isEqualByComparingTo("9.123456");
    }

    @Test
    void rejectedAndExpiredFinishedGoodsStayUnavailable() throws Exception {
        finishedWarehouse();
        long id = create();
        putaway(id, 409);
        receive(id, 200);
        quality(id, "REJECTED", key(), 200);
        putaway(id, 409);
        long b = read("/api/v1/wms/finished-receipts/" + id).get("quality_balance_id").asLong();
        assertThat(stock(b, "available_qty")).isZero();
        long expired = create();
        receive(expired, 200);
        db.update("UPDATE wms_finished_receipt SET expiry_date='2000-01-01' WHERE id=?", expired);
        quality(expired, "QUALIFIED", key(), 409);
        quality(expired, "REJECTED", key(), 200);
    }

    @Test
    void invalidSourceDatesLocationsAndChangedDemandsAreRejected() throws Exception {
        finishedWarehouse();
        var body = new HashMap<>(demand());
        long id = postAs("/api/local/finished-receipts", body, admin(), 201).get("id").asLong();
        body.put("quantity", "11");
        postAs("/api/local/finished-receipts", body, admin(), 409);
        postAs(
                "/api/v1/wms/finished-receipts/" + id + "/receive",
                Map.of("locationId", location),
                admin(),
                409);
        postAs(
                "/api/v1/wms/finished-receipts/" + id + "/receive",
                Map.of("locationId", storage),
                admin(),
                409);
        body = new HashMap<>(demand());
        body.put("warehouseId", warehouse);
        postAs("/api/local/finished-receipts", body, admin(), 409);
        body = new HashMap<>(demand());
        db.update("UPDATE mdm_material SET require_date_code=1 WHERE id=?", material);
        body.put("dateCode", "");
        postAs("/api/local/finished-receipts", body, admin(), 409);
    }

    @Test
    void outboxFailureRollsBackPhysicalReceipt() throws Exception {
        finishedWarehouse();
        long id = create();
        db.execute(
                "ALTER TABLE wms_outbox ADD CONSTRAINT test_fg_failure CHECK(aggregate_type<>'FinishedReceipt' OR aggregate_id<>"
                        + id
                        + ")");
        try {
            receive(id, 503);
        } finally {
            db.execute("ALTER TABLE wms_outbox DROP CHECK test_fg_failure");
        }
        assertThat(read("/api/v1/wms/finished-receipts/" + id).get("status").asText())
                .isEqualTo("OPEN");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE finished_receipt_id=?",
                                Long.class,
                                id))
                .isZero();
        receive(id, 200);
    }

    @Test
    void permissionsScopeCsrfAndQmsEventIdentityAreEnforced() throws Exception {
        finishedWarehouse();
        long id = create();
        postAs(
                "/api/v1/wms/finished-receipts/" + id + "/receive",
                Map.of("locationId", inspection),
                operator("outside", "wms:finished:receive"),
                403);
        mvc.perform(post("/api/v1/wms/finished-receipts/" + id + "/receive").with(admin()))
                .andExpect(status().isForbidden());
        postAs(
                "/api/local/finished-receipts",
                demand(),
                operator("outside", "wms:finished:receive"),
                403);
        receive(id, 200);
        String event = key();
        quality(id, "QUALIFIED", event, 200);
        quality(id, "REJECTED", event, 409);
        long second = create();
        receive(second, 200);
        quality(second, "QUALIFIED", event, 409);
        assertThat(read("/api/v1/wms/finished-receipts/" + second).get("status").asText())
                .isEqualTo("RECEIVED");
    }
}
