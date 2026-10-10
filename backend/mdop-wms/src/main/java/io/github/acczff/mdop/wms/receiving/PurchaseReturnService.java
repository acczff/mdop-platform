package io.github.acczff.mdop.wms.receiving;

import static io.github.acczff.mdop.wms.receiving.PurchaseReturnModels.*;
import static io.github.acczff.mdop.wms.receiving.ReceivingModels.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.masterdata.catalog.CatalogService;
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
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class PurchaseReturnService {
    private final JdbcClient db;
    private final ReceivingAccess access;
    private final CurrentActorProvider actor;
    private final CatalogService catalog;
    private final Clock clock;
    private final ObjectMapper json;

    public PurchaseReturnService(
            JdbcClient db,
            ReceivingAccess access,
            CurrentActorProvider actor,
            CatalogService catalog,
            Clock clock,
            ObjectMapper json) {
        this.db = db;
        this.access = access;
        this.actor = actor;
        this.catalog = catalog;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<ReturnRecord> list(long warehouseId) {
        access.requireWarehouse(warehouseId);
        return db.sql(
                        "SELECT p.* FROM wms_purchase_return p JOIN wms_receipt r ON r.id=p.receipt_id JOIN wms_arrival_notice a ON a.id=r.arrival_id WHERE a.warehouse_id=? ORDER BY p.id DESC LIMIT 100")
                .param(warehouseId)
                .query(ReturnRecord.class)
                .list();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> candidates(long warehouseId) {
        access.requireWarehouse(warehouseId);
        return db.sql(
                        """
            SELECT i.id AS receipt_item_id,r.receipt_no,i.receipt_id,ai.material_code,ai.material_name,
                a.supplier_name,l.name AS location_name,i.batch_no,
                CAST(q.rejected_qty AS CHAR) AS rejected_qty,
                CAST(COALESCE(t.committed,0) AS CHAR) AS committed_qty,
                CAST(q.rejected_qty-COALESCE(t.committed,0) AS CHAR) AS remaining_qty
            FROM wms_quality_item q JOIN wms_receipt_item i ON i.id=q.receipt_item_id
            JOIN wms_receipt r ON r.id=i.receipt_id JOIN wms_arrival_notice a ON a.id=r.arrival_id
            JOIN wms_arrival_notice_item ai ON ai.id=i.arrival_item_id
            JOIN mdm_location l ON l.id=i.location_id
            LEFT JOIN (SELECT receipt_item_id,SUM(quantity) AS committed FROM wms_purchase_return
                WHERE status IN ('PENDING','APPROVED','RETURNED') GROUP BY receipt_item_id) t ON t.receipt_item_id=i.id
            WHERE a.warehouse_id=? AND q.rejected_qty>0 AND r.correction_status='NONE'
            ORDER BY i.id DESC LIMIT 100
            """)
                .param(warehouseId)
                .query()
                .listOfRows();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(long id) {
        var record = record(id);
        checkWarehouse(record.receiptId());
        return db.sql(
                        "SELECT action,actor,detail,occurred_at FROM wms_purchase_return_audit WHERE return_id=? ORDER BY id")
                .param(id)
                .query()
                .listOfRows();
    }

    public ReturnRecord create(CreateInput input) {
        var item = item(input.receiptItemId());
        var receipt = lockReceipt(item.receiptId());
        if (!receipt.status().equals("SUBMITTED") || !receipt.correctionStatus().equals("NONE"))
            throw conflict("仅已提交且未冲正的收货明细可退货");
        String digest = hash("CREATE", input);
        var old =
                db.sql("SELECT * FROM wms_purchase_return WHERE request_key=? FOR UPDATE")
                        .param(input.idempotencyKey())
                        .query()
                        .listOfRows();
        if (!old.isEmpty()) {
            if (!digest.equals(old.getFirst().get("request_hash"))) throw conflict("请求编号已用于其他申请");
            return record(((Number) old.getFirst().get("id")).longValue());
        }
        BigDecimal rejected =
                db.sql(
                                "SELECT rejected_qty FROM wms_quality_item WHERE receipt_item_id=? FOR UPDATE")
                        .param(item.id())
                        .query(BigDecimal.class)
                        .optional()
                        .orElseThrow(() -> conflict("尚无质检结果"));
        BigDecimal allocated =
                db
                        .sql(
                                "SELECT quantity FROM wms_purchase_return WHERE receipt_item_id=? AND status IN ('PENDING','APPROVED','RETURNED') FOR UPDATE")
                        .param(item.id())
                        .query(BigDecimal.class)
                        .list()
                        .stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (input.quantity().compareTo(rejected.subtract(allocated)) > 0)
            throw conflict("申请数量超过剩余可退额度；待审批和已批准申请也占用额度");
        var keys = new GeneratedKeyHolder();
        db.sql(
                        "INSERT INTO wms_purchase_return(receipt_id,receipt_item_id,quantity,reason,request_key,request_hash,requested_by,requested_at) VALUES (?,?,?,?,?,?,?,?)")
                .params(
                        receipt.id(),
                        item.id(),
                        input.quantity(),
                        text(input.reason(), 500),
                        input.idempotencyKey(),
                        digest,
                        actor.currentActor(),
                        now())
                .update(keys);
        long id = keys.getKey().longValue();
        audit(id, "REQUESTED", input.reason());
        return record(id);
    }

    public ReturnRecord decide(long id, DecisionInput input) {
        var row = lockReturn(id);
        if (row.get("requested_by").equals(actor.currentActor()))
            throw new BusinessException(403, "SELF_APPROVAL_DENIED", "申请人不能审批自己的退货申请");
        String digest = hash("DECISION", input);
        if (replay(row, "decision", input.idempotencyKey(), digest)) return record(id);
        requireState(row, "PENDING", input.version());
        db.sql(
                        "UPDATE wms_purchase_return SET status=?,version=version+1,decision_key=?,decision_hash=?,decided_by=?,decided_at=?,decision_reason=? WHERE id=?")
                .params(
                        input.decision().equals("APPROVE") ? "APPROVED" : "REJECTED",
                        input.idempotencyKey(),
                        digest,
                        actor.currentActor(),
                        now(),
                        text(input.reason(), 500),
                        id)
                .update();
        audit(id, input.decision().equals("APPROVE") ? "APPROVED" : "REJECTED", input.reason());
        return record(id);
    }

    public ReturnRecord cancel(long id, CancelInput input) {
        var row = lockReturn(id);
        if (!row.get("requested_by").equals(actor.currentActor()))
            throw new BusinessException(403, "RETURN_OWNER_REQUIRED", "只有申请人可以撤销未退货申请");
        String digest = hash("CANCEL", input);
        if (replay(row, "cancel", input.idempotencyKey(), digest)) return record(id);
        if (!Set.of("PENDING", "APPROVED").contains(row.get("status"))) throw conflict("此申请不能撤销");
        requireVersion(row, input.version());
        db.sql(
                        "UPDATE wms_purchase_return SET status='CANCELLED',version=version+1,cancel_key=?,cancel_hash=? WHERE id=?")
                .params(input.idempotencyKey(), digest, id)
                .update();
        audit(id, "CANCELLED", text(input.reason(), 500));
        return record(id);
    }

    public ReturnRecord confirm(long id, ConfirmInput input) {
        var row = lockReturn(id);
        String digest = hash("CONFIRM", input);
        if (replay(row, "confirm", input.idempotencyKey(), digest)) return record(id);
        requireState(row, "APPROVED", input.version());
        var record = record(id);
        var item = item(record.receiptItemId());
        var receipt =
                db.sql("SELECT * FROM wms_receipt WHERE id=?")
                        .param(record.receiptId())
                        .query(Receipt.class)
                        .single();
        var arrival =
                db.sql("SELECT * FROM wms_arrival_notice WHERE id=?")
                        .param(receipt.arrivalId())
                        .query(Arrival.class)
                        .single();
        if (!receipt.status().equals("SUBMITTED") || !receipt.correctionStatus().equals("NONE"))
            throw conflict("收货单状态不允许退货");
        db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                .param(arrival.warehouseId())
                .query(Long.class)
                .single();
        catalog.requireReceivingWarehouse(arrival.warehouseId());
        long balanceId =
                db.sql(
                                "SELECT balance_id FROM wms_inventory_transaction WHERE receipt_item_id=? AND transaction_type='QUALITY_REJECT'")
                        .param(item.id())
                        .query(Long.class)
                        .optional()
                        .orElseThrow(() -> conflict("缺少不合格库存来源流水"));
        var balance =
                db.sql("SELECT * FROM wms_inventory_balance WHERE id=? FOR UPDATE")
                        .param(balanceId)
                        .query()
                        .singleRow();
        io.github.acczff.mdop.wms.inventory.StockFreeze.requireUnfrozen(balance);
        if (!balance.get("quality_status").equals("REJECTED")
                || ((BigDecimal) balance.get("available_qty")).signum() != 0)
            throw conflict("退货来源必须是不合格不可用库存");
        var before = (BigDecimal) balance.get("on_hand_qty");
        var after = before.subtract(record.quantity());
        if (after.signum() < 0) throw conflict("不合格库存不足，不能确认实际退货");
        String handover = text(input.handoverNo(), 64);
        db.sql("UPDATE wms_inventory_balance SET on_hand_qty=? WHERE id=?")
                .params(after, balanceId)
                .update();
        db.sql(
                        "INSERT INTO wms_inventory_transaction(receipt_id,receipt_item_id,balance_id,before_qty,change_qty,after_qty,created_by,created_at,transaction_type,purchase_return_id) VALUES (?,?,?,?,?,?,?,?,'RETURN_OUT',?)")
                .params(
                        receipt.id(),
                        item.id(),
                        balanceId,
                        before,
                        record.quantity().negate(),
                        after,
                        actor.currentActor(),
                        now(),
                        id)
                .update();
        db.sql(
                        "UPDATE wms_purchase_return SET status='RETURNED',version=version+1,confirm_key=?,confirm_hash=?,handover_no=?,confirmed_by=?,confirmed_at=? WHERE id=?")
                .params(input.idempotencyKey(), digest, handover, actor.currentActor(), now(), id)
                .update();
        audit(id, "RETURNED", "交接凭证：" + handover);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("returnId", id);
        payload.put("receiptId", receipt.id());
        payload.put("receiptItemId", item.id());
        payload.put("arrivalId", arrival.id());
        payload.put("purchaseOrderNo", arrival.purchaseOrderNo());
        payload.put("supplierId", arrival.supplierId());
        payload.put("warehouseId", arrival.warehouseId());
        payload.put("materialId", item.materialId());
        payload.put("locationId", item.locationId());
        payload.put("batchNo", item.batchNo());
        payload.put("quantity", record.quantity().toPlainString());
        payload.put("reason", record.reason());
        payload.put("handoverNo", handover);
        payload.put("operator", actor.currentActor());
        db.sql(
                        "INSERT INTO wms_outbox(message_id,event_type,aggregate_id,trace_id,payload,occurred_at,business_key) VALUES (?,'PurchaseReturnConfirmed',?,?,?,?,?)")
                .params(
                        UUID.randomUUID().toString(),
                        receipt.id(),
                        "purchase-return-" + id,
                        json.writeValueAsString(payload),
                        now(),
                        String.valueOf(id))
                .update();
        return record(id);
    }

    private ReturnRecord record(long id) {
        return db.sql("SELECT * FROM wms_purchase_return WHERE id=?")
                .param(id)
                .query(ReturnRecord.class)
                .optional()
                .orElseThrow(() -> new BusinessException(404, "RETURN_NOT_FOUND", "退货申请不存在"));
    }

    private Item item(long id) {
        return db.sql("SELECT * FROM wms_receipt_item WHERE id=?")
                .param(id)
                .query(Item.class)
                .optional()
                .orElseThrow(() -> new BusinessException(404, "ITEM_NOT_FOUND", "收货明细不存在"));
    }

    private void checkWarehouse(long receiptId) {
        long warehouse =
                db.sql(
                                "SELECT a.warehouse_id FROM wms_receipt r JOIN wms_arrival_notice a ON a.id=r.arrival_id WHERE r.id=?")
                        .param(receiptId)
                        .query(Long.class)
                        .single();
        access.requireWarehouse(warehouse);
    }

    private Receipt lockReceipt(long id) {
        checkWarehouse(id);
        long arrivalId =
                db.sql("SELECT arrival_id FROM wms_receipt WHERE id=?")
                        .param(id)
                        .query(Long.class)
                        .single();
        PurchaseArrivalLock.acquire(db, access, arrivalId);
        db.sql("SELECT id FROM wms_arrival_notice WHERE id=? FOR UPDATE")
                .param(arrivalId)
                .query(Long.class)
                .single();
        return db.sql("SELECT * FROM wms_receipt WHERE id=? FOR UPDATE")
                .param(id)
                .query(Receipt.class)
                .single();
    }

    private Map<String, Object> lockReturn(long id) {
        var reference = record(id);
        lockReceipt(reference.receiptId());
        return db.sql("SELECT * FROM wms_purchase_return WHERE id=? FOR UPDATE")
                .param(id)
                .query()
                .singleRow();
    }

    private boolean replay(Map<String, Object> row, String operation, String key, String digest) {
        if (row.get(operation + "_key") == null) return false;
        if (!key.equals(row.get(operation + "_key"))
                || !digest.equals(row.get(operation + "_hash")))
            throw conflict("此操作已处理，请刷新申请记录；原请求可安全重试");
        return true;
    }

    private void requireState(Map<String, Object> row, String state, long version) {
        if (!state.equals(row.get("status"))) throw conflict("申请状态已变化，请刷新后重试");
        requireVersion(row, version);
    }

    private void requireVersion(Map<String, Object> row, long version) {
        if (((Number) row.get("version")).longValue() != version) throw conflict("申请版本已变化，请刷新后重试");
    }

    private void audit(long id, String action, String detail) {
        db.sql(
                        "INSERT INTO wms_purchase_return_audit(return_id,action,actor,detail,occurred_at) VALUES (?,?,?,?,?)")
                .params(id, action, actor.currentActor(), detail, now())
                .update();
    }

    private String text(String value, int length) {
        String cleaned = value.strip();
        if (cleaned.isEmpty()
                || cleaned.length() > length
                || cleaned.codePoints()
                        .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'))
            throw new BusinessException(400, "VALIDATION_FAILED", "请输入有效的原因或交接凭证");
        return cleaned;
    }

    private String hash(String operation, Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            json.writeValueAsString(
                                                            List.of(
                                                                    operation,
                                                                    actor.currentActor(),
                                                                    value))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private BusinessException conflict(String message) {
        return new BusinessException(409, "PURCHASE_RETURN_CONFLICT", message);
    }
}
