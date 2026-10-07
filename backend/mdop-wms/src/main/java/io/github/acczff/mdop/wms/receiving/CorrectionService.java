package io.github.acczff.mdop.wms.receiving;

import static io.github.acczff.mdop.common.BusinessText.*;
import static io.github.acczff.mdop.wms.receiving.CorrectionModels.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class CorrectionService {
    private final JdbcClient db;
    private final ReceivingAccess access;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public CorrectionService(
            JdbcClient db,
            ReceivingAccess access,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json) {
        this.db = db;
        this.access = access;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
    }

    private BusinessException conflict(String code, String detail) {
        return new BusinessException(409, code, detail);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private void lockArrival(long id) {
        var warehouse =
                db.sql("SELECT warehouse_id FROM wms_arrival_notice WHERE id=? FOR UPDATE")
                        .param(id)
                        .query(Long.class)
                        .optional()
                        .orElseThrow(
                                () -> new BusinessException(404, "ARRIVAL_NOT_FOUND", "到货通知不存在"));
        access.requireWarehouse(warehouse);
    }

    @Transactional(readOnly = true)
    public List<CaseRecord> list(long warehouseId) {
        access.requireWarehouse(warehouseId);
        return db.sql(
                        "SELECT c.* FROM wms_receiving_case c JOIN wms_arrival_notice a ON a.id=c.arrival_id WHERE a.warehouse_id=? ORDER BY c.id DESC LIMIT 100")
                .param(warehouseId)
                .query(CaseRecord.class)
                .list();
    }

    @Transactional(readOnly = true)
    public CaseRecord get(long id) {
        var result =
                db.sql("SELECT * FROM wms_receiving_case WHERE id=?")
                        .param(id)
                        .query(CaseRecord.class)
                        .optional()
                        .orElseThrow(() -> new BusinessException(404, "CASE_NOT_FOUND", "申请不存在"));
        long warehouse =
                db.sql("SELECT warehouse_id FROM wms_arrival_notice WHERE id=?")
                        .param(result.arrivalId())
                        .query(Long.class)
                        .single();
        access.requireWarehouse(warehouse);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(long id) {
        get(id);
        return db.sql(
                        "SELECT action,actor,detail,occurred_at FROM wms_receiving_case_audit WHERE case_id=? ORDER BY id")
                .param(id)
                .query()
                .listOfRows();
    }

    private CaseRecord previous(String key, String hash) {
        var rows =
                db.sql(
                                "SELECT id,request_hash,requested_by FROM wms_receiving_case WHERE request_key=?")
                        .param(key)
                        .query()
                        .listOfRows();
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!row.get("request_hash").equals(hash)
                || !row.get("requested_by").equals(actor.currentActor()))
            throw conflict("IDEMPOTENCY_CONFLICT", "幂等键已用于其他申请或用户");
        return get(((Number) row.get("id")).longValue());
    }

    public CaseRecord difference(DifferenceInput input) {
        lockArrival(input.arrivalId());
        String hash = hash("DIFFERENCE", input);
        var old = previous(input.idempotencyKey(), hash);
        if (old != null) return old;
        if (input.type() != DifferenceType.WRONG_MATERIAL && input.arrivalItemId() == null)
            throw new BusinessException(400, "ITEM_REQUIRED", "必须选择通知明细");
        if (input.arrivalItemId() != null
                && db.sql(
                                        "SELECT COUNT(*) FROM wms_arrival_notice_item WHERE id=? AND arrival_id=?")
                                .params(input.arrivalItemId(), input.arrivalId())
                                .query(Integer.class)
                                .single()
                        != 1) throw new BusinessException(400, "INVALID_ITEM", "明细不属于当前通知");
        String observed =
                input.type() == DifferenceType.WRONG_MATERIAL
                        ? required(input.observedMaterial(), "实到物料", 64)
                        : optional(input.observedMaterial(), "实到物料", 64);
        var key = new GeneratedKeyHolder();
        db.sql(
                        "INSERT INTO wms_receiving_case(kind,arrival_id,arrival_item_id,difference_type,observed_material,quantity,reason,request_key,request_hash,requested_by,requested_at) VALUES ('DIFFERENCE',?,?,?,?,?,?,?,?,?,?)")
                .params(
                        input.arrivalId(),
                        input.arrivalItemId(),
                        input.type().name(),
                        observed,
                        input.quantity(),
                        reason(input.reason()),
                        input.idempotencyKey(),
                        hash,
                        actor.currentActor(),
                        now())
                .update(key);
        long id = key.getKey().longValue();
        audit(id, "REQUESTED", input.reason());
        return get(id);
    }

    public CaseRecord reversal(ReversalInput input) {
        long arrival =
                db.sql("SELECT arrival_id FROM wms_receipt WHERE id=?")
                        .param(input.receiptId())
                        .query(Long.class)
                        .optional()
                        .orElseThrow(
                                () -> new BusinessException(404, "RECEIPT_NOT_FOUND", "收货单不存在"));
        lockArrival(arrival);
        String hash = hash("REVERSAL", input);
        var old = previous(input.idempotencyKey(), hash);
        if (old != null) return old;
        eligible(input.receiptId());
        if (db.sql("SELECT COUNT(*) FROM wms_receiving_case WHERE active_receipt_id=?")
                        .param(input.receiptId())
                        .query(Integer.class)
                        .single()
                > 0) throw conflict("REVERSAL_EXISTS", "已有待审批或已通过的冲正申请");
        var key = new GeneratedKeyHolder();
        db.sql(
                        "INSERT INTO wms_receiving_case(kind,arrival_id,receipt_id,reason,request_key,request_hash,requested_by,requested_at) VALUES ('REVERSAL',?,?,?,?,?,?,?)")
                .params(
                        arrival,
                        input.receiptId(),
                        reason(input.reason()),
                        input.idempotencyKey(),
                        hash,
                        actor.currentActor(),
                        now())
                .update(key);
        long id = key.getKey().longValue();
        audit(id, "REQUESTED", input.reason());
        return get(id);
    }

    private void eligible(long receiptId) {
        var row =
                db.sql(
                                "SELECT status,correction_status,downstream_stage FROM wms_receipt WHERE id=? FOR UPDATE")
                        .param(receiptId)
                        .query()
                        .singleRow();
        if (!row.get("status").equals("SUBMITTED") || !row.get("correction_status").equals("NONE"))
            throw conflict("RECEIPT_NOT_REVERSIBLE", "只有未冲正的已提交收货单可以申请冲正");
        if (!row.get("downstream_stage").equals("NONE"))
            throw conflict("DOWNSTREAM_STARTED", "已进入检验或上架流程，请使用退货或异常流程");
    }

    public CaseRecord decide(long id, DecisionInput input) {
        var reference = get(id);
        lockArrival(reference.arrivalId());
        var row =
                db.sql("SELECT * FROM wms_receiving_case WHERE id=? FOR UPDATE")
                        .param(id)
                        .query()
                        .singleRow();
        if (row.get("requested_by").equals(actor.currentActor()))
            throw new BusinessException(403, "SELF_APPROVAL_DENIED", "申请人不能审批自己的申请");
        String digest = hash("DECISION", input);
        if (!row.get("status").equals("PENDING")) {
            if (input.idempotencyKey().equals(row.get("decision_key"))
                    && digest.equals(row.get("decision_hash"))
                    && actor.currentActor().equals(row.get("decided_by"))) return get(id);
            throw conflict("CASE_ALREADY_DECIDED", "申请已处理，请刷新");
        }
        if (((Number) row.get("version")).longValue() != input.version())
            throw conflict("VERSION_CONFLICT", "申请版本已变化，请刷新");
        String reason = reason(input.reason());
        if (input.decision() == Decision.APPROVE && reference.kind().equals("REVERSAL"))
            reverse(reference);
        db.sql(
                        "UPDATE wms_receiving_case SET status=?,version=version+1,decision_key=?,decision_hash=?,decided_by=?,decided_at=?,decision_reason=? WHERE id=?")
                .params(
                        input.decision() == Decision.APPROVE ? "APPROVED" : "REJECTED",
                        input.idempotencyKey(),
                        digest,
                        actor.currentActor(),
                        now(),
                        reason,
                        id)
                .update();
        audit(id, input.decision().name(), reason);
        return get(id);
    }

    private void reverse(CaseRecord request) {
        long receiptId = request.receiptId();
        eligible(receiptId);
        var entries =
                db.sql(
                                "SELECT t.*,i.arrival_item_id FROM wms_inventory_transaction t JOIN wms_receipt_item i ON i.id=t.receipt_item_id JOIN wms_inventory_balance b ON b.id=t.balance_id WHERE t.receipt_id=? AND t.transaction_type='RECEIPT' ORDER BY b.stock_key,t.id")
                        .param(receiptId)
                        .query()
                        .listOfRows();
        if (entries.isEmpty()) throw conflict("LEDGER_MISSING", "原收货流水缺失，不能冲正");
        for (var original : entries) {
            long balanceId = ((Number) original.get("balance_id")).longValue();
            BigDecimal qty = (BigDecimal) original.get("change_qty");
            var balance =
                    db.sql(
                                    "SELECT on_hand_qty,available_qty,quality_status,active_freeze_id FROM wms_inventory_balance WHERE id=? FOR UPDATE")
                            .param(balanceId)
                            .query()
                            .singleRow();
            BigDecimal before = (BigDecimal) balance.get("on_hand_qty");
            io.github.acczff.mdop.wms.inventory.StockFreeze.requireUnfrozen(balance);
            if (!balance.get("quality_status").equals("PENDING_INSPECTION")
                    || ((BigDecimal) balance.get("available_qty")).signum() != 0
                    || before.compareTo(qty) < 0)
                throw conflict("STOCK_NOT_REVERSIBLE", "库存已变化，不能直接冲正");
            db.sql("UPDATE wms_inventory_balance SET on_hand_qty=on_hand_qty-? WHERE id=?")
                    .params(qty, balanceId)
                    .update();
            if (db.sql(
                                    "UPDATE wms_arrival_notice_item SET received_qty=received_qty-? WHERE id=? AND received_qty>=?")
                            .params(qty, original.get("arrival_item_id"), qty)
                            .update()
                    != 1) throw conflict("ARRIVAL_COUNT_CONFLICT", "累计收货数量异常");
            db.sql(
                            "INSERT INTO wms_inventory_transaction(receipt_id,receipt_item_id,balance_id,before_qty,change_qty,after_qty,created_by,created_at,transaction_type,reversed_transaction_id) VALUES (?,?,?,?,?,?,?,?,'REVERSAL',?)")
                    .params(
                            receiptId,
                            original.get("receipt_item_id"),
                            balanceId,
                            before,
                            qty.negate(),
                            before.subtract(qty),
                            actor.currentActor(),
                            now(),
                            original.get("id"))
                    .update();
        }
        db.sql("UPDATE wms_receipt SET correction_status='REVERSED',version=version+1 WHERE id=?")
                .param(receiptId)
                .update();
        int positive =
                db.sql(
                                "SELECT COUNT(*) FROM wms_arrival_notice_item WHERE arrival_id=? AND received_qty>0")
                        .param(request.arrivalId())
                        .query(Integer.class)
                        .single();
        int remaining =
                db.sql(
                                "SELECT COUNT(*) FROM wms_arrival_notice_item WHERE arrival_id=? AND received_qty<notice_qty")
                        .param(request.arrivalId())
                        .query(Integer.class)
                        .single();
        db.sql("UPDATE wms_arrival_notice SET status=?,version=version+1 WHERE id=?")
                .params(
                        positive == 0
                                ? "PENDING_RECEIPT"
                                : remaining == 0 ? "RECEIVED" : "PARTIALLY_RECEIVED",
                        request.arrivalId())
                .update();
        db.sql(
                        "INSERT INTO wms_receiving_audit(receipt_id,action,actor,occurred_at) VALUES (?,'REVERSED',?,?)")
                .params(receiptId, actor.currentActor(), now())
                .update();
        for (String event : List.of("PurchaseReceiptReversed", "IncomingInspectionCancelled")) {
            db.sql(
                            "INSERT INTO wms_outbox(message_id,event_type,aggregate_id,trace_id,payload,occurred_at) VALUES (?,?,?,?,?,?)")
                    .params(
                            UUID.randomUUID().toString(),
                            event,
                            receiptId,
                            "correction-" + request.id(),
                            json.writeValueAsString(
                                    Map.of(
                                            "receiptId",
                                            receiptId,
                                            "arrivalId",
                                            request.arrivalId(),
                                            "caseId",
                                            request.id(),
                                            "reason",
                                            request.reason(),
                                            "operator",
                                            actor.currentActor(),
                                            "originalEvents",
                                            db.sql(
                                                            "SELECT message_id,event_type FROM wms_outbox WHERE aggregate_id=? AND event_type IN ('PurchaseReceiptConfirmed','IncomingInspectionRequested')")
                                                    .param(receiptId)
                                                    .query()
                                                    .listOfRows())),
                            now())
                    .update();
        }
    }

    /** Future QMS/putaway adapters must call this within their business transaction, never update the marker directly. */
    public void markDownstreamStarted(long receiptId, String stage) {
        if (!Set.of("INSPECTION", "PUTAWAY").contains(stage))
            throw new IllegalArgumentException("Invalid downstream stage");
        long arrival =
                db.sql("SELECT arrival_id FROM wms_receipt WHERE id=?")
                        .param(receiptId)
                        .query(Long.class)
                        .single();
        lockArrival(arrival);
        eligible(receiptId);
        db.sql("UPDATE wms_receipt SET downstream_stage=?,version=version+1 WHERE id=?")
                .params(stage, receiptId)
                .update();
    }

    private void audit(long id, String action, String detail) {
        db.sql(
                        "INSERT INTO wms_receiving_case_audit(case_id,action,actor,detail,occurred_at) VALUES (?,?,?,?,?)")
                .params(id, action, actor.currentActor(), detail, now())
                .update();
    }

    private String hash(String type, Object input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            (type + json.writeValueAsString(input))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String reason(String value) {
        String text = value == null ? "" : value.replace("\r\n", "\n").strip();
        if (text.isBlank()
                || text.length() > 500
                || text.codePoints()
                        .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'))
            throw new BusinessException(400, "VALIDATION_FAILED", "原因或意见必须为500字以内的有效文字");
        return text;
    }
}
