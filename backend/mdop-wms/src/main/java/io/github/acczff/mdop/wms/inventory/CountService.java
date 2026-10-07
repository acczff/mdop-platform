package io.github.acczff.mdop.wms.inventory;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.masterdata.catalog.CatalogService;
import io.github.acczff.mdop.wms.receiving.ReceivingAccess;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class CountService {
    private final JdbcClient db;
    private final ReceivingAccess access;
    private final CatalogService catalog;
    private final CurrentActorProvider actor;
    private final Clock clock;

    public CountService(
            JdbcClient db,
            ReceivingAccess access,
            CatalogService catalog,
            CurrentActorProvider actor,
            Clock clock) {
        this.db = db;
        this.access = access;
        this.catalog = catalog;
        this.actor = actor;
        this.clock = clock;
    }

    private static final String SELECT =
            """
        SELECT c.id,c.warehouse_id,c.balance_id,c.status,c.created_by,c.created_at,c.reviewed_by,c.reviewed_at,
        c.reason,c.decision_reason,CAST(c.snapshot_qty AS CHAR) AS snapshot_qty,
        CAST(c.counted_qty AS CHAR) AS counted_qty,CAST(c.counted_qty-c.snapshot_qty AS CHAR) AS difference,
        m.code AS material_code,m.name AS material_name,m.unit,l.name AS location_name,b.batch_no
        FROM wms_stock_count c JOIN wms_inventory_balance b ON b.id=c.balance_id
        JOIN mdm_material m ON m.id=b.material_id JOIN mdm_location l ON l.id=b.location_id
        """;

    public InventoryService.Page list(long warehouse, int page, int size) {
        access.requireWarehouse(warehouse);
        long total =
                db.sql("SELECT COUNT(*) FROM wms_stock_count WHERE warehouse_id=?")
                        .param(warehouse)
                        .query(Long.class)
                        .single();
        return new InventoryService.Page(
                db.sql(SELECT + " WHERE c.warehouse_id=? ORDER BY c.id DESC LIMIT ? OFFSET ?")
                        .params(warehouse, size, (long) page * size)
                        .query()
                        .listOfRows(),
                page,
                size,
                total,
                (total + size - 1) / size);
    }

    private Map<String, Object> detail(long id) {
        return db.sql(SELECT + " WHERE c.id=?").param(id).query().singleRow();
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private BigDecimal qty(Map<String, Object> row, String key) {
        return (BigDecimal) row.get(key);
    }

    private BusinessException conflict(String text) {
        return new BusinessException(409, "COUNT_CONFLICT", text);
    }

    private Map<String, Object> balance(long id, boolean lock) {
        return db
                .sql("SELECT * FROM wms_inventory_balance WHERE id=?" + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "STOCK_NOT_FOUND", "库存不存在"));
    }

    private void warehouse(long id) {
        access.requireWarehouse(id);
        db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                .param(id)
                .query(Long.class)
                .single();
    }

    private void eligible(Map<String, Object> b) {
        catalog.requireReceivingWarehouse(number(b, "warehouse_id"));
        String area =
                db.sql("SELECT area_type FROM mdm_location WHERE id=?")
                        .param(number(b, "location_id"))
                        .query(String.class)
                        .single();
        if (!"QUALIFIED".equals(b.get("quality_status"))
                || !"STORAGE".equals(area)
                || qty(b, "available_qty").compareTo(qty(b, "on_hand_qty")) != 0)
            throw conflict("首版仅支持正式库位中无预占的合格库存盘点");
        if (b.get("expiry_date") != null
                && LocalDate.parse(b.get("expiry_date").toString()).isBefore(LocalDate.now(clock)))
            throw conflict("过期库存不允许普通盘点调整");
    }

    private long lastLedger(long balance) {
        return db.sql(
                        "SELECT COALESCE(MAX(id),0) FROM wms_inventory_transaction WHERE balance_id=?")
                .param(balance)
                .query(Long.class)
                .single();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    public Map<String, Object> create(CountController.Create input) {
        var reference = balance(input.balanceId(), false);
        long warehouse = number(reference, "warehouse_id");
        warehouse(warehouse);
        var existing =
                db
                        .sql("SELECT * FROM wms_stock_count WHERE request_key=?")
                        .param(input.idempotencyKey())
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst();
        if (existing.isPresent()) {
            var old = existing.get();
            if (number(old, "balance_id") != input.balanceId()
                    || !actor.currentActor().equals(old.get("created_by")))
                throw conflict("幂等键已用于其他盘点请求");
            return detail(number(old, "id"));
        }
        var b = balance(input.balanceId(), true);
        eligible(b);
        db.sql(
                        """
            INSERT INTO wms_stock_count(warehouse_id,balance_id,snapshot_qty,snapshot_available,last_ledger_id,request_key,created_by,created_at)
            VALUES(?,?,?,?,?,?,?,?)
            """)
                .params(
                        warehouse,
                        input.balanceId(),
                        b.get("on_hand_qty"),
                        b.get("available_qty"),
                        lastLedger(input.balanceId()),
                        input.idempotencyKey(),
                        actor.currentActor(),
                        now())
                .update();
        return detail(
                db.sql("SELECT id FROM wms_stock_count WHERE request_key=?")
                        .param(input.idempotencyKey())
                        .query(Long.class)
                        .single());
    }

    private Map<String, Object> locked(long id) {
        var reference =
                db
                        .sql("SELECT warehouse_id FROM wms_stock_count WHERE id=?")
                        .param(id)
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(404, "COUNT_NOT_FOUND", "盘点单不存在"));
        warehouse(number(reference, "warehouse_id"));
        return db.sql("SELECT * FROM wms_stock_count WHERE id=? FOR UPDATE")
                .param(id)
                .query()
                .singleRow();
    }

    private void creator(Map<String, Object> c) {
        if (!actor.currentActor().equals(c.get("created_by")))
            throw new BusinessException(403, "COUNT_OWNER_REQUIRED", "仅盘点创建人可录入或取消");
    }

    private void reviewer(Map<String, Object> c) {
        if (actor.currentActor().equals(c.get("created_by")))
            throw new BusinessException(403, "COUNT_SELF_REVIEW", "盘点必须由另一人审核");
    }

    public Map<String, Object> submit(long id, CountController.Submit input) {
        var c = locked(id);
        creator(c);
        if ("PENDING".equals(c.get("status"))
                && input.quantity().compareTo(qty(c, "counted_qty")) == 0
                && input.reason().trim().equals(c.get("reason"))) return detail(id);
        if (!"DRAFT".equals(c.get("status"))) throw conflict("只有待录入盘点单可提交，已提交内容不可修改");
        db.sql("UPDATE wms_stock_count SET counted_qty=?,reason=?,status='PENDING' WHERE id=?")
                .params(input.quantity(), input.reason().trim(), id)
                .update();
        return detail(id);
    }

    public Map<String, Object> approve(long id) {
        var c = locked(id);
        reviewer(c);
        if ("APPROVED".equals(c.get("status"))) return detail(id);
        if (!"PENDING".equals(c.get("status"))) throw conflict("只有待审核盘点单可过账");
        long balanceId = number(c, "balance_id");
        var b = balance(balanceId, true);
        eligible(b);
        if (lastLedger(balanceId) != number(c, "last_ledger_id")
                || qty(b, "on_hand_qty").compareTo(qty(c, "snapshot_qty")) != 0
                || qty(b, "available_qty").compareTo(qty(c, "snapshot_available")) != 0)
            throw conflict("盘点期间库存已变化，请取消原单并重新盘点");
        BigDecimal after = qty(c, "counted_qty"), delta = after.subtract(qty(c, "snapshot_qty"));
        if (delta.signum() != 0) {
            db.sql("UPDATE wms_inventory_balance SET on_hand_qty=?,available_qty=? WHERE id=?")
                    .params(after, after, balanceId)
                    .update();
            db.sql(
                            """
                INSERT INTO wms_inventory_transaction(balance_id,before_qty,change_qty,after_qty,created_by,created_at,transaction_type,count_id)
                VALUES(?,?,?,?,?,?,?,?)
                """)
                    .params(
                            balanceId,
                            c.get("snapshot_qty"),
                            delta,
                            after,
                            actor.currentActor(),
                            now(),
                            delta.signum() > 0 ? "COUNT_GAIN" : "COUNT_LOSS",
                            id)
                    .update();
        }
        db.sql(
                        "UPDATE wms_stock_count SET status='APPROVED',reviewed_by=?,reviewed_at=? WHERE id=?")
                .params(actor.currentActor(), now(), id)
                .update();
        return detail(id);
    }

    public Map<String, Object> close(long id, String reason, boolean cancel) {
        var c = locked(id);
        if (cancel) creator(c);
        else reviewer(c);
        String target = cancel ? "CANCELLED" : "REJECTED";
        if (target.equals(c.get("status"))
                && actor.currentActor().equals(c.get("reviewed_by"))
                && reason.trim().equals(c.get("decision_reason"))) return detail(id);
        if (!"PENDING".equals(c.get("status")) && !(cancel && "DRAFT".equals(c.get("status"))))
            throw conflict("当前状态不允许取消或驳回");
        db.sql(
                        "UPDATE wms_stock_count SET status=?,decision_reason=?,reviewed_by=?,reviewed_at=? WHERE id=?")
                .params(target, reason.trim(), actor.currentActor(), now(), id)
                .update();
        return detail(id);
    }
}
