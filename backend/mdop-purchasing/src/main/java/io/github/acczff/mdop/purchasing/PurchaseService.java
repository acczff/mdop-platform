package io.github.acczff.mdop.purchasing;

import static io.github.acczff.mdop.common.BusinessText.required;
import static io.github.acczff.mdop.purchasing.PurchaseModels.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
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

    public PurchaseService(
            JdbcClient db,
            CatalogService catalog,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json) {
        this.db = db;
        this.catalog = catalog;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
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

    @Transactional(readOnly = true)
    public Map<String, Object> detail(long id) {
        var d = header(id);
        requireWarehouse(n(d, "warehouse_id"));
        d.put("lines", lines(id));
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
        if (!Set.of("submit", "approve", "reject", "cancel").contains(action))
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
                requireState(d, "DRAFT", "REJECTED", "SUBMITTED", "APPROVED");
                if (d.get("active_order_id") != null) throw conflict("需求已有有效采购订单，不能撤销");
                state = "CANCELLED";
                if (!isRequest(d)) {
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
        return d;
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
