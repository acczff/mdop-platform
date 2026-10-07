package io.github.acczff.mdop.wms.inventory;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
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
public class ProductionService {
    public record Consumption(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String eventNo,
            @Positive long issueId,
            @NotBlank @Size(max = 64) String workOrderNo,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity) {}

    public record ReturnInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String eventNo,
            @Positive long issueId,
            @NotBlank @Size(max = 64) String workOrderNo,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity,
            @NotBlank @Pattern(regexp = "QUALIFIED|REJECTED") String qualityStatus,
            @Positive long targetLocationId,
            @NotBlank @Size(max = 500) String reason) {}

    private final JdbcClient db;
    private final IssueService issues;
    private final ProductionEvents feedback;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public ProductionService(
            JdbcClient db,
            IssueService issues,
            ProductionEvents feedback,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json) {
        this.db = db;
        this.issues = issues;
        this.feedback = feedback;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(long id) {
        var issue = new LinkedHashMap<>(issues.detail(id));
        var totals =
                db.sql(
                                "SELECT CAST(COALESCE(SUM(quantity),0) AS CHAR) AS pending_return_qty FROM wms_production_return WHERE issue_id=? AND status='PENDING'")
                        .param(id)
                        .query()
                        .singleRow();
        issue.putAll(totals);
        issue.put(
                "consumable_qty",
                new BigDecimal(issue.get("remaining_qty").toString())
                        .subtract(new BigDecimal(totals.get("pending_return_qty").toString()))
                        .toPlainString());
        return issue;
    }

    @Transactional(readOnly = true)
    public InventoryService.Page records(long id, boolean returns, int page, int size) {
        issues.detail(id);
        String table = returns ? "wms_production_return" : "wms_production_consumption";
        long total =
                db.sql("SELECT COUNT(*) FROM " + table + " WHERE issue_id=?")
                        .param(id)
                        .query(Long.class)
                        .single();
        String columns =
                returns
                        ? "id,event_no,issue_id,CAST(quantity AS CHAR) AS quantity,quality_status,status,target_location_id,target_balance_id,reason,requested_by,requested_at,closed_by,closed_at,cancel_reason"
                        : "id,event_no,issue_id,CAST(quantity AS CHAR) AS quantity,reported_by,reported_at";
        return new InventoryService.Page(
                db.sql(
                                "SELECT "
                                        + columns
                                        + " FROM "
                                        + table
                                        + " WHERE issue_id=? ORDER BY id DESC LIMIT ? OFFSET ?")
                        .params(id, size, (long) page * size)
                        .query()
                        .listOfRows(),
                page,
                size,
                total,
                (total + size - 1) / size);
    }

    public Map<String, Object> consume(Consumption input) {
        var d = issues.locked(input.issueId());
        issued(d, input.workOrderNo());
        var old =
                db.sql("SELECT * FROM wms_production_consumption WHERE event_no=?")
                        .param(input.eventNo())
                        .query()
                        .listOfRows();
        if (!old.isEmpty()) {
            var o = old.getFirst();
            if (n(o, "issue_id") != input.issueId()
                    || q(o, "quantity").compareTo(input.quantity()) != 0)
                throw conflict("MES 消耗事件号内容不一致");
            return consumption(n(o, "id"));
        }
        issues.eligibleWarehouses(n(d, "warehouse_id"), n(d, "target_warehouse_id"));
        if (input.quantity().compareTo(free(d)) > 0) throw conflict("消耗超过领料剩余额度，请先核对已消耗和待退数量");
        var b = balance(n(d, "target_balance_id"));
        db.sql(
                        "INSERT INTO wms_production_consumption(event_no,issue_id,quantity,reported_by,reported_at) VALUES(?,?,?,?,?)")
                .params(
                        input.eventNo(),
                        input.issueId(),
                        input.quantity(),
                        actor.currentActor(),
                        now())
                .update();
        long id =
                db.sql("SELECT id FROM wms_production_consumption WHERE event_no=?")
                        .param(input.eventNo())
                        .query(Long.class)
                        .single();
        spend(b, input.quantity());
        db.sql("UPDATE wms_material_issue SET consumed_qty=consumed_qty+? WHERE id=?")
                .params(input.quantity(), input.issueId())
                .update();
        ledger(b, input.quantity().negate(), "CONSUMPTION", id, null);
        feedback.record(
                input.issueId(), "ProductionConsumed", String.valueOf(id), input.quantity());
        return consumption(id);
    }

    private Map<String, Object> consumption(long id) {
        return db.sql(
                        "SELECT id,event_no,issue_id,CAST(quantity AS CHAR) AS quantity,reported_by,reported_at FROM wms_production_consumption WHERE id=?")
                .param(id)
                .query()
                .singleRow();
    }

    public Map<String, Object> requestReturn(ReturnInput input) {
        var d = issues.locked(input.issueId());
        issued(d, input.workOrderNo());
        var old =
                db.sql("SELECT * FROM wms_production_return WHERE event_no=?")
                        .param(input.eventNo())
                        .query()
                        .listOfRows();
        if (!old.isEmpty()) {
            var o = old.getFirst();
            if (n(o, "issue_id") != input.issueId()
                    || q(o, "quantity").compareTo(input.quantity()) != 0
                    || !input.qualityStatus().equals(o.get("quality_status"))
                    || n(o, "target_location_id") != input.targetLocationId()
                    || !input.reason().trim().equals(o.get("reason")))
                throw conflict("MES 退料事件号内容不一致");
            return returned(n(o, "id"));
        }
        issues.eligibleWarehouses(n(d, "warehouse_id"), n(d, "target_warehouse_id"));
        if (input.quantity().compareTo(free(d)) > 0) throw conflict("退料超过可申请额度，已消耗或待退数量不能重复退回");
        var b = balance(n(d, "target_balance_id"));
        destination(d, b, input.targetLocationId(), input.qualityStatus());
        db.sql(
                        "INSERT INTO wms_production_return(event_no,issue_id,quantity,quality_status,reason,target_location_id,requested_by,requested_at) VALUES(?,?,?,?,?,?,?,?)")
                .params(
                        input.eventNo(),
                        input.issueId(),
                        input.quantity(),
                        input.qualityStatus(),
                        input.reason().trim(),
                        input.targetLocationId(),
                        actor.currentActor(),
                        now())
                .update();
        return returned(
                db.sql("SELECT id FROM wms_production_return WHERE event_no=?")
                        .param(input.eventNo())
                        .query(Long.class)
                        .single());
    }

    public Map<String, Object> cancel(long id, String reason) {
        var p = lockedReturn(id);
        if ("CANCELLED".equals(p.get("status"))) {
            if (!reason.trim().equals(p.get("cancel_reason"))) throw conflict("原取消原因不可修改");
            return returned(id);
        }
        if (!"PENDING".equals(p.get("status"))) throw conflict("已退料单不能取消");
        db.sql(
                        "UPDATE wms_production_return SET status='CANCELLED',cancel_reason=?,closed_by=?,closed_at=? WHERE id=?")
                .params(reason.trim(), actor.currentActor(), now(), id)
                .update();
        return returned(id);
    }

    public Map<String, Object> confirm(long id) {
        var p = lockedReturn(id);
        if ("RETURNED".equals(p.get("status"))) return returned(id);
        if (!"PENDING".equals(p.get("status"))) throw conflict("只有待确认退料单可接收实物");
        var d = issues.locked(n(p, "issue_id"));
        issues.eligibleWarehouses(n(d, "warehouse_id"), n(d, "target_warehouse_id"));
        var b = balance(n(d, "target_balance_id"));
        long location = n(p, "target_location_id"), warehouse = n(d, "warehouse_id");
        String quality = (String) p.get("quality_status");
        destination(d, b, location, quality);
        String key =
                hash(
                        Arrays.asList(
                                warehouse,
                                location,
                                n(b, "material_id"),
                                n(b, "supplier_id"),
                                b.get("batch_no"),
                                b.get("date_code"),
                                date(b, "production_date"),
                                date(b, "expiry_date"),
                                quality,
                                b.get("owner_type"),
                                n(b, "owner_id")));
        db.sql(
                        "INSERT INTO wms_inventory_balance(stock_key,warehouse_id,location_id,material_id,supplier_id,batch_no,date_code,production_date,expiry_date,quality_status,owner_type,owner_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE stock_key=stock_key")
                .params(
                        key,
                        warehouse,
                        location,
                        b.get("material_id"),
                        b.get("supplier_id"),
                        b.get("batch_no"),
                        b.get("date_code"),
                        date(b, "production_date"),
                        date(b, "expiry_date"),
                        quality,
                        b.get("owner_type"),
                        b.get("owner_id"))
                .update();
        var target =
                db.sql("SELECT * FROM wms_inventory_balance WHERE stock_key=? FOR UPDATE")
                        .param(key)
                        .query()
                        .singleRow();
        BigDecimal amount = q(p, "quantity");
        spend(b, amount);
        db.sql(
                        "UPDATE wms_inventory_balance SET on_hand_qty=on_hand_qty+?,available_qty=IF(active_freeze_id IS NULL,available_qty+?,0) WHERE id=?")
                .params(
                        amount,
                        "QUALIFIED".equals(quality) ? amount : BigDecimal.ZERO,
                        n(target, "id"))
                .update();
        db.sql("UPDATE wms_material_issue SET returned_qty=returned_qty+? WHERE id=?")
                .params(amount, n(d, "id"))
                .update();
        ledger(b, amount.negate(), "PROD_RETURN_OUT", null, id);
        ledger(target, amount, "PROD_RETURN_IN", null, id);
        db.sql(
                        "UPDATE wms_production_return SET status='RETURNED',target_balance_id=?,closed_by=?,closed_at=? WHERE id=?")
                .params(n(target, "id"), actor.currentActor(), now(), id)
                .update();
        feedback.record(n(d, "id"), "ProductionMaterialReturned", String.valueOf(id), amount);
        return returned(id);
    }

    private Map<String, Object> lockedReturn(long id) {
        long issue =
                db.sql("SELECT issue_id FROM wms_production_return WHERE id=?")
                        .param(id)
                        .query(Long.class)
                        .optional()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                404, "PRODUCTION_RETURN_NOT_FOUND", "退料单不存在"));
        issues.locked(issue);
        return db.sql("SELECT * FROM wms_production_return WHERE id=? FOR UPDATE")
                .param(id)
                .query()
                .singleRow();
    }

    private Map<String, Object> returned(long id) {
        return db.sql(
                        "SELECT id,event_no,issue_id,CAST(quantity AS CHAR) AS quantity,quality_status,status,reason,target_location_id,target_balance_id,requested_by,requested_at,closed_by,closed_at,cancel_reason FROM wms_production_return WHERE id=?")
                .param(id)
                .query()
                .singleRow();
    }

    private void issued(Map<String, Object> d, String workOrder) {
        if (!"ISSUED".equals(d.get("status")) || !workOrder.trim().equals(d.get("work_order_no")))
            throw conflict("仅已发料且工单匹配的领料单可处理生产业务");
    }

    private BigDecimal free(Map<String, Object> d) {
        BigDecimal pending =
                db.sql(
                                "SELECT COALESCE(SUM(quantity),0) FROM wms_production_return WHERE issue_id=? AND status='PENDING'")
                        .param(n(d, "id"))
                        .query(BigDecimal.class)
                        .single();
        return q(d, "quantity")
                .subtract(q(d, "consumed_qty"))
                .subtract(q(d, "returned_qty"))
                .subtract(pending);
    }

    private void destination(
            Map<String, Object> d, Map<String, Object> b, long location, String quality) {
        var l =
                db
                        .sql("SELECT warehouse_id,area_type FROM mdm_location WHERE id=?")
                        .param(location)
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> conflict("退回库位不存在"));
        if (n(l, "warehouse_id") != n(d, "warehouse_id")
                || !("QUALIFIED".equals(quality) ? "STORAGE" : "INSPECTION")
                        .equals(l.get("area_type")))
            throw conflict("合格余料须退回原料仓正式库位，不合格余料须退入原料仓隔离待检位");
        LocalDate expiry = date(b, "expiry_date");
        if ("QUALIFIED".equals(quality) && expiry != null && expiry.isBefore(LocalDate.now(clock)))
            throw conflict("过期退料必须按不合格隔离处理，请取消后重新申请");
    }

    private void spend(Map<String, Object> b, BigDecimal amount) {
        if (q(b, "production_qty").compareTo(amount) < 0) throw conflict("线边归属库存不足，请核对库存");
        db.sql(
                        "UPDATE wms_inventory_balance SET on_hand_qty=on_hand_qty-?,production_qty=production_qty-? WHERE id=?")
                .params(amount, amount, n(b, "id"))
                .update();
    }

    private void ledger(
            Map<String, Object> b, BigDecimal delta, String type, Long consumption, Long returned) {
        db.sql(
                        "INSERT INTO wms_inventory_transaction(balance_id,before_qty,change_qty,after_qty,created_by,created_at,transaction_type,consumption_id,production_return_id) VALUES(?,?,?,?,?,?,?,?,?)")
                .params(
                        n(b, "id"),
                        q(b, "on_hand_qty"),
                        delta,
                        q(b, "on_hand_qty").add(delta),
                        actor.currentActor(),
                        now(),
                        type,
                        consumption,
                        returned)
                .update();
    }

    private Map<String, Object> balance(long id) {
        return db.sql("SELECT * FROM wms_inventory_balance WHERE id=? FOR UPDATE")
                .param(id)
                .query()
                .singleRow();
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
        return new BusinessException(409, "PRODUCTION_CONFLICT", message);
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
