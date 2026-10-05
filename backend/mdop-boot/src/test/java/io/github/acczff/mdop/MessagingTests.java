package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.acczff.mdop.integration.messaging.*;
import io.github.acczff.mdop.test.support.MdopInfrastructureTestBase;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;

@SpringBootTest(
        properties = {"mdop.messaging.enabled=true", "mdop.messaging.initial-delay-ms=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessagingTests extends MdopInfrastructureTestBase {
    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", RABBITMQ::getHost);
        r.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired EventPublisher publisher;
    @Autowired DeliveryService delivery;
    @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;
    long warehouse, supplier, material, location;

    @BeforeEach
    void setup() throws Exception {
        String code = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        warehouse =
                create(
                                "/api/master-data/warehouses",
                                Map.of(
                                        "code",
                                        "W" + code,
                                        "name",
                                        "消息验收仓",
                                        "purpose",
                                        "RAW_MATERIAL",
                                        "form",
                                        "PHYSICAL",
                                        "managementCategory",
                                        "GENERAL"))
                        .get("id")
                        .asLong();
        supplier =
                create("/api/master-data/suppliers", Map.of("code", "S" + code, "name", "模拟供应商"))
                        .get("id")
                        .asLong();
        material =
                create(
                                "/api/master-data/materials",
                                Map.of(
                                        "code",
                                        "M" + code,
                                        "name",
                                        "模拟物料",
                                        "unit",
                                        "件",
                                        "trackingMode",
                                        "QUANTITY",
                                        "requireDateCode",
                                        false,
                                        "requireExpiry",
                                        false))
                        .get("id")
                        .asLong();
        location =
                create(
                                "/api/master-data/locations",
                                Map.of(
                                        "warehouseId",
                                        warehouse,
                                        "code",
                                        "L" + code,
                                        "name",
                                        "待检位",
                                        "areaType",
                                        "INSPECTION"))
                        .get("id")
                        .asLong();
    }

    EventEnvelope event(String notice) {
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                "ArrivalNoticeCreated",
                1,
                "ERP",
                Instant.now(),
                UUID.randomUUID().toString(),
                "ArrivalNotice",
                notice,
                json.valueToTree(
                        Map.of(
                                "externalNoticeNo",
                                notice,
                                "purchaseOrderNo",
                                "PO-01",
                                "supplierId",
                                supplier,
                                "warehouseId",
                                warehouse,
                                "items",
                                List.of(Map.of("materialId", material, "quantity", "10.000001")))));
    }

    @Test
    void brokerRoundTripDeduplicatesAndFeedbackSurvivesLostCompletion() throws Exception {
        var e = event(UUID.randomUUID().toString());
        publisher.publish(e);
        publisher.publish(e);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                count(
                                                        "SELECT COUNT(*) FROM integration_inbox WHERE message_id=?",
                                                        e.messageId()))
                                        .isEqualTo(1));
        delivery.dispatch(DeliveryService.Direction.INBOX);
        long arrival =
                db.queryForObject(
                        "SELECT arrival_id FROM integration_inbox WHERE message_id=?",
                        Long.class,
                        e.messageId());
        long line =
                db.queryForObject(
                        "SELECT id FROM wms_arrival_notice_item WHERE arrival_id=?",
                        Long.class,
                        arrival);
        var draft =
                create(
                        "/api/v1/wms/arrival-notices/" + arrival + "/receipts",
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
                                                "10.000001",
                                                "batchNo",
                                                "",
                                                "dateCode",
                                                ""))));
        long receipt = draft.get("receipt").get("id").asLong();
        mvc.perform(
                        post("/api/v1/wms/receipts/" + receipt + "/submit")
                                .with(user("tester").roles("ADMIN"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "version",
                                                        0,
                                                        "idempotencyKey",
                                                        UUID.randomUUID().toString()))))
                .andExpect(status().isOk());
        // Broker accepted the message, but the process crashed before recording PUBLISHED.
        var claim = delivery.claim(DeliveryService.Direction.OUTBOX);
        publisher.publish(delivery.outgoing(claim.messageId()));
        db.update(
                "UPDATE wms_outbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE message_id=?",
                claim.messageId());
        delivery.dispatch(DeliveryService.Direction.OUTBOX);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                count(
                                                        "SELECT COUNT(*) FROM integration_simulated_result WHERE aggregate_id=?",
                                                        receipt))
                                        .isEqualTo(2));
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_outbox WHERE aggregate_id=? AND status='PUBLISHED'",
                                receipt))
                .isEqualTo(2);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE receipt_id=?",
                                receipt))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT available_qty FROM wms_inventory_balance WHERE warehouse_id=?",
                                java.math.BigDecimal.class,
                                warehouse))
                .isZero();
    }

    @Test
    void differentMessagesWithSameBusinessKeyCreateOneArrivalAndRejectConflictingContent() {
        var first = event(UUID.randomUUID().toString());
        delivery.accept(first);
        delivery.dispatch(DeliveryService.Direction.INBOX);
        var repeat =
                new EventEnvelope(
                        UUID.randomUUID().toString(),
                        first.eventType(),
                        1,
                        "ERP",
                        Instant.now(),
                        first.traceId(),
                        first.aggregateType(),
                        first.aggregateId(),
                        first.payload());
        delivery.accept(repeat);
        delivery.dispatch(DeliveryService.Direction.INBOX);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_arrival_notice WHERE external_notice_no=?",
                                first.aggregateId()))
                .isEqualTo(1);
        var changed = event(first.aggregateId());
        var payload = (tools.jackson.databind.node.ObjectNode) changed.payload();
        payload.put("purchaseOrderNo", "DIFFERENT");
        assertThatThrownBy(
                        () ->
                                delivery.accept(
                                        new EventEnvelope(
                                                first.messageId(),
                                                first.eventType(),
                                                1,
                                                "ERP",
                                                Instant.now(),
                                                first.traceId(),
                                                first.aggregateType(),
                                                first.aggregateId(),
                                                payload)))
                .isInstanceOf(RuntimeException.class);
        delivery.accept(changed);
        delivery.dispatch(DeliveryService.Direction.INBOX);
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM integration_inbox WHERE message_id=?",
                                String.class,
                                changed.messageId()))
                .isEqualTo("FAILED");
        db.update(
                "UPDATE integration_inbox SET status='DEAD' WHERE message_id=?",
                changed.messageId());
    }

    @Test
    void businessRollbackRetryAndDeadReplayAreAudited() throws Exception {
        var e = event(UUID.randomUUID().toString());
        delivery.accept(e);
        db.execute(
                "ALTER TABLE wms_arrival_notice_item ADD CONSTRAINT test_reject_item CHECK (material_id<>"
                        + material
                        + ")");
        try {
            delivery.dispatch(DeliveryService.Direction.INBOX);
        } finally {
            db.execute("ALTER TABLE wms_arrival_notice_item DROP CHECK test_reject_item");
        }
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_arrival_notice WHERE external_notice_no=?",
                                e.aggregateId()))
                .isZero();
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM integration_arrival_identity WHERE external_notice_no=?",
                                e.aggregateId()))
                .isZero();
        db.update(
                "UPDATE integration_inbox SET attempts=4,next_attempt_at=CURRENT_TIMESTAMP(6) WHERE message_id=?",
                e.messageId());
        var claim = delivery.claim(DeliveryService.Direction.INBOX);
        delivery.fail(
                DeliveryService.Direction.INBOX,
                claim,
                new IllegalStateException("not persisted secret"));
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM integration_inbox WHERE message_id=?",
                                String.class,
                                e.messageId()))
                .isEqualTo("DEAD");
        String path = "/api/integration/messages/INBOX/" + e.messageId() + "/replay";
        mvc.perform(
                        post(path)
                                .with(user("reader").roles("USER"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"修复资料\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(path)
                                .with(user("admin").roles("ADMIN"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"修复资料\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(path)
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"修复资料后重放\"}"))
                .andExpect(status().isNoContent());
        delivery.dispatch(DeliveryService.Direction.INBOX);
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM integration_inbox WHERE message_id=?",
                                String.class,
                                e.messageId()))
                .isEqualTo("PROCESSED");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM integration_delivery_log WHERE message_id=? AND action='REPLAY' AND actor='admin'",
                                e.messageId()))
                .isEqualTo(1);
        mvc.perform(
                        post(path)
                                .with(user("admin").roles("ADMIN"))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"再次重放\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void expiredLeaseCanBeRecoveredAndStaleWorkerCannotComplete() {
        var e = event(UUID.randomUUID().toString());
        delivery.accept(e);
        var old = delivery.claim(DeliveryService.Direction.INBOX);
        db.update(
                "UPDATE integration_inbox SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE message_id=?",
                old.messageId());
        var current = delivery.claim(DeliveryService.Direction.INBOX);
        delivery.complete(DeliveryService.Direction.INBOX, old);
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM integration_inbox WHERE message_id=?",
                                String.class,
                                e.messageId()))
                .isEqualTo("PROCESSING");
        delivery.process(current);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_arrival_notice WHERE external_notice_no=?",
                                e.aggregateId()))
                .isEqualTo(1);
    }

    @Test
    void brokerOutageCanRecoverAndUnroutablePublishIsNotSuccess() throws Exception {
        var e = event(UUID.randomUUID().toString());
        assertThatThrownBy(
                        () ->
                                publisher.publish(
                                        new EventEnvelope(
                                                e.messageId(),
                                                "UnknownEvent",
                                                1,
                                                e.sourceSystem(),
                                                e.occurredAt(),
                                                e.traceId(),
                                                e.aggregateType(),
                                                e.aggregateId(),
                                                e.payload())))
                .isInstanceOf(IllegalStateException.class);
        try {
            assertThat(RABBITMQ.execInContainer("rabbitmqctl", "stop_app").getExitCode()).isZero();
            assertThatThrownBy(() -> publisher.publish(e))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            assertThat(RABBITMQ.execInContainer("rabbitmqctl", "start_app").getExitCode()).isZero();
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> publisher.publish(e));
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(
                                                count(
                                                        "SELECT COUNT(*) FROM integration_inbox WHERE message_id=?",
                                                        e.messageId()))
                                        .isEqualTo(1));
        delivery.dispatch(DeliveryService.Direction.INBOX);
    }

    @Test
    void messageQueriesRequireAdmin() throws Exception {
        mvc.perform(
                        get("/api/integration/messages?direction=INBOX")
                                .with(user("reader").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/integration/messages?direction=INBOX")
                                .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(
                        get("/api/integration/messages?direction=INBOX&page=-1")
                                .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedMessageIsQuarantinedWithoutBlockingNextArrival() {
        rabbit.send(
                MessagingConfiguration.EXCHANGE,
                "ArrivalNoticeCreated",
                new org.springframework.amqp.core.Message(
                        "not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                count(
                                                        "SELECT COUNT(*) FROM integration_rejected_message WHERE reason='INVALID_EVENT'"))
                                        .isEqualTo(1));
        var e = event(UUID.randomUUID().toString());
        publisher.publish(e);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                count(
                                                        "SELECT COUNT(*) FROM integration_inbox WHERE message_id=?",
                                                        e.messageId()))
                                        .isEqualTo(1));
        delivery.dispatch(DeliveryService.Direction.INBOX);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_arrival_notice WHERE external_notice_no=?",
                                e.aggregateId()))
                .isEqualTo(1);
    }

    @Test
    void concurrentConsumersUseBusinessIdentityToCreateOneNotice() throws Exception {
        var e = event(UUID.randomUUID().toString());
        var second =
                new EventEnvelope(
                        UUID.randomUUID().toString(),
                        e.eventType(),
                        1,
                        "ERP",
                        Instant.now(),
                        e.traceId(),
                        e.aggregateType(),
                        e.aggregateId(),
                        e.payload());
        delivery.accept(e);
        delivery.accept(second);
        var one = delivery.claim(DeliveryService.Direction.INBOX);
        var two = delivery.claim(DeliveryService.Direction.INBOX);
        assertThat(one.messageId()).isNotEqualTo(two.messageId());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var first =
                    pool.submit(
                            () -> {
                                gate.await();
                                delivery.process(one);
                                return true;
                            });
            var next =
                    pool.submit(
                            () -> {
                                gate.await();
                                delivery.process(two);
                                return true;
                            });
            gate.countDown();
            first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            next.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM wms_arrival_notice WHERE external_notice_no=?",
                                e.aggregateId()))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM integration_inbox WHERE external_notice_no=? AND status='PROCESSED'",
                                e.aggregateId()))
                .isEqualTo(2);
    }

    int count(String sql, Object... args) {
        return db.queryForObject(sql, Integer.class, args);
    }

    JsonNode create(String path, Object body) throws Exception {
        return json.readTree(
                mvc.perform(
                                post(path)
                                        .with(user("tester").roles("ADMIN"))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }
}
