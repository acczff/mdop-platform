package io.github.acczff.mdop.purchasing;

import static io.github.acczff.mdop.common.BusinessText.required;
import static io.github.acczff.mdop.purchasing.PurchaseModels.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.common.purchasing.PurchaseReceivingPort;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class PurchaseService {
    private final JdbcClient db;
    private final CatalogService catalog;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;
    private final PurchaseReceivingPort receiving;
    private final PurchaseFulfillment fulfillment;

    public PurchaseService(
            JdbcClient db,
            CatalogService catalog,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json,
            PurchaseReceivingPort receiving,
            PurchaseFulfillment fulfillment) {
        this.db = db;
        this.catalog = catalog;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
        this.receiving = receiving;
        this.fulfillment = fulfillment;
    }

    public record Page(List<Map<String, Object>> items, long total, int page, int size) {}

    @Transactional(readOnly = true)
    public Page list(long warehouse, String kind, int page, int size) {
        requireWarehouse(warehouse);
        if (!Set.of("REQUEST", "ORDER").contains(kind)) throw invalid("单据类型不合法");
        long total =
                db.sql("SELECT COUNT(*) FROM pur_document WHERE warehouse_id=? AND kind=?")
                        .params(warehouse, kind)
                        .query(Long.class)
                        .single();
        return new Page(
                db
                        .sql(
                                "SELECT * FROM pur_document WHERE warehouse_id=? AND kind=? ORDER BY id DESC LIMIT ? OFFSET ?")
                        .params(warehouse, kind, size, (long) page * size)
                        .query()
                        .listOfRows()
                        .stream()
                        .map(this::present)
                        .toList(),
                total,
                page,
                size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(long id) {
        var d = header(id);
        requireWarehouse(n(d, "warehouse_id"));
        d.put("lines", lines(id));
        d.put("arrangements", arrangements(id, n(d, "warehouse_id"), true));
        if (!isRequest(d)) {
            var facts = fulfillment.read(id, n(d, "warehouse_id"));
            d.put("fulfillment", facts);
            var closed = closure(id);
            d.put("closure", closed);
            d.put(
                    "closureMatches",
                    closed == null
                            || (facts.canClose()
                                    && facts.factHash().equals(closed.get("fact_hash"))));
        }
        d.put(
                "history",
                db.sql(
                                "SELECT id,action,reason,created_by,created_at,before_state,after_state FROM pur_audit WHERE document_id=? ORDER BY id DESC")
                        .param(id)
                        .query()
                        .listOfRows());
        d.put(
                "related",
                db.sql(
                                "SELECT id,document_no,kind,status FROM pur_document WHERE request_id=? OR id=? ORDER BY id")
                        .params(id, d.get("request_id"))
                        .query()
                        .listOfRows());
        return d;
    }

    public Map<String, Object> create(Draft in) {
        lockWarehouse(in.warehouseId());
        String hash = hash("CREATE", 0, in);
        Long replay = replay(in.idempotencyKey(), hash);
        if (replay != null) return detail(replay);
        if (in.version() != null || in.supplierId() != null) throw invalid("新需求不接受版本或供应商");
        String purpose = required(in.purpose(), "用途", 500);
        catalog.requireReceivingWarehouse(in.warehouseId());
        long id = insert("REQUEST", null, in.warehouseId(), null, null, purpose, in.neededDate());
        saveRequestLines(id, in.lines());
        audit(id, "CREATE", "创建手工需求", null);
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
        if (in.warehouseId() != n(d, "warehouse_id")) throw conflict("建单后不能更换收货仓库");
        var before = snapshot(id);
        String purpose = required(in.purpose(), "用途", 500);
        String reason = required(in.reason(), "变更原因", 500);
        catalog.requireReceivingWarehouse(in.warehouseId());
        if (isRequest(d)) {
            if (in.supplierId() != null) throw invalid("需求不接受供应商");
            db.sql("DELETE FROM pur_line WHERE document_id=?").param(id).update();
            saveRequestLines(id, in.lines());
        } else {
            if (in.supplierId() == null) throw invalid("请选择供应商");
            var quantities = merged(in.lines());
            var existing = lines(id);
            if (quantities.size() != existing.size()
                    || existing.stream()
                            .anyMatch(
                                    l ->
                                            !quantities.containsKey(n(l, "material_id"))
                                                    || quantities
                                                                    .get(n(l, "material_id"))
                                                                    .compareTo(
                                                                            new BigDecimal(
                                                                                    l.get(
                                                                                                    "quantity")
                                                                                            .toString()))
                                                            != 0))
                throw conflict("订单必须保留已批准需求的整行数量，不能拆改");
            var supplier = catalog.referenceSupplier(in.supplierId());
            db.sql("UPDATE pur_document SET supplier_id=?,supplier_name=? WHERE id=?")
                    .params(supplier.id(), supplier.name(), id)
                    .update();
            validateMaterials(id, false, false);
        }
        db.sql(
                        "UPDATE pur_document SET purpose=?,needed_date=?,last_edited_by=?,version=version+1,updated_at=? WHERE id=?")
                .params(purpose, in.neededDate(), actor.currentActor(), now(), id)
                .update();
        audit(id, "EDIT", reason, before);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> action(long id, String action, Action in) {
        if (!Set.of("submit", "approve", "reject", "cancel", "close").contains(action))
            throw invalid("不支持的采购动作");
        var d = lockDocument(id);
        String hash = hash(action, id, in);
        Long replay = replay(in.idempotencyKey(), hash);
        if (replay != null) return detail(replay);
        version(d, in.version());
        String reason = required(in.reason(), "原因", 500);
        var before = snapshot(id);
        String state;
        switch (action) {
            case "close" -> {
                requireOrder(d);
                requireState(d, "FULFILLING");
                var facts = fulfillment.read(id, n(d, "warehouse_id"));
                if (!facts.canClose()) throw conflict("不能结案：" + String.join("；", facts.blockers()));
                db.sql(
                                "INSERT INTO pur_closure(document_id,outcome,fact_hash,snapshot,reason,closed_by,closed_at) VALUES(?,?,?,?,?,?,?)")
                        .params(
                                id,
                                facts.outcome(),
                                facts.factHash(),
                                json.writeValueAsString(facts),
                                reason,
                                actor.currentActor(),
                                now())
                        .update();
                state = "CLOSED";
            }
            case "submit" -> {
                requireState(d, "DRAFT", "REJECTED");
                validateAuthorization(d, false);
                state = "SUBMITTED";
                db.sql("UPDATE pur_document SET submitted_by=? WHERE id=?")
                        .params(actor.currentActor(), id)
                        .update();
            }
            case "approve", "reject" -> {
                requireState(d, "SUBMITTED");
                if (actor.currentActor().equals(d.get("created_by"))
                        || actor.currentActor().equals(d.get("last_edited_by"))
                        || actor.currentActor().equals(d.get("submitted_by")))
                    throw conflict("建单人、编辑人或提交人不能审核本人经办单据");
                if (action.equals("approve")) validateAuthorization(d, true);
                state = action.equals("approve") ? "APPROVED" : "REJECTED";
            }
            default -> {
                requireState(d, "DRAFT", "REJECTED", "SUBMITTED", "APPROVED", "FULFILLING");
                if (d.get("active_order_id") != null) throw conflict("需求已有有效采购订单，不能撤销");
                state = "CANCELLED";
                if (!isRequest(d)) {
                    if (activeArrangements(id) > 0) throw conflict("仍有未撤回的到货安排，请先确认全部撤回");
                    long request = n(d, "request_id");
                    var origin = header(request);
                    if (!Objects.equals(origin.get("active_order_id"), d.get("id"))
                            || !"CONVERTED".equals(origin.get("status")))
                        throw conflict("需求占用与订单不一致，请核对");
                    var originBefore = snapshot(request);
                    db.sql(
                                    "UPDATE pur_document SET active_order_id=NULL,status='APPROVED',version=version+1,updated_at=? WHERE id=?")
                            .params(now(), request)
                            .update();
                    audit(request, "RELEASE_ORDER", reason, originBefore);
                }
            }
        }
        db.sql("UPDATE pur_document SET status=?,version=version+1,updated_at=? WHERE id=?")
                .params(state, now(), id)
                .update();
        audit(id, action.toUpperCase(Locale.ROOT), reason, before);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> convert(long id, Conversion in) {
        var d = lockDocument(id);
        String hash = hash("CONVERT", id, in);
        Long replay = replay(in.idempotencyKey(), hash);
        if (replay != null) return detail(replay);
        version(d, in.version());
        if (!isRequest(d)) throw conflict("只有采购需求可以转单");
        requireState(d, "APPROVED");
        if (d.get("active_order_id") != null) throw conflict("需求已被采购订单占用");
        String reason = required(in.reason(), "转单原因", 500);
        validateAuthorization(d, false);
        var supplier = catalog.referenceSupplier(in.supplierId());
        var before = snapshot(id);
        long order =
                insert(
                        "ORDER",
                        id,
                        n(d, "warehouse_id"),
                        supplier.id(),
                        supplier.name(),
                        d.get("purpose").toString(),
                        in.neededDate());
        db.sql(
                        "INSERT INTO pur_line(document_id,source_line_id,material_id,material_code,material_name,unit,quantity) SELECT ?,id,material_id,material_code,material_name,unit,quantity FROM pur_line WHERE document_id=? ORDER BY id")
                .params(order, id)
                .update();
        db.sql(
                        "UPDATE pur_document SET status='CONVERTED',active_order_id=?,version=version+1,updated_at=? WHERE id=?")
                .params(order, now(), id)
                .update();
        audit(id, "CONVERT", reason, before);
        audit(order, "CREATE", reason, null);
        remember(in.idempotencyKey(), hash, order);
        return detail(order);
    }

    public Map<String, Object> arrange(long id, ArrangementInput in) {
        var d = lockDocument(id);
        String hash = hash("ARRANGE", id, in);
        Long previous = replay(in.idempotencyKey(), hash);
        if (previous != null) return detail(previous);
        version(d, in.version());
        requireOrder(d);
        requireState(d, "APPROVED", "FULFILLING");
        String reason = required(in.reason(), "安排原因", 500);
        catalog.requireReceivingWarehouse(n(d, "warehouse_id"));
        catalog.referenceSupplier(n(d, "supplier_id"));
        validateMaterials(id, false, false);
        var before = snapshot(id);
        var authorized = new HashMap<Long, BigDecimal>();
        for (var l : lines(id))
            authorized.put(n(l, "id"), new BigDecimal(l.get("quantity").toString()));
        var seen = new HashSet<Long>();
        for (var line : in.lines()) {
            if (!seen.add(line.orderLineId()) || !authorized.containsKey(line.orderLineId()))
                throw invalid("安排行必须来自当前订单且不能重复");
            BigDecimal used =
                    db.sql(
                                    "SELECT COALESCE(SUM(l.quantity),0) FROM pur_arrangement_line l JOIN pur_arrangement a ON a.id=l.arrangement_id WHERE l.order_line_id=? AND a.status<>'WITHDRAWN'")
                            .param(line.orderLineId())
                            .query(BigDecimal.class)
                            .single();
            if (used.add(line.quantity()).compareTo(authorized.get(line.orderLineId())) > 0)
                throw conflict("安排数量超过订单剩余额度");
        }
        var key = new GeneratedKeyHolder();
        db.sql(
                        "INSERT INTO pur_arrangement(order_id,expected_date,created_by,created_at) VALUES(?,?,?,?)")
                .params(id, in.expectedDate(), actor.currentActor(), now())
                .update(key);
        long arrangement = key.getKey().longValue();
        for (var line : in.lines())
            db.sql(
                            "INSERT INTO pur_arrangement_line(arrangement_id,order_line_id,quantity) VALUES(?,?,?)")
                    .params(arrangement, line.orderLineId(), line.quantity())
                    .update();
        touch(id);
        audit(id, "ARRANGE", reason, before);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> arrangementAction(
            long id, long arrangement, String action, Action in) {
        if (!Set.of("deliver", "withdraw").contains(action)) throw invalid("不支持的到货操作");
        var d = lockDocument(id);
        String hash = hash(action + ":" + arrangement, id, in);
        Long previous = replay(in.idempotencyKey(), hash);
        if (previous != null) return detail(previous);
        version(d, in.version());
        requireOrder(d);
        requireState(d, "APPROVED", "FULFILLING");
        var a = arrangement(id, arrangement);
        String reason = required(in.reason(), "操作原因", 500);
        var before = snapshot(id);
        if (action.equals("deliver")) {
            if (!"PENDING".equals(a.get("status"))) throw conflict("只有待送达安排可以送达");
            var payload = new ArrayList<PurchaseReceivingPort.Line>();
            for (var l : arrangementLines(arrangement))
                payload.add(
                        new PurchaseReceivingPort.Line(
                                n(l, "id"),
                                n(l, "order_line_id"),
                                n(l, "material_id"),
                                l.get("material_code").toString(),
                                l.get("material_name").toString(),
                                l.get("unit").toString(),
                                new BigDecimal(l.get("quantity").toString())));
            var delivered =
                    receiving.deliver(
                            new PurchaseReceivingPort.Notice(
                                    arrangement,
                                    "PA-" + String.format(Locale.ROOT, "%08d", arrangement),
                                    d.get("document_no").toString(),
                                    n(d, "warehouse_id"),
                                    n(d, "supplier_id"),
                                    d.get("supplier_name").toString(),
                                    payload));
            if (delivered.lines().size() != payload.size()) throw conflict("WMS 到货行映射不完整");
            for (var mapping : delivered.lines()) {
                int updated =
                        db.sql(
                                        "UPDATE pur_arrangement_line SET wms_arrival_item_id=? WHERE id=? AND arrangement_id=?")
                                .params(
                                        mapping.arrivalItemId(),
                                        mapping.arrangementLineId(),
                                        arrangement)
                                .update();
                if (updated != 1) throw conflict("WMS 到货行映射不匹配");
            }
            db.sql(
                            "UPDATE pur_arrangement SET status='DELIVERED',wms_arrival_id=?,last_error=NULL WHERE id=?")
                    .params(delivered.arrivalId(), arrangement)
                    .update();
            db.sql("UPDATE pur_document SET status='FULFILLING' WHERE id=?").param(id).update();
        } else {
            if ("WITHDRAWN".equals(a.get("status"))) throw conflict("到货安排已撤回");
            if ("DELIVERED".equals(a.get("status")))
                receiving.withdraw(n(d, "warehouse_id"), arrangement, reason);
            db.sql("UPDATE pur_arrangement SET status='WITHDRAWN',last_error=NULL WHERE id=?")
                    .param(arrangement)
                    .update();
            if (activeArrangements(id) == 0)
                db.sql("UPDATE pur_document SET status='APPROVED' WHERE id=?").param(id).update();
        }
        touch(id);
        audit(id, action.equals("deliver") ? "DELIVER" : "WITHDRAW", reason, before);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    /** Runs only after the delivery transaction has rolled back. Failure does not release quota. */
    public void recordDeliveryFailure(
            long id, long arrangement, Long expectedVersion, String error) {
        var d = lockDocument(id);
        if (expectedVersion == null || n(d, "version") != expectedVersion) return;
        var a = arrangement(id, arrangement);
        if (!"PENDING".equals(a.get("status"))) return;
        String safe = error.length() > 350 ? error.substring(0, 350) : error;
        if (safe.equals(a.get("last_error"))) return;
        var before = snapshot(id);
        db.sql("UPDATE pur_arrangement SET last_error=? WHERE id=?")
                .params(safe, arrangement)
                .update();
        audit(id, "DELIVERY_FAILED", "到货安排 #" + arrangement + "：" + safe, before);
    }

    private void requireOrder(Map<String, Object> d) {
        if (isRequest(d)) throw conflict("只有采购订单可以安排到货");
    }

    private long activeArrangements(long id) {
        return db.sql(
                        "SELECT COUNT(*) FROM pur_arrangement WHERE order_id=? AND status<>'WITHDRAWN'")
                .param(id)
                .query(Long.class)
                .single();
    }

    private void touch(long id) {
        db.sql("UPDATE pur_document SET version=version+1,updated_at=? WHERE id=?")
                .params(now(), id)
                .update();
    }

    private Map<String, Object> arrangement(long order, long id) {
        return db
                .sql("SELECT * FROM pur_arrangement WHERE id=? AND order_id=?")
                .params(id, order)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> conflict("到货安排不存在或不属于当前订单"));
    }

    private List<Map<String, Object>> arrangementLines(long id) {
        return db.sql(
                        "SELECT a.id,a.order_line_id,CAST(a.quantity AS CHAR) quantity,a.wms_arrival_item_id,l.material_id,l.material_code,l.material_name,l.unit FROM pur_arrangement_line a JOIN pur_line l ON l.id=a.order_line_id WHERE a.arrangement_id=? ORDER BY a.id")
                .param(id)
                .query()
                .listOfRows();
    }

    private List<Map<String, Object>> arrangements(long order, long warehouse, boolean withWms) {
        var rows =
                db.sql("SELECT * FROM pur_arrangement WHERE order_id=? ORDER BY id DESC")
                        .param(order)
                        .query()
                        .listOfRows();
        for (var a : rows) {
            if (a.get("expected_date") instanceof java.sql.Date date)
                a.put("expected_date", date.toLocalDate().toString());
            a.put("lines", arrangementLines(n(a, "id")));
            if (withWms && a.get("wms_arrival_id") != null)
                a.put("wms", receiving.view(warehouse, n(a, "id")));
        }
        return rows;
    }

    private long insert(
            String kind,
            Long request,
            long warehouse,
            Long supplier,
            String supplierName,
            String purpose,
            java.time.LocalDate date) {
        String warehouseName =
                db.sql("SELECT name FROM mdm_warehouse WHERE id=?")
                        .param(warehouse)
                        .query(String.class)
                        .single();
        var key = new GeneratedKeyHolder();
        db.sql(
                        "INSERT INTO pur_document(document_no,kind,source_type,request_id,warehouse_id,warehouse_name,supplier_id,supplier_name,purpose,needed_date,created_by,last_edited_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .params(
                        (kind.equals("REQUEST") ? "PR-" : "PO-") + UUID.randomUUID(),
                        kind,
                        kind.equals("REQUEST") ? "MANUAL" : "PURCHASE_REQUEST",
                        request,
                        warehouse,
                        warehouseName,
                        supplier,
                        supplierName,
                        purpose,
                        date,
                        actor.currentActor(),
                        actor.currentActor(),
                        now(),
                        now())
                .update(key);
        long id = key.getKey().longValue();
        db.sql("UPDATE pur_document SET document_no=? WHERE id=?")
                .params(
                        (kind.equals("REQUEST") ? "PR-" : "PO-")
                                + String.format(Locale.ROOT, "%08d", id),
                        id)
                .update();
        return id;
    }

    private SortedMap<Long, BigDecimal> merged(List<Line> input) {
        var result = new TreeMap<Long, BigDecimal>();
        for (var l : input) result.merge(l.materialId(), l.quantity(), BigDecimal::add);
        if (result.values().stream()
                .anyMatch(
                        q ->
                                q.signum() <= 0
                                        || q.scale() > 6
                                        || q.compareTo(new BigDecimal("999999999999.999999")) > 0))
            throw invalid("合并后数量超出允许范围");
        return result;
    }

    private void saveRequestLines(long id, List<Line> input) {
        for (var entry : merged(input).entrySet()) {
            var m = catalog.availableMaterial(entry.getKey());
            db.sql(
                            "INSERT INTO pur_line(document_id,material_id,material_code,material_name,unit,quantity) VALUES(?,?,?,?,?,?)")
                    .params(id, m.id(), m.code(), m.name(), m.unit(), entry.getValue())
                    .update();
        }
    }

    private void validateAuthorization(Map<String, Object> d, boolean approve) {
        catalog.requireReceivingWarehouse(n(d, "warehouse_id"));
        if (!isRequest(d)) {
            var supplier = catalog.referenceSupplier(n(d, "supplier_id"));
            db.sql("UPDATE pur_document SET supplier_name=? WHERE id=?")
                    .params(supplier.name(), n(d, "id"))
                    .update();
        }
        validateMaterials(
                n(d, "id"),
                approve && isRequest(d),
                isRequest(d) && !"APPROVED".equals(d.get("status")));
    }

    private void validateMaterials(long id, boolean authorize, boolean refreshSnapshot) {
        for (var l : lines(id)) {
            var m =
                    authorize
                            ? catalog.referenceMaterial(n(l, "material_id"))
                            : catalog.availableMaterial(n(l, "material_id"));
            if (authorize && !m.unit().equals(l.get("unit")))
                throw conflict("物料基本单位在提交后发生变化，请驳回并重新核对需求数量");
            if (refreshSnapshot)
                db.sql("UPDATE pur_line SET material_code=?,material_name=?,unit=? WHERE id=?")
                        .params(m.code(), m.name(), m.unit(), n(l, "id"))
                        .update();
        }
    }

    private Map<String, Object> header(long id) {
        return present(
                db
                        .sql("SELECT * FROM pur_document WHERE id=?")
                        .param(id)
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () -> new BusinessException(404, "PURCHASE_NOT_FOUND", "采购单据不存在")));
    }

    private Map<String, Object> present(Map<String, Object> row) {
        // A business date has no instant or zone; JDBC Date must not serialize as UTC midnight.
        if (row.get("needed_date") instanceof java.sql.Date date)
            row.put("needed_date", date.toLocalDate().toString());
        return row;
    }

    private List<Map<String, Object>> lines(long id) {
        return db.sql(
                        "SELECT id,document_id,source_line_id,material_id,material_code,material_name,unit,CAST(quantity AS CHAR) quantity FROM pur_line WHERE document_id=? ORDER BY material_id")
                .param(id)
                .query()
                .listOfRows();
    }

    private Map<String, Object> snapshot(long id) {
        var d = header(id);
        d.put("lines", lines(id));
        d.put("arrangements", arrangements(id, n(d, "warehouse_id"), false));
        d.put("closure", closure(id));
        return d;
    }

    private Map<String, Object> closure(long id) {
        return db
                .sql(
                        "SELECT outcome,fact_hash,snapshot,reason,closed_by,closed_at FROM pur_closure WHERE document_id=?")
                .param(id)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void audit(long id, String action, String reason, Map<String, Object> before) {
        db.sql(
                        "INSERT INTO pur_audit(document_id,action,reason,before_state,after_state,created_by,created_at) VALUES(?,?,?,?,?,?,?)")
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

    private void lockWarehouse(long warehouse) {
        requireWarehouse(warehouse);
        if (db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                .param(warehouse)
                .query(Long.class)
                .optional()
                .isEmpty()) throw new BusinessException(404, "WAREHOUSE_NOT_FOUND", "仓库不存在");
    }

    private Map<String, Object> lockDocument(long id) {
        lockWarehouse(n(header(id), "warehouse_id"));
        return db.sql("SELECT * FROM pur_document WHERE id=? FOR UPDATE")
                .param(id)
                .query()
                .singleRow();
    }

    private void requireWarehouse(long warehouse) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || authentication.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals("wms:warehouse:" + warehouse)))
            throw new BusinessException(403, "WAREHOUSE_ACCESS_DENIED", "没有目标仓库的数据权限");
    }

    private Long replay(String key, String hash) {
        var rows =
                db.sql("SELECT * FROM pur_command WHERE request_key=?")
                        .param(key)
                        .query()
                        .listOfRows();
        if (rows.isEmpty()) return null;
        if (!hash.equals(rows.getFirst().get("request_hash"))) throw conflict("幂等键已用于其他载荷、操作者或动作");
        return n(rows.getFirst(), "document_id");
    }

    private void remember(String key, String hash, long id) {
        db.sql(
                        "INSERT INTO pur_command(request_key,request_hash,document_id,created_at) VALUES(?,?,?,?)")
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
        if (!Arrays.asList(states).contains(d.get("status"))) throw conflict("当前状态不允许此操作，请刷新核对");
    }

    private boolean isRequest(Map<String, Object> d) {
        return "REQUEST".equals(d.get("kind"));
    }

    private long n(Map<String, Object> d, String key) {
        return ((Number) d.get(key)).longValue();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private BusinessException conflict(String text) {
        return new BusinessException(409, "PURCHASE_CONFLICT", text);
    }

    private BusinessException invalid(String text) {
        return new BusinessException(400, "PURCHASE_INVALID", text);
    }
}
