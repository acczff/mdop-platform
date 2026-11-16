package io.github.acczff.mdop.wms.receiving.application;

import static io.github.acczff.mdop.wms.receiving.application.QualityModels.*;
import static io.github.acczff.mdop.wms.receiving.application.ReceivingModels.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.masterdata.catalog.CatalogService;
import io.github.acczff.mdop.wms.inventory.ledger.InventoryLedger;
import io.github.acczff.mdop.wms.receiving.ReceivingAccess;
import io.github.acczff.mdop.wms.receiving.domain.ArrivalLockPort;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class QualityService {
    private final JdbcClient db;
    private final ReceivingAccess access;
    private final ArrivalLockPort arrivalLock;
    private final CatalogService catalog;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;
    private final ReceivingQueries queries;
    private final InventoryLedger ledger;

    public QualityService(
            JdbcClient db,
            ReceivingAccess access,
            ArrivalLockPort arrivalLock,
            CatalogService catalog,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json,
            ReceivingQueries queries,
            InventoryLedger ledger) {
        this.db = db;
        this.access = access;
        this.arrivalLock = arrivalLock;
        this.catalog = catalog;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
        this.queries = queries;
        this.ledger = ledger;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(long warehouseId) {
        access.requireWarehouse(warehouseId);
        return db.sql(
                        """
            SELECT r.id,r.receipt_no,r.version,r.downstream_stage,
                   q.reference_no,q.reason,q.created_by,q.created_at
            FROM wms_receipt r JOIN wms_arrival_notice a ON a.id=r.arrival_id
            LEFT JOIN wms_quality_result q ON q.receipt_id=r.id
            WHERE a.warehouse_id=? AND r.status='SUBMITTED' AND r.correction_status='NONE'
            ORDER BY r.id DESC LIMIT 100
            """)
                .param(warehouseId)
                .query()
                .listOfRows();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> detail(long receiptId) {
        var receipt = receipt(receiptId, false);
        access.requireWarehouse(arrival(receipt.arrivalId(), false).warehouseId());
        return db.sql(
                        """
            SELECT i.id,i.material_id,a.material_code,a.material_name,i.location_id,
                   CAST(i.quantity AS CHAR) AS quantity,i.batch_no,i.date_code,i.expiry_date,
                   CAST(q.qualified_qty AS CHAR) AS qualified_qty,CAST(q.rejected_qty AS CHAR) AS rejected_qty,
                   q.putaway_location_id,q.putaway_by,q.putaway_at
            FROM wms_receipt_item i JOIN wms_arrival_notice_item a ON a.id=i.arrival_item_id
            LEFT JOIN wms_quality_item q ON q.receipt_item_id=i.id
            WHERE i.receipt_id=? ORDER BY i.id
            """)
                .param(receiptId)
                .query()
                .listOfRows();
    }

    public List<Map<String, Object>> inspect(ResultInput input) {
        var reference = receipt(input.receiptId(), false);
        arrivalLock.lockForArrival(reference.arrivalId());
        var arrival = arrival(reference.arrivalId(), true);
        var receipt = receipt(reference.id(), true);
        String digest = hash(List.of(actor.currentActor(), input));
        var old =
                db
                        .sql(
                                "SELECT request_key,request_hash FROM wms_quality_result WHERE receipt_id=?")
                        .param(receipt.id())
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst();
        if (old.isPresent()) {
            if (!input.idempotencyKey().equals(old.get().get("request_key"))
                    || !digest.equals(old.get().get("request_hash")))
                throw conflict("此收货单已有质检结果，不能修改或重复判定");
            return detail(receipt.id());
        }
        if (!receipt.status().equals("SUBMITTED")
                || !receipt.correctionStatus().equals("NONE")
                || !receipt.downstreamStage().equals("NONE")
                || receipt.version() != input.version())
            throw conflict("收货状态或版本已变化，请刷新；已冲正或已进入下游的单据不能判定");
        // Serialize quality/putaway within the warehouse; receiving holds a shared warehouse lock.
        lockWarehouse(arrival.warehouseId());
        var items = queries.items(receipt.id());
        var results = new HashMap<Long, ResultLine>();
        for (var line : input.items()) {
            if (results.put(line.receiptItemId(), line) != null) throw invalid("质检明细不能重复");
        }
        if (results.size() != items.size()) throw invalid("质检结果必须覆盖全部收货明细");
        for (var item : items) {
            var result = results.get(item.id());
            if (result == null
                    || result.qualifiedQty().add(result.rejectedQty()).compareTo(item.quantity())
                            != 0) throw invalid("合格与不合格数量之和必须等于收货数量");
        }
        // All pending balances are locked in the same order used by receiving and corrections.
        var ordered = new ArrayList<>(items);
        ordered.sort(
                Comparator.comparing(
                        i -> stockKey(arrival, i, i.locationId(), "PENDING_INSPECTION")));
        for (var item : ordered) balance(arrival, item, item.locationId(), "PENDING_INSPECTION");
        db.sql(
                        "INSERT INTO wms_quality_result(receipt_id,request_key,request_hash,reference_no,reason,created_by,created_at) VALUES (?,?,?,?,?,?,?)")
                .params(
                        receipt.id(),
                        input.idempotencyKey(),
                        digest,
                        input.referenceNo().strip(),
                        input.reason().strip(),
                        actor.currentActor(),
                        now())
                .update();
        for (var item : ordered) {
            var result = results.get(item.id());
            change(
                    arrival,
                    item,
                    item.locationId(),
                    "PENDING_INSPECTION",
                    item.quantity().negate(),
                    false,
                    "QUALITY_OUT");
            if (result.qualifiedQty().signum() > 0)
                change(
                        arrival,
                        item,
                        item.locationId(),
                        "QUALIFIED",
                        result.qualifiedQty(),
                        false,
                        "QUALITY_PASS");
            if (result.rejectedQty().signum() > 0)
                change(
                        arrival,
                        item,
                        item.locationId(),
                        "REJECTED",
                        result.rejectedQty(),
                        false,
                        "QUALITY_REJECT");
            db.sql(
                            "INSERT INTO wms_quality_item(receipt_item_id,receipt_id,qualified_qty,rejected_qty) VALUES (?,?,?,?)")
                    .params(item.id(), receipt.id(), result.qualifiedQty(), result.rejectedQty())
                    .update();
        }
        db.sql("UPDATE wms_receipt SET downstream_stage='INSPECTION',version=version+1 WHERE id=?")
                .param(receipt.id())
                .update();
        audit(receipt.id(), "QUALITY_RECORDED");
        return detail(receipt.id());
    }

    public List<Map<String, Object>> putaway(long itemId, PutawayInput input) {
        var item =
                queries.findItem(itemId)
                        .orElseThrow(() -> new BusinessException(404, "ITEM_NOT_FOUND", "收货明细不存在"));
        var reference = receipt(item.receiptId(), false);
        arrivalLock.lockForArrival(reference.arrivalId());
        var arrival = arrival(reference.arrivalId(), true);
        var receipt = receipt(reference.id(), true);
        var result =
                db
                        .sql(
                                "SELECT qualified_qty,putaway_key,putaway_hash FROM wms_quality_item WHERE receipt_item_id=? FOR UPDATE")
                        .param(itemId)
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> conflict("尚未接收质检结果"));
        String digest = hash(List.of(actor.currentActor(), itemId, input));
        if (result.get("putaway_key") != null) {
            if (!input.idempotencyKey().equals(result.get("putaway_key"))
                    || !digest.equals(result.get("putaway_hash"))) throw conflict("此明细已经上架，不能重复处理");
            return detail(receipt.id());
        }
        if (!receipt.correctionStatus().equals("NONE") || !receipt.status().equals("SUBMITTED"))
            throw conflict("此收货单不能上架");
        var quantity = (BigDecimal) result.get("qualified_qty");
        if (quantity.signum() <= 0) throw conflict("无合格数量，不允许上架");
        if (item.expiryDate() != null && item.expiryDate().isBefore(LocalDate.now(clock)))
            throw conflict("物料已过期，不能转为可用库存");
        lockWarehouse(arrival.warehouseId());
        var destination =
                db.sql("SELECT id,warehouse_id,code,name,area_type FROM mdm_location WHERE id=?")
                        .param(input.locationId())
                        .query(CatalogService.Location.class)
                        .optional()
                        .orElseThrow(() -> invalid("目标库位不存在"));
        if (destination.warehouseId() != arrival.warehouseId()
                || destination.areaType() != CatalogService.Area.STORAGE)
            throw invalid("目标必须为同仓正式存储库位");
        change(
                arrival,
                item,
                item.locationId(),
                "QUALIFIED",
                quantity.negate(),
                false,
                "PUTAWAY_OUT");
        change(arrival, item, destination.id(), "QUALIFIED", quantity, true, "PUTAWAY_IN");
        db.sql(
                        "UPDATE wms_quality_item SET putaway_location_id=?,putaway_key=?,putaway_hash=?,putaway_by=?,putaway_at=? WHERE receipt_item_id=?")
                .params(
                        destination.id(),
                        input.idempotencyKey(),
                        digest,
                        actor.currentActor(),
                        now(),
                        itemId)
                .update();
        db.sql("UPDATE wms_receipt SET downstream_stage='PUTAWAY',version=version+1 WHERE id=?")
                .param(receipt.id())
                .update();
        audit(receipt.id(), "PUTAWAY_CONFIRMED");
        return detail(receipt.id());
    }

    private void lockWarehouse(long id) {
        db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                .param(id)
                .query(Long.class)
                .single();
        catalog.requireReceivingWarehouse(id);
    }

    private Arrival arrival(long id, boolean lock) {
        var row =
                queries.findArrival(id, lock)
                        .orElseThrow(
                                () -> new BusinessException(404, "ARRIVAL_NOT_FOUND", "到货通知不存在"));
        access.requireWarehouse(row.warehouseId());
        return row;
    }

    private Receipt receipt(long id, boolean lock) {
        return queries.findReceipt(id, lock)
                .orElseThrow(() -> new BusinessException(404, "RECEIPT_NOT_FOUND", "收货单不存在"));
    }

    private String stockKey(Arrival a, Item i, long location, String quality) {
        return hash(
                Arrays.asList(
                        a.warehouseId(),
                        location,
                        i.materialId(),
                        a.supplierId(),
                        i.batchNo(),
                        i.dateCode(),
                        i.productionDate(),
                        i.expiryDate(),
                        quality,
                        "ENTERPRISE",
                        0));
    }

    private Map<String, Object> balance(Arrival a, Item i, long location, String quality) {
        String key = stockKey(a, i, location, quality);
        return ledger.ensureAndLock(
                new InventoryLedger.BalanceSpec(
                        key,
                        a.warehouseId(),
                        location,
                        i.materialId(),
                        a.supplierId(),
                        i.batchNo(),
                        i.dateCode(),
                        i.productionDate(),
                        i.expiryDate(),
                        quality,
                        "ENTERPRISE",
                        0L,
                        "PURCHASE"));
    }

    private void change(
            Arrival a,
            Item i,
            long location,
            String quality,
            BigDecimal delta,
            boolean available,
            String type) {
        var row = balance(a, i, location, quality);
        long id = ((Number) row.get("id")).longValue();
        ledger.postMovement(
                InventoryLedger.MovementCommand.of(
                        id,
                        delta,
                        available ? delta : BigDecimal.ZERO,
                        true,
                        delta.signum() < 0,
                        type,
                        InventoryLedger.LedgerRef.receipt(i.receiptId(), i.id()),
                        "CONFLICT",
                        "库存不足，请核对库存流水"));
    }

    private void audit(long receipt, String action) {
        db.sql(
                        "INSERT INTO wms_receiving_audit(receipt_id,action,actor,occurred_at) VALUES (?,?,?,?)")
                .params(receipt, action, actor.currentActor(), now())
                .update();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            json.writeValueAsString(value)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(409, "QUALITY_CONFLICT", message);
    }

    private BusinessException invalid(String message) {
        return new BusinessException(400, "VALIDATION_FAILED", message);
    }
}
