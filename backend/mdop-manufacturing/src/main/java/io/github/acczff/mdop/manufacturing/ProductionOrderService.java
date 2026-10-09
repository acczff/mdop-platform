package io.github.acczff.mdop.manufacturing;

import static io.github.acczff.mdop.common.BusinessText.required;
import static io.github.acczff.mdop.manufacturing.ProductionOrderModels.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.common.manufacturing.*;
import io.github.acczff.mdop.masterdata.catalog.CatalogService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ProductionOrderService implements ProductionDemandUsage {
    private final JdbcClient db;
    private final CatalogService catalog;
    private final BomService boms;
    private final SalesDemandSource sales;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public ProductionOrderService(
            JdbcClient db,
            CatalogService catalog,
            BomService boms,
            SalesDemandSource sales,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json) {
        this.db = db;
        this.catalog = catalog;
        this.boms = boms;
        this.sales = sales;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
    }

    public record Page(List<Map<String, Object>> items, long total, int page, int size) {}

    @Transactional(readOnly = true)
    public Page list(long warehouse, int page, int size) {
        access(warehouse);
        var rows =
                db.sql(
                                "SELECT d.*,o.id order_id,o.order_no,o.status order_status FROM mfg_demand d LEFT JOIN mfg_order o ON o.active_demand_id=d.id WHERE d.warehouse_id=? ORDER BY d.id DESC LIMIT ? OFFSET ?")
                        .params(warehouse, size, (long) page * size)
                        .query()
                        .listOfRows();
        return new Page(
                rows.stream().map(this::present).toList(),
                db.sql("SELECT COUNT(*) FROM mfg_demand WHERE warehouse_id=?")
                        .param(warehouse)
                        .query(Long.class)
                        .single(),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> sources(long warehouse) {
        access(warehouse);
        return sales.candidates(warehouse);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(long id) {
        var d = header(id);
        access(n(d, "warehouse_id"));
        d.put("orders", orders(id));
        d.put(
                "history",
                db.sql(
                                "SELECT * FROM mfg_order_audit WHERE demand_id=? ORDER BY id DESC LIMIT 1000")
                        .param(id)
                        .query()
                        .listOfRows());
        return d;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveSalesDemand(long id) {
        return db.sql("SELECT COUNT(*) FROM mfg_demand WHERE sales_order_id=? AND status='OPEN'")
                        .param(id)
                        .query(Long.class)
                        .single()
                > 0;
    }

    public Map<String, Object> create(Demand in) {
        lock(in.warehouseId());
        String hash = hash("DEMAND", 0, in);
        Long old = replay(in.idempotencyKey(), hash);
        if (old != null) return detail(old);
        eligible(in.warehouseId());
        Long product = in.productId(), salesOrder = null;
        BigDecimal quantity = in.quantity();
        var date = in.neededDate();
        String reference = in.sourceReference();
        SalesDemandSource.Source source = null;
        if ("SALES".equals(in.sourceType())) {
            if (in.salesLineId() == null
                    || product != null
                    || quantity != null
                    || date != null
                    || (reference != null && !reference.isBlank()))
                throw invalid("销售需求只接受销售行与用途，产品、数量和日期取自已批准来源");
            source = sales.require(in.warehouseId(), in.salesLineId());
            product = source.materialId();
            quantity = source.quantity();
            date = java.time.LocalDate.parse(source.neededDate());
            reference = source.documentNo() + "/" + source.lineId();
            salesOrder = source.orderId();
        } else if (in.salesLineId() != null || product == null || quantity == null || date == null)
            throw invalid("手工需求须填写依据、产品、计划数量和需求日期");
        var m = catalog.referenceMaterial(product);
        if (source != null && !source.unit().equals(m.unit())) throw conflict("销售来源单位与当前产品单位不一致");
        var key = new GeneratedKeyHolder();
        db.sql(
                        "INSERT INTO mfg_demand(warehouse_id,source_type,source_reference,sales_line_id,sales_order_id,product_id,product_code,product_name,unit,unit_id,quantity,needed_date,purpose,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .params(
                        in.warehouseId(),
                        in.sourceType(),
                        required(reference, "来源依据", 100),
                        in.salesLineId(),
                        salesOrder,
                        product,
                        m.code(),
                        m.name(),
                        m.unit(),
                        m.unitId(),
                        quantity,
                        date,
                        required(in.purpose(), "用途", 500),
                        actor.currentActor(),
                        now())
                .update(key);
        long id = key.getKey().longValue();
        audit(id, null, "DEMAND", "创建生产需求", null);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> createOrder(long id, Order in) {
        var d = lockedDemand(id);
        String hash = hash("ORDER", id, in);
        Long old = replay(in.idempotencyKey(), hash);
        if (old != null) return detail(old);
        version(d, in.version());
        state(d, "OPEN");
        if (db.sql("SELECT COUNT(*) FROM mfg_order WHERE active_demand_id=?")
                        .param(id)
                        .query(Long.class)
                        .single()
                > 0) throw conflict("本需求已有有效工单，请查看原单；仅安全取消后可重建");
        var before = snapshot(id);
        validateDemand(d);
        var site = site(in.siteId());
        var bom = bom(d, in.bomId());
        date(d, in.plannedDate());
        var key = new GeneratedKeyHolder();
        db.sql(
                        "INSERT INTO mfg_order(order_no,demand_id,active_demand_id,site_id,site_code,site_name,warehouse_name,bom_id,bom_snapshot,planned_date,created_by,last_edited_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .params(
                        "MFG-" + UUID.randomUUID(),
                        id,
                        id,
                        in.siteId(),
                        site.get("code"),
                        site.get("name"),
                        warehouseName(n(d, "warehouse_id")),
                        in.bomId(),
                        json.writeValueAsString(bom),
                        in.plannedDate(),
                        actor.currentActor(),
                        actor.currentActor(),
                        now(),
                        now())
                .update(key);
        long order = key.getKey().longValue();
        db.sql("UPDATE mfg_order SET order_no=? WHERE id=?")
                .params("MFG-" + String.format(Locale.ROOT, "%08d", order), order)
                .update();
        touchDemand(id);
        audit(id, order, "ORDER", in.reason(), before);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> editOrder(long orderId, Order in) {
        long id = n(order(orderId), "demand_id");
        var d = lockedDemand(id);
        String hash = hash("EDIT", orderId, in);
        Long old = replay(in.idempotencyKey(), hash);
        if (old != null) return detail(old);
        var o = order(orderId);
        version(o, in.version());
        state(o, "DRAFT", "REJECTED");
        state(d, "OPEN");
        var before = snapshot(id);
        validateDemand(d);
        var site = site(in.siteId());
        var bom = bom(d, in.bomId());
        date(d, in.plannedDate());
        db.sql(
                        "UPDATE mfg_order SET site_id=?,site_code=?,site_name=?,warehouse_name=?,bom_id=?,bom_snapshot=?,planned_date=?,last_edited_by=?,version=version+1,updated_at=? WHERE id=?")
                .params(
                        in.siteId(),
                        site.get("code"),
                        site.get("name"),
                        warehouseName(n(d, "warehouse_id")),
                        in.bomId(),
                        json.writeValueAsString(bom),
                        in.plannedDate(),
                        actor.currentActor(),
                        now(),
                        orderId)
                .update();
        audit(id, orderId, "EDIT", in.reason(), before);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> action(long orderId, String action, Action in) {
        if (!Set.of("submit", "approve", "reject", "cancel").contains(action))
            throw invalid("不支持的工单动作");
        long id = n(order(orderId), "demand_id");
        var d = lockedDemand(id);
        String hash = hash(action, orderId, in);
        Long old = replay(in.idempotencyKey(), hash);
        if (old != null) return detail(old);
        var o = order(orderId);
        version(o, in.version());
        var before = snapshot(id);
        String next;
        switch (action) {
            case "submit" -> {
                state(o, "DRAFT", "REJECTED");
                validateAuthorization(d, o);
                next = "SUBMITTED";
                db.sql("UPDATE mfg_order SET submitted_by=? WHERE id=?")
                        .params(actor.currentActor(), orderId)
                        .update();
            }
            case "approve", "reject" -> {
                state(o, "SUBMITTED");
                if (Arrays.asList(
                                d.get("created_by"),
                                o.get("created_by"),
                                o.get("last_edited_by"),
                                o.get("submitted_by"))
                        .contains(actor.currentActor()))
                    throw conflict("需求创建、工单创建、编辑或提交人不能审核本人经办工单");
                if (action.equals("approve")) {
                    validateAuthorization(d, o);
                    db.sql("UPDATE mfg_order SET approved_by=?,approved_at=? WHERE id=?")
                            .params(actor.currentActor(), now(), orderId)
                            .update();
                }
                next = action.equals("approve") ? "APPROVED" : "REJECTED";
            }
            default -> {
                state(o, "DRAFT", "REJECTED", "SUBMITTED", "APPROVED");
                // This slice has no downstream dispatch; MFG- numbers are rejected by legacy
                // inputs.
                next = "CANCELLED";
                touchDemand(id);
            }
        }
        db.sql(
                        "UPDATE mfg_order SET status=?,active_demand_id=?,version=version+1,updated_at=? WHERE id=?")
                .params(next, next.equals("CANCELLED") ? null : id, now(), orderId)
                .update();
        audit(id, orderId, action.toUpperCase(Locale.ROOT), in.reason(), before);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> cancelDemand(long id, Action in) {
        var d = lockedDemand(id);
        String hash = hash("CANCEL_DEMAND", id, in);
        Long old = replay(in.idempotencyKey(), hash);
        if (old != null) return detail(old);
        version(d, in.version());
        state(d, "OPEN");
        if (db.sql("SELECT COUNT(*) FROM mfg_order WHERE active_demand_id=?")
                        .param(id)
                        .query(Long.class)
                        .single()
                > 0) throw conflict("须先安全取消所有有效工单");
        var before = snapshot(id);
        db.sql("UPDATE mfg_demand SET status='CANCELLED',version=version+1 WHERE id=?")
                .param(id)
                .update();
        audit(id, null, "CANCEL_DEMAND", in.reason(), before);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    private void validateAuthorization(Map<String, Object> d, Map<String, Object> o) {
        state(d, "OPEN");
        validateDemand(d);
        site(n(o, "site_id"));
        bom(d, n(o, "bom_id"));
        // Saved snapshot stays immutable; approved BOM content cannot change in-place.
    }

    private void validateDemand(Map<String, Object> d) {
        eligible(n(d, "warehouse_id"));
        var m = catalog.availableMaterial(n(d, "product_id"));
        if (m.unitId() != n(d, "unit_id") || !m.unit().equals(d.get("unit")))
            throw conflict("产品单位已变化");
        if (d.get("sales_line_id") != null) {
            var s = sales.require(n(d, "warehouse_id"), n(d, "sales_line_id"));
            if (s.materialId() != n(d, "product_id")
                    || s.quantity().compareTo(new BigDecimal(d.get("quantity").toString())) != 0
                    || !s.unit().equals(d.get("unit"))) throw conflict("销售来源已变化，不能新增生产授权");
        }
    }

    private Map<String, Object> bom(Map<String, Object> d, long id) {
        var b = boms.detail(id);
        state(b, "PUBLISHED");
        if (n(b, "product_id") != n(d, "product_id")) throw conflict("BOM 产品与需求不一致");
        @SuppressWarnings("unchecked")
        var parts = (List<Map<String, Object>>) b.get("components");
        for (var p : parts) catalog.availableMaterial(n(p, "material_id"));
        b.remove("history");
        b.remove("versions");
        return b;
    }

    private Map<String, Object> site(long id) {
        var s =
                db
                        .sql("SELECT * FROM mdm_production_site WHERE id=? FOR SHARE")
                        .param(id)
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> missing("生产地点"));
        state(s, "ENABLED");
        return s;
    }

    private void date(Map<String, Object> d, java.time.LocalDate date) {
        if (date == null) throw invalid("请填写计划日期");
    }

    private void eligible(long w) {
        catalog.requireReceivingWarehouse(w);
        if (!"FINISHED_GOODS"
                .equals(
                        db.sql("SELECT purpose FROM mdm_warehouse WHERE id=?")
                                .param(w)
                                .query(String.class)
                                .single())) throw conflict("目标仓须为启用的成品仓");
    }

    private String warehouseName(long w) {
        return db.sql("SELECT name FROM mdm_warehouse WHERE id=?")
                .param(w)
                .query(String.class)
                .single();
    }

    private void access(long w) {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null
                || a.getAuthorities().stream()
                        .noneMatch(p -> p.getAuthority().equals("wms:warehouse:" + w)))
            throw new BusinessException(403, "WAREHOUSE_ACCESS_DENIED", "没有目标仓库权限");
    }

    private void lock(long w) {
        access(w);
        if (db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                .param(w)
                .query(Long.class)
                .optional()
                .isEmpty()) throw missing("仓库");
        db.sql("SELECT id FROM mfg_bom_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
    }

    private Map<String, Object> lockedDemand(long id) {
        lock(n(header(id), "warehouse_id"));
        return header(id);
    }

    private Map<String, Object> header(long id) {
        return present(
                db
                        .sql("SELECT * FROM mfg_demand WHERE id=?")
                        .param(id)
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> missing("生产需求")));
    }

    private Map<String, Object> order(long id) {
        return present(
                db.sql("SELECT * FROM mfg_order WHERE id=?").param(id).query().listOfRows().stream()
                        .findFirst()
                        .orElseThrow(() -> missing("生产订单")));
    }

    private List<Map<String, Object>> orders(long id) {
        return db
                .sql("SELECT * FROM mfg_order WHERE demand_id=? ORDER BY id DESC LIMIT 1000")
                .param(id)
                .query()
                .listOfRows()
                .stream()
                .map(this::present)
                .toList();
    }

    private Map<String, Object> snapshot(long id) {
        var d = header(id);
        d.put("orders", orders(id));
        return d;
    }

    private Map<String, Object> present(Map<String, Object> r) {
        r.replaceAll(
                (k, v) -> v instanceof BigDecimal || v instanceof java.sql.Date ? v.toString() : v);
        return r;
    }

    private void touchDemand(long id) {
        db.sql("UPDATE mfg_demand SET version=version+1 WHERE id=?").param(id).update();
    }

    private void audit(
            long id, Long order, String action, String reason, Map<String, Object> before) {
        db.sql(
                        "INSERT INTO mfg_order_audit(demand_id,order_id,action,reason,before_state,after_state,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)")
                .params(
                        id,
                        order,
                        action,
                        required(reason, "原因", 500),
                        before == null ? null : json.writeValueAsString(before),
                        json.writeValueAsString(snapshot(id)),
                        actor.currentActor(),
                        now())
                .update();
    }

    private Long replay(String key, String hash) {
        var rows =
                db.sql("SELECT * FROM mfg_order_command WHERE request_key=?")
                        .param(key)
                        .query()
                        .listOfRows();
        if (rows.isEmpty()) return null;
        if (!hash.equals(rows.getFirst().get("request_hash"))) throw conflict("请求键已用于其他载荷、操作者或动作");
        return n(rows.getFirst(), "demand_id");
    }

    private void remember(String key, String hash, long id) {
        db.sql(
                        "INSERT INTO mfg_order_command(request_key,request_hash,demand_id,created_at) VALUES(?,?,?,?)")
                .params(key, hash, id, now())
                .update();
    }

    private String hash(String a, long id, Object in) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            json.writeValueAsString(
                                                            List.of(
                                                                    a,
                                                                    id,
                                                                    actor.currentActor(),
                                                                    in))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void version(Map<String, Object> d, Long v) {
        if (v == null || n(d, "version") != v) throw conflict("版本已变化，请刷新核对");
    }

    private void state(Map<String, Object> d, String... states) {
        if (!Arrays.asList(states).contains(d.get("status")))
            throw conflict("当前状态不允许此操作：" + d.get("status"));
    }

    private long n(Map<String, Object> d, String k) {
        return ((Number) d.get(k)).longValue();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private BusinessException conflict(String m) {
        return new BusinessException(409, "PRODUCTION_ORDER_CONFLICT", m);
    }

    private BusinessException invalid(String m) {
        return new BusinessException(400, "PRODUCTION_ORDER_INVALID", m);
    }

    private BusinessException missing(String m) {
        return new BusinessException(404, "PRODUCTION_ORDER_NOT_FOUND", m + "不存在");
    }
}
