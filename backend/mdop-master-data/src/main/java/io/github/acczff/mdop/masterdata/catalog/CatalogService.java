package io.github.acczff.mdop.masterdata.catalog;

import static io.github.acczff.mdop.common.BusinessText.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class CatalogService {
    private final JdbcClient jdbc;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public CatalogService(
            JdbcClient jdbc, CurrentActorProvider actor, Clock clock, ObjectMapper json) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
    }

    public enum Tracking {
        QUANTITY,
        BATCH
    }

    public enum Area {
        RECEIVING,
        INSPECTION,
        STORAGE
    }

    public record Supplier(long id, String code, String name, String status, long version) {}

    public record Material(
            long id,
            String code,
            String name,
            String unit,
            Tracking trackingMode,
            boolean requireDateCode,
            boolean requireExpiry,
            long unitId,
            String status,
            long version,
            boolean identityLocked) {}

    public record Location(long id, long warehouseId, String code, String name, Area areaType) {}

    @Transactional(readOnly = true)
    public List<Supplier> suppliers() {
        return jdbc.sql("SELECT * FROM mdm_supplier ORDER BY code LIMIT 1000")
                .query(Supplier.class)
                .list();
    }

    @Transactional(readOnly = true)
    public List<Material> materials() {
        return jdbc.sql("SELECT * FROM mdm_material ORDER BY code LIMIT 1000")
                .query(Material.class)
                .list();
    }

    @Transactional(readOnly = true)
    public List<Location> locations() {
        return jdbc.sql("SELECT * FROM mdm_location ORDER BY warehouse_id,code LIMIT 1000")
                .query(Location.class)
                .list();
    }

    public Supplier supplier(long id) {
        return jdbc.sql("SELECT * FROM mdm_supplier WHERE id=?")
                .param(id)
                .query(Supplier.class)
                .optional()
                .orElseThrow(() -> missing("供应商"));
    }

    public Material material(long id) {
        return jdbc.sql("SELECT * FROM mdm_material WHERE id=?")
                .param(id)
                .query(Material.class)
                .optional()
                .orElseThrow(() -> missing("物料"));
    }

    public Location receivingLocation(long id, long warehouseId) {
        var location =
                jdbc.sql("SELECT * FROM mdm_location WHERE id=?")
                        .param(id)
                        .query(Location.class)
                        .optional()
                        .orElseThrow(() -> missing("库位"));
        if (location.warehouseId() != warehouseId || location.areaType() == Area.STORAGE) {
            throw new BusinessException(409, "INVALID_RECEIVING_LOCATION", "待检物料必须进入本仓库收货暂存区或待检区");
        }
        return location;
    }

    // Lock held until the caller's transaction commits: a concurrent disable cannot slip through.
    public void requireReceivingWarehouse(long id) {
        var status =
                jdbc.sql(
                                "SELECT status FROM mdm_warehouse WHERE id=? AND deleted_at IS NULL FOR SHARE")
                        .param(id)
                        .query(String.class)
                        .optional()
                        .orElseThrow(() -> missing("仓库"));
        if (!status.equals("ENABLED"))
            throw new BusinessException(409, "WAREHOUSE_DISABLED", "仓库已停用，不能用于收货");
    }

    public Supplier createSupplier(String code, String name) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO mdm_supplier(code,name,created_by,created_at) VALUES (?,?,?,?)")
                .params(
                        code(code),
                        required(name, "供应商名称", 100),
                        actor.currentActor(),
                        Timestamp.from(clock.instant()))
                .update(keys);
        long id = keys.getKey().longValue();
        audit("suppliers", id, "CREATE", null, supplier(id), "创建资料");
        return supplier(id);
    }

    public Material createMaterial(
            String code,
            String name,
            String unit,
            Tracking tracking,
            boolean dateCode,
            boolean expiry) {
        // Legacy callers may pass an exact registered label, but cannot silently create a unit.
        var unitRow =
                jdbc.sql("SELECT id FROM mdm_unit WHERE CAST(name AS BINARY)=CAST(? AS BINARY)")
                        .param(required(unit, "单位", 16))
                        .query(Long.class)
                        .optional()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                409, "UNIT_NOT_REGISTERED", "请先维护基本单位，再选择已有单位"));
        return createMaterialWithUnit(code, name, unitRow, tracking, dateCode, expiry);
    }

    public Material createMaterialWithUnit(
            String code,
            String name,
            long unitId,
            Tracking tracking,
            boolean dateCode,
            boolean expiry) {
        var unit = activeUnit(unitId);
        var keys = new GeneratedKeyHolder();
        jdbc.sql(
                        "INSERT INTO mdm_material(code,name,unit,unit_id,tracking_mode,require_date_code,require_expiry,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?)")
                .params(
                        code(code),
                        required(name, "物料名称", 100),
                        unit.get("name"),
                        unitId,
                        tracking.name(),
                        dateCode,
                        expiry,
                        actor.currentActor(),
                        Timestamp.from(clock.instant()))
                .update(keys);
        long id = keys.getKey().longValue();
        audit("materials", id, "CREATE", null, material(id), "创建资料");
        return material(id);
    }

    /** Only call when authorizing a new document, never when reading or finishing an old one. */
    public Supplier referenceSupplier(long id) {
        var value =
                jdbc.sql("SELECT * FROM mdm_supplier WHERE id=? FOR SHARE")
                        .param(id)
                        .query(Supplier.class)
                        .optional()
                        .orElseThrow(() -> missing("供应商"));
        requireEnabled(value.status());
        return value;
    }

    public Map<String, Object> referenceCustomer(long id) {
        var value =
                jdbc.sql("SELECT * FROM mdm_customer WHERE id=? FOR SHARE")
                        .param(id)
                        .query(new ColumnMapRowMapper())
                        .optional()
                        .orElseThrow(() -> missing("客户"));
        requireEnabled(value.get("status").toString());
        return value;
    }

    public Material referenceMaterial(long id) {
        var value =
                jdbc.sql("SELECT * FROM mdm_material WHERE id=? FOR UPDATE")
                        .param(id)
                        .query(Material.class)
                        .optional()
                        .orElseThrow(() -> missing("物料"));
        requireEnabled(value.status());
        activeUnit(value.unitId());
        if (!value.identityLocked())
            jdbc.sql("UPDATE mdm_material SET identity_locked=TRUE,version=version+1 WHERE id=?")
                    .param(id)
                    .update();
        return material(id);
    }

    /** Validate a draft without making its material identity a business authorization. */
    public Material availableMaterial(long id) {
        var value =
                jdbc.sql("SELECT * FROM mdm_material WHERE id=? FOR SHARE")
                        .param(id)
                        .query(Material.class)
                        .optional()
                        .orElseThrow(() -> missing("物料"));
        requireEnabled(value.status());
        activeUnit(value.unitId());
        return value;
    }

    public record Edit(
            @NotNull @PositiveOrZero Long version,
            String name,
            String status,
            Long unitId,
            Tracking trackingMode,
            Boolean requireDateCode,
            Boolean requireExpiry,
            String reason) {}

    @Transactional(readOnly = true)
    public List<Map<String, Object>> directory(String kind) {
        String table = table(kind);
        return jdbc.sql("SELECT * FROM " + table + " ORDER BY code LIMIT 1000")
                .query()
                .listOfRows();
    }

    public Map<String, Object> createDirectory(String kind, String code, String name) {
        if (!List.of("customers", "units", "organizations").contains(kind)) throw missing("资料类型");
        String table = table(kind);
        String value = required(name, "名称", kind.equals("units") ? 16 : 100);
        long id;
        if (kind.equals("organizations")) {
            jdbc.sql("INSERT INTO mdm_organization(id,code,name) VALUES(1,?,?)")
                    .params(code(code), value)
                    .update();
            id = 1;
        } else {
            var keys = new GeneratedKeyHolder();
            if (kind.equals("units"))
                jdbc.sql("INSERT INTO mdm_unit(code,name) VALUES(?,?)")
                        .params(code(code), value)
                        .update(keys);
            else
                jdbc.sql(
                                "INSERT INTO mdm_customer(code,name,created_by,created_at) VALUES(?,?,?,?)")
                        .params(
                                code(code),
                                value,
                                actor.currentActor(),
                                Timestamp.from(clock.instant()))
                        .update(keys);
            id = keys.getKey().longValue();
        }
        var result = row(table, id, false);
        audit(kind, id, "CREATE", null, result, "创建资料");
        return result;
    }

    public Object edit(String kind, long id, Edit input) {
        String table = table(kind);
        var before = row(table, id, true);
        if (input.version() == null
                || input.version() < 0
                || ((Number) before.get("version")).longValue() != input.version())
            throw new BusinessException(409, "CATALOG_VERSION_CONFLICT", "资料已变化，请刷新核对最新状态");
        String name = required(input.name(), "名称", kind.equals("units") ? 16 : 100);
        String reason = required(input.reason(), "变更原因", 500);
        String status = input.status();
        if (!kind.equals("organizations")
                && !List.of("ENABLED", "DISABLED").contains(status == null ? "" : status))
            throw new BusinessException(400, "INVALID_STATUS", "请选择启用或停用状态");
        if (kind.equals("materials")) {
            var m = material(id);
            if (input.unitId() == null
                    || input.trackingMode() == null
                    || input.requireDateCode() == null
                    || input.requireExpiry() == null)
                throw new BusinessException(400, "INVALID_MATERIAL", "请选择基本单位和追踪方式");
            boolean changed =
                    m.unitId() != input.unitId()
                            || m.trackingMode() != input.trackingMode()
                            || m.requireDateCode() != input.requireDateCode()
                            || m.requireExpiry() != input.requireExpiry();
            if (changed && m.identityLocked())
                throw new BusinessException(
                        409, "MATERIAL_IDENTITY_LOCKED", "物料已被业务引用，单位和追踪策略不能修改");
            String unit = changed ? activeUnit(input.unitId()).get("name").toString() : m.unit();
            jdbc.sql(
                            "UPDATE mdm_material SET name=?,status=?,unit=?,unit_id=?,tracking_mode=?,require_date_code=?,require_expiry=?,version=version+1 WHERE id=?")
                    .params(
                            name,
                            status,
                            unit,
                            input.unitId(),
                            input.trackingMode().name(),
                            input.requireDateCode(),
                            input.requireExpiry(),
                            id)
                    .update();
        } else if (kind.equals("organizations")) {
            jdbc.sql("UPDATE mdm_organization SET name=?,version=version+1 WHERE id=?")
                    .params(name, id)
                    .update();
        } else {
            if (kind.equals("units")
                    && !name.equals(before.get("name"))
                    && jdbc.sql("SELECT COUNT(*) FROM mdm_material WHERE unit_id=?")
                                    .param(id)
                                    .query(Long.class)
                                    .single()
                            > 0)
                throw new BusinessException(409, "UNIT_IN_USE", "单位已被物料引用，名称不可改变；请创建新单位");
            jdbc.sql("UPDATE " + table + " SET name=?,status=?,version=version+1 WHERE id=?")
                    .params(name, status, id)
                    .update();
        }
        Object after = kind.equals("materials") ? material(id) : row(table, id, false);
        audit(kind, id, "UPDATE", before, after, reason);
        return after;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String kind, long id) {
        row(table(kind), id, false);
        return jdbc.sql(
                        "SELECT id,action_type,before_state,after_state,reason,created_by,created_at FROM mdm_catalog_audit WHERE entity_type=? AND entity_id=? ORDER BY id DESC LIMIT 1000")
                .params(kind, id)
                .query()
                .listOfRows();
    }

    private Map<String, Object> activeUnit(long id) {
        var value =
                jdbc.sql("SELECT * FROM mdm_unit WHERE id=? FOR SHARE")
                        .param(id)
                        .query(new ColumnMapRowMapper())
                        .optional()
                        .orElseThrow(() -> missing("单位"));
        requireEnabled(value.get("status").toString());
        return value;
    }

    private void requireEnabled(String status) {
        if (!"ENABLED".equals(status))
            throw new BusinessException(
                    409, "MASTER_DATA_DISABLED", "资料或基本单位已停用，不能用于新的业务授权；已有单据请按原流程收尾");
    }

    private Map<String, Object> row(String table, long id, boolean locked) {
        return jdbc.sql("SELECT * FROM " + table + " WHERE id=?" + (locked ? " FOR UPDATE" : ""))
                .param(id)
                .query(new ColumnMapRowMapper())
                .optional()
                .orElseThrow(() -> missing("资料"));
    }

    private String table(String kind) {
        return switch (kind) {
            case "suppliers" -> "mdm_supplier";
            case "customers" -> "mdm_customer";
            case "units" -> "mdm_unit";
            case "organizations" -> "mdm_organization";
            case "materials" -> "mdm_material";
            default -> throw missing("资料类型");
        };
    }

    private void audit(
            String kind, long id, String action, Object before, Object after, String reason) {
        jdbc.sql(
                        "INSERT INTO mdm_catalog_audit(entity_type,entity_id,action_type,before_state,after_state,reason,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)")
                .params(
                        kind,
                        id,
                        action,
                        before == null ? null : json.writeValueAsString(before),
                        json.writeValueAsString(after),
                        reason,
                        actor.currentActor(),
                        Timestamp.from(clock.instant()))
                .update();
    }

    public Location createLocation(long warehouseId, String code, String name, Area area) {
        requireReceivingWarehouse(warehouseId);
        var keys = new GeneratedKeyHolder();
        jdbc.sql(
                        "INSERT INTO mdm_location(warehouse_id,code,name,area_type,created_by,created_at) VALUES (?,?,?,?,?,?)")
                .params(
                        warehouseId,
                        code(code),
                        required(name, "库位名称", 100),
                        area.name(),
                        actor.currentActor(),
                        Timestamp.from(clock.instant()))
                .update(keys);
        return jdbc.sql("SELECT * FROM mdm_location WHERE id=?")
                .param(keys.getKey().longValue())
                .query(Location.class)
                .single();
    }

    private BusinessException missing(String name) {
        return new BusinessException(404, "MASTER_DATA_NOT_FOUND", name + "不存在");
    }
}
