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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class CrossTransferService {
    public record Action(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotBlank @Size(max = 500) String reason) {}

    private final JdbcClient db;
    private final ReceivingAccess access;
    private final CatalogService catalog;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public CrossTransferService(
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
            """
        SELECT d.id,d.source_warehouse_id,d.target_warehouse_id,d.source_balance_id,d.target_location_id,d.target_balance_id,
        d.reason,d.status,d.created_by,d.created_at,d.reviewed_by,d.reviewed_at,d.shipped_by,d.shipped_at,d.received_by,d.received_at,
        CAST(d.quantity AS CHAR) AS quantity,
        CAST(CAST(IF(d.status='IN_TRANSIT',d.quantity,0) AS DECIMAL(18,6)) AS CHAR) AS in_transit_qty,
        m.code AS material_code,m.name AS material_name,m.unit,b.batch_no,
        s.name AS source_warehouse,t.name AS target_warehouse,l.name AS target_location
        FROM wms_cross_transfer d JOIN wms_inventory_balance b ON b.id=d.source_balance_id
        JOIN mdm_material m ON m.id=b.material_id
        JOIN mdm_warehouse s ON s.id=d.source_warehouse_id JOIN mdm_warehouse t ON t.id=d.target_warehouse_id
        JOIN mdm_location l ON l.id=d.target_location_id
        """;

    @Transactional(readOnly = true)
    public InventoryService.Page list(long warehouseId, int page, int size) {
        access.requireWarehouse(warehouseId);
        long total =
                db.sql(
                                "SELECT COUNT(*) FROM wms_cross_transfer WHERE source_warehouse_id=? OR target_warehouse_id=?")
                        .params(warehouseId, warehouseId)
                        .query(Long.class)
                        .single();
        var rows =
                db.sql(
                                SELECT
                                        + " WHERE d.source_warehouse_id=? OR d.target_warehouse_id=? ORDER BY d.id DESC LIMIT ? OFFSET ?")
                        .params(warehouseId, warehouseId, size, (long) page * size)
                        .query()
                        .listOfRows();
        return new InventoryService.Page(rows, page, size, total, (total + size - 1) / size);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(long id) {
        var d = one(SELECT + " WHERE d.id=?", id);
        if (!access.canAccessWarehouse(n(d, "source_warehouse_id")))
            access.requireWarehouse(n(d, "target_warehouse_id"));
        return d;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(long id) {
        detail(id);
        return db.sql(
                        "SELECT id,action_type,reason,created_by,created_at FROM wms_cross_transfer_action WHERE cross_transfer_id=? ORDER BY id")
                .param(id)
                .query()
                .listOfRows();
    }

    public Map<String, Object> create(TransferService.Input in) {
        var source = balance(in.sourceBalanceId());
        var target = one("SELECT * FROM mdm_location WHERE id=?", in.targetLocationId());
        long sw = n(source, "warehouse_id"), tw = n(target, "warehouse_id");
        access.requireWarehouse(sw);
        access.requireWarehouse(tw);
        lockWarehouses(sw, tw);
        String digest =
                hash(
                        Arrays.asList(
                                "CREATE",
                                actor.currentActor(),
                                in.sourceBalanceId(),
                                in.targetLocationId(),
                                in.quantity().stripTrailingZeros().toPlainString(),
                                in.reason().trim()));
        var previous =
                db.sql("SELECT id,request_hash FROM wms_cross_transfer WHERE request_key=?")
                        .param(in.idempotencyKey())
                        .query()
                        .listOfRows();
        if (!previous.isEmpty()) {
            if (!digest.equals(previous.getFirst().get("request_hash")))
                throw conflict("幂等键已用于不同申请");
            return detail(n(previous.getFirst(), "id"));
        }
        compatible(sw, tw);
        targetStorage(in.targetLocationId(), tw);
        source = balance(in.sourceBalanceId());
        eligible(source);
        if (in.quantity().compareTo(q(source, "available_qty")) > 0) throw conflict("可用库存不足");
        db.sql(
                        """
            INSERT INTO wms_cross_transfer(source_warehouse_id,target_warehouse_id,source_balance_id,target_location_id,
            quantity,reason,request_key,request_hash,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)
            """)
                .params(
                        sw,
                        tw,
                        in.sourceBalanceId(),
                        in.targetLocationId(),
                        in.quantity(),
                        in.reason().trim(),
                        in.idempotencyKey(),
                        digest,
                        actor.currentActor(),
                        now())
                .update();
        long id =
                db.sql("SELECT id FROM wms_cross_transfer WHERE request_key=?")
                        .param(in.idempotencyKey())
                        .query(Long.class)
                        .single();
        record(id, "CREATE", new Action(in.idempotencyKey(), in.reason()), digest);
        return detail(id);
    }

    public Map<String, Object> act(long id, String type, Action in) {
        var ref = one("SELECT * FROM wms_cross_transfer WHERE id=?", id);
        long sw = n(ref, "source_warehouse_id"), tw = n(ref, "target_warehouse_id");
        access.requireWarehouse(type.equals("RECEIVE") ? tw : sw);
        lockWarehouses(sw, tw);
        var d = one("SELECT * FROM wms_cross_transfer WHERE id=? FOR UPDATE", id);
        String digest = hash(List.of(id, type, actor.currentActor(), in.reason().trim()));
        var old =
                db.sql("SELECT payload_hash FROM wms_cross_transfer_action WHERE request_key=?")
                        .param(in.idempotencyKey())
                        .query(String.class)
                        .optional();
        if (old.isPresent()) {
            if (!digest.equals(old.get())) throw conflict("幂等键已用于不同操作，请核对原记录");
            return detail(id);
        }
        String status = d.get("status").toString();
        var source = balance(n(d, "source_balance_id"));
        switch (type) {
            case "APPROVE", "REJECT" -> {
                requireStatus(status, "PENDING");
                if (actor.currentActor().equals(d.get("created_by")))
                    throw conflict("申请人不能审批本人调拨单");
                if (type.equals("APPROVE")) {
                    compatible(sw, tw);
                    targetStorage(n(d, "target_location_id"), tw);
                    eligible(source);
                    if (q(d, "quantity").compareTo(q(source, "available_qty")) > 0)
                        throw conflict("可用库存不足，请重新核对");
                    reservation(d, source, "RESERVE");
                }
                db.sql(
                                "UPDATE wms_cross_transfer SET status=?,reviewed_by=?,reviewed_at=? WHERE id=?")
                        .params(
                                type.equals("APPROVE") ? "APPROVED" : "REJECTED",
                                actor.currentActor(),
                                now(),
                                id)
                        .update();
            }
            case "CANCEL" -> {
                if (!Set.of("PENDING", "APPROVED").contains(status))
                    throw conflict("仅待审批或已审批未发出的调拨可以取消");
                if (status.equals("APPROVED")) reservation(d, source, "RELEASE");
                db.sql("UPDATE wms_cross_transfer SET status='CANCELLED' WHERE id=?")
                        .param(id)
                        .update();
            }
            case "SHIP" -> {
                requireStatus(status, "APPROVED");
                compatible(sw, tw);
                eligible(source);
                targetStorage(n(d, "target_location_id"), tw);
                reservation(d, source, "CONSUME");
                ledger(source, q(d, "quantity").negate(), id, "CROSS_OUT");
                db.sql(
                                "UPDATE wms_cross_transfer SET status='IN_TRANSIT',shipped_by=?,shipped_at=? WHERE id=?")
                        .params(actor.currentActor(), now(), id)
                        .update();
            }
            case "RECEIVE" -> {
                requireStatus(status, "IN_TRANSIT");
                catalog.requireReceivingWarehouse(tw);
                // Receiving does not depend on the source warehouse remaining enabled after
                // shipment.
                samePurpose(sw, tw);
                targetStorage(n(d, "target_location_id"), tw);
                notExpired(source);
                var destination = destination(d, source);
                db.sql(
                                "UPDATE wms_inventory_balance SET on_hand_qty=on_hand_qty+?,available_qty=IF(active_freeze_id IS NULL,available_qty+?,0) WHERE id=?")
                        .params(q(d, "quantity"), q(d, "quantity"), n(destination, "id"))
                        .update();
                ledger(destination, q(d, "quantity"), id, "CROSS_IN");
                db.sql(
                                "UPDATE wms_cross_transfer SET status='RECEIVED',target_balance_id=?,received_by=?,received_at=? WHERE id=?")
                        .params(n(destination, "id"), actor.currentActor(), now(), id)
                        .update();
            }
            default -> throw new IllegalArgumentException("Unsupported action");
        }
        record(id, type, in, digest);
        return detail(id);
    }

    private void reservation(Map<String, Object> d, Map<String, Object> b, String type) {
        BigDecimal qty = q(d, "quantity"), delta = type.equals("RESERVE") ? qty : qty.negate();
        db.sql(
                        "UPDATE wms_inventory_balance SET reserved_qty=reserved_qty+?,available_qty=IF(active_freeze_id IS NULL,available_qty+?,0),on_hand_qty=on_hand_qty+?,reservation_version=reservation_version+1 WHERE id=?")
                .params(
                        delta,
                        type.equals("CONSUME") ? BigDecimal.ZERO : delta.negate(),
                        type.equals("CONSUME") ? qty.negate() : BigDecimal.ZERO,
                        n(b, "id"))
                .update();
        db.sql(
                        "INSERT INTO wms_reservation_event(cross_transfer_id,balance_id,event_type,quantity,before_reserved,after_reserved,created_by,created_at) VALUES (?,?,?,?,?,?,?,?)")
                .params(
                        n(d, "id"),
                        n(b, "id"),
                        type,
                        qty,
                        q(b, "reserved_qty"),
                        q(b, "reserved_qty").add(delta),
                        actor.currentActor(),
                        now())
                .update();
    }

    private void ledger(Map<String, Object> b, BigDecimal delta, long id, String type) {
        db.sql(
                        "INSERT INTO wms_inventory_transaction(balance_id,before_qty,change_qty,after_qty,created_by,created_at,transaction_type,cross_transfer_id) VALUES (?,?,?,?,?,?,?,?)")
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

    private Map<String, Object> destination(Map<String, Object> d, Map<String, Object> s) {
        long warehouse = n(d, "target_warehouse_id"), location = n(d, "target_location_id");
        String key =
                hash(
                        Arrays.asList(
                                warehouse,
                                location,
                                n(s, "material_id"),
                                s.get("supplier_id"),
                                s.get("batch_no"),
                                s.get("date_code"),
                                date(s, "production_date"),
                                date(s, "expiry_date"),
                                s.get("quality_status"),
                                s.get("owner_type"),
                                n(s, "owner_id")));
        db.sql(
                        """
            INSERT INTO wms_inventory_balance(stock_key,warehouse_id,location_id,material_id,supplier_id,batch_no,date_code,
            production_date,expiry_date,quality_status,owner_type,owner_id,origin_type) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE stock_key=stock_key
            """)
                .params(
                        key,
                        warehouse,
                        location,
                        s.get("material_id"),
                        s.get("supplier_id"),
                        s.get("batch_no"),
                        s.get("date_code"),
                        date(s, "production_date"),
                        date(s, "expiry_date"),
                        s.get("quality_status"),
                        s.get("owner_type"),
                        s.get("owner_id"),
                        s.get("origin_type"))
                .update();
        return one("SELECT * FROM wms_inventory_balance WHERE stock_key=? FOR UPDATE", key);
    }

    private void record(long id, String type, Action in, String digest) {
        db.sql(
                        "INSERT INTO wms_cross_transfer_action(cross_transfer_id,request_key,payload_hash,action_type,reason,created_by,created_at) VALUES (?,?,?,?,?,?,?)")
                .params(
                        id,
                        in.idempotencyKey(),
                        digest,
                        type,
                        in.reason().trim(),
                        actor.currentActor(),
                        now())
                .update();
    }

    private void compatible(long sw, long tw) {
        if (sw == tw) throw conflict("跨仓调拨必须选择不同仓库");
        catalog.requireReceivingWarehouse(sw);
        catalog.requireReceivingWarehouse(tw);
        samePurpose(sw, tw);
    }

    private void samePurpose(long sw, long tw) {
        String purpose =
                db.sql("SELECT purpose FROM mdm_warehouse WHERE id=?")
                        .param(sw)
                        .query(String.class)
                        .single();
        String target =
                db.sql("SELECT purpose FROM mdm_warehouse WHERE id=?")
                        .param(tw)
                        .query(String.class)
                        .single();
        if (!Set.of("RAW_MATERIAL", "FINISHED_GOODS").contains(purpose) || !purpose.equals(target))
            throw conflict("仅相同用途的原料仓或成品仓支持跨仓调拨");
    }

    private void targetStorage(long id, long warehouse) {
        var l = one("SELECT * FROM mdm_location WHERE id=?", id);
        if (n(l, "warehouse_id") != warehouse || !"STORAGE".equals(l.get("area_type")))
            throw conflict("目标必须为指定仓库正式存储库位");
    }

    private void eligible(Map<String, Object> b) {
        StockFreeze.requireUnfrozen(b);
        targetStorage(n(b, "location_id"), n(b, "warehouse_id"));
        if (!"QUALIFIED".equals(b.get("quality_status"))
                || !"ENTERPRISE".equals(b.get("owner_type"))
                || n(b, "owner_id") != 0
                || q(b, "production_qty").signum() != 0) throw conflict("仅企业自有、合格且未分配给生产的库存支持调拨");
        notExpired(b);
    }

    private void notExpired(Map<String, Object> b) {
        LocalDate expiry = date(b, "expiry_date");
        if (expiry != null && expiry.isBefore(LocalDate.now(clock)))
            throw conflict("库存已过期，不能继续普通调拨；在途货物须等待异常处理");
    }

    private void lockWarehouses(long a, long b) {
        // Match every existing inventory writer's warehouse-before-balance lock order.
        for (long id : new TreeSet<>(List.of(a, b)))
            db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                    .param(id)
                    .query(Long.class)
                    .single();
    }

    private Map<String, Object> balance(long id) {
        return one("SELECT * FROM wms_inventory_balance WHERE id=?", id);
    }

    private Map<String, Object> one(String sql, Object value) {
        return db.sql(sql).param(value).query().listOfRows().stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        404, "CROSS_TRANSFER_NOT_FOUND", "调拨单、库存或目标库位不存在"));
    }

    private void requireStatus(String actual, String expected) {
        if (!actual.equals(expected)) throw conflict("当前状态不允许此操作，请刷新单据");
    }

    private long n(Map<String, Object> r, String k) {
        return ((Number) r.get(k)).longValue();
    }

    private BigDecimal q(Map<String, Object> r, String k) {
        return new BigDecimal(r.get(k).toString());
    }

    private LocalDate date(Map<String, Object> r, String k) {
        return r.get(k) == null ? null : LocalDate.parse(r.get(k).toString());
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
        return new BusinessException(409, "CROSS_TRANSFER_CONFLICT", message);
    }
}
