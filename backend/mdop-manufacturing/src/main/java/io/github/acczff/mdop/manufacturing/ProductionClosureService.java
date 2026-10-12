package io.github.acczff.mdop.manufacturing;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.common.manufacturing.*;
import jakarta.validation.constraints.*;
import java.math.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ProductionClosureService {
    public record Close(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String factHash,
            @NotBlank @Size(max = 500) String reason,
            @NotNull @Size(max = 500) String varianceReason) {}

    private final JdbcClient db;
    private final ProductionMaterialPort materials;
    private final ProductionCompletionPort completions;
    private final ProductionPurchasePort purchases;
    private final CurrentActorProvider actor;
    private final ObjectMapper json;
    private final Clock clock;

    public ProductionClosureService(
            JdbcClient db,
            ProductionMaterialPort materials,
            ProductionCompletionPort completions,
            ProductionPurchasePort purchases,
            CurrentActorProvider actor,
            ObjectMapper json,
            Clock clock) {
        this.db = db;
        this.materials = materials;
        this.completions = completions;
        this.purchases = purchases;
        this.actor = actor;
        this.json = json;
        this.clock = clock;
    }

    private Map<String, Object> order(long id) {
        return db
                .sql(
                        "SELECT o.*,d.warehouse_id,d.product_id,d.quantity,d.unit,d.created_by demand_creator FROM mfg_order o JOIN mfg_demand d ON d.id=o.demand_id WHERE o.id=?")
                .param(id)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(
                        () -> new BusinessException(404, "PRODUCTION_ORDER_NOT_FOUND", "工单不存在"));
    }

    private SortedSet<Long> warehouses(Map<String, Object> o) {
        var result = new TreeSet<Long>();
        result.add(n(o, "warehouse_id"));
        result.addAll(
                db.sql("SELECT DISTINCT warehouse_id FROM mfg_material_plan WHERE order_id=?")
                        .param(n(o, "id"))
                        .query(Long.class)
                        .list());
        if (o.get("material_target_warehouse_id") != null)
            result.add(n(o, "material_target_warehouse_id"));
        return result;
    }

    private void access(long warehouse) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || auth.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals("wms:warehouse:" + warehouse)))
            throw new BusinessException(403, "WAREHOUSE_ACCESS_DENIED", "结束核对需要工单成品仓、供料仓和线边仓权限");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(long id) {
        var o = order(id);
        warehouses(o).forEach(this::access);
        var facts = facts(o);
        var closed =
                db
                        .sql(
                                "SELECT fact_hash,snapshot,reason,variance_reason,closed_by,closed_at FROM mfg_order_closure WHERE order_id=?")
                        .param(id)
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElse(null);
        var result = new LinkedHashMap<String, Object>();
        result.put("orderNo", o.get("order_no"));
        result.put("orderStatus", o.get("status"));
        result.put("orderVersion", o.get("version"));
        result.put("facts", facts);
        result.put("factHash", hash(facts));
        result.put("closure", closed);
        result.put("closureMatches", closed == null || closed.get("fact_hash").equals(hash(facts)));
        result.put(
                "canClose",
                "APPROVED".equals(o.get("status")) && rows(facts, "blockers").isEmpty());
        result.put("selfReview", participants(o).contains(actor.currentActor()));
        result.put("costStatus", "未建立成本结算依据");
        return result;
    }

    public Map<String, Object> close(long id, Close in) {
        var o = order(id);
        var locked = warehouses(o);
        for (long w : locked) {
            access(w);
            db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                    .param(w)
                    .query(Long.class)
                    .single();
        }
        db.sql("SELECT id FROM mfg_bom_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        o = order(id);
        if (!locked.equals(warehouses(o))) throw conflict("关联仓库已变化，请刷新核对");
        String requestHash = hash(List.of("CLOSE", id, actor.currentActor(), in));
        var old =
                db.sql("SELECT request_hash FROM mfg_order_command WHERE request_key=?")
                        .param(in.idempotencyKey())
                        .query(String.class)
                        .optional();
        if (old.isPresent()) {
            if (!old.get().equals(requestHash)) throw conflict("请求键已用于其他载荷、动作或操作者");
            return detail(id);
        }
        if (!"APPROVED".equals(o.get("status")) || n(o, "version") != in.version())
            throw conflict("工单状态或版本已变化，请刷新核对");
        if (participants(o).contains(actor.currentActor()))
            throw new BusinessException(
                    403, "PRODUCTION_CLOSURE_SELF_REVIEW", "工单经办人或实际完工登记人不能审核结束本人业务");
        var facts = facts(o);
        if (!hash(facts).equals(in.factHash())) throw conflict("材料、采购或完工事实已变化，请刷新后重新核对");
        if (!rows(facts, "blockers").isEmpty()) throw conflict("存在未完成事项，不能结束工单");
        if (Boolean.TRUE.equals(facts.get("hasVariance")) && in.varianceReason().isBlank())
            throw conflict("实际材料消耗与理论用量不一致，必须填写差异说明");
        var now = Timestamp.from(clock.instant());
        db.sql(
                        "INSERT INTO mfg_order_closure(order_id,fact_hash,snapshot,reason,variance_reason,closed_by,closed_at) VALUES(?,?,?,?,?,?,?)")
                .params(
                        id,
                        hash(facts),
                        json.writeValueAsString(facts),
                        in.reason().trim(),
                        in.varianceReason().trim(),
                        actor.currentActor(),
                        now)
                .update();
        db.sql("UPDATE mfg_order SET status='COMPLETED',version=version+1,updated_at=? WHERE id=?")
                .params(now, id)
                .update();
        db.sql(
                        "INSERT INTO mfg_order_audit(demand_id,order_id,action,reason,after_state,created_by,created_at) VALUES(?,?,'CLOSE',?,?,?,?)")
                .params(
                        n(o, "demand_id"),
                        id,
                        in.reason().trim(),
                        json.writeValueAsString(
                                Map.of(
                                        "facts",
                                        facts,
                                        "varianceReason",
                                        in.varianceReason().trim())),
                        actor.currentActor(),
                        now)
                .update();
        db.sql(
                        "INSERT INTO mfg_order_command(request_key,request_hash,demand_id,created_at) VALUES(?,?,?,?)")
                .params(in.idempotencyKey(), requestHash, n(o, "demand_id"), now)
                .update();
        return detail(id);
    }

    private Set<String> participants(Map<String, Object> o) {
        var people = new HashSet<String>();
        for (String key : List.of("demand_creator", "created_by", "last_edited_by", "submitted_by"))
            if (o.get(key) != null) people.add(o.get(key).toString());
        people.addAll(
                db.sql(
                                "SELECT created_by FROM mfg_completion WHERE order_id=? AND status='APPROVED'")
                        .param(n(o, "id"))
                        .query(String.class)
                        .list());
        return people;
    }

    private Map<String, Object> facts(Map<String, Object> o) {
        long id = n(o, "id");
        var blockers = new ArrayList<String>();
        var issues = materials.closureReferences(id);
        var supplies = purchases.references(id);
        var reports =
                db.sql(
                                "SELECT id,report_no,status,batch_no,date_code,CAST(production_date AS CHAR) production_date,CAST(expiry_date AS CHAR) expiry_date,CAST(quantity AS CHAR) quantity FROM mfg_completion WHERE order_id=? ORDER BY id")
                        .param(id)
                        .query()
                        .listOfRows();
        var receipts = completions.references(id);
        if (db.sql("SELECT COUNT(*) FROM mfg_material_plan WHERE order_id=?")
                        .param(id)
                        .query(Long.class)
                        .single()
                == 0) blockers.add("尚未建立材料计划，缺少材料核对依据");
        for (var i : issues) {
            String label = "领料单 #" + i.get("id");
            if (!materialLedgerMatches(i) || !Boolean.TRUE.equals(i.get("allocationMatches")))
                blockers.add(label + "库存流水或线边归属与来源不一致");
            if (!Set.of("CANCELLED", "ISSUED").contains(i.get("status")))
                blockers.add(label + "尚未发料或安全取消");
            if (q(i, "remaining_qty").signum() != 0) blockers.add(label + "仍有线边余料");
            if (q(i, "pending_return_qty").signum() != 0) blockers.add(label + "仍有待确认退料");
            var reversals = maps(i, "reversals");
            if (reversals.stream().anyMatch(r -> "PENDING".equals(r.get("status"))))
                blockers.add(label + "仍有待审冲正");
            BigDecimal consumed =
                    maps(i, "consumptions").stream()
                            .map(r -> q(r, "quantity"))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal returned =
                    maps(i, "returns").stream()
                            .filter(r -> "RETURNED".equals(r.get("status")))
                            .map(r -> q(r, "quantity"))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            for (var r : reversals)
                if ("APPROVED".equals(r.get("status"))) {
                    if (r.get("consumption_id") != null)
                        consumed = consumed.subtract(q(r, "quantity"));
                    else returned = returned.subtract(q(r, "quantity"));
                }
            if (consumed.compareTo(q(i, "consumed_qty")) != 0
                    || returned.compareTo(q(i, "returned_qty")) != 0
                    || consumed.signum() < 0
                    || returned.signum() < 0) blockers.add(label + "累计数量与消耗、退料及冲正来源不一致");
            if ("CANCELLED".equals(i.get("status"))
                    && (consumed.signum() != 0 || returned.signum() != 0))
                blockers.add(label + "已取消但仍有材料事实");
        }
        var bom = json.readTree(o.get("bom_snapshot").toString());
        var lines = new ArrayList<Map<String, Object>>();
        var known = new HashSet<Long>();
        boolean variance = false;
        for (var c : bom.get("components")) {
            long material = c.get("material_id").asLong();
            known.add(material);
            var theoretical =
                    q(o, "quantity")
                            .multiply(new BigDecimal(c.get("quantity").asText()))
                            .divide(
                                    new BigDecimal(bom.get("base_quantity").asText()),
                                    6,
                                    RoundingMode.CEILING);
            BigDecimal used = BigDecimal.ZERO, returned = BigDecimal.ZERO, issued = BigDecimal.ZERO;
            for (var i : issues)
                if (n(i, "material_id") == material && "ISSUED".equals(i.get("status"))) {
                    used = used.add(q(i, "consumed_qty"));
                    returned = returned.add(q(i, "returned_qty"));
                    issued = issued.add(q(i, "quantity"));
                }
            var delta = used.subtract(theoretical);
            if (delta.signum() != 0) variance = true;
            if (used.signum() <= 0) blockers.add(c.get("material_code").asText() + "缺少实际净消耗依据");
            if (issued.compareTo(theoretical) > 0)
                blockers.add(c.get("material_code").asText() + "领料超过固定 BOM 授权");
            lines.add(
                    Map.of(
                            "materialId",
                            material,
                            "code",
                            c.get("material_code").asText(),
                            "name",
                            c.get("material_name").asText(),
                            "unit",
                            c.get("unit").asText(),
                            "theoretical",
                            theoretical.toPlainString(),
                            "consumed",
                            used.toPlainString(),
                            "returned",
                            returned.toPlainString(),
                            "issued",
                            issued.toPlainString(),
                            "variance",
                            delta.toPlainString()));
        }
        if (issues.stream().anyMatch(i -> !known.contains(n(i, "material_id"))))
            blockers.add("存在固定 BOM 以外的领料来源");
        for (var p : supplies) {
            var orders = maps(p, "orders");
            boolean settled =
                    orders.stream()
                            .anyMatch(
                                    x ->
                                            "CLOSED".equals(x.get("status"))
                                                    && Boolean.TRUE.equals(
                                                            x.get("closureMatches")));
            boolean pending =
                    orders.stream()
                            .anyMatch(
                                    x ->
                                            !"CANCELLED".equals(x.get("status"))
                                                    && !("CLOSED".equals(x.get("status"))
                                                            && Boolean.TRUE.equals(
                                                                    x.get("closureMatches"))));
            if (pending || (!"CANCELLED".equals(p.get("status")) && !settled))
                blockers.add("采购需求 " + p.get("document_no") + "尚未收尾或结案事实待核对");
        }
        BigDecimal approved = BigDecimal.ZERO, stored = BigDecimal.ZERO;
        var receiptFacts = new ArrayList<Map<String, Object>>();
        for (var r : reports) {
            if ("SUBMITTED".equals(r.get("status")))
                blockers.add("完工报告 " + r.get("report_no") + "尚待复核");
            if (!"APPROVED".equals(r.get("status"))) continue;
            approved = approved.add(q(r, "quantity"));
            var matches =
                    receipts.stream()
                            .filter(f -> n(f, "completion_report_id") == n(r, "id"))
                            .toList();
            if (matches.size() != 1) {
                blockers.add("完工报告 " + r.get("report_no") + "缺少唯一入库来源");
                continue;
            }
            var f = matches.getFirst();
            if (n(f, "warehouse_id") != n(o, "warehouse_id")
                    || n(f, "material_id") != n(o, "product_id")
                    || !Objects.equals(f.get("unit"), o.get("unit"))
                    || !Objects.equals(f.get("work_order_no"), o.get("order_no")))
                blockers.add("完工报告 " + r.get("report_no") + "的产品、单位或成品仓来源不一致");
            for (String field : List.of("batch_no", "date_code", "production_date", "expiry_date"))
                if (!Objects.equals(
                        Objects.toString(f.get(field), null), Objects.toString(r.get(field), null)))
                    blockers.add("完工报告 " + r.get("report_no") + "的批次或日期来源不一致");
            if (!finishedLedgerMatches(f)) blockers.add("完工报告 " + r.get("report_no") + "的入库流水不一致");
            if (q(f, "quantity").compareTo(q(r, "quantity")) != 0)
                blockers.add("完工报告 " + r.get("report_no") + "与成品入库数量不一致");
            if (!"STORED".equals(f.get("status")))
                blockers.add("完工报告 " + r.get("report_no") + "的成品尚未合格上架（待收货、待检或质量待处理）");
            else stored = stored.add(q(f, "quantity"));
        }
        for (var f : receipts) {
            if (reports.stream()
                    .noneMatch(
                            r ->
                                    n(r, "id") == n(f, "completion_report_id")
                                            && "APPROVED".equals(r.get("status"))))
                blockers.add("存在无有效批准报告的成品入库来源");
            var selected = new LinkedHashMap<String, Object>();
            for (String key :
                    List.of(
                            "id",
                            "completion_report_id",
                            "warehouse_id",
                            "material_id",
                            "status",
                            "quantity",
                            "batch_no",
                            "received_balance_id",
                            "stored_balance_id"))
                selected.put(key, f.get(key) == null ? null : f.get(key).toString());
            receiptFacts.add(selected);
            selected.put("ledger", f.get("ledger"));
        }
        if (approved.compareTo(q(o, "quantity")) != 0) blockers.add("已批准完工量尚未达到工单授权数量");
        if (stored.compareTo(q(o, "quantity")) != 0) blockers.add("成品合格上架量尚未达到工单授权数量");
        var result = new LinkedHashMap<String, Object>();
        result.put("authorized", q(o, "quantity").toPlainString());
        result.put("unit", o.get("unit"));
        result.put("approved", approved.toPlainString());
        result.put("stored", stored.toPlainString());
        result.put("materials", lines);
        result.put("issues", issues);
        result.put("purchases", supplies);
        result.put("reports", reports);
        result.put("receipts", receiptFacts);
        result.put("hasVariance", variance);
        result.put("blockers", blockers);
        return result;
    }

    private boolean materialLedgerMatches(Map<String, Object> i) {
        var ledger = maps(i, "ledger");
        int expected = 0;
        if ("ISSUED".equals(i.get("status"))) {
            expected += 2;
            if (!entry(
                            ledger,
                            "ISSUE_OUT",
                            "issue_id",
                            n(i, "id"),
                            i.get("source_balance_id"),
                            q(i, "quantity").negate())
                    || !entry(
                            ledger,
                            "ISSUE_IN",
                            "issue_id",
                            n(i, "id"),
                            i.get("target_balance_id"),
                            q(i, "quantity"))) return false;
        }
        for (var c : maps(i, "consumptions")) {
            expected++;
            if (!entry(
                    ledger,
                    "CONSUMPTION",
                    "consumption_id",
                    n(c, "id"),
                    i.get("target_balance_id"),
                    q(c, "quantity").negate())) return false;
        }
        for (var r : maps(i, "returns"))
            if ("RETURNED".equals(r.get("status"))) {
                expected += 2;
                if (!entry(
                                ledger,
                                "PROD_RETURN_OUT",
                                "production_return_id",
                                n(r, "id"),
                                i.get("target_balance_id"),
                                q(r, "quantity").negate())
                        || !entry(
                                ledger,
                                "PROD_RETURN_IN",
                                "production_return_id",
                                n(r, "id"),
                                r.get("target_balance_id"),
                                q(r, "quantity"))) return false;
            }
        for (var r : maps(i, "reversals"))
            if ("APPROVED".equals(r.get("status"))) {
                if (r.get("consumption_id") != null) {
                    expected++;
                    if (!entry(
                            ledger,
                            "PRC_RESTORE",
                            "production_reversal_id",
                            n(r, "id"),
                            i.get("target_balance_id"),
                            q(r, "quantity"))) return false;
                } else {
                    expected += 2;
                    var original =
                            maps(i, "returns").stream()
                                    .filter(x -> n(x, "id") == n(r, "production_return_id"))
                                    .findFirst();
                    if (original.isEmpty()
                            || !entry(
                                    ledger,
                                    "PRR_REMOVE",
                                    "production_reversal_id",
                                    n(r, "id"),
                                    original.get().get("target_balance_id"),
                                    q(r, "quantity").negate())
                            || !entry(
                                    ledger,
                                    "PRR_RESTORE",
                                    "production_reversal_id",
                                    n(r, "id"),
                                    i.get("target_balance_id"),
                                    q(r, "quantity"))) return false;
                }
            }
        return ledger.size() == expected;
    }

    private boolean finishedLedgerMatches(Map<String, Object> f) {
        if (!"STORED".equals(f.get("status"))) return true;
        var ledger = maps(f, "ledger");
        return ledger.size() == 5
                && "QUALIFIED".equals(f.get("quality_status"))
                && entry(
                        ledger,
                        "FG_RECEIPT",
                        null,
                        0,
                        f.get("received_balance_id"),
                        q(f, "quantity"))
                && entry(
                        ledger,
                        "FG_QC_OUT",
                        null,
                        0,
                        f.get("received_balance_id"),
                        q(f, "quantity").negate())
                && entry(ledger, "FG_QC_IN", null, 0, f.get("quality_balance_id"), q(f, "quantity"))
                && entry(
                        ledger,
                        "FG_PUT_OUT",
                        null,
                        0,
                        f.get("quality_balance_id"),
                        q(f, "quantity").negate())
                && entry(
                        ledger, "FG_PUT_IN", null, 0, f.get("stored_balance_id"), q(f, "quantity"));
    }

    private boolean entry(
            List<Map<String, Object>> ledger,
            String type,
            String source,
            long id,
            Object balance,
            BigDecimal quantity) {
        return balance != null
                && ledger.stream()
                                .filter(
                                        t ->
                                                type.equals(t.get("transaction_type"))
                                                        && (source == null
                                                                || (t.get(source) != null
                                                                        && n(t, source) == id))
                                                        && Objects.equals(
                                                                t.get("balance_id"), balance)
                                                        && q(t, "quantity").compareTo(quantity)
                                                                == 0)
                                .count()
                        == 1;
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> m) {
            var r = new TreeMap<String, Object>();
            m.forEach((k, v) -> r.put(k.toString(), canonical(v)));
            return r;
        }
        if (value instanceof List<?> l) return l.stream().map(this::canonical).toList();
        return value;
    }

    private String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(json.writeValueAsBytes(canonical(value))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long n(Map<String, Object> r, String k) {
        return ((Number) r.get(k)).longValue();
    }

    private BigDecimal q(Map<String, Object> r, String k) {
        return new BigDecimal(r.get(k).toString());
    }

    private List<?> rows(Map<String, Object> r, String k) {
        return (List<?>) r.get(k);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Map<String, Object> r, String k) {
        return (List<Map<String, Object>>) r.get(k);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(409, "PRODUCTION_CLOSURE_CONFLICT", message);
    }
}
