package io.github.acczff.mdop.wms.inventory;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.wms.receiving.ReceivingAccess;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FreezeService {
    public record Input(
            @Positive long balanceId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotBlank @Size(max = 500) String reason) {}

    private final JdbcClient db;
    private final ReceivingAccess access;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public FreezeService(
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

    private static final String SELECT =
            """
        SELECT f.id,f.balance_id,f.warehouse_id,f.status,f.reason,f.created_by,f.created_at,
        f.release_reason,f.released_by,f.released_at,
        CAST(f.frozen_on_hand AS CHAR) AS frozen_on_hand,CAST(f.frozen_available AS CHAR) AS frozen_available,
        CAST(f.frozen_reserved AS CHAR) AS frozen_reserved,CAST(f.released_on_hand AS CHAR) AS released_on_hand,
        CAST(f.released_available AS CHAR) AS released_available,CAST(f.released_reserved AS CHAR) AS released_reserved,
        CAST(b.on_hand_qty AS CHAR) AS on_hand_qty,CAST(b.available_qty AS CHAR) AS available_qty,
        CAST(b.reserved_qty AS CHAR) AS reserved_qty,b.active_freeze_id,b.quality_status,b.batch_no,
        m.code AS material_code,m.name AS material_name,m.unit,l.name AS location_name,w.name AS warehouse_name
        FROM wms_stock_freeze f JOIN wms_inventory_balance b ON b.id=f.balance_id
        JOIN mdm_material m ON m.id=b.material_id JOIN mdm_location l ON l.id=b.location_id
        JOIN mdm_warehouse w ON w.id=f.warehouse_id
        """;

    @Transactional(readOnly = true)
    public InventoryService.Page list(long warehouseId, int page, int size) {
        access.requireWarehouse(warehouseId);
        long total =
                db.sql("SELECT COUNT(*) FROM wms_stock_freeze WHERE warehouse_id=?")
                        .param(warehouseId)
                        .query(Long.class)
                        .single();
        var rows =
                db.sql(SELECT + " WHERE f.warehouse_id=? ORDER BY f.id DESC LIMIT ? OFFSET ?")
                        .params(warehouseId, size, (long) page * size)
                        .query()
                        .listOfRows();
        return new InventoryService.Page(rows, page, size, total, (total + size - 1) / size);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(long id) {
        var f = one(SELECT + " WHERE f.id=?", id);
        access.requireWarehouse(n(f, "warehouse_id"));
        return f;
    }

    public Map<String, Object> create(Input in) {
        var ref = one("SELECT * FROM wms_inventory_balance WHERE id=?", in.balanceId());
        long warehouse = n(ref, "warehouse_id");
        lock(warehouse);
        String digest = hash(List.of(in.balanceId(), actor.currentActor(), in.reason().trim()));
        var old =
                db.sql("SELECT id,request_hash FROM wms_stock_freeze WHERE request_key=?")
                        .param(in.idempotencyKey())
                        .query()
                        .listOfRows();
        if (!old.isEmpty()) {
            if (!digest.equals(old.getFirst().get("request_hash"))) throw conflict("幂等键已用于不同冻结申请");
            return detail(n(old.getFirst(), "id"));
        }
        var b = one("SELECT * FROM wms_inventory_balance WHERE id=? FOR UPDATE", in.balanceId());
        StockFreeze.requireUnfrozen(b);
        String purpose =
                db.sql("SELECT purpose FROM mdm_warehouse WHERE id=?")
                        .param(warehouse)
                        .query(String.class)
                        .single();
        if (purpose.equals("LINE_SIDE") || q(b, "production_qty").signum() != 0)
            throw conflict("首版不支持线边库存和生产占用库存冻结");
        if (q(b, "on_hand_qty").signum() <= 0) throw conflict("仅有在手数量的库存可以冻结");
        db.sql(
                        """
            INSERT INTO wms_stock_freeze(balance_id,warehouse_id,reason,request_key,request_hash,
            frozen_on_hand,frozen_available,frozen_reserved,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)
            """)
                .params(
                        in.balanceId(),
                        warehouse,
                        in.reason().trim(),
                        in.idempotencyKey(),
                        digest,
                        b.get("on_hand_qty"),
                        b.get("available_qty"),
                        b.get("reserved_qty"),
                        actor.currentActor(),
                        now())
                .update();
        long id =
                db.sql("SELECT id FROM wms_stock_freeze WHERE request_key=?")
                        .param(in.idempotencyKey())
                        .query(Long.class)
                        .single();
        db.sql(
                        "UPDATE wms_inventory_balance SET active_freeze_id=?,available_qty=0,reservation_version=reservation_version+1 WHERE id=?")
                .params(id, in.balanceId())
                .update();
        return detail(id);
    }

    public Map<String, Object> release(long id, CrossTransferService.Action in) {
        var ref = one("SELECT * FROM wms_stock_freeze WHERE id=?", id);
        lock(n(ref, "warehouse_id"));
        var f = one("SELECT * FROM wms_stock_freeze WHERE id=? FOR UPDATE", id);
        String digest = hash(List.of(id, actor.currentActor(), in.reason().trim()));
        if (f.get("release_key") != null && f.get("release_key").equals(in.idempotencyKey())) {
            if (!digest.equals(f.get("release_hash"))) throw conflict("幂等键已用于不同解冻审批");
            return detail(id);
        }
        if (!"FROZEN".equals(f.get("status"))) throw conflict("本次冻结已解除，请核对当前冻结记录");
        if (actor.currentActor().equals(f.get("created_by"))) throw conflict("冻结操作人不能审批本人解冻");
        var b =
                one(
                        "SELECT b.*,l.area_type FROM wms_inventory_balance b JOIN mdm_location l ON l.id=b.location_id WHERE b.id=? FOR UPDATE",
                        n(f, "balance_id"));
        if (!Objects.equals(b.get("active_freeze_id"), f.get("id")))
            throw conflict("当前冻结记录已变化，请刷新");
        BigDecimal available =
                "QUALIFIED".equals(b.get("quality_status")) && "STORAGE".equals(b.get("area_type"))
                        ? q(b, "on_hand_qty")
                                .subtract(q(b, "reserved_qty"))
                                .subtract(q(b, "production_qty"))
                        : BigDecimal.ZERO;
        db.sql(
                        "UPDATE wms_inventory_balance SET active_freeze_id=NULL,available_qty=?,reservation_version=reservation_version+1 WHERE id=?")
                .params(available, n(f, "balance_id"))
                .update();
        db.sql(
                        """
            UPDATE wms_stock_freeze SET status='RELEASED',release_key=?,release_hash=?,release_reason=?,released_by=?,released_at=?,
            released_on_hand=?,released_available=?,released_reserved=? WHERE id=?
            """)
                .params(
                        in.idempotencyKey(),
                        digest,
                        in.reason().trim(),
                        actor.currentActor(),
                        now(),
                        b.get("on_hand_qty"),
                        available,
                        b.get("reserved_qty"),
                        id)
                .update();
        return detail(id);
    }

    private void lock(long warehouse) {
        access.requireWarehouse(warehouse);
        db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                .param(warehouse)
                .query(Long.class)
                .single();
    }

    private Map<String, Object> one(String sql, long id) {
        return db.sql(sql).param(id).query().listOfRows().stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "FREEZE_NOT_FOUND", "库存或冻结记录不存在"));
    }

    private long n(Map<String, Object> r, String k) {
        return ((Number) r.get(k)).longValue();
    }

    private BigDecimal q(Map<String, Object> r, String k) {
        return (BigDecimal) r.get(k);
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
        return new BusinessException(409, "FREEZE_CONFLICT", message);
    }
}
