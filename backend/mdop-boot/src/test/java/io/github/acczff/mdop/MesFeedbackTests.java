package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.*;

@SpringBootTest(
        properties = {"mdop.messaging.enabled=true", "mdop.messaging.initial-delay-ms=3600000"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MesFeedbackTests extends InventoryScenarioSupport {
    @org.springframework.test.context.DynamicPropertySource
    static void broker(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", RABBITMQ::getHost);
        r.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

    @Autowired io.github.acczff.mdop.integration.messaging.DeliveryService delivery;
    @Autowired io.github.acczff.mdop.integration.messaging.MesResultStore store;
    @Autowired io.github.acczff.mdop.integration.messaging.MesSimulatorListener listener;
    @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;

    long issued() throws Exception {
        long b = ready()[0];
        lineSide();
        long id = demand("20");
        reserve(id, b, 200);
        confirmIssue(id, 200);
        return id;
    }

    String message(long id) {
        return db.queryForObject(
                "SELECT message_id FROM wms_outbox WHERE aggregate_type='MaterialIssue' AND aggregate_id=? AND event_type='MaterialIssued'",
                String.class,
                id);
    }

    long accepted(long id) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM integration_mes_result WHERE issue_id=? AND event_type='MaterialIssued'",
                Long.class,
                id);
    }

    @Test
    void actualRabbitDeliveryKeepsOneMesResultAcrossRepeatedIssueAndRedelivery() throws Exception {
        long id = issued();
        confirmIssue(id, 200);
        delivery.dispatch(
                io.github.acczff.mdop.integration.messaging.DeliveryService.Direction.OUTBOX);
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(accepted(id)).isEqualTo(1));
        var event = delivery.outgoing(message(id));
        store.accept(event);
        store.accept(reidentify(event, UUID.randomUUID().toString(), event.payload()));
        assertThat(accepted(id)).isEqualTo(1);
        var feedback = read("/api/integration/material-issues/" + id + "/feedback");
        assertThat(feedback.get(0).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(feedback.get(0).get("received_at").isNull()).isFalse();
        mvc.perform(
                        get("/api/integration/material-issues/" + id + "/feedback")
                                .with(operator("outside", "wms:issue:read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void brokerRoutingFailureRetriesAndManualReplayDoesNotDuplicateInventory() throws Exception {
        long id = issued();
        String message = message(id);
        var admin = new org.springframework.amqp.rabbit.core.RabbitAdmin(rabbit);
        var binding =
                new org.springframework.amqp.core.Binding(
                        io.github.acczff.mdop.integration.messaging.MessagingConfiguration.MES,
                        org.springframework.amqp.core.Binding.DestinationType.QUEUE,
                        io.github.acczff.mdop.integration.messaging.MessagingConfiguration.EXCHANGE,
                        "MaterialIssued",
                        null);
        admin.removeBinding(binding);
        try {
            delivery.dispatch(
                    io.github.acczff.mdop.integration.messaging.DeliveryService.Direction.OUTBOX);
        } finally {
            admin.declareBinding(binding);
        }
        assertThat(
                        db.queryForObject(
                                "SELECT status FROM wms_outbox WHERE message_id=?",
                                String.class,
                                message))
                .isEqualTo("FAILED");
        assertThat(accepted(id)).isZero();
        postAs(
                "/api/integration/messages/OUTBOX/" + message + "/replay",
                Map.of("reason", "修复路由后重试"),
                admin(),
                204);
        delivery.dispatch(
                io.github.acczff.mdop.integration.messaging.DeliveryService.Direction.OUTBOX);
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(accepted(id)).isEqualTo(1));
        // Simulate a crash after broker acknowledgement but before the durable completion marker.
        db.update("UPDATE wms_outbox SET status='FAILED' WHERE message_id=?", message);
        postAs(
                "/api/integration/messages/OUTBOX/" + message + "/replay",
                Map.of("reason", "确认发布后进程中断"),
                admin(),
                204);
        delivery.dispatch(
                io.github.acczff.mdop.integration.messaging.DeliveryService.Direction.OUTBOX);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM wms_inventory_transaction WHERE issue_id=?",
                                Long.class,
                                id))
                .isEqualTo(2);
        assertThat(accepted(id)).isEqualTo(1);
    }

    @Test
    void conflictsAndMalformedResultsAreQuarantinedWithoutOverwritingBusinessState()
            throws Exception {
        long id = issued();
        var event = delivery.outgoing(message(id));
        store.accept(event);
        var changed = (tools.jackson.databind.node.ObjectNode) event.payload().deepCopy();
        changed.put("quantity", "99");
        var conflict = reidentify(event, UUID.randomUUID().toString(), changed);
        assertThatThrownBy(
                        () ->
                                listener.consume(
                                        new org.springframework.amqp.core.Message(
                                                json.writeValueAsBytes(conflict),
                                                new org.springframework.amqp.core
                                                        .MessageProperties())))
                .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT quantity FROM integration_mes_result WHERE issue_id=? AND event_type='MaterialIssued'",
                                BigDecimal.class,
                                id))
                .isEqualByComparingTo("20");
        assertThatThrownBy(() -> store.accept(reidentify(event, event.messageId(), changed)))
                .isInstanceOf(io.github.acczff.mdop.common.BusinessException.class);
        assertThatThrownBy(
                        () ->
                                listener.consume(
                                        new org.springframework.amqp.core.Message(
                                                "{}"
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8),
                                                new org.springframework.amqp.core
                                                        .MessageProperties())))
                .isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM integration_rejected_message WHERE reason LIKE '%MES%'",
                                Long.class))
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void quantityOnlyMaterialAllowsEmptyBatchInMesFeedback() throws Exception {
        long id = issued();
        var event = delivery.outgoing(message(id));
        var payload = (tools.jackson.databind.node.ObjectNode) event.payload().deepCopy();
        payload.put("batchNo", "");
        store.accept(reidentify(event, event.messageId(), payload));
        assertThat(accepted(id)).isEqualTo(1);
    }

    @Test
    void failedFeedbackInsertRollsBackIssueAndKeepsReservation() throws Exception {
        long balance = ready()[0];
        lineSide();
        long id = demand("10");
        reserve(id, balance, 200);
        db.execute(
                "ALTER TABLE wms_outbox ADD CONSTRAINT ck_test_issue_feedback CHECK(aggregate_type<>'MaterialIssue' OR aggregate_id<>"
                        + id
                        + ")");
        try {
            confirmIssue(id, 503);
        } finally {
            db.execute("ALTER TABLE wms_outbox DROP CHECK ck_test_issue_feedback");
        }
        assertThat(qty(balance)).isEqualByComparingTo("80");
        assertThat(stock(balance, "reserved_qty")).isEqualByComparingTo("10");
        assertThat(read("/api/v1/wms/issues/" + id).get("status").asText()).isEqualTo("RESERVED");
        confirmIssue(id, 200);
    }

    io.github.acczff.mdop.integration.messaging.EventEnvelope reidentify(
            io.github.acczff.mdop.integration.messaging.EventEnvelope e,
            String id,
            tools.jackson.databind.JsonNode payload) {
        return new io.github.acczff.mdop.integration.messaging.EventEnvelope(
                id,
                e.eventType(),
                e.eventVersion(),
                e.sourceSystem(),
                e.occurredAt(),
                e.traceId(),
                e.aggregateType(),
                e.aggregateId(),
                payload);
    }
}
