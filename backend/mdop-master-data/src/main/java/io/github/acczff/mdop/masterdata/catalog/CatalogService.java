package io.github.acczff.mdop.masterdata.catalog;

import static io.github.acczff.mdop.common.BusinessText.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CatalogService {
    private final JdbcClient jdbc;
    private final CurrentActorProvider actor;
    private final Clock clock;

    public CatalogService(JdbcClient jdbc, CurrentActorProvider actor, Clock clock) {
        this.jdbc = jdbc;
        this.actor = actor;
        this.clock = clock;
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

    public record Supplier(long id, String code, String name) {}

    public record Material(
            long id,
            String code,
            String name,
            String unit,
            Tracking trackingMode,
            boolean requireDateCode,
            boolean requireExpiry) {}

    public record Location(long id, long warehouseId, String code, String name, Area areaType) {}

    @Transactional(readOnly = true)
    public List<Supplier> suppliers() {
        return jdbc.sql("SELECT id,code,name FROM mdm_supplier ORDER BY code LIMIT 1000")
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
        return jdbc.sql("SELECT id,code,name FROM mdm_supplier WHERE id=?")
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
        return supplier(keys.getKey().longValue());
    }

    public Material createMaterial(
            String code,
            String name,
            String unit,
            Tracking tracking,
            boolean dateCode,
            boolean expiry) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(
                        "INSERT INTO mdm_material(code,name,unit,tracking_mode,require_date_code,require_expiry,created_by,created_at) VALUES (?,?,?,?,?,?,?,?)")
                .params(
                        code(code),
                        required(name, "物料名称", 100),
                        required(unit, "单位", 16),
                        tracking.name(),
                        dateCode,
                        expiry,
                        actor.currentActor(),
                        Timestamp.from(clock.instant()))
                .update(keys);
        return material(keys.getKey().longValue());
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
