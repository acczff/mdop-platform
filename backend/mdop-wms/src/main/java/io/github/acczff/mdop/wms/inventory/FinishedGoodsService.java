package io.github.acczff.mdop.wms.inventory;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.masterdata.catalog.CatalogService;
import io.github.acczff.mdop.wms.receiving.ReceivingAccess;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FinishedGoodsService {
    public record Demand(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String demandNo,
            @NotBlank @Size(max = 64) String workOrderNo,
            @Positive long warehouseId,
            @Positive long materialId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity,
            @NotBlank @Size(max = 64) String batchNo,
            @Size(max = 32) String dateCode,
            @NotNull LocalDate productionDate,
            LocalDate expiryDate) {}

    public record Quality(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String eventNo,
            @NotBlank @Pattern(regexp = "QUALIFIED|REJECTED") String result,
            @NotBlank @Size(max = 500) String reason) {}

    private final JdbcClient db;
    private final CatalogService catalog;
    private final ReceivingAccess access;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public FinishedGoodsService(
            JdbcClient db,
            CatalogService catalog,
            ReceivingAccess access,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json) {
        this.db = db;
        this.catalog = catalog;
        this.access = access;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
    }

    private static final String SELECT =
            "SELECT d.*,CAST(d.quantity AS CHAR) AS amount,m.name AS material_name,m.code AS material_code,m.unit,rl.name AS received_location,sl.name AS stored_location FROM wms_finished_receipt d JOIN mdm_material m ON m.id=d.material_id LEFT JOIN mdm_location rl ON rl.id=d.received_location_id LEFT JOIN mdm_location sl ON sl.id=d.stored_location_id";

    public InventoryService.Page list(long warehouse, int page, int size) {
        access.requireWarehouse(warehouse);
        long total =
                db.sql("SELECT COUNT(*) FROM wms_finished_receipt WHERE warehouse_id=?")
                        .param(warehouse)
                        .query(Long.class)
                        .single();
        return new InventoryService.Page(
                db.sql(SELECT + " WHERE d.warehouse_id=? ORDER BY d.id DESC LIMIT ? OFFSET ?")
                        .params(warehouse, size, (long) page * size)
                        .query()
                        .listOfRows(),
                page,
                size,
                total,
                (total + size - 1) / size);
    }

    public Map<String, Object> detail(long id) {
        var d = find(SELECT + " WHERE d.id=?", id);
        access.requireWarehouse(n(d, "warehouse_id"));
        return d;
    }

    public Map<String, Object> create(Demand in) {
        lockWarehouse(in.warehouseId());
        String code = in.dateCode() == null ? "" : in.dateCode().trim();
        String hash =
                hash(
                        Arrays.asList(
                                in.workOrderNo().trim(),
                                in.warehouseId(),
                                in.materialId(),
                                in.quantity().stripTrailingZeros().toPlainString(),
                                in.batchNo().trim(),
                                code,
                                in.productionDate(),
                                in.expiryDate()));
        var old =
                db.sql("SELECT id,request_hash FROM wms_finished_receipt WHERE demand_no=?")
                        .param(in.demandNo())
                        .query()
                        .listOfRows();
        if (!old.isEmpty()) {
            if (!hash.equals(old.getFirst().get("request_hash"))) throw conflict("MES 完工需求号内容不一致");
            return detail(n(old.getFirst(), "id"));
        }
        eligible(in.warehouseId());
        var m = catalog.material(in.materialId());
        if (m.requireDateCode() && code.isBlank()) throw conflict("此成品必须填写 Date Code");
        if (m.requireExpiry() && in.expiryDate() == null) throw conflict("此成品必须填写有效期");
        if (in.productionDate().isAfter(LocalDate.now(clock))
                || (in.expiryDate() != null && in.expiryDate().isBefore(in.productionDate())))
            throw conflict("生产日期或有效期不合法");
        db.sql(
                        "INSERT INTO wms_finished_receipt(demand_no,request_hash,work_order_no,warehouse_id,material_id,quantity,batch_no,date_code,production_date,expiry_date,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
                .params(
                        in.demandNo(),
                        hash,
                        in.workOrderNo().trim(),
                        in.warehouseId(),
                        in.materialId(),
                        in.quantity(),
                        in.batchNo().trim(),
                        code,
                        in.productionDate(),
                        in.expiryDate(),
                        actor.currentActor(),
                        now())
                .update();
        return detail(
                db.sql("SELECT id FROM wms_finished_receipt WHERE demand_no=?")
                        .param(in.demandNo())
                        .query(Long.class)
                        .single());
    }

    public Map<String, Object> receive(long id, long location) {
        var d = locked(id);
        if (!"OPEN".equals(d.get("status"))) {
            if (n(d, "received_location_id") != location) throw conflict("原收货库位不可改变");
            return detail(id);
        }
        eligible(n(d, "warehouse_id"));
        location(d, location, "INSPECTION");
        var b = balance(d, location, "PENDING_INSPECTION");
        move(b, q(d), false, "FG_RECEIPT", id);
        db.sql(
                        "UPDATE wms_finished_receipt SET status='RECEIVED',received_location_id=?,received_balance_id=?,received_by=?,received_at=? WHERE id=?")
                .params(location, n(b, "id"), actor.currentActor(), now(), id)
                .update();
        event(d, "FinishedGoodsReceived", "PENDING_INSPECTION");
        event(d, "FinishedInspectionRequested", "PENDING_INSPECTION");
        return detail(id);
    }

    public Map<String, Object> quality(long id, Quality in) {
        var d = locked(id);
        if (d.get("quality_event_no") != null) {
            if (!in.eventNo().equals(d.get("quality_event_no"))
                    || !in.result().equals(d.get("quality_status"))
                    || !in.reason().trim().equals(d.get("quality_reason")))
                throw conflict("原 QMS 判定内容不可修改");
            return detail(id);
        }
        if (!"RECEIVED".equals(d.get("status"))) throw conflict("仅已收货待检单可接收 QMS 结果");
        eligible(n(d, "warehouse_id"));
        if (in.result().equals("QUALIFIED")) unexpired(d);
        var source =
                find(
                        "SELECT * FROM wms_inventory_balance WHERE id=? FOR UPDATE",
                        n(d, "received_balance_id"));
        var target = balance(d, n(d, "received_location_id"), in.result());
        move(source, q(d).negate(), false, "FG_QC_OUT", id);
        move(target, q(d), false, "FG_QC_IN", id);
        db.sql(
                        "UPDATE wms_finished_receipt SET status=?,quality_event_no=?,quality_status=?,quality_reason=?,quality_by=?,quality_at=?,quality_balance_id=? WHERE id=?")
                .params(
                        in.result(),
                        in.eventNo(),
                        in.result(),
                        in.reason().trim(),
                        actor.currentActor(),
                        now(),
                        n(target, "id"),
                        id)
                .update();
        return detail(id);
    }

    public Map<String, Object> putaway(long id, long location) {
        var d = locked(id);
        if ("STORED".equals(d.get("status"))) {
            if (n(d, "stored_location_id") != location) throw conflict("原上架库位不可改变");
            return detail(id);
        }
        if (!"QUALIFIED".equals(d.get("status"))) throw conflict("仅 QMS 判定合格的成品可上架");
        eligible(n(d, "warehouse_id"));
        unexpired(d);
        location(d, location, "STORAGE");
        var source =
                find(
                        "SELECT * FROM wms_inventory_balance WHERE id=? FOR UPDATE",
                        n(d, "quality_balance_id"));
        var target = balance(d, location, "QUALIFIED");
        move(source, q(d).negate(), false, "FG_PUT_OUT", id);
        move(target, q(d), true, "FG_PUT_IN", id);
        db.sql(
                        "UPDATE wms_finished_receipt SET status='STORED',stored_location_id=?,stored_balance_id=?,stored_by=?,stored_at=? WHERE id=?")
                .params(location, n(target, "id"), actor.currentActor(), now(), id)
                .update();
        event(d, "FinishedGoodsPutaway", "QUALIFIED");
        return detail(id);
    }

    private Map<String, Object> locked(long id) {
        var ref = find("SELECT * FROM wms_finished_receipt WHERE id=?", id);
        lockWarehouse(n(ref, "warehouse_id"));
        return find("SELECT * FROM wms_finished_receipt WHERE id=? FOR UPDATE", id);
    }

    private void lockWarehouse(long id) {
        access.requireWarehouse(id);
        find("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE", id);
    }

    private void eligible(long id) {
        catalog.requireReceivingWarehouse(id);
        if (!"FINISHED_GOODS"
                .equals(find("SELECT purpose FROM mdm_warehouse WHERE id=?", id).get("purpose")))
            throw conflict("成品入库仅支持成品仓");
    }

    private void location(Map<String, Object> d, long id, String area) {
        var l = find("SELECT * FROM mdm_location WHERE id=?", id);
        if (n(l, "warehouse_id") != n(d, "warehouse_id") || !area.equals(l.get("area_type")))
            throw conflict("请选择本成品仓对应区域的库位");
    }

    private void unexpired(Map<String, Object> d) {
        if (d.get("expiry_date") != null
                && LocalDate.parse(d.get("expiry_date").toString()).isBefore(LocalDate.now(clock)))
            throw conflict("过期成品不得判为合格或上架");
    }

    private Map<String, Object> balance(Map<String, Object> d, long location, String quality) {
        String key =
                hash(
                        Arrays.asList(
                                n(d, "warehouse_id"),
                                location,
                                n(d, "material_id"),
                                null,
                                d.get("batch_no"),
                                d.get("date_code"),
                                date(d, "production_date"),
                                date(d, "expiry_date"),
                                quality,
                                "ENTERPRISE",
                                0L));
        db.sql(
                        "INSERT INTO wms_inventory_balance(stock_key,warehouse_id,location_id,material_id,supplier_id,batch_no,date_code,production_date,expiry_date,quality_status,origin_type) VALUES(?,?,?,?,NULL,?,?,?,?,?,'PRODUCTION') ON DUPLICATE KEY UPDATE stock_key=stock_key")
                .params(
                        key,
                        n(d, "warehouse_id"),
                        location,
                        n(d, "material_id"),
                        d.get("batch_no"),
                        d.get("date_code"),
                        date(d, "production_date"),
                        date(d, "expiry_date"),
                        quality)
                .update();
        return db.sql("SELECT * FROM wms_inventory_balance WHERE stock_key=? FOR UPDATE")
                .param(key)
                .query()
                .singleRow();
    }

    private void move(
            Map<String, Object> b, BigDecimal delta, boolean available, String type, long id) {
        BigDecimal before = (BigDecimal) b.get("on_hand_qty");
        if (before.add(delta).signum() < 0) throw conflict("成品库存不足");
        db.sql(
                        "UPDATE wms_inventory_balance SET on_hand_qty=on_hand_qty+?,available_qty=available_qty+? WHERE id=?")
                .params(delta, available ? delta : BigDecimal.ZERO, n(b, "id"))
                .update();
        db.sql(
                        "INSERT INTO wms_inventory_transaction(balance_id,before_qty,change_qty,after_qty,transaction_type,finished_receipt_id,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)")
                .params(
                        n(b, "id"),
                        before,
                        delta,
                        before.add(delta),
                        type,
                        id,
                        actor.currentActor(),
                        now())
                .update();
    }

    private void event(Map<String, Object> d, String type, String quality) {
        var payload =
                Map.of(
                        "receiptId",
                        n(d, "id"),
                        "workOrderNo",
                        d.get("work_order_no"),
                        "warehouseId",
                        n(d, "warehouse_id"),
                        "materialId",
                        n(d, "material_id"),
                        "batchNo",
                        d.get("batch_no"),
                        "quantity",
                        q(d).toPlainString(),
                        "qualityStatus",
                        quality);
        db.sql(
                        "INSERT INTO wms_outbox(message_id,event_type,aggregate_type,aggregate_id,trace_id,payload,occurred_at) VALUES(?,?,'FinishedReceipt',?,?,?,?)")
                .params(
                        UUID.randomUUID().toString(),
                        type,
                        n(d, "id"),
                        UUID.randomUUID().toString(),
                        json.writeValueAsString(payload),
                        now())
                .update();
    }

    private Map<String, Object> find(String sql, long id) {
        return db.sql(sql).param(id).query().listOfRows().stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        404, "FINISHED_REFERENCE_NOT_FOUND", "成品单或关联记录不存在"));
    }

    private long n(Map<String, Object> d, String k) {
        return ((Number) d.get(k)).longValue();
    }

    private BigDecimal q(Map<String, Object> d) {
        return (BigDecimal) d.get("quantity");
    }

    private LocalDate date(Map<String, Object> d, String k) {
        return d.get(k) == null ? null : LocalDate.parse(d.get(k).toString());
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private BusinessException conflict(String m) {
        return new BusinessException(409, "FINISHED_CONFLICT", m);
    }

    private String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(json.writeValueAsBytes(value)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
