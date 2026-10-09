package io.github.acczff.mdop.manufacturing;

import static io.github.acczff.mdop.common.BusinessText.required;
import static io.github.acczff.mdop.manufacturing.BomModels.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.masterdata.catalog.CatalogService;
import io.github.acczff.mdop.masterdata.catalog.CatalogService.Material;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class BomService {
    private final JdbcClient db;
    private final CatalogService catalog;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public BomService(
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
    public Page list(String q, String status, int page, int size) {
        String filter =
                " WHERE (product_code LIKE ? OR product_name LIKE ? OR version_label LIKE ?) AND (?='' OR status=?)";
        String term = "%" + q.trim() + "%";
        return new Page(
                db
                        .sql(
                                "SELECT * FROM mfg_bom"
                                        + filter
                                        + " ORDER BY id DESC LIMIT ? OFFSET ?")
                        .params(term, term, term, status, status, size, (long) page * size)
                        .query()
                        .listOfRows()
                        .stream()
                        .map(this::present)
                        .toList(),
                db.sql("SELECT COUNT(*) FROM mfg_bom" + filter)
                        .params(term, term, term, status, status)
                        .query(Long.class)
                        .single(),
                page,
                size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(long id) {
        var d = snapshot(id);
        d.put(
                "history",
                db.sql(
                                "SELECT id,action,reason,before_state,after_state,created_by,created_at FROM mfg_bom_audit WHERE bom_id=? ORDER BY id DESC LIMIT 1000")
                        .param(id)
                        .query()
                        .listOfRows());
        d.put(
                "versions",
                db.sql(
                                "SELECT id,version_label,status,published_at,copied_from_id FROM mfg_bom WHERE product_id=? ORDER BY id DESC LIMIT 1000")
                        .param(n(d, "product_id"))
                        .query()
                        .listOfRows());
        return d;
    }

    public Map<String, Object> create(Draft in) {
        lock();
        String hash = hash("CREATE", 0, in);
        Long old = replay(in.idempotencyKey(), hash);
        if (old != null) return detail(old);
        if (in.version() != null) throw invalid("新建不接受版本号");
        long id = insert(in, null);
        audit(id, "CREATE", in.reason(), null);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> edit(long id, Draft in) {
        lock();
        String hash = hash("EDIT", id, in);
        Long old = replay(in.idempotencyKey(), hash);
        if (old != null) return detail(old);
        var d = snapshot(id);
        version(d, in.version());
        state(d, "DRAFT");
        if (n(d, "product_id") != in.productId()
                || !d.get("version_label").equals(in.versionLabel()))
            throw conflict("产品与版本标识创建后不能更换，请新建版本");
        var materials = validate(in);
        var p = materials.get(in.productId());
        db.sql(
                        "UPDATE mfg_bom SET product_code=?,product_name=?,product_unit=?,product_unit_id=?,base_quantity=?,description=?,version=version+1,updated_at=? WHERE id=?")
                .params(
                        p.code(),
                        p.name(),
                        p.unit(),
                        p.unitId(),
                        in.baseQuantity(),
                        required(in.description(), "说明", 500),
                        now(),
                        id)
                .update();
        db.sql("DELETE FROM mfg_bom_component WHERE bom_id=?").param(id).update();
        saveComponents(id, in.components(), materials);
        audit(id, "EDIT", in.reason(), d);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> copy(long id, Copy in) {
        lock();
        String hash = hash("COPY", id, in);
        Long old = replay(in.idempotencyKey(), hash);
        if (old != null) return detail(old);
        var d = header(id);
        version(d, in.version());
        // A copied draft must not reinterpret an old quantity in a new unit.
        var sourceComponents = components(id);
        var ids = new TreeSet<Long>();
        ids.add(n(d, "product_id"));
        sourceComponents.forEach(c -> ids.add(n(c, "material_id")));
        var materials = new HashMap<Long, Material>();
        for (long material : ids) materials.put(material, catalog.availableMaterial(material));
        identity(materials.get(n(d, "product_id")), d.get("product_unit"), n(d, "product_unit_id"));
        for (var c : sourceComponents)
            identity(materials.get(n(c, "material_id")), c.get("unit"), n(c, "unit_id"));
        var parts =
                sourceComponents.stream()
                        .map(
                                c ->
                                        new Component(
                                                n(c, "material_id"),
                                                new BigDecimal(c.get("quantity").toString())))
                        .toList();
        long target =
                insert(
                        new Draft(
                                in.idempotencyKey(),
                                null,
                                n(d, "product_id"),
                                in.versionLabel(),
                                new BigDecimal(d.get("base_quantity").toString()),
                                d.get("description").toString(),
                                parts,
                                in.reason()),
                        id);
        audit(target, "COPY", in.reason(), null);
        remember(in.idempotencyKey(), hash, target);
        return detail(target);
    }

    public Map<String, Object> action(long id, String action, Action in) {
        if (!Set.of("publish", "disable").contains(action)) throw invalid("不支持的 BOM 操作");
        lock();
        String hash = hash(action, id, in);
        Long old = replay(in.idempotencyKey(), hash);
        if (old != null) return detail(old);
        var d = snapshot(id);
        version(d, in.version());
        required(in.reason(), "原因", 500);
        if (action.equals("publish")) {
            state(d, "DRAFT");
            var parts = components(id);
            if (parts.isEmpty()) throw conflict("BOM 至少需要一个组件");
            var ids = new TreeSet<Long>();
            ids.add(n(d, "product_id"));
            parts.forEach(c -> ids.add(n(c, "material_id")));
            var materials = new HashMap<Long, Material>();
            for (long material : ids) materials.put(material, catalog.referenceMaterial(material));
            identity(
                    materials.get(n(d, "product_id")),
                    d.get("product_unit"),
                    n(d, "product_unit_id"));
            for (var c : parts)
                identity(materials.get(n(c, "material_id")), c.get("unit"), n(c, "unit_id"));
            rejectCycle(n(d, "product_id"), parts);
            db.sql(
                            "UPDATE mfg_bom SET status='PUBLISHED',published_by=?,published_at=?,version=version+1,updated_at=? WHERE id=?")
                    .params(actor.currentActor(), now(), now(), id)
                    .update();
        } else {
            state(d, "PUBLISHED");
            db.sql(
                            "UPDATE mfg_bom SET status='DISABLED',disabled_by=?,disabled_at=?,version=version+1,updated_at=? WHERE id=?")
                    .params(actor.currentActor(), now(), now(), id)
                    .update();
        }
        audit(id, action.toUpperCase(Locale.ROOT), in.reason(), d);
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    private long insert(Draft in, Long copiedFrom) {
        var materials = validate(in);
        var p = materials.get(in.productId());
        var key = new GeneratedKeyHolder();
        db.sql(
                        "INSERT INTO mfg_bom(product_id,product_code,product_name,product_unit,product_unit_id,version_label,base_quantity,description,copied_from_id,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
                .params(
                        in.productId(),
                        p.code(),
                        p.name(),
                        p.unit(),
                        p.unitId(),
                        in.versionLabel(),
                        in.baseQuantity(),
                        required(in.description(), "说明", 500),
                        copiedFrom,
                        actor.currentActor(),
                        now(),
                        now())
                .update(key);
        long id = key.getKey().longValue();
        saveComponents(id, in.components(), materials);
        return id;
    }

    private Map<Long, Material> validate(Draft in) {
        var ids = new TreeSet<Long>();
        ids.add(in.productId());
        for (var c : in.components()) {
            if (c.materialId() == in.productId()) throw invalid("产品不能作为自身组件");
            if (!ids.add(c.materialId())) throw invalid("组件不能重复，请合并同物料用量");
        }
        var materials = new HashMap<Long, Material>();
        for (long id : ids) materials.put(id, catalog.availableMaterial(id));
        return materials;
    }

    private void saveComponents(long id, List<Component> parts, Map<Long, Material> materials) {
        for (var c : parts) {
            var m = materials.get(c.materialId());
            db.sql(
                            "INSERT INTO mfg_bom_component(bom_id,material_id,material_code,material_name,unit,unit_id,quantity) VALUES(?,?,?,?,?,?,?)")
                    .params(id, m.id(), m.code(), m.name(), m.unit(), m.unitId(), c.quantity())
                    .update();
        }
    }

    private void identity(Material m, Object unit, long unitId) {
        if (m.unitId() != unitId || !m.unit().equals(unit))
            throw conflict("物料 " + m.code() + " 的基本单位已变化，请重新编辑草稿并核对用量后发布");
    }

    private void rejectCycle(long product, List<Map<String, Object>> parts) {
        var graph = new HashMap<Long, Set<Long>>();
        for (var edge :
                db.sql(
                                "SELECT b.product_id,c.material_id FROM mfg_bom b JOIN mfg_bom_component c ON c.bom_id=b.id WHERE b.status='PUBLISHED'")
                        .query()
                        .listOfRows())
            graph.computeIfAbsent(n(edge, "product_id"), ignored -> new HashSet<>())
                    .add(n(edge, "material_id"));
        var pending = new ArrayDeque<Long>();
        parts.forEach(c -> pending.add(n(c, "material_id")));
        var seen = new HashSet<Long>();
        while (!pending.isEmpty()) {
            long node = pending.removeFirst();
            if (node == product) throw conflict("组件与已发布 BOM 形成循环引用，请核对版本依赖");
            if (seen.add(node)) pending.addAll(graph.getOrDefault(node, Set.of()));
        }
    }

    private void lock() {
        db.sql("SELECT id FROM mfg_bom_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
    }

    private Map<String, Object> header(long id) {
        return db.sql("SELECT * FROM mfg_bom WHERE id=?").param(id).query().listOfRows().stream()
                .findFirst()
                .map(this::present)
                .orElseThrow(() -> new BusinessException(404, "BOM_NOT_FOUND", "BOM 版本不存在"));
    }

    private Map<String, Object> present(Map<String, Object> row) {
        row.replaceAll((k, v) -> v instanceof BigDecimal number ? number.toPlainString() : v);
        return row;
    }

    private List<Map<String, Object>> components(long id) {
        return db
                .sql("SELECT * FROM mfg_bom_component WHERE bom_id=? ORDER BY material_id")
                .param(id)
                .query()
                .listOfRows()
                .stream()
                .map(this::present)
                .toList();
    }

    private Map<String, Object> snapshot(long id) {
        var d = header(id);
        d.put("components", components(id));
        return d;
    }

    private void audit(long id, String action, String reason, Map<String, Object> before) {
        db.sql(
                        "INSERT INTO mfg_bom_audit(bom_id,action,reason,before_state,after_state,created_by,created_at) VALUES(?,?,?,?,?,?,?)")
                .params(
                        id,
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
                db.sql("SELECT request_hash,bom_id FROM mfg_bom_command WHERE request_key=?")
                        .param(key)
                        .query()
                        .listOfRows();
        if (rows.isEmpty()) return null;
        if (!hash.equals(rows.getFirst().get("request_hash"))) throw conflict("请求键已用于其他载荷、操作者或动作");
        return n(rows.getFirst(), "bom_id");
    }

    private void remember(String key, String hash, long id) {
        db.sql(
                        "INSERT INTO mfg_bom_command(request_key,request_hash,bom_id,created_at) VALUES(?,?,?,?)")
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
        if (expected == null || n(d, "version") != expected) throw conflict("版本已变化，请刷新核对");
    }

    private void state(Map<String, Object> d, String expected) {
        if (!expected.equals(d.get("status"))) throw conflict("当前状态不允许此操作；已发布内容不可修改，请复制新版本");
    }

    private long n(Map<String, Object> d, String key) {
        return ((Number) d.get(key)).longValue();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private BusinessException conflict(String m) {
        return new BusinessException(409, "BOM_CONFLICT", m);
    }

    private BusinessException invalid(String m) {
        return new BusinessException(400, "BOM_INVALID", m);
    }
}
