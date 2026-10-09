package io.github.acczff.mdop.sales;

import static io.github.acczff.mdop.common.BusinessText.required;
import static io.github.acczff.mdop.sales.SalesModels.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.common.sales.SalesShippingPort;
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
public class SalesOrderService {
    private final JdbcClient db;
    private final CatalogService catalog;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;
    private final SalesShippingPort shipping;
    private final SalesFulfillment fulfillment;

    public SalesOrderService(
            JdbcClient db,
            CatalogService catalog,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json,
            SalesShippingPort shipping,
            SalesFulfillment fulfillment) {
        this.db = db;
        this.catalog = catalog;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
        this.shipping = shipping;
        this.fulfillment = fulfillment;
    }

    public record Page(List<Map<String, Object>> items, long total, int page, int size) {}

    @Transactional(readOnly = true)
    public Page list(long warehouse, int page, int size) {
        requireWarehouse(warehouse);
        return new Page(
                db
                        .sql(
                                "SELECT * FROM sal_document WHERE warehouse_id=? ORDER BY id DESC LIMIT ? OFFSET ?")
                        .params(warehouse, size, (long) page * size)
                        .query()
                        .listOfRows()
                        .stream()
                        .map(this::present)
                        .toList(),
                db.sql("SELECT COUNT(*) FROM sal_document WHERE warehouse_id=?")
                        .param(warehouse)
                        .query(Long.class)
                        .single(),
                page,
                size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(long id) {
        var d = header(id);
        requireWarehouse(n(d, "warehouse_id"));
        d.put("lines", lines(id));
        var facts = fulfillment.read(id, n(d, "warehouse_id"));
        d.put("fulfillment", facts);
        d.put("arrangements", facts.arrangements());
        var closed = closure(id);
        d.put("closure", closed);
        d.put(
                "closureMatches",
                closed == null
                        || (facts.canClose() && facts.factHash().equals(closed.get("fact_hash"))));
        d.put(
                "history",
                db.sql(
                                "SELECT id,action,reason,created_by,created_at,before_state,after_state FROM sal_audit WHERE document_id=? ORDER BY id DESC")
                        .param(id)
                        .query()
                        .listOfRows());
        return d;
    }

    public Map<String, Object> create(Draft in) {
        lockWarehouse(in.warehouseId());
        String hash = hash("CREATE", 0, in);
        Long replay = replay(in.idempotencyKey(), hash);
        if (replay != null) return detail(replay);
        if (in.version() != null) throw invalid("新订单不接受版本");
        eligible(in.warehouseId());
        var customer = catalog.referenceCustomer(in.customerId());
        var key = new GeneratedKeyHolder();
        db.sql(
                        "INSERT INTO sal_document(document_no,warehouse_id,warehouse_name,customer_id,customer_code,customer_name,customer_reference,purpose,needed_date,created_by,last_edited_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .params(
                        "SO-" + UUID.randomUUID(),
                        in.warehouseId(),
                        db.sql("SELECT name FROM mdm_warehouse WHERE id=?")
                                .param(in.warehouseId())
                                .query(String.class)
                                .single(),
                        in.customerId(),
                        customer.get("code"),
                        customer.get("name"),
                        trim(in.customerReference()),
                        required(in.purpose(), "用途", 500),
                        in.neededDate(),
                        actor.currentActor(),
                        actor.currentActor(),
                        now(),
                        now())
                .update(key);
        long id = key.getKey().longValue();
        db.sql("UPDATE sal_document SET document_no=? WHERE id=?")
                .params("SO-" + String.format(Locale.ROOT, "%08d", id), id)
                .update();
        saveLines(id, in.lines());
        audit(id, "CREATE", "创建客户订单", null);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> edit(long id, Draft in) {
        var d = lockDocument(id);
        String hash = hash("EDIT", id, in);
        Long replay = replay(in.idempotencyKey(), hash);
        if (replay != null) return detail(replay);
        version(d, in.version());
        requireState(d, "DRAFT", "REJECTED");
        if (n(d, "warehouse_id") != in.warehouseId()) throw conflict("建单后不能更换出库仓");
        eligible(in.warehouseId());
        var c = catalog.referenceCustomer(in.customerId());
        var before = snapshot(id);
        db.sql(
                        "UPDATE sal_document SET customer_id=?,customer_code=?,customer_name=?,customer_reference=?,purpose=?,needed_date=?,last_edited_by=?,version=version+1,updated_at=? WHERE id=?")
                .params(
                        in.customerId(),
                        c.get("code"),
                        c.get("name"),
                        trim(in.customerReference()),
                        required(in.purpose(), "用途", 500),
                        in.neededDate(),
                        actor.currentActor(),
                        now(),
                        id)
                .update();
        db.sql("DELETE FROM sal_line WHERE document_id=?").param(id).update();
        saveLines(id, in.lines());
        audit(id, "EDIT", required(in.reason(), "变更原因", 500), before);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> action(long id, String action, Action in) {
        if (!Set.of("submit", "approve", "reject", "cancel", "close").contains(action))
            throw invalid("不支持的销售操作");
        var d = lockDocument(id);
        String hash = hash(action, id, in);
        Long replay = replay(in.idempotencyKey(), hash);
        if (replay != null) return detail(replay);
        version(d, in.version());
        String reason = required(in.reason(), "原因", 500);
        var before = snapshot(id);
        String state;
        switch (action) {
            case "submit" -> {
                requireState(d, "DRAFT", "REJECTED");
                validateAuthorization(d, false, true);
                state = "SUBMITTED";
                db.sql("UPDATE sal_document SET submitted_by=? WHERE id=?")
                        .params(actor.currentActor(), id)
                        .update();
            }
            case "approve", "reject" -> {
                requireState(d, "SUBMITTED");
                if (List.of(d.get("created_by"), d.get("last_edited_by"), d.get("submitted_by"))
                        .contains(actor.currentActor())) throw conflict("建单、编辑或提交人不能审核本人经办订单");
                if (action.equals("approve")) validateAuthorization(d, true, false);
                state = action.equals("approve") ? "APPROVED" : "REJECTED";
            }
            case "cancel" -> {
                requireState(d, "DRAFT", "REJECTED", "SUBMITTED", "APPROVED", "FULFILLING");
                if (activeArrangements(id) > 0) throw conflict("请先确认所有发货安排安全撤回");
                for (var a : arrangements(id))
                    if (a.get("wms_sales_id") != null) {
                        var e = shipping.view(n(d, "warehouse_id"), n(a, "id"));
                        if (!"CANCELLED".equals(e.status()) || e.ledgerCount() != 0)
                            throw conflict("下游仍有执行事实，不能取消");
                    }
                state = "CANCELLED";
            }
            default -> {
                requireState(d, "FULFILLING");
                var f = fulfillment.read(id, n(d, "warehouse_id"));
                if (!f.canClose()) throw conflict("不能结案：" + String.join("；", f.blockers()));
                db.sql(
                                "INSERT INTO sal_closure(document_id,fact_hash,snapshot,reason,closed_by,closed_at) VALUES(?,?,?,?,?,?)")
                        .params(
                                id,
                                f.factHash(),
                                json.writeValueAsString(f),
                                reason,
                                actor.currentActor(),
                                now())
                        .update();
                state = "CLOSED";
            }
        }
        db.sql("UPDATE sal_document SET status=?,version=version+1,updated_at=? WHERE id=?")
                .params(state, now(), id)
                .update();
        audit(id, action.toUpperCase(Locale.ROOT), reason, before);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> arrange(long id, ArrangementInput in) {
        var d = lockDocument(id);
        String hash = hash("ARRANGE", id, in);
        Long replay = replay(in.idempotencyKey(), hash);
        if (replay != null) return detail(replay);
        version(d, in.version());
        requireState(d, "APPROVED", "FULFILLING");
        validateAuthorization(d, false, false);
        var line =
                lines(id).stream()
                        .filter(l -> n(l, "id") == in.orderLineId())
                        .findFirst()
                        .orElseThrow(() -> invalid("发货行不属于当前订单"));
        var used =
                db.sql(
                                "SELECT COALESCE(SUM(quantity),0) FROM sal_arrangement WHERE order_line_id=? AND status<>'WITHDRAWN'")
                        .param(in.orderLineId())
                        .query(BigDecimal.class)
                        .single();
        if (used.add(in.quantity()).compareTo(new BigDecimal(line.get("quantity").toString())) > 0)
            throw conflict("发货安排超过剩余授权");
        var before = snapshot(id);
        db.sql(
                        "INSERT INTO sal_arrangement(order_id,order_line_id,quantity,expected_date,created_by,created_at) VALUES(?,?,?,?,?,?)")
                .params(
                        id,
                        in.orderLineId(),
                        in.quantity(),
                        in.expectedDate(),
                        actor.currentActor(),
                        now())
                .update();
        touch(id);
        audit(id, "ARRANGE", required(in.reason(), "安排原因", 500), before);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> arrangementAction(
            long id, long arrangement, String action, Action in) {
        if (!Set.of("deliver", "withdraw").contains(action)) throw invalid("不支持的发货安排操作");
        var d = lockDocument(id);
        String hash = hash(action + ":" + arrangement, id, in);
        Long replay = replay(in.idempotencyKey(), hash);
        if (replay != null) return detail(replay);
        version(d, in.version());
        requireState(d, "APPROVED", "FULFILLING");
        var a =
                arrangements(id).stream()
                        .filter(row -> n(row, "id") == arrangement)
                        .findFirst()
                        .orElseThrow(() -> conflict("发货安排不属于当前订单"));
        var before = snapshot(id);
        String reason = required(in.reason(), "原因", 500);
        if (action.equals("deliver")) {
            if (!"PENDING".equals(a.get("status"))) throw conflict("仅待送达安排可以送达");
            validateAuthorization(d, false, false);
            var l =
                    lines(id).stream()
                            .filter(row -> n(row, "id") == n(a, "order_line_id"))
                            .findFirst()
                            .orElseThrow(() -> conflict("授权来源缺失"));
            var e =
                    shipping.deliver(
                            new SalesShippingPort.Dispatch(
                                    arrangement,
                                    n(l, "id"),
                                    d.get("document_no").toString(),
                                    n(d, "warehouse_id"),
                                    d.get("customer_name").toString(),
                                    n(l, "material_id"),
                                    l.get("material_code").toString(),
                                    l.get("material_name").toString(),
                                    l.get("unit").toString(),
                                    new BigDecimal(a.get("quantity").toString())));
            if (e.arrangementId() != arrangement
                    || e.orderLineId() != n(l, "id")
                    || e.warehouseId() != n(d, "warehouse_id")) throw conflict("WMS 发货映射不匹配");
            db.sql(
                            "UPDATE sal_arrangement SET status='DELIVERED',wms_sales_id=?,last_error=NULL WHERE id=?")
                    .params(e.id(), arrangement)
                    .update();
            db.sql("UPDATE sal_document SET status='FULFILLING' WHERE id=?").param(id).update();
        } else {
            if ("WITHDRAWN".equals(a.get("status"))) throw conflict("发货安排已撤回");
            if (a.get("wms_sales_id") != null)
                shipping.withdraw(n(d, "warehouse_id"), arrangement, reason);
            db.sql("UPDATE sal_arrangement SET status='WITHDRAWN',last_error=NULL WHERE id=?")
                    .param(arrangement)
                    .update();
            if (activeArrangements(id) == 0)
                db.sql("UPDATE sal_document SET status='APPROVED' WHERE id=?").param(id).update();
        }
        touch(id);
        audit(id, action.equals("deliver") ? "DELIVER" : "WITHDRAW", reason, before);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public void recordDeliveryFailure(
            long id, long arrangement, Long expectedVersion, String error) {
        var d = lockDocument(id);
        if (expectedVersion == null || n(d, "version") != expectedVersion) return;
        var rows =
                db.sql(
                                "SELECT * FROM sal_arrangement WHERE id=? AND order_id=? AND status='PENDING'")
                        .params(arrangement, id)
                        .query()
                        .listOfRows();
        if (rows.isEmpty()) return;
        String safe = error.substring(0, Math.min(350, error.length()));
        if (safe.equals(rows.getFirst().get("last_error"))) return;
        var before = snapshot(id);
        db.sql("UPDATE sal_arrangement SET last_error=? WHERE id=?")
                .params(safe, arrangement)
                .update();
        audit(id, "DELIVERY_FAILED", safe, before);
    }

    private void eligible(long warehouse) {
        catalog.requireReceivingWarehouse(warehouse);
        if (!"FINISHED_GOODS"
                .equals(
                        db.sql("SELECT purpose FROM mdm_warehouse WHERE id=?")
                                .param(warehouse)
                                .query(String.class)
                                .single())) throw conflict("销售订单仅支持成品仓");
    }

    private void validateAuthorization(Map<String, Object> d, boolean approve, boolean refresh) {
        eligible(n(d, "warehouse_id"));
        var c = catalog.referenceCustomer(n(d, "customer_id"));
        if (refresh)
            db.sql("UPDATE sal_document SET customer_code=?,customer_name=? WHERE id=?")
                    .params(c.get("code"), c.get("name"), n(d, "id"))
                    .update();
        for (var l : lines(n(d, "id"))) {
            var m =
                    approve
                            ? catalog.referenceMaterial(n(l, "material_id"))
                            : catalog.availableMaterial(n(l, "material_id"));
            if (!refresh && !m.unit().equals(l.get("unit"))) throw conflict("单位在授权前发生变化，请驳回重新核对数量");
            if (refresh)
                db.sql("UPDATE sal_line SET material_code=?,material_name=?,unit=? WHERE id=?")
                        .params(m.code(), m.name(), m.unit(), n(l, "id"))
                        .update();
        }
    }

    private void saveLines(long id, List<Line> input) {
        var merged = new TreeMap<Long, BigDecimal>();
        for (var l : input) merged.merge(l.materialId(), l.quantity(), BigDecimal::add);
        for (var entry : merged.entrySet()) {
            if (entry.getValue().compareTo(new BigDecimal("999999999999.999999")) > 0)
                throw invalid("合并数量溢出");
            var m = catalog.availableMaterial(entry.getKey());
            db.sql(
                            "INSERT INTO sal_line(document_id,material_id,material_code,material_name,unit,quantity) VALUES(?,?,?,?,?,?)")
                    .params(id, m.id(), m.code(), m.name(), m.unit(), entry.getValue())
                    .update();
        }
    }

    private long activeArrangements(long id) {
        return db.sql(
                        "SELECT COUNT(*) FROM sal_arrangement WHERE order_id=? AND status<>'WITHDRAWN'")
                .param(id)
                .query(Long.class)
                .single();
    }

    private void touch(long id) {
        db.sql("UPDATE sal_document SET version=version+1,updated_at=? WHERE id=?")
                .params(now(), id)
                .update();
    }

    private Map<String, Object> header(long id) {
        return present(
                db
                        .sql("SELECT * FROM sal_document WHERE id=?")
                        .param(id)
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                404, "SALES_ORDER_NOT_FOUND", "客户订单不存在")));
    }

    private Map<String, Object> present(Map<String, Object> row) {
        if (row.get("needed_date") instanceof java.sql.Date d)
            row.put("needed_date", d.toLocalDate().toString());
        return row;
    }

    private List<Map<String, Object>> lines(long id) {
        return db.sql(
                        "SELECT id,material_id,material_code,material_name,unit,CAST(quantity AS CHAR) quantity FROM sal_line WHERE document_id=? ORDER BY material_id")
                .param(id)
                .query()
                .listOfRows();
    }

    private List<Map<String, Object>> arrangements(long id) {
        var rows =
                db.sql(
                                "SELECT id,order_id,order_line_id,CAST(quantity AS CHAR) quantity,expected_date,status,wms_sales_id,last_error,created_by,created_at FROM sal_arrangement WHERE order_id=? ORDER BY id")
                        .param(id)
                        .query()
                        .listOfRows();
        for (var a : rows)
            if (a.get("expected_date") instanceof java.sql.Date date)
                a.put("expected_date", date.toLocalDate().toString());
        return rows;
    }

    private Map<String, Object> closure(long id) {
        return db
                .sql(
                        "SELECT fact_hash,snapshot,reason,closed_by,closed_at FROM sal_closure WHERE document_id=?")
                .param(id)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> snapshot(long id) {
        var d = header(id);
        d.put("lines", lines(id));
        d.put("arrangements", arrangements(id));
        d.put("closure", closure(id));
        return d;
    }

    private void audit(long id, String action, String reason, Map<String, Object> before) {
        db.sql(
                        "INSERT INTO sal_audit(document_id,action,reason,before_state,after_state,created_by,created_at) VALUES(?,?,?,?,?,?,?)")
                .params(
                        id,
                        action,
                        reason,
                        before == null ? null : json.writeValueAsString(before),
                        json.writeValueAsString(snapshot(id)),
                        actor.currentActor(),
                        now())
                .update();
    }

    private void lockWarehouse(long id) {
        requireWarehouse(id);
        if (db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                .param(id)
                .query(Long.class)
                .optional()
                .isEmpty()) throw new BusinessException(404, "WAREHOUSE_NOT_FOUND", "仓库不存在");
    }

    private Map<String, Object> lockDocument(long id) {
        lockWarehouse(n(header(id), "warehouse_id"));
        return db.sql("SELECT * FROM sal_document WHERE id=? FOR UPDATE")
                .param(id)
                .query()
                .singleRow();
    }

    private void requireWarehouse(long id) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || auth.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals("wms:warehouse:" + id)))
            throw new BusinessException(403, "WAREHOUSE_ACCESS_DENIED", "没有目标仓库权限");
    }

    private Long replay(String key, String hash) {
        var rows =
                db.sql("SELECT * FROM sal_command WHERE request_key=?")
                        .param(key)
                        .query()
                        .listOfRows();
        if (rows.isEmpty()) return null;
        if (!hash.equals(rows.getFirst().get("request_hash"))) throw conflict("幂等键已用于其他载荷、操作者或动作");
        return n(rows.getFirst(), "document_id");
    }

    private void remember(String key, String hash, long id) {
        db.sql(
                        "INSERT INTO sal_command(request_key,request_hash,document_id,created_at) VALUES(?,?,?,?)")
                .params(key, hash, id, now())
                .update();
    }

    private String hash(String action, long id, Object input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            json.writeValueAsString(
                                                            List.of(
                                                                    action,
                                                                    id,
                                                                    actor.currentActor(),
                                                                    input))
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void version(Map<String, Object> d, Long expected) {
        if (expected == null || n(d, "version") != expected) throw conflict("单据版本已变化，请刷新核对");
    }

    private void requireState(Map<String, Object> d, String... states) {
        if (!Arrays.asList(states).contains(d.get("status"))) throw conflict("当前状态不允许此操作");
    }

    private long n(Map<String, Object> d, String key) {
        return ((Number) d.get(key)).longValue();
    }

    private String trim(String v) {
        return v == null ? null : v.trim();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private BusinessException conflict(String m) {
        return new BusinessException(409, "SALES_ORDER_CONFLICT", m);
    }

    private BusinessException invalid(String m) {
        return new BusinessException(400, "SALES_ORDER_INVALID", m);
    }
}
