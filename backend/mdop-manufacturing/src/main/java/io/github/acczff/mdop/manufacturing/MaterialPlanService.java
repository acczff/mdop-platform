package io.github.acczff.mdop.manufacturing;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.common.manufacturing.*;
import io.github.acczff.mdop.masterdata.catalog.CatalogService;
import jakarta.validation.constraints.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class MaterialPlanService {
    public record Calculate(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @Positive long warehouseId,
            @NotNull @PositiveOrZero Long orderVersion,
            @NotNull @PositiveOrZero Long planVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record Confirm(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotNull @PositiveOrZero Long planVersion,
            @Positive long lineId,
            @NotBlank @Size(max = 500) String reason) {}

    private final JdbcClient db;
    private final ObjectMapper json;
    private final Clock clock;
    private final CurrentActorProvider actor;
    private final CatalogService catalog;
    private final MaterialStockReference stock;
    private final ProductionPurchasePort purchase;

    public MaterialPlanService(
            JdbcClient db,
            ObjectMapper json,
            Clock clock,
            CurrentActorProvider actor,
            CatalogService catalog,
            MaterialStockReference stock,
            ProductionPurchasePort purchase) {
        this.db = db;
        this.json = json;
        this.clock = clock;
        this.actor = actor;
        this.catalog = catalog;
        this.stock = stock;
        this.purchase = purchase;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(long orderId) {
        var order = order(orderId);
        access(n(order, "warehouse_id"));
        var plan = latest(orderId);
        if (plan != null) access(n(plan, "warehouse_id"));
        var result = new LinkedHashMap<String, Object>();
        result.put("orderId", orderId);
        result.put("orderNo", order.get("order_no"));
        result.put("orderStatus", order.get("status"));
        result.put("orderVersion", order.get("version"));
        result.put("planVersion", plan == null ? 0 : n(plan, "version"));
        result.put("plan", plan);
        result.put("purchases", plan == null ? List.of() : purchase.references(orderId));
        if (plan != null)
            result.put(
                    "lines",
                    db.sql(
                                    "SELECT id,material_id,CAST(suggested_quantity AS CHAR) suggested_quantity,purchase_request_id FROM mfg_material_plan_line WHERE plan_id=? ORDER BY material_id")
                            .param(n(plan, "id"))
                            .query()
                            .listOfRows());
        result.put(
                "history",
                db.sql(
                                "SELECT id,version,created_by,created_at FROM mfg_material_plan WHERE order_id=? ORDER BY version DESC LIMIT 1000")
                        .param(orderId)
                        .query()
                        .listOfRows());
        return result;
    }

    public Map<String, Object> calculate(long orderId, Calculate in) {
        var o = lock(orderId, in.warehouseId());
        String hash = hash(List.of("CALCULATE", orderId, actor.currentActor(), in));
        if (replay(in.idempotencyKey(), hash)) return detail(orderId);
        if (n(o, "version") != in.orderVersion()) throw conflict("工单已变化，请刷新");
        var old = latest(orderId);
        long version = old == null ? 0 : n(old, "version");
        if (version != in.planVersion()) throw conflict("已有更新的计算，请刷新");
        if (old != null && n(old, "warehouse_id") != in.warehouseId())
            throw conflict("首版计算后固定供料仓；不支持跨仓混合采购建议");
        var facts = calculateFacts(o, in.warehouseId());
        var key = new GeneratedKeyHolder();
        db.sql(
                        "INSERT INTO mfg_material_plan(order_id,warehouse_id,version,fact_hash,snapshot,created_by,created_at) VALUES(?,?,?,?,?,?,?)")
                .params(
                        orderId,
                        in.warehouseId(),
                        version + 1,
                        hash(facts),
                        json.writeValueAsString(facts),
                        actor.currentActor(),
                        now())
                .update(key);
        long plan = key.getKey().longValue();
        for (var line : rows(facts.get("lines")))
            db.sql(
                            "INSERT INTO mfg_material_plan_line(plan_id,material_id,suggested_quantity) VALUES(?,?,?)")
                    .params(
                            plan,
                            n(line, "materialId"),
                            new BigDecimal(line.get("suggested").toString()))
                    .update();
        audit(
                o,
                "MATERIAL_CALCULATE",
                in.reason(),
                Map.of("planId", plan, "version", version + 1, "warehouseId", in.warehouseId()));
        remember(in.idempotencyKey(), hash, orderId);
        return detail(orderId);
    }

    public Map<String, Object> confirm(long orderId, Confirm in) {
        var before = latest(orderId);
        if (before == null) throw conflict("请先计算材料需求");
        var o = lock(orderId, n(before, "warehouse_id"));
        String hash = hash(List.of("CONFIRM", orderId, actor.currentActor(), in));
        if (replay(in.idempotencyKey(), hash)) return detail(orderId);
        var plan = latest(orderId);
        if (n(plan, "version") != in.planVersion()) throw conflict("计算版本已过期，请重新计算核对");
        var line =
                db
                        .sql("SELECT * FROM mfg_material_plan_line WHERE id=? AND plan_id=?")
                        .params(in.lineId(), n(plan, "id"))
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> conflict("建议行不属于当前计算"));
        if (line.get("purchase_request_id") != null) throw conflict("建议已生成采购需求，请查看关联原单");
        var current = calculateFacts(o, n(plan, "warehouse_id"));
        if (!plan.get("fact_hash").equals(hash(current)))
            throw conflict("库存、资料或关联采购已变化，请重新计算后确认，不能使用旧快照");
        var facts =
                rows(current.get("lines")).stream()
                        .filter(l -> n(l, "materialId") == n(line, "material_id"))
                        .findFirst()
                        .orElseThrow();
        var quantity = new BigDecimal(facts.get("suggested").toString());
        if (quantity.signum() <= 0) throw conflict("没有可确认的采购建议；已有有效供给时请先核对原单");
        long request =
                purchase.create(
                        new ProductionPurchasePort.Input(
                                in.lineId(),
                                orderId,
                                o.get("order_no").toString(),
                                n(plan, "warehouse_id"),
                                n(line, "material_id"),
                                facts.get("unit").toString(),
                                quantity,
                                java.time.LocalDate.parse(o.get("needed_date").toString()),
                                in.reason()));
        db.sql("UPDATE mfg_material_plan_line SET purchase_request_id=? WHERE id=?")
                .params(request, in.lineId())
                .update();
        audit(
                o,
                "MATERIAL_PURCHASE",
                in.reason(),
                Map.of(
                        "planId",
                        n(plan, "id"),
                        "lineId",
                        in.lineId(),
                        "purchaseRequestId",
                        request,
                        "quantity",
                        quantity.toPlainString()));
        remember(in.idempotencyKey(), hash, orderId);
        return detail(orderId);
    }

    private Map<String, Object> calculateFacts(Map<String, Object> o, long warehouse) {
        if (!"APPROVED".equals(o.get("status"))) throw conflict("只允许已批准且未取消的生产工单计算/确认材料建议");
        catalog.requireReceivingWarehouse(warehouse);
        if (!"RAW_MATERIAL"
                .equals(
                        db.sql("SELECT purpose FROM mdm_warehouse WHERE id=?")
                                .param(warehouse)
                                .query(String.class)
                                .single())) throw conflict("供料及采购目标须为启用原材料仓");
        var snapshot = json.readTree(o.get("bom_snapshot").toString());
        var base = new BigDecimal(snapshot.get("base_quantity").asText());
        var output = new BigDecimal(o.get("quantity").toString());
        var ids = new ArrayList<Long>();
        snapshot.get("components").forEach(c -> ids.add(c.get("material_id").asLong()));
        Collections.sort(ids);
        var balances = stock.read(warehouse, ids);
        var supplies = purchase.references(n(o, "id"));
        var lines = new ArrayList<Map<String, Object>>();
        for (long material : ids) {
            var c =
                    snapshot.get("components")
                            .valueStream()
                            .filter(x -> x.get("material_id").asLong() == material)
                            .findFirst()
                            .orElseThrow();
            var m = catalog.availableMaterial(material);
            if (m.unitId() != c.get("unit_id").asLong() || !m.unit().equals(c.get("unit").asText()))
                throw conflict("组件单位与工单依据不一致，请核对");
            var numerator = output.multiply(new BigDecimal(c.get("quantity").asText()));
            var required = numerator.divide(base, 6, RoundingMode.CEILING);
            if (required.compareTo(new BigDecimal("999999999999.999999")) > 0)
                throw conflict("理论需求超出数量上限，不能生成采购建议");
            BigDecimal available = BigDecimal.ZERO,
                    linked = BigDecimal.ZERO,
                    outstanding = BigDecimal.ZERO,
                    putaway = BigDecimal.ZERO,
                    returned = BigDecimal.ZERO;
            boolean active = false;
            for (var b : balances)
                if (n(b, "material_id") == material)
                    available = available.add(new BigDecimal(b.get("available_qty").toString()));
            for (var p : supplies)
                if (n(p, "material_id") == material && !"CANCELLED".equals(p.get("status"))) {
                    active = true;
                    linked = linked.add(q(p, "quantity"));
                    outstanding = outstanding.add(q(p, "outstanding"));
                    putaway = putaway.add(q(p, "putaway"));
                    returned = returned.add(q(p, "returned"));
                }
            var gap = required.subtract(available).subtract(outstanding).max(BigDecimal.ZERO);
            var l = new LinkedHashMap<String, Object>();
            l.put("materialId", material);
            l.put("code", c.get("material_code").asText());
            l.put("name", c.get("material_name").asText());
            l.put("unit", c.get("unit").asText());
            l.put("output", output.toPlainString());
            l.put("base", base.toPlainString());
            l.put("componentQuantity", c.get("quantity").asText());
            l.put("numerator", numerator.toPlainString());
            l.put("required", required.toPlainString());
            // Exact rational delta, including repeating decimals: (rounded * denominator -
            // numerator) / denominator.
            l.put("roundingNumerator", required.multiply(base).subtract(numerator).toPlainString());
            l.put("roundingDenominator", base.toPlainString());
            l.put("available", available.toPlainString());
            l.put("linked", linked.toPlainString());
            l.put("outstanding", outstanding.toPlainString());
            l.put("putaway", putaway.toPlainString());
            l.put("returned", returned.toPlainString());
            l.put("gap", gap.toPlainString());
            l.put("activePurchase", active);
            l.put("suggested", active ? "0" : gap.toPlainString());
            lines.add(l);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("orderVersion", o.get("version"));
        result.put("warehouseId", warehouse);
        result.put("date", LocalDate.now(clock).toString());
        result.put("balances", balances);
        result.put("purchases", supplies);
        result.put("lines", lines);
        return result;
    }

    private Map<String, Object> lock(long id, long raw) {
        var o = order(id);
        long fg = n(o, "warehouse_id");
        access(fg);
        access(raw);
        for (long w : new TreeSet<>(List.of(fg, raw)))
            if (db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                    .param(w)
                    .query(Long.class)
                    .optional()
                    .isEmpty()) throw conflict("仓库不存在");
        db.sql("SELECT id FROM mfg_bom_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        return order(id);
    }

    private Map<String, Object> order(long id) {
        var o =
                db
                        .sql(
                                "SELECT o.*,d.warehouse_id,d.quantity,d.needed_date FROM mfg_order o JOIN mfg_demand d ON d.id=o.demand_id WHERE o.id=?")
                        .param(id)
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                404, "MATERIAL_PLAN_NOT_FOUND", "工单不存在"));
        return o;
    }

    private Map<String, Object> latest(long id) {
        return db
                .sql(
                        "SELECT * FROM mfg_material_plan WHERE order_id=? ORDER BY version DESC LIMIT 1")
                .param(id)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void access(long w) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || auth.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals("wms:warehouse:" + w)))
            throw new BusinessException(403, "WAREHOUSE_ACCESS_DENIED", "需要工单成品仓与供料原材料仓权限");
    }

    private void audit(Map<String, Object> o, String action, String reason, Object state) {
        db.sql(
                        "INSERT INTO mfg_order_audit(demand_id,order_id,action,reason,after_state,created_by,created_at) VALUES(?,?,?,?,?,?,?)")
                .params(
                        n(o, "demand_id"),
                        n(o, "id"),
                        action,
                        io.github.acczff.mdop.common.BusinessText.required(reason, "原因", 500),
                        json.writeValueAsString(state),
                        actor.currentActor(),
                        now())
                .update();
    }

    private boolean replay(String key, String hash) {
        var r =
                db.sql("SELECT request_hash FROM mfg_material_command WHERE request_key=?")
                        .param(key)
                        .query(String.class)
                        .optional();
        if (r.isEmpty()) return false;
        if (!hash.equals(r.get())) throw conflict("请求键已用于其他载荷、操作者或动作");
        return true;
    }

    private void remember(String key, String hash, long id) {
        db.sql(
                        "INSERT INTO mfg_material_command(request_key,request_hash,order_id,created_at) VALUES(?,?,?,?)")
                .params(key, hash, id, now())
                .update();
    }

    private String hash(Object o) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            json.writeValueAsString(o)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Object o) {
        return (List<Map<String, Object>>) o;
    }

    private long n(Map<String, Object> r, String k) {
        return ((Number) r.get(k)).longValue();
    }

    private BigDecimal q(Map<String, Object> r, String k) {
        return new BigDecimal(r.get(k).toString());
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private BusinessException conflict(String message) {
        return new BusinessException(409, "MATERIAL_PLAN_CONFLICT", message);
    }
}
