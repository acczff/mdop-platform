package io.github.acczff.mdop.integration.messaging;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.wms.receiving.ReceivingModels.ArrivalInput;
import io.github.acczff.mdop.wms.receiving.ReceivingService;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class DeliveryService {
    public enum Direction {
        INBOX("integration_inbox", "PROCESSING", "PROCESSED"),
        OUTBOX("wms_outbox", "PUBLISHING", "PUBLISHED");
        final String table, working, done;

        Direction(String table, String working, String done) {
            this.table = table;
            this.working = working;
            this.done = done;
        }
    }

    public record Claim(String messageId, String token, int attempts) {}

    private final JdbcClient db;
    private final ObjectMapper json;
    private final Validator validator;
    private final ReceivingService receiving;
    private final EventPublisher publisher;
    private final CurrentActorProvider actor;
    private final TransactionTemplate tx;

    public DeliveryService(
            JdbcClient db,
            ObjectMapper json,
            Validator validator,
            ReceivingService receiving,
            EventPublisher publisher,
            CurrentActorProvider actor,
            PlatformTransactionManager manager) {
        this.db = db;
        this.json = json;
        this.validator = validator;
        this.receiving = receiving;
        this.publisher = publisher;
        this.actor = actor;
        this.tx = new TransactionTemplate(manager);
    }

    public ArrivalInput validateArrival(EventEnvelope event) {
        if (event == null
                || !validator.validate(event).isEmpty()
                || !event.eventType().equals("ArrivalNoticeCreated")
                || !event.sourceSystem().equals("ERP")
                || !event.aggregateType().equals("ArrivalNotice"))
            throw new BusinessException(400, "INVALID_EVENT", "到货事件契约不符合要求");
        ArrivalInput input;
        try {
            input = json.treeToValue(event.payload(), ArrivalInput.class);
        } catch (Exception malformed) {
            throw new BusinessException(400, "INVALID_EVENT", "到货明细格式不正确");
        }
        if (input == null
                || !validator.validate(input).isEmpty()
                || !event.aggregateId().equals(input.externalNoticeNo())
                || !input.externalNoticeNo().equals(input.externalNoticeNo().strip())
                || json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8).length > 262144)
            throw new BusinessException(400, "INVALID_EVENT", "到货明细或外部单号不符合要求");
        return input;
    }

    public void accept(EventEnvelope event) {
        var input = validateArrival(event);
        String digest = hash(json.writeValueAsString(input));
        tx.executeWithoutResult(
                status -> {
                    db.sql(
                                    "INSERT INTO integration_inbox(message_id,source_system,external_notice_no,payload_hash,envelope) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE message_id=message_id")
                            .params(
                                    event.messageId(),
                                    event.sourceSystem(),
                                    input.externalNoticeNo(),
                                    digest,
                                    json.writeValueAsString(event))
                            .update();
                    var previous =
                            db.sql(
                                            "SELECT payload_hash FROM integration_inbox WHERE message_id=? FOR UPDATE")
                                    .param(event.messageId())
                                    .query(String.class)
                                    .single();
                    if (!previous.equals(digest))
                        throw new BusinessException(409, "MESSAGE_ID_CONFLICT", "相同消息编号的业务内容不一致");
                });
    }

    public Claim claim(Direction direction) {
        return tx.execute(
                status -> {
                    var rows =
                            db.sql(
                                            "SELECT message_id,attempts FROM "
                                                    + direction.table
                                                    + " WHERE ((status IN ('PENDING','FAILED') AND next_attempt_at<=CURRENT_TIMESTAMP(6)) OR (status=? AND lease_until<CURRENT_TIMESTAMP(6))) ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED")
                                    .param(direction.working)
                                    .query()
                                    .listOfRows();
                    if (rows.isEmpty()) return null;
                    var row = rows.getFirst();
                    String id = (String) row.get("message_id"),
                            token = UUID.randomUUID().toString();
                    int attempts = ((Number) row.get("attempts")).intValue() + 1;
                    if (attempts > 5) {
                        db.sql(
                                        "UPDATE "
                                                + direction.table
                                                + " SET status='DEAD',lease_token=NULL,lease_until=NULL,last_error='LEASE_EXHAUSTED' WHERE message_id=?")
                                .param(id)
                                .update();
                        log(direction, id, "DEAD", "system:delivery", "LEASE_EXHAUSTED");
                        return null;
                    }
                    db.sql(
                                    "UPDATE "
                                            + direction.table
                                            + " SET status=?,attempts=?,lease_token=?,lease_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6)) WHERE message_id=?")
                            .params(direction.working, attempts, token, id)
                            .update();
                    log(direction, id, direction.working, "system:delivery", "attempt=" + attempts);
                    return new Claim(id, token, attempts);
                });
    }

    public void dispatch(Direction direction) {
        for (int i = 0; i < 20; i++) {
            var claim = claim(direction);
            if (claim == null) return;
            try {
                if (direction == Direction.INBOX) process(claim);
                else {
                    publisher.publish(outgoing(claim.messageId()));
                    complete(direction, claim);
                }
            } catch (Exception failure) {
                fail(direction, claim, failure);
            }
        }
    }

    public void process(Claim claim) {
        tx.executeWithoutResult(
                status -> {
                    var rows =
                            db.sql(
                                            "SELECT envelope FROM integration_inbox WHERE message_id=? AND lease_token=? AND status='PROCESSING' FOR UPDATE")
                                    .params(claim.messageId(), claim.token())
                                    .query(String.class)
                                    .list();
                    if (rows.isEmpty()) return;
                    var event = json.readValue(rows.getFirst(), EventEnvelope.class);
                    var input = validateArrival(event);
                    String digest = hash(json.writeValueAsString(input));
                    db.sql(
                                    "INSERT INTO integration_arrival_identity(source_system,external_notice_no,payload_hash) VALUES (?,?,?) ON DUPLICATE KEY UPDATE source_system=source_system")
                            .params(event.sourceSystem(), input.externalNoticeNo(), digest)
                            .update();
                    var identity =
                            db.sql(
                                            "SELECT payload_hash,arrival_id FROM integration_arrival_identity WHERE source_system=? AND external_notice_no=? FOR UPDATE")
                                    .params(event.sourceSystem(), input.externalNoticeNo())
                                    .query()
                                    .singleRow();
                    if (!identity.get("payload_hash").equals(digest))
                        throw new BusinessException(
                                409, "ARRIVAL_CONTENT_CONFLICT", "外部到货单已存在且内容不同");
                    Long arrivalId =
                            identity.get("arrival_id") == null
                                    ? null
                                    : ((Number) identity.get("arrival_id")).longValue();
                    if (arrivalId == null) {
                        var previous = SecurityContextHolder.getContext();
                        var context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(
                                new UsernamePasswordAuthenticationToken(
                                        "integration:ERP",
                                        null,
                                        List.of(
                                                new SimpleGrantedAuthority(
                                                        "wms:warehouse:" + input.warehouseId()))));
                        try {
                            SecurityContextHolder.setContext(context);
                            arrivalId =
                                    receiving
                                            .receiveArrival(event.sourceSystem(), input)
                                            .arrival()
                                            .id();
                        } finally {
                            SecurityContextHolder.setContext(previous);
                        }
                        db.sql(
                                        "UPDATE integration_arrival_identity SET arrival_id=? WHERE source_system=? AND external_notice_no=?")
                                .params(arrivalId, event.sourceSystem(), input.externalNoticeNo())
                                .update();
                    }
                    db.sql("UPDATE integration_inbox SET arrival_id=? WHERE message_id=?")
                            .params(arrivalId, claim.messageId())
                            .update();
                    finish(Direction.INBOX, claim);
                });
    }

    public EventEnvelope outgoing(String id) {
        return db.sql("SELECT * FROM wms_outbox WHERE message_id=?")
                .param(id)
                .query(
                        (rs, row) ->
                                new EventEnvelope(
                                        rs.getString("message_id"),
                                        rs.getString("event_type"),
                                        rs.getInt("event_version"),
                                        rs.getString("source_system"),
                                        rs.getTimestamp("occurred_at").toInstant(),
                                        rs.getString("trace_id"),
                                        rs.getString("aggregate_type"),
                                        rs.getString("aggregate_id"),
                                        json.readTree(rs.getString("payload"))))
                .single();
    }

    public void complete(Direction direction, Claim claim) {
        tx.executeWithoutResult(status -> finish(direction, claim));
    }

    private void finish(Direction direction, Claim claim) {
        String completed = direction == Direction.INBOX ? "processed_at" : "published_at";
        int changed =
                db.sql(
                                "UPDATE "
                                        + direction.table
                                        + " SET status=?,"
                                        + completed
                                        + "=CURRENT_TIMESTAMP(6),lease_token=NULL,lease_until=NULL,last_error=NULL WHERE message_id=? AND lease_token=? AND status=?")
                        .params(direction.done, claim.messageId(), claim.token(), direction.working)
                        .update();
        if (changed == 1)
            log(direction, claim.messageId(), direction.done, "system:delivery", null);
    }

    public void fail(Direction direction, Claim claim, Exception failure) {
        // Persist a safe error category, never broker URLs, credentials or payloads.
        String code =
                failure instanceof BusinessException business
                        ? business.getCode()
                        : failure.getClass().getSimpleName();
        tx.executeWithoutResult(
                status -> {
                    int changed =
                            db.sql(
                                            "UPDATE "
                                                    + direction.table
                                                    + " SET status=?,next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),lease_token=NULL,lease_until=NULL,last_error=? WHERE message_id=? AND lease_token=? AND status=?")
                                    .params(
                                            claim.attempts() >= 5 ? "DEAD" : "FAILED",
                                            Math.min(300, 1 << Math.min(claim.attempts(), 8)),
                                            code,
                                            claim.messageId(),
                                            claim.token(),
                                            direction.working)
                                    .update();
                    if (changed == 1)
                        log(direction, claim.messageId(), "FAILED", "system:delivery", code);
                });
    }

    public void replay(Direction direction, String id, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 200)
            throw new BusinessException(400, "REASON_REQUIRED", "请填写200字以内的重放原因");
        tx.executeWithoutResult(
                status -> {
                    int changed =
                            db.sql(
                                            "UPDATE "
                                                    + direction.table
                                                    + " SET status='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP(6),lease_token=NULL,lease_until=NULL WHERE message_id=? AND status IN ('FAILED','DEAD')")
                                    .param(id)
                                    .update();
                    if (changed != 1)
                        throw new BusinessException(
                                409, "REPLAY_NOT_ALLOWED", "只有失败或死信消息可以重放，请刷新状态");
                    log(direction, id, "REPLAY", actor.currentActor(), reason);
                });
    }

    public List<Map<String, Object>> list(Direction direction, String state, int page) {
        if (page < 0
                || page > 100000
                || (!state.isEmpty()
                        && !Set.of("PENDING", "FAILED", "DEAD", direction.working, direction.done)
                                .contains(state)))
            throw new BusinessException(400, "INVALID_FILTER", "消息筛选条件不合法");
        String time = direction == Direction.INBOX ? "received_at" : "occurred_at";
        return db.sql(
                        "SELECT message_id,status,attempts,last_error,next_attempt_at,"
                                + time
                                + " AS created_at FROM "
                                + direction.table
                                + " WHERE (?='' OR status=?) ORDER BY "
                                + time
                                + " DESC,message_id LIMIT 50 OFFSET ?")
                .params(state, state, page * 50)
                .query()
                .listOfRows();
    }

    public List<Map<String, Object>> history(Direction direction, String id) {
        return db.sql(
                        "SELECT action,actor,detail,occurred_at FROM integration_delivery_log WHERE direction=? AND message_id=? ORDER BY id DESC LIMIT 100")
                .params(direction.name(), id)
                .query()
                .listOfRows();
    }

    public Map<String, Object> summary() {
        var result = new LinkedHashMap<String, Object>();
        for (var d : Direction.values())
            result.put(
                    d.name(),
                    db.sql("SELECT status,COUNT(*) AS total FROM " + d.table + " GROUP BY status")
                            .query()
                            .listOfRows());
        result.put(
                "unconfirmedResults",
                db.sql(
                                "SELECT COUNT(*) FROM wms_outbox o WHERE o.status='PUBLISHED' AND NOT EXISTS (SELECT 1 FROM integration_simulated_result r WHERE r.message_id=o.message_id)")
                        .query(Long.class)
                        .single());
        result.put(
                "stalled",
                db.sql(
                                "SELECT (SELECT COUNT(*) FROM integration_inbox WHERE status NOT IN ('PROCESSED','DEAD') AND received_at<TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP(6))) + (SELECT COUNT(*) FROM wms_outbox WHERE status NOT IN ('PUBLISHED','DEAD') AND occurred_at<TIMESTAMPADD(MINUTE,-5,CURRENT_TIMESTAMP(6)))")
                        .query(Long.class)
                        .single());
        result.put(
                "rejected",
                db.sql("SELECT COALESCE(SUM(occurrences),0) FROM integration_rejected_message")
                        .query(Long.class)
                        .single());
        return result;
    }

    public List<Map<String, Object>> results() {
        return db.sql(
                        "SELECT o.message_id,o.event_type,o.aggregate_type,o.aggregate_id,o.business_key,o.status,r.target_system,r.received_at,CASE WHEN o.aggregate_type='MaterialIssue' THEN IF(r.received_at IS NULL,NULL,'RECEIVED') WHEN o.event_type='PurchaseReturnConfirmed' THEN p.state ELSE s.state END AS downstream_state FROM wms_outbox o LEFT JOIN integration_simulated_result r ON r.message_id=o.message_id LEFT JOIN integration_simulated_receipt_state s ON s.target_system=r.target_system AND s.receipt_id=o.aggregate_id LEFT JOIN integration_simulated_purchase_return p ON o.event_type='PurchaseReturnConfirmed' AND CAST(p.return_id AS CHAR)=o.business_key AND r.target_system='ERP' ORDER BY o.occurred_at DESC,o.message_id LIMIT 100")
                .query()
                .listOfRows();
    }

    public void reject(byte[] body, String reason) {
        // Retain a fingerprint for correlation; malformed payloads stay in the broker quarantine.
        String fingerprint = hash(Base64.getEncoder().encodeToString(body));
        db.sql(
                        "INSERT INTO integration_rejected_message(fingerprint,reason) VALUES (?,?) ON DUPLICATE KEY UPDATE occurrences=occurrences+1,received_at=CURRENT_TIMESTAMP(6)")
                .params(fingerprint, reason)
                .update();
    }

    public List<Map<String, Object>> rejections() {
        return db.sql(
                        "SELECT fingerprint,reason,occurrences,received_at FROM integration_rejected_message ORDER BY received_at DESC LIMIT 100")
                .query()
                .listOfRows();
    }

    private void log(Direction d, String id, String action, String actor, String detail) {
        db.sql(
                        "INSERT INTO integration_delivery_log(direction,message_id,action,actor,detail) VALUES (?,?,?,?,?)")
                .params(d.name(), id, action, actor, detail)
                .update();
    }

    private static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
