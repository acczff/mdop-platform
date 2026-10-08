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
public class SalesService {
    public record Demand(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String demandNo,
            @NotBlank @Size(max = 64) String salesOrderNo,
            @NotBlank @Size(max = 128) String customerReference,
            @Positive long warehouseId,
            @Positive long materialId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity) {}

    public record Reserve(@Positive long balanceId) {}

    public record Pick(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestKey,
            @NotBlank @Size(max = 64) String batchNo,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity) {}

    public record Review(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestKey,
            @Positive long pickId,
            boolean approved,
            @NotBlank @Size(max = 500) String reason) {}

    public record Cancel(@NotBlank @Size(max = 500) String reason) {}

    private final JdbcClient db;
    private final ReceivingAccess access;
    private final CatalogService catalog;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public SalesService(
            JdbcClient db,
            ReceivingAccess access,
            CatalogService catalog,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json) {
        this.db = db;
        this.access = access;
        this.catalog = catalog;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
    }

    private static final String SELECT =
            "SELECT d.*,CAST(d.quantity AS CHAR) AS amount,b.batch_no,l.name AS location_name FROM wms_sales_order d LEFT JOIN wms_inventory_balance b ON b.id=d.source_balance_id LEFT JOIN mdm_location l ON l.id=b.location_id";

