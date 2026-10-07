package io.github.acczff.mdop.wms.inventory;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ProductionReversalService {
    public record Input(
            @NotBlank @Pattern(regexp = "CONSUMPTION|RETURN") String kind,
            @Positive long sourceId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestKey,
            @NotBlank @Size(max = 64) String externalReference,
            @NotBlank @Size(max = 500) String reason) {}

    private final JdbcClient db;
    private final IssueService issues;
    private final ProductionEvents events;
    private final CurrentActorProvider actor;
    private final Clock clock;

    public ProductionReversalService(
            JdbcClient db,
            IssueService issues,
            ProductionEvents events,
            CurrentActorProvider actor,
            Clock clock) {
        this.db = db;
        this.issues = issues;
        this.events = events;
        this.actor = actor;
        this.clock = clock;
    }

    private static final String SELECT =
            "SELECT r.*,CAST(r.quantity AS CHAR) AS amount FROM wms_production_reversal r";

    public InventoryService.Page list(long issue, int page, int size) {
        issues.detail(issue);
        long total =
                db.sql("SELECT COUNT(*) FROM wms_production_reversal WHERE issue_id=?")
                        .param(issue)
                        .query(Long.class)
                        .single();
        return new InventoryService.Page(
                db.sql(SELECT + " WHERE issue_id=? ORDER BY id DESC LIMIT ? OFFSET ?")
                        .params(issue, size, (long) page * size)
                        .query()
                        .listOfRows(),
                page,
                size,
                total,
                (total + size - 1) / size);
    }

    public Map<String, Object> create(Input in) {
        boolean consumption = in.kind().equals("CONSUMPTION");
        String table = consumption ? "wms_production_consumption" : "wms_production_return";
        var source = find("SELECT * FROM " + table + " WHERE id=?", in.sourceId());
        issues.locked(n(source, "issue_id"));
        var old =
                db.sql("SELECT * FROM wms_production_reversal WHERE request_key=?")
                        .param(in.requestKey())
                        .query()
                        .listOfRows();
        String column = consumption ? "consumption_id" : "production_return_id";
        if (!old.isEmpty()) {
            var o = old.getFirst();
            if (o.get(column) == null
                    || n(o, column) != in.sourceId()
                    || !o.get("reason").equals(in.reason().trim())
                    || !o.get("external_reference").equals(in.externalReference().trim())
                    || !o.get("created_by").equals(actor.currentActor()))
                throw conflict("冲正请求号内容不一致");
            return detail(n(o, "id"));
        }
        if (!consumption && !"RETURNED".equals(source.get("status"))) throw conflict("仅已确认退料可申请冲正");
        if (db.sql(
                                "SELECT COUNT(*) FROM wms_production_reversal WHERE "
                                        + column
                                        + "=? AND status IN ('PENDING','APPROVED')")
                        .param(in.sourceId())
                        .query(Long.class)
                        .single()
                > 0) throw conflict("原记录已有待审或已批准冲正");
        db.sql(
                        "INSERT INTO wms_production_reversal(issue_id,consumption_id,production_return_id,request_key,external_reference,reason,quantity,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)")
                .params(
                        n(source, "issue_id"),
                        consumption ? in.sourceId() : null,
                        consumption ? null : in.sourceId(),
                        in.requestKey(),
                        in.externalReference().trim(),
                        in.reason().trim(),
                        source.get("quantity"),
                        actor.currentActor(),
                        now())
                .update();
        return detail(
                db.sql("SELECT id FROM wms_production_reversal WHERE request_key=?")
                        .param(in.requestKey())
                        .query(Long.class)
                        .single());
    }

    public Map<String, Object> decide(long id, String decision, String reason) {
        var ref = find("SELECT * FROM wms_production_reversal WHERE id=?", id);
        var d = issues.locked(n(ref, "issue_id"));
        var r = find("SELECT * FROM wms_production_reversal WHERE id=? FOR UPDATE", id);
        boolean cancel = decision.equals("CANCELLED");
        if (cancel
                ? !actor.currentActor().equals(r.get("created_by"))
                : actor.currentActor().equals(r.get("created_by")))
            throw new BusinessException(
                    403, "REVERSAL_ACTOR_INVALID", cancel ? "仅申请人可撤销" : "必须由另一人审批冲正");
        if (decision.equals(r.get("status"))) {
            if (!reason.trim().equals(r.get("decision_reason"))) throw conflict("原审批原因不可修改");
            return detail(id);
        }
        if (!"PENDING".equals(r.get("status"))) throw conflict("只有待审冲正可处理");
        if (decision.equals("APPROVED")) {
            issues.eligibleWarehouses(n(d, "warehouse_id"), n(d, "target_warehouse_id"));
            BigDecimal qty = (BigDecimal) r.get("quantity");
            var line =
                    find(
                            "SELECT * FROM wms_inventory_balance WHERE id=? FOR UPDATE",
                            n(d, "target_balance_id"));
            boolean consumption = r.get("consumption_id") != null;
            if (consumption) {
                var original =
                        find(
                                "SELECT * FROM wms_inventory_transaction WHERE consumption_id=?",
                                n(r, "consumption_id"));
                restore(line, qty);
                ledger(line, qty, "PRC_RESTORE", id, n(original, "id"));
                db.sql("UPDATE wms_material_issue SET consumed_qty=consumed_qty-? WHERE id=?")
                        .params(qty, n(d, "id"))
                        .update();
            } else {
                var p =
                        find(
                                "SELECT * FROM wms_production_return WHERE id=?",
                                n(r, "production_return_id"));
                var target =
                        find(
                                "SELECT * FROM wms_inventory_balance WHERE id=? FOR UPDATE",
                                n(p, "target_balance_id"));
                var original =
                        find(
                                "SELECT * FROM wms_inventory_transaction WHERE production_return_id=? AND transaction_type='PROD_RETURN_IN'",
                                n(p, "id"));
                long latest =
                        db.sql("SELECT MAX(id) FROM wms_inventory_transaction WHERE balance_id=?")
                                .param(n(target, "id"))
                                .query(Long.class)
                                .single();
                if (latest != n(original, "id")
                        || q(target, "reserved_qty").signum() != 0
                        || q(target, "production_qty").signum() != 0
                        || q(target, "on_hand_qty").compareTo(qty) < 0)
                    throw conflict("退回库存已有后续业务或占用，禁止直接冲正");
                StockFreeze.requireUnfrozen(target);
                BigDecimal available =
                        "QUALIFIED".equals(p.get("quality_status")) ? qty : BigDecimal.ZERO;
                if (q(target, "available_qty").compareTo(available) < 0) throw conflict("可冲正库存不足");
                db.sql(
                                "UPDATE wms_inventory_balance SET on_hand_qty=on_hand_qty-?,available_qty=available_qty-? WHERE id=?")
                        .params(qty, available, n(target, "id"))
                        .update();
                restore(line, qty);
                ledger(target, qty.negate(), "PRR_REMOVE", id, n(original, "id"));
                var out =
                        find(
                                "SELECT * FROM wms_inventory_transaction WHERE production_return_id=? AND transaction_type='PROD_RETURN_OUT'",
                                n(p, "id"));
                ledger(line, qty, "PRR_RESTORE", id, n(out, "id"));
                db.sql("UPDATE wms_material_issue SET returned_qty=returned_qty-? WHERE id=?")
                        .params(qty, n(d, "id"))
                        .update();
            }
            events.reverse(
                    n(d, "id"),
                    consumption ? "ProductionConsumptionReversed" : "ProductionReturnReversed",
                    String.valueOf(id),
                    consumption ? "ProductionConsumed" : "ProductionMaterialReturned",
                    String.valueOf(n(r, consumption ? "consumption_id" : "production_return_id")),
                    qty);
        }
        db.sql(
                        "UPDATE wms_production_reversal SET status=?,reviewed_by=?,reviewed_at=?,decision_reason=? WHERE id=?")
                .params(decision, actor.currentActor(), now(), reason.trim(), id)
                .update();
        return detail(id);
    }

    private void restore(Map<String, Object> b, BigDecimal qty) {
        db.sql(
                        "UPDATE wms_inventory_balance SET on_hand_qty=on_hand_qty+?,production_qty=production_qty+? WHERE id=?")
                .params(qty, qty, n(b, "id"))
                .update();
    }

    private void ledger(
            Map<String, Object> b, BigDecimal delta, String type, long reversal, long original) {
        db.sql(
                        "INSERT INTO wms_inventory_transaction(balance_id,before_qty,change_qty,after_qty,transaction_type,production_reversal_id,reversed_transaction_id,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?)")
                .params(
                        n(b, "id"),
                        q(b, "on_hand_qty"),
                        delta,
                        q(b, "on_hand_qty").add(delta),
                        type,
                        reversal,
                        original,
                        actor.currentActor(),
                        now())
                .update();
    }

    private Map<String, Object> detail(long id) {
        return find(SELECT + " WHERE r.id=?", id);
    }

    private Map<String, Object> find(String sql, long id) {
        return db.sql(sql).param(id).query().listOfRows().stream()
                .findFirst()
                .orElseThrow(
                        () -> new BusinessException(404, "PRODUCTION_RECORD_NOT_FOUND", "生产记录不存在"));
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

    private BusinessException conflict(String message) {
        return new BusinessException(409, "PRODUCTION_REVERSAL_CONFLICT", message);
    }
}
