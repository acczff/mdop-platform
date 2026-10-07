package io.github.acczff.mdop.wms.inventory;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.masterdata.catalog.CatalogService;
import io.github.acczff.mdop.wms.receiving.ReceivingAccess;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class IssueService {
    public record Demand(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String demandNo,
            @NotBlank @Size(max = 64) String workOrderNo,
            @Positive long warehouseId,
            @Positive long targetWarehouseId,
            @Positive long materialId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity) {}

    public record Reserve(@Positive long balanceId, @Positive long targetLocationId) {}

    public record Cancel(@NotBlank @Size(max = 500) String reason) {}

    private final JdbcClient db;
    private final ReceivingAccess access;
    private final CatalogService catalog;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;
    private final ProductionEvents feedback;

    public IssueService(
            JdbcClient db,
            ReceivingAccess access,
            CatalogService catalog,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json,
            ProductionEvents feedback) {
        this.db = db;
        this.access = access;
        this.catalog = catalog;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
        this.feedback = feedback;
    }

    private static final String SELECT =
            """
        SELECT d.id,d.demand_no,d.work_order_no,d.warehouse_id,d.target_warehouse_id,d.material_id,
        CAST(d.quantity AS CHAR) AS quantity,CAST(d.consumed_qty AS CHAR) AS consumed_qty,CAST(d.returned_qty AS CHAR) AS returned_qty,CAST(d.quantity-d.consumed_qty-d.returned_qty AS CHAR) AS remaining_qty,d.status,d.source_balance_id,d.target_location_id,d.target_balance_id,
        d.created_by,d.created_at,d.reserved_by,d.reserved_at,d.closed_by,d.closed_at,d.cancel_reason,
        m.code AS material_code,m.name AS material_name,m.unit,s.name AS warehouse_name,w.name AS target_warehouse_name,
        b.batch_no,l.name AS source_location,t.name AS target_location
        FROM wms_material_issue d JOIN mdm_material m ON m.id=d.material_id
        JOIN mdm_warehouse s ON s.id=d.warehouse_id JOIN mdm_warehouse w ON w.id=d.target_warehouse_id
        LEFT JOIN wms_inventory_balance b ON b.id=d.source_balance_id
        LEFT JOIN mdm_location l ON l.id=b.location_id LEFT JOIN mdm_location t ON t.id=d.target_location_id
        """;

    @Transactional(readOnly = true)
    public InventoryService.Page list(long warehouse, long target, int page, int size) {
        access.requireWarehouse(warehouse);
        access.requireWarehouse(target);
        String where = " WHERE d.warehouse_id=? AND d.target_warehouse_id=?";
        long total =
                db.sql("SELECT COUNT(*) FROM wms_material_issue d" + where)
                        .params(warehouse, target)
                        .query(Long.class)
                        .single();
        return new InventoryService.Page(
                db.sql(SELECT + where + " ORDER BY d.id DESC LIMIT ? OFFSET ?")
                        .params(warehouse, target, size, (long) page * size)
                        .query()
                        .listOfRows(),
                page,
                size,
                total,
                (total + size - 1) / size);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(long id) {
        var row = find(SELECT + " WHERE d.id=?", id);
        access.requireWarehouse(n(row, "warehouse_id"));
        access.requireWarehouse(n(row, "target_warehouse_id"));
        return row;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> events(long id) {
        detail(id);
        return db.sql(
                        "SELECT id,event_type,CAST(quantity AS CHAR) AS quantity,CAST(before_reserved AS CHAR) AS before_reserved,CAST(after_reserved AS CHAR) AS after_reserved,created_by,created_at FROM wms_reservation_event WHERE issue_id=? ORDER BY id")
                .param(id)
                .query()
                .listOfRows();
    }

    public Map<String, Object> create(Demand input) {
        lockWarehouses(input.warehouseId(), input.targetWarehouseId());
        var existing =
                db.sql("SELECT * FROM wms_material_issue WHERE demand_no=?")
                        .param(input.demandNo())
                        .query()
                        .listOfRows();
        if (!existing.isEmpty()) {
            var d = existing.getFirst();
            access.requireWarehouse(n(d, "warehouse_id"));
            access.requireWarehouse(n(d, "target_warehouse_id"));
            if (n(d, "warehouse_id") != input.warehouseId()
                    || n(d, "target_warehouse_id") != input.targetWarehouseId()
                    || n(d, "material_id") != input.materialId()
                    || q(d, "quantity").compareTo(input.quantity()) != 0
                    || !input.workOrderNo().trim().equals(d.get("work_order_no")))
                throw conflict("需求号已用于其他领料内容");
            return detail(n(d, "id"));
        }
        eligibleWarehouses(input.warehouseId(), input.targetWarehouseId());
        if (db.sql("SELECT COUNT(*) FROM mdm_material WHERE id=?")
                        .param(input.materialId())
                        .query(Long.class)
                        .single()
                == 0) throw conflict("物料不存在");
        db.sql(
                        "INSERT INTO wms_material_issue(demand_no,work_order_no,warehouse_id,target_warehouse_id,material_id,quantity,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)")
                .params(
                        input.demandNo(),
                        input.workOrderNo().trim(),
                        input.warehouseId(),
                        input.targetWarehouseId(),
                        input.materialId(),
                        input.quantity(),
                        actor.currentActor(),
                        now())
                .update();
        return detail(
                db.sql("SELECT id FROM wms_material_issue WHERE demand_no=?")
                        .param(input.demandNo())
                        .query(Long.class)
                        .single());
    }

    public Map<String, Object> reserve(long id, Reserve input) {
        var d = locked(id);
        if (Set.of("RESERVED", "ISSUED").contains(d.get("status"))) {
            if (n(d, "source_balance_id") != input.balanceId()
                    || n(d, "target_location_id") != input.targetLocationId())
                throw conflict("原预占内容不可修改");
            return detail(id);
        }
        if (!"OPEN".equals(d.get("status"))) throw conflict("只有待预占领料单可预占");
        eligibleWarehouses(n(d, "warehouse_id"), n(d, "target_warehouse_id"));
        // Check source scope before locking a balance from any other warehouse.
        var reference = balance(input.balanceId(), false);
        if (n(reference, "warehouse_id") != n(d, "warehouse_id")
                || n(reference, "material_id") != n(d, "material_id"))
            throw conflict("来源库存与领料仓或物料不一致");
        var b = balance(input.balanceId(), true);
        eligibleStock(b);
        targetLocation(input.targetLocationId(), n(d, "target_warehouse_id"));
        if (q(b, "available_qty").compareTo(q(d, "quantity")) < 0) throw conflict("可用库存不足，无法整单预占");
        db.sql(
                        "UPDATE wms_inventory_balance SET available_qty=available_qty-?,reserved_qty=reserved_qty+?,reservation_version=reservation_version+1 WHERE id=?")
                .params(q(d, "quantity"), q(d, "quantity"), input.balanceId())
                .update();
        event(d, b, "RESERVE", q(d, "quantity"));
        db.sql(
                        "UPDATE wms_material_issue SET status='RESERVED',source_balance_id=?,target_location_id=?,reserved_by=?,reserved_at=? WHERE id=?")
                .params(
                        input.balanceId(),
                        input.targetLocationId(),
                        actor.currentActor(),
                        now(),
                        id)
                .update();
        return detail(id);
    }

    public Map<String, Object> cancel(long id, String reason) {
        var d = locked(id);
        if ("CANCELLED".equals(d.get("status"))) {
            if (!reason.trim().equals(d.get("cancel_reason"))) throw conflict("取消原因不可修改");
            return detail(id);
        }
        if (!Set.of("OPEN", "RESERVED").contains(d.get("status"))) throw conflict("已发料单不能取消");
        if ("RESERVED".equals(d.get("status"))) {
            var b = balance(n(d, "source_balance_id"), true);
            db.sql(
                            "UPDATE wms_inventory_balance SET available_qty=available_qty+?,reserved_qty=reserved_qty-?,reservation_version=reservation_version+1 WHERE id=?")
                    .params(q(d, "quantity"), q(d, "quantity"), n(b, "id"))
                    .update();
            event(d, b, "RELEASE", q(d, "quantity").negate());
        }
        db.sql(
                        "UPDATE wms_material_issue SET status='CANCELLED',cancel_reason=?,closed_by=?,closed_at=? WHERE id=?")
                .params(reason.trim(), actor.currentActor(), now(), id)
                .update();
        return detail(id);
    }

    public Map<String, Object> confirm(long id) {
        var d = locked(id);
        if ("ISSUED".equals(d.get("status"))) return detail(id);
        if (!"RESERVED".equals(d.get("status"))) throw conflict("请先完成库存预占，再确认实际发料");
        eligibleWarehouses(n(d, "warehouse_id"), n(d, "target_warehouse_id"));
        var source = balance(n(d, "source_balance_id"), true);
        eligibleStock(source);
        long targetWarehouse = n(d, "target_warehouse_id"), location = n(d, "target_location_id");
        targetLocation(location, targetWarehouse);
        String stockKey =
                hash(
                        Arrays.asList(
                                targetWarehouse,
                                location,
                                n(source, "material_id"),
                                n(source, "supplier_id"),
                                source.get("batch_no"),
                                source.get("date_code"),
                                date(source, "production_date"),
                                date(source, "expiry_date"),
                                source.get("quality_status"),
                                source.get("owner_type"),
                                n(source, "owner_id")));
        db.sql(
                        """
            INSERT INTO wms_inventory_balance(stock_key,warehouse_id,location_id,material_id,supplier_id,batch_no,date_code,production_date,expiry_date,quality_status,owner_type,owner_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE stock_key=stock_key
            """)
                .params(
                        stockKey,
                        targetWarehouse,
                        location,
                        source.get("material_id"),
                        source.get("supplier_id"),
                        source.get("batch_no"),
                        source.get("date_code"),
                        date(source, "production_date"),
                        date(source, "expiry_date"),
                        source.get("quality_status"),
                        source.get("owner_type"),
                        source.get("owner_id"))
                .update();
        var target =
                db.sql("SELECT * FROM wms_inventory_balance WHERE stock_key=? FOR UPDATE")
                        .param(stockKey)
                        .query()
                        .singleRow();
        BigDecimal amount = q(d, "quantity");
        db.sql(
                        "UPDATE wms_inventory_balance SET on_hand_qty=on_hand_qty-?,reserved_qty=reserved_qty-?,reservation_version=reservation_version+1 WHERE id=?")
                .params(amount, amount, n(source, "id"))
                .update();
        db.sql(
                        "UPDATE wms_inventory_balance SET on_hand_qty=on_hand_qty+?,production_qty=production_qty+? WHERE id=?")
                .params(amount, amount, n(target, "id"))
                .update();
        event(d, source, "CONSUME", amount.negate());
        ledger(id, source, amount.negate(), "ISSUE_OUT");
        ledger(id, target, amount, "ISSUE_IN");
        db.sql(
                        "UPDATE wms_material_issue SET status='ISSUED',target_balance_id=?,closed_by=?,closed_at=? WHERE id=?")
                .params(n(target, "id"), actor.currentActor(), now(), id)
                .update();
        feedback.record(id, "MaterialIssued", "", amount);
        return detail(id);
    }

    private void event(
            Map<String, Object> d, Map<String, Object> b, String type, BigDecimal delta) {
        db.sql(
                        "INSERT INTO wms_reservation_event(issue_id,balance_id,event_type,quantity,before_reserved,after_reserved,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)")
                .params(
                        n(d, "id"),
                        n(b, "id"),
                        type,
                        delta.abs(),
                        q(b, "reserved_qty"),
                        q(b, "reserved_qty").add(delta),
                        actor.currentActor(),
                        now())
                .update();
    }

    private void ledger(long id, Map<String, Object> b, BigDecimal delta, String type) {
        db.sql(
                        "INSERT INTO wms_inventory_transaction(balance_id,before_qty,change_qty,after_qty,created_by,created_at,transaction_type,issue_id) VALUES(?,?,?,?,?,?,?,?)")
                .params(
                        n(b, "id"),
                        q(b, "on_hand_qty"),
                        delta,
                        q(b, "on_hand_qty").add(delta),
                        actor.currentActor(),
                        now(),
                        type,
                        id)
                .update();
    }

    private void targetLocation(long id, long warehouse) {
        var l = find("SELECT warehouse_id,area_type FROM mdm_location WHERE id=?", id);
        if (n(l, "warehouse_id") != warehouse || !"STORAGE".equals(l.get("area_type")))
            throw conflict("目标必须是指定线边仓的正式存储库位");
    }

    private void eligibleStock(Map<String, Object> b) {
        String area =
                db.sql("SELECT area_type FROM mdm_location WHERE id=?")
                        .param(n(b, "location_id"))
                        .query(String.class)
                        .single();
        if (!"QUALIFIED".equals(b.get("quality_status")) || !"STORAGE".equals(area))
            throw conflict("仅正式库位合格库存可领料");
        LocalDate expiry = date(b, "expiry_date");
        if (expiry != null && expiry.isBefore(LocalDate.now(clock)))
            throw conflict("过期库存不允许领料，请取消预占");
    }

    void eligibleWarehouses(long source, long target) {
        catalog.requireReceivingWarehouse(source);
        catalog.requireReceivingWarehouse(target);
        String a =
                db.sql("SELECT purpose FROM mdm_warehouse WHERE id=?")
                        .param(source)
                        .query(String.class)
                        .single();
        String b =
                db.sql("SELECT purpose FROM mdm_warehouse WHERE id=?")
                        .param(target)
                        .query(String.class)
                        .single();
        if (!"RAW_MATERIAL".equals(a) || !"LINE_SIDE".equals(b)) throw conflict("领料仅支持原材料仓到线边仓");
    }

    private void lockWarehouses(long source, long target) {
        access.requireWarehouse(source);
        access.requireWarehouse(target);
        if (source == target) throw conflict("来源仓与线边仓必须不同");
        // Match other inventory writers: warehouse locks precede document and balance locks.
        for (long id : new long[] {Math.min(source, target), Math.max(source, target)})
            find("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE", id);
    }

    Map<String, Object> locked(long id) {
        var d =
                find(
                        "SELECT warehouse_id,target_warehouse_id FROM wms_material_issue WHERE id=?",
                        id);
        lockWarehouses(n(d, "warehouse_id"), n(d, "target_warehouse_id"));
        return find("SELECT * FROM wms_material_issue WHERE id=? FOR UPDATE", id);
    }

    private Map<String, Object> balance(long id, boolean lock) {
        return find(
                "SELECT * FROM wms_inventory_balance WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }

    private Map<String, Object> find(String sql, long id) {
        return db.sql(sql).param(id).query().listOfRows().stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        404, "ISSUE_REFERENCE_NOT_FOUND", "领料单或关联记录不存在"));
    }

    private long n(Map<String, Object> r, String k) {
        return ((Number) r.get(k)).longValue();
    }

    private BigDecimal q(Map<String, Object> r, String k) {
        return (BigDecimal) r.get(k);
    }

    private LocalDate date(Map<String, Object> r, String k) {
        return r.get(k) == null ? null : LocalDate.parse(r.get(k).toString());
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private BusinessException conflict(String message) {
        return new BusinessException(409, "ISSUE_CONFLICT", message);
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
}