    public InventoryService.Page list(long warehouse, int page, int size) {
        access.requireWarehouse(warehouse);
        long count =
                db.sql("SELECT COUNT(*) FROM wms_sales_order WHERE warehouse_id=?")
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
                count,
                (count + size - 1) / size);
    }

    public Map<String, Object> detail(long id) {
        var d = find(SELECT + " WHERE d.id=?", id);
        access.requireWarehouse(n(d, "warehouse_id"));
        return d;
    }

    public List<Map<String, Object>> history(long id) {
        detail(id);
        return db.sql(
                        "SELECT id,action_type,pick_id,reason,created_by,created_at FROM wms_sales_action WHERE sales_order_id=? ORDER BY id")
                .param(id)
                .query()
                .listOfRows();
    }

    public List<Map<String, Object>> reservations(long id) {
        detail(id);
        return db.sql(
                        "SELECT event_type,CAST(quantity AS CHAR) AS quantity,CAST(before_reserved AS CHAR) AS before_reserved,CAST(after_reserved AS CHAR) AS after_reserved,created_by,created_at FROM wms_reservation_event WHERE sales_order_id=? ORDER BY id")
                .param(id)
                .query()
                .listOfRows();
    }

    public Map<String, Object> create(Demand in) {
        lockWarehouse(in.warehouseId());
        var old =
                db.sql("SELECT * FROM wms_sales_order WHERE demand_no=?")
                        .param(in.demandNo())
                        .query()
                        .listOfRows();
        if (!old.isEmpty()) {
            var d = old.getFirst();
            access.requireWarehouse(n(d, "warehouse_id"));
            if (n(d, "warehouse_id") != in.warehouseId()
                    || n(d, "material_id") != in.materialId()
                    || q(d, "quantity").compareTo(in.quantity()) != 0
                    || !d.get("sales_order_no").equals(in.salesOrderNo().trim())
                    || !d.get("customer_reference").equals(in.customerReference().trim()))
                throw conflict("ERP 需求号已用于不同内容");
            return detail(n(d, "id"));
        }
        eligible(in.warehouseId());
        var material = catalog.referenceMaterial(in.materialId());
        db.sql(
                        "INSERT INTO wms_sales_order(demand_no,sales_order_no,customer_reference,warehouse_id,material_id,quantity,created_by,created_at,material_code,material_name,unit) VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                .params(
                        in.demandNo(),
                        in.salesOrderNo().trim(),
                        in.customerReference().trim(),
                        in.warehouseId(),
                        in.materialId(),
                        in.quantity(),
                        actor.currentActor(),
                        now(),
                        material.code(),
                        material.name(),
                        material.unit())
                .update();
        return detail(
                db.sql("SELECT id FROM wms_sales_order WHERE demand_no=?")
                        .param(in.demandNo())
                        .query(Long.class)
                        .single());
    }

    public Map<String, Object> reserve(long id, Reserve in) {
        var d = locked(id);
        if (Set.of("RESERVED", "PICKED", "VERIFIED", "SHIPPED").contains(d.get("status"))) {
            if (n(d, "source_balance_id") != in.balanceId()) throw conflict("原预占库存不可修改");
            return detail(id);
        }
        if (!"OPEN".equals(d.get("status"))) throw conflict("仅待预占单可预占");
        eligible(n(d, "warehouse_id"));
        var ref = find("SELECT * FROM wms_inventory_balance WHERE id=?", in.balanceId());
        if (n(ref, "warehouse_id") != n(d, "warehouse_id")
                || n(ref, "material_id") != n(d, "material_id")) throw conflict("库存与仓库或成品物料不一致");
        var b = stock(in.balanceId());
        eligibleStock(b);
        if (q(b, "available_qty").compareTo(q(d, "quantity")) < 0) throw conflict("成品可用库存不足");
        reservation(d, b, "RESERVE");
        db.sql(
                        "UPDATE wms_sales_order SET status='RESERVED',source_balance_id=?,reserved_by=?,reserved_at=? WHERE id=?")
                .params(in.balanceId(), actor.currentActor(), now(), id)
                .update();
        return detail(id);
    }

    public Map<String, Object> pick(long id, Pick in) {
        var d = locked(id);
        String hash =
                hash(
                        Arrays.asList(
                                id,
                                "PICK",
                                actor.currentActor(),
                                in.batchNo().trim(),
                                in.quantity().stripTrailingZeros().toPlainString()));
        if (replay(in.requestKey(), hash)) return detail(id);
        if (!"RESERVED".equals(d.get("status"))) throw conflict("仅已预占单可拣货");
        eligible(n(d, "warehouse_id"));
        var b = stock(n(d, "source_balance_id"));
        eligibleStock(b);
        if (!b.get("batch_no").equals(in.batchNo().trim())
                || q(d, "quantity").compareTo(in.quantity()) != 0) throw conflict("实拣批次或数量与预占不一致");
        long action = action(id, in.requestKey(), "PICK", hash, null, null);
        db.sql(
                        "UPDATE wms_sales_order SET status='PICKED',pick_id=?,picked_by=?,picked_at=?,reviewed_by=NULL,reviewed_at=NULL WHERE id=?")
                .params(action, actor.currentActor(), now(), id)
                .update();
        return detail(id);
    }

    public Map<String, Object> review(long id, Review in) {
        var d = locked(id);
        String type = in.approved() ? "APPROVE" : "REJECT";
        String hash =
                hash(
                        Arrays.asList(
                                id, type, actor.currentActor(), in.pickId(), in.reason().trim()));
        if (replay(in.requestKey(), hash)) return detail(id);
        if (!"PICKED".equals(d.get("status")) || n(d, "pick_id") != in.pickId())
            throw conflict("拣货记录已变化，请刷新后复核");
        if (actor.currentActor().equals(d.get("picked_by"))) throw conflict("拣货人与复核人必须不同");
        if (in.approved()) {
            eligible(n(d, "warehouse_id"));
            eligibleStock(stock(n(d, "source_balance_id")));
        }
        action(id, in.requestKey(), type, hash, in.pickId(), in.reason().trim());
        db.sql("UPDATE wms_sales_order SET status=?,reviewed_by=?,reviewed_at=? WHERE id=?")
                .params(in.approved() ? "VERIFIED" : "RESERVED", actor.currentActor(), now(), id)
                .update();
        return detail(id);
    }

    public Map<String, Object> cancel(long id, String reason) {
        var d = locked(id);
        if ("CANCELLED".equals(d.get("status"))) {
            if (!reason.trim().equals(d.get("cancel_reason"))) throw conflict("原取消原因不可修改");
            return detail(id);
        }
        if ("SHIPPED".equals(d.get("status"))) throw conflict("已出库单不能取消");
        if (d.get("source_balance_id") != null)
            reservation(d, stock(n(d, "source_balance_id")), "RELEASE");
        db.sql(
                        "UPDATE wms_sales_order SET status='CANCELLED',cancel_reason=?,closed_by=?,closed_at=? WHERE id=?")
                .params(reason.trim(), actor.currentActor(), now(), id)
                .update();
        return detail(id);
    }

    public Map<String, Object> ship(long id) {
        var d = locked(id);
        if ("SHIPPED".equals(d.get("status"))) return detail(id);
        if (!"VERIFIED".equals(d.get("status"))) throw conflict("请先完成独立复核");
        eligible(n(d, "warehouse_id"));
        var b = stock(n(d, "source_balance_id"));
        eligibleStock(b);
        var amount = q(d, "quantity");
        if (q(b, "reserved_qty").compareTo(amount) < 0 || q(b, "on_hand_qty").compareTo(amount) < 0)
            throw conflict("预占或现有库存不足");
        reservation(d, b, "CONSUME");
        db.sql(
                        "INSERT INTO wms_inventory_transaction(balance_id,before_qty,change_qty,after_qty,transaction_type,sales_order_id,created_by,created_at) VALUES(?,?,?,?,'SALES_OUT',?,?,?)")
                .params(
                        n(b, "id"),
                        q(b, "on_hand_qty"),
                        amount.negate(),
                        q(b, "on_hand_qty").subtract(amount),
                        id,
                        actor.currentActor(),
                        now())
                .update();
        db.sql("UPDATE wms_sales_order SET status='SHIPPED',closed_by=?,closed_at=? WHERE id=?")
                .params(actor.currentActor(), now(), id)
                .update();
        var payload =
                Map.of(
                        "salesOrderId",
                        id,
                        "salesOrderNo",
                        d.get("sales_order_no"),
                        "customerReference",
                        d.get("customer_reference"),
                        "warehouseId",
                        n(d, "warehouse_id"),
                        "materialId",
                        n(d, "material_id"),
                        "batchNo",
                        b.get("batch_no"),
                        "quantity",
                        amount.toPlainString());
        db.sql(
                        "INSERT INTO wms_outbox(message_id,event_type,aggregate_type,aggregate_id,trace_id,payload,occurred_at) VALUES(?,'SalesOutboundConfirmed','SalesOrder',?,?,?,?)")
                .params(
                        UUID.randomUUID().toString(),
                        id,
                        UUID.randomUUID().toString(),
                        json.writeValueAsString(payload),
                        now())
                .update();
        return detail(id);
    }

    private void reservation(Map<String, Object> d, Map<String, Object> b, String type) {
        var amount = q(d, "quantity");
        var delta = type.equals("RESERVE") ? amount : amount.negate();
        if (q(b, "reserved_qty").add(delta).signum() < 0) throw conflict("预占库存不足");
        var available = type.equals("CONSUME") ? BigDecimal.ZERO : delta.negate();
        var physical = type.equals("CONSUME") ? amount.negate() : BigDecimal.ZERO;
        db.sql(
                        "UPDATE wms_inventory_balance SET reserved_qty=reserved_qty+?,available_qty=IF(active_freeze_id IS NULL,available_qty+?,0),on_hand_qty=on_hand_qty+?,reservation_version=reservation_version+1 WHERE id=?")
                .params(delta, available, physical, n(b, "id"))
                .update();
        db.sql(
                        "INSERT INTO wms_reservation_event(sales_order_id,balance_id,event_type,quantity,before_reserved,after_reserved,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)")
                .params(
                        n(d, "id"),
                        n(b, "id"),
                        type,
                        amount,
                        q(b, "reserved_qty"),
                        q(b, "reserved_qty").add(delta),
                        actor.currentActor(),
                        now())
                .update();
    }

    private boolean replay(String key, String hash) {
        var old =
                db.sql("SELECT payload_hash FROM wms_sales_action WHERE request_key=?")
                        .param(key)
                        .query(String.class)
                        .optional();
        if (old.isEmpty()) return false;
        if (!hash.equals(old.get())) throw conflict("操作请求号内容不一致");
        return true;
    }

    private long action(long id, String key, String type, String hash, Long pick, String reason) {
        db.sql(
                        "INSERT INTO wms_sales_action(sales_order_id,request_key,action_type,payload_hash,pick_id,reason,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)")
                .params(id, key, type, hash, pick, reason, actor.currentActor(), now())
                .update();
        return db.sql("SELECT id FROM wms_sales_action WHERE request_key=?")
                .param(key)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> locked(long id) {
        var ref = find("SELECT * FROM wms_sales_order WHERE id=?", id);
        lockWarehouse(n(ref, "warehouse_id"));
        return find("SELECT * FROM wms_sales_order WHERE id=? FOR UPDATE", id);
    }

    private void lockWarehouse(long id) {
        access.requireWarehouse(id);
        find("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE", id);
    }

    private void eligible(long id) {
        catalog.requireReceivingWarehouse(id);
        if (!"FINISHED_GOODS"
                .equals(find("SELECT purpose FROM mdm_warehouse WHERE id=?", id).get("purpose")))
            throw conflict("销售出库仅支持成品仓");
    }

    private void eligibleStock(Map<String, Object> b) {
        StockFreeze.requireUnfrozen(b);
        if (!"PRODUCTION".equals(b.get("origin_type"))
                || !"QUALIFIED".equals(b.get("quality_status"))
                || !"STORAGE".equals(b.get("area_type"))
                || !"ENTERPRISE".equals(b.get("owner_type"))
                || n(b, "owner_id") != 0
                || q(b, "production_qty").signum() != 0) throw conflict("仅支持已上架的企业自有合格自产成品");
        if (b.get("expiry_date") != null
                && LocalDate.parse(b.get("expiry_date").toString()).isBefore(LocalDate.now(clock)))
            throw conflict("过期成品不能出库");
    }

    private Map<String, Object> stock(long id) {
        return find(
                "SELECT b.*,l.area_type FROM wms_inventory_balance b JOIN mdm_location l ON l.id=b.location_id WHERE b.id=? FOR UPDATE",
                id);
    }

    private Map<String, Object> find(String sql, long id) {
        return db.sql(sql).param(id).query().listOfRows().stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "SALES_NOT_FOUND", "销售单或关联记录不存在"));
    }

    private long n(Map<String, Object> d, String k) {
        return ((Number) d.get(k)).longValue();
    }

    private BigDecimal q(Map<String, Object> d, String k) {
        return (BigDecimal) d.get(k);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private BusinessException conflict(String m) {
        return new BusinessException(409, "SALES_CONFLICT", m);
    }

    private String hash(Object v) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(json.writeValueAsBytes(v)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
