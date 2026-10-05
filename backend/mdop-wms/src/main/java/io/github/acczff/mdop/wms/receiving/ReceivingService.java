package io.github.acczff.mdop.wms.receiving;

import static io.github.acczff.mdop.common.BusinessText.*;
import static io.github.acczff.mdop.wms.receiving.ReceivingModels.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.masterdata.catalog.CatalogService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class ReceivingService {
    private final JdbcClient jdbc;
    private final CatalogService catalog;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;
    private final ReceivingAccess access;

    public ReceivingService(
            JdbcClient jdbc,
            CatalogService catalog,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json,
            ReceivingAccess access) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
        this.access = access;
    }

    public ArrivalDetail simulateArrival(ArrivalInput input) {
        access.requireWarehouse(input.warehouseId());
        catalog.requireReceivingWarehouse(input.warehouseId());
        catalog.supplier(input.supplierId());
        String number = required(input.externalNoticeNo(), "外部到货单号", 64);
        String purchase = required(input.purchaseOrderNo(), "采购订单号", 64);
        long id =
                insert(
                        "INSERT INTO wms_arrival_notice(source_system,external_notice_no,purchase_order_no,supplier_id,warehouse_id,created_by,created_at) VALUES ('ERP_SIMULATOR',?,?,?,?,?,?)",
                        number,
                        purchase,
                        input.supplierId(),
                        input.warehouseId(),
                        actor.currentActor(),
                        now());
        Set<Long> seen = new HashSet<>();
        for (var line : input.items()) {
            if (!seen.add(line.materialId())) throw invalid("同一通知中的物料不能重复，请合并数量");
            var material = catalog.material(line.materialId());
            jdbc.sql(
                            "INSERT INTO wms_arrival_notice_item(arrival_id,material_id,material_code,material_name,unit,notice_qty) VALUES (?,?,?,?,?,?)")
                    .params(
                            id,
                            material.id(),
                            material.code(),
                            material.name(),
                            material.unit(),
                            quantity(line.quantity()))
                    .update();
        }
        return arrival(id);
    }

    @Transactional(readOnly = true)
    public List<Arrival> arrivals(long warehouseId) {
        access.requireWarehouse(warehouseId);
        return jdbc.sql(
                        "SELECT * FROM wms_arrival_notice WHERE warehouse_id=? ORDER BY id DESC LIMIT 100")
                .param(warehouseId)
                .query(Arrival.class)
                .list();
    }

    @Transactional(readOnly = true)
    public ArrivalDetail arrival(long id) {
        var arrival = loadArrival(id, false);
        return new ArrivalDetail(
                arrival,
                jdbc.sql("SELECT * FROM wms_arrival_notice_item WHERE arrival_id=? ORDER BY id")
                        .param(id)
                        .query(ArrivalItem.class)
                        .list());
    }

    @Transactional(readOnly = true)
    public List<Receipt> receipts(long arrivalId) {
        loadArrival(arrivalId, false);
        return jdbc.sql("SELECT * FROM wms_receipt WHERE arrival_id=? ORDER BY id DESC LIMIT 100")
                .param(arrivalId)
                .query(Receipt.class)
                .list();
    }

    @Transactional(readOnly = true)
    public ReceiptDetail receipt(long id) {
        var receipt = loadReceipt(id, false);
        loadArrival(receipt.arrivalId(), false);
        return new ReceiptDetail(receipt, items(id));
    }

    public ReceiptDetail createDraft(long arrivalId, DraftInput input) {
        var arrival = loadArrival(arrivalId, true);
        String digest = hash(json.writeValueAsString(input.items()));
        var previous =
                jdbc.sql("SELECT * FROM wms_receipt WHERE creation_key=? FOR UPDATE")
                        .param(input.idempotencyKey())
                        .query(Receipt.class)
                        .optional();
        if (previous.isPresent()) {
            var old = previous.get();
            if (old.arrivalId() != arrivalId || !old.creationHash().equals(digest))
                throw conflict("IDEMPOTENCY_CONFLICT", "同一幂等键不能用于不同收货内容");
            return receipt(old.id());
        }
        if (arrival.status().equals("RECEIVED")) throw conflict("ARRIVAL_COMPLETED", "此通知已经全部收货");
        catalog.requireReceivingWarehouse(arrival.warehouseId());
        long id =
                insert(
                        "INSERT INTO wms_receipt(receipt_no,arrival_id,creation_key,creation_hash,created_by,created_at) VALUES (?,?,?,?,?,?)",
                        "REC-" + UUID.randomUUID(),
                        arrivalId,
                        input.idempotencyKey(),
                        digest,
                        actor.currentActor(),
                        now());
        writeItems(id, arrival, input.items());
        audit(id, "DRAFT_CREATED");
        return receipt(id);
    }

    public ReceiptDetail updateDraft(long id, UpdateDraft input) {
        var receipt = loadReceipt(id, true);
        var arrival = loadArrival(receipt.arrivalId(), false);
        if (!receipt.status().equals("DRAFT")) throw conflict("RECEIPT_IMMUTABLE", "已提交收货单不能修改");
        if (receipt.version() != input.version())
            throw conflict("VERSION_CONFLICT", "草稿已变化，请刷新后重试");
        catalog.requireReceivingWarehouse(arrival.warehouseId());
        jdbc.sql("DELETE FROM wms_receipt_item WHERE receipt_id=?").param(id).update();
        writeItems(id, arrival, input.items());
        jdbc.sql("UPDATE wms_receipt SET version=version+1 WHERE id=?").param(id).update();
        audit(id, "DRAFT_UPDATED");
        return receipt(id);
    }

    public ReceiptDetail submit(long id, SubmitInput input) {
        // All submissions lock the arrival first, then the receipt, then sorted balance keys.
        var reference = loadReceipt(id, false);
        var arrival = loadArrival(reference.arrivalId(), true);
        var receipt = loadReceipt(id, true);
        if (receipt.status().equals("SUBMITTED")) {
            if (!input.idempotencyKey().equals(receipt.submitKey()))
                throw conflict("RECEIPT_IMMUTABLE", "此收货单已提交，请查看处理结果");
            return receipt(id);
        }
        if (receipt.version() != input.version())
            throw conflict("VERSION_CONFLICT", "草稿已变化，请刷新后提交");
        catalog.requireReceivingWarehouse(arrival.warehouseId());
        var lines = new ArrayList<>(items(id));
        if (lines.isEmpty()) throw invalid("至少需要一条收货明细");
        for (var item : lines)
            validateLine(
                    arrival,
                    new ReceiptLine(
                            item.arrivalItemId(),
                            item.locationId(),
                            item.quantity(),
                            item.batchNo(),
                            item.dateCode(),
                            item.productionDate(),
                            item.expiryDate()));
        lines.sort(Comparator.comparing(item -> stockKey(arrival, item)));
        for (var item : lines) {
            int changed =
                    jdbc.sql(
                                    "UPDATE wms_arrival_notice_item SET received_qty=received_qty+? WHERE id=? AND arrival_id=? AND received_qty+?<=notice_qty")
                            .params(
                                    item.quantity(),
                                    item.arrivalItemId(),
                                    arrival.id(),
                                    item.quantity())
                            .update();
            if (changed != 1) throw conflict("OVER_RECEIPT", "本次收货超过通知剩余数量，请刷新到货通知");
            increaseStock(arrival, item);
        }
        int remaining =
                jdbc.sql(
                                "SELECT COUNT(*) FROM wms_arrival_notice_item WHERE arrival_id=? AND received_qty<notice_qty")
                        .param(arrival.id())
                        .query(Integer.class)
                        .single();
        jdbc.sql("UPDATE wms_arrival_notice SET status=?,version=version+1 WHERE id=?")
                .params(remaining == 0 ? "RECEIVED" : "PARTIALLY_RECEIVED", arrival.id())
                .update();
        jdbc.sql(
                        "UPDATE wms_receipt SET status='SUBMITTED',submit_key=?,version=version+1,submitted_by=?,submitted_at=? WHERE id=?")
                .params(input.idempotencyKey(), actor.currentActor(), now(), id)
                .update();
        audit(id, "SUBMITTED");
        for (String type : List.of("IncomingInspectionRequested", "PurchaseReceiptConfirmed")) {
            jdbc.sql(
                            "INSERT INTO wms_outbox(message_id,event_type,aggregate_id,trace_id,payload,occurred_at) VALUES (?,?,?,?,?,?)")
                    .params(
                            UUID.randomUUID().toString(),
                            type,
                            id,
                            input.idempotencyKey(),
                            json.writeValueAsString(
                                    Map.of(
                                            "receipt",
                                            receipt(id),
                                            "arrival",
                                            arrival(arrival.id()),
                                            "supplier",
                                            catalog.supplier(arrival.supplierId()),
                                            "operator",
                                            actor.currentActor())),
                            now())
                    .update();
        }
        return receipt(id);
    }

    private void increaseStock(Arrival arrival, Item item) {
        String key = stockKey(arrival, item);
        jdbc.sql(
                        "INSERT INTO wms_inventory_balance(stock_key,warehouse_id,location_id,material_id,supplier_id,batch_no,date_code,production_date,expiry_date) VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE stock_key=stock_key")
                .params(
                        key,
                        arrival.warehouseId(),
                        item.locationId(),
                        item.materialId(),
                        arrival.supplierId(),
                        item.batchNo(),
                        item.dateCode(),
                        item.productionDate(),
                        item.expiryDate())
                .update();
        var balance =
                jdbc.sql("SELECT * FROM wms_inventory_balance WHERE stock_key=? FOR UPDATE")
                        .param(key)
                        .query(Balance.class)
                        .single();
        BigDecimal after = balance.onHandQty().add(item.quantity());
        if (after.compareTo(new BigDecimal("999999999999.999999")) > 0) throw invalid("库存数量超出支持范围");
        jdbc.sql("UPDATE wms_inventory_balance SET on_hand_qty=? WHERE id=?")
                .params(after, balance.id())
                .update();
        jdbc.sql(
                        "INSERT INTO wms_inventory_transaction(receipt_id,receipt_item_id,balance_id,before_qty,change_qty,after_qty,created_by,created_at) VALUES (?,?,?,?,?,?,?,?)")
                .params(
                        item.receiptId(),
                        item.id(),
                        balance.id(),
                        balance.onHandQty(),
                        item.quantity(),
                        after,
                        actor.currentActor(),
                        now())
                .update();
    }

    private void writeItems(long receiptId, Arrival arrival, List<ReceiptLine> lines) {
        for (var line : lines) {
            long materialId = validateLine(arrival, line);
            jdbc.sql(
                            "INSERT INTO wms_receipt_item(receipt_id,arrival_item_id,material_id,location_id,quantity,batch_no,date_code,production_date,expiry_date) VALUES (?,?,?,?,?,?,?,?,?)")
                    .params(
                            receiptId,
                            line.arrivalItemId(),
                            materialId,
                            line.locationId(),
                            quantity(line.quantity()),
                            optional(line.batchNo(), "批次", 64),
                            optional(line.dateCode(), "Date Code", 32),
                            line.productionDate(),
                            line.expiryDate())
                    .update();
        }
    }

    private long validateLine(Arrival arrival, ReceiptLine line) {
        var source =
                jdbc.sql("SELECT * FROM wms_arrival_notice_item WHERE id=? AND arrival_id=?")
                        .params(line.arrivalItemId(), arrival.id())
                        .query(ArrivalItem.class)
                        .optional()
                        .orElseThrow(() -> invalid("收货物料不属于当前到货通知"));
        var material = catalog.material(source.materialId());
        catalog.receivingLocation(line.locationId(), arrival.warehouseId());
        quantity(line.quantity());
        if (material.trackingMode() == CatalogService.Tracking.BATCH)
            required(line.batchNo(), "供应商批次", 64);
        if (material.requireDateCode()) required(line.dateCode(), "Date Code", 32);
        if (material.requireExpiry() && line.expiryDate() == null) throw invalid("此物料必须填写有效期");
        if (line.expiryDate() != null && line.expiryDate().isBefore(LocalDate.now(clock)))
            throw invalid("不能正常接收已过期物料，请按异常流程处理");
        if (line.productionDate() != null
                && (line.productionDate().isAfter(LocalDate.now(clock))
                        || (line.expiryDate() != null
                                && line.productionDate().isAfter(line.expiryDate()))))
            throw invalid("生产日期不能晚于今天或有效期");
        return material.id();
    }

    @Transactional(readOnly = true)
    public List<Balance> inventory(long warehouseId) {
        access.requireWarehouse(warehouseId);
        return jdbc.sql(
                        "SELECT * FROM wms_inventory_balance WHERE warehouse_id=? ORDER BY id DESC LIMIT 100")
                .param(warehouseId)
                .query(Balance.class)
                .list();
    }

    @Transactional(readOnly = true)
    public List<Ledger> ledger(long receiptId) {
        receipt(receiptId);
        return jdbc.sql("SELECT * FROM wms_inventory_transaction WHERE receipt_id=? ORDER BY id")
                .param(receiptId)
                .query(Ledger.class)
                .list();
    }

    private Arrival loadArrival(long id, boolean lock) {
        var arrival =
                jdbc.sql(
                                "SELECT * FROM wms_arrival_notice WHERE id=?"
                                        + (lock ? " FOR UPDATE" : ""))
                        .param(id)
                        .query(Arrival.class)
                        .optional()
                        .orElseThrow(
                                () -> new BusinessException(404, "ARRIVAL_NOT_FOUND", "到货通知不存在"));
        access.requireWarehouse(arrival.warehouseId());
        return arrival;
    }

    private Receipt loadReceipt(long id, boolean lock) {
        return jdbc.sql("SELECT * FROM wms_receipt WHERE id=?" + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query(Receipt.class)
                .optional()
                .orElseThrow(() -> new BusinessException(404, "RECEIPT_NOT_FOUND", "收货记录不存在"));
    }

    private List<Item> items(long id) {
        return jdbc.sql("SELECT * FROM wms_receipt_item WHERE receipt_id=? ORDER BY id")
                .param(id)
                .query(Item.class)
                .list();
    }

    private void audit(long id, String action) {
        jdbc.sql(
                        "INSERT INTO wms_receiving_audit(receipt_id,action,actor,occurred_at) VALUES (?,?,?,?)")
                .params(id, action, actor.currentActor(), now())
                .update();
    }

    private long insert(String sql, Object... params) {
        var key = new GeneratedKeyHolder();
        jdbc.sql(sql).params(params).update(key);
        return key.getKey().longValue();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private String stockKey(Arrival arrival, Item item) {
        return hash(
                json.writeValueAsString(
                        Arrays.asList(
                                arrival.warehouseId(),
                                item.locationId(),
                                item.materialId(),
                                arrival.supplierId(),
                                item.batchNo(),
                                item.dateCode(),
                                item.productionDate(),
                                item.expiryDate(),
                                "PENDING_INSPECTION",
                                "ENTERPRISE",
                                0)));
    }

    private static String hash(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static BigDecimal quantity(BigDecimal value) {
        if (value == null
                || value.signum() <= 0
                || value.scale() > 6
                || value.compareTo(new BigDecimal("999999999999.999999")) > 0)
            throw invalid("数量必须大于0，整数不超过12位，小数不超过6位");
        return value;
    }

    private static BusinessException invalid(String detail) {
        return new BusinessException(400, "VALIDATION_FAILED", detail);
    }

    private static BusinessException conflict(String code, String detail) {
        return new BusinessException(409, code, detail);
    }
}
