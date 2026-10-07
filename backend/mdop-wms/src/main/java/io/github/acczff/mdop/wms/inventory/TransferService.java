package io.github.acczff.mdop.wms.inventory;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.masterdata.catalog.CatalogService;
import io.github.acczff.mdop.wms.receiving.ReceivingAccess;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class TransferService {
    public record Input(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @Positive long sourceBalanceId,
            @Positive long targetLocationId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity,
            @NotBlank @Size(max = 500) String reason) {}

    private final JdbcClient db;
    private final ReceivingAccess access;
    private final CatalogService catalog;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public TransferService(
            JdbcClient db,
            ReceivingAccess access,
            CatalogService catalog,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json) {
        this.db = db;
        this.access = access;
        this.catalog = catalog;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
    }

    private static final String SELECT =
            """
        SELECT t.id,t.warehouse_id,t.source_balance_id,t.target_balance_id,
               CAST(t.quantity AS CHAR) AS quantity,t.reason,t.confirmed_by,t.confirmed_at,
               m.code AS material_code,m.name AS material_name,m.unit,s.batch_no,
               a.name AS source_location,b.name AS target_location
        FROM wms_stock_transfer t JOIN wms_inventory_balance s ON s.id=t.source_balance_id
        JOIN wms_inventory_balance d ON d.id=t.target_balance_id
        JOIN mdm_location a ON a.id=s.location_id JOIN mdm_location b ON b.id=d.location_id
        JOIN mdm_material m ON m.id=s.material_id
        """;

    @Transactional(readOnly = true)
    public InventoryService.Page list(long warehouseId, int page, int size) {
        access.requireWarehouse(warehouseId);
        long total =
                db.sql("SELECT COUNT(*) FROM wms_stock_transfer WHERE warehouse_id=?")
                        .param(warehouseId)
                        .query(Long.class)
                        .single();
        var rows =
                db.sql(SELECT + " WHERE t.warehouse_id=? ORDER BY t.id DESC LIMIT ? OFFSET ?")
                        .params(warehouseId, size, (long) page * size)
                        .query()
                        .listOfRows();
        return new InventoryService.Page(rows, page, size, total, (total + size - 1) / size);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(long id) {
        var row =
                db.sql(SELECT + " WHERE t.id=?").param(id).query().listOfRows().stream()
                        .findFirst()
                        .orElseThrow(
                                () -> new BusinessException(404, "TRANSFER_NOT_FOUND", "移库单不存在"));
        access.requireWarehouse(number(row, "warehouse_id"));
        return row;
    }

    public Map<String, Object> confirm(Input input) {
        var reference = balance(input.sourceBalanceId(), false);
        long warehouse = number(reference, "warehouse_id");
        access.requireWarehouse(warehouse);
        // Existing receiving/quality writers also lock the warehouse before inventory balances.
        db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                .param(warehouse)
                .query(Long.class)
                .single();
        String digest =
                hash(
                        Arrays.asList(
                                actor.currentActor(),
                                input.sourceBalanceId(),
                                input.targetLocationId(),
                                input.quantity().stripTrailingZeros().toPlainString(),
                                input.reason().trim()));
        var old =
                db.sql("SELECT id,request_hash FROM wms_stock_transfer WHERE request_key=?")
                        .param(input.idempotencyKey())
                        .query()
                        .listOfRows();
        if (!old.isEmpty()) {
            if (!digest.equals(old.getFirst().get("request_hash"))) throw conflict("幂等键已用于不同移库请求");
            return detail(number(old.getFirst(), "id"));
        }
        catalog.requireReceivingWarehouse(warehouse);
        var source = balance(input.sourceBalanceId(), true);
        var target =
                db
                        .sql("SELECT warehouse_id,area_type FROM mdm_location WHERE id=?")
                        .param(input.targetLocationId())
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> conflict("目标库位不存在"));
        String sourceArea =
                db.sql("SELECT area_type FROM mdm_location WHERE id=?")
                        .param(number(source, "location_id"))
                        .query(String.class)
                        .single();
        if (number(target, "warehouse_id") != warehouse
                || !"STORAGE".equals(target.get("area_type"))
                || !"STORAGE".equals(sourceArea)
                || number(source, "location_id") == input.targetLocationId())
            throw conflict("移库仅允许同仓不同正式存储库位之间操作");
        if (!"QUALIFIED".equals(source.get("quality_status"))) throw conflict("仅合格可用库存允许移库");
        LocalDate expiry = date(source, "expiry_date");
        if (expiry != null && expiry.isBefore(LocalDate.now(clock))) throw conflict("过期库存不允许普通移库");
        StockFreeze.requireUnfrozen(source);
        if (input.quantity().compareTo((BigDecimal) source.get("available_qty")) > 0)
            throw conflict("可用库存不足，请刷新库存后重试");
        String stockKey =
                hash(
                        Arrays.asList(
                                warehouse,
                                input.targetLocationId(),
                                number(source, "material_id"),
                                source.get("supplier_id"),
                                source.get("batch_no"),
                                source.get("date_code"),
                                date(source, "production_date"),
                                expiry,
                                source.get("quality_status"),
                                source.get("owner_type"),
                                number(source, "owner_id")));
        db.sql(
                        """
            INSERT INTO wms_inventory_balance(stock_key,warehouse_id,location_id,material_id,supplier_id,
            batch_no,date_code,production_date,expiry_date,quality_status,owner_type,owner_id,origin_type)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE stock_key=stock_key
            """)
                .params(
                        stockKey,
                        warehouse,
                        input.targetLocationId(),
                        source.get("material_id"),
                        source.get("supplier_id"),
                        source.get("batch_no"),
                        source.get("date_code"),
                        date(source, "production_date"),
                        expiry,
                        source.get("quality_status"),
                        source.get("owner_type"),
                        source.get("owner_id"),
                        source.get("origin_type"))
                .update();
        var destination =
                db.sql("SELECT * FROM wms_inventory_balance WHERE stock_key=? FOR UPDATE")
                        .param(stockKey)
                        .query()
                        .singleRow();
        db.sql(
                        """
            INSERT INTO wms_stock_transfer(warehouse_id,source_balance_id,target_balance_id,quantity,reason,
            request_key,request_hash,confirmed_by,confirmed_at) VALUES (?,?,?,?,?,?,?,?,?)
            """)
                .params(
                        warehouse,
                        input.sourceBalanceId(),
                        number(destination, "id"),
                        input.quantity(),
                        input.reason().trim(),
                        input.idempotencyKey(),
                        digest,
                        actor.currentActor(),
                        Timestamp.from(clock.instant()))
                .update();
        long transfer =
                db.sql("SELECT id FROM wms_stock_transfer WHERE request_key=?")
                        .param(input.idempotencyKey())
                        .query(Long.class)
                        .single();
        change(source, input.quantity().negate(), transfer, "TRANSFER_OUT");
        change(destination, input.quantity(), transfer, "TRANSFER_IN");
        return detail(transfer);
    }

    private void change(Map<String, Object> balance, BigDecimal delta, long transfer, String type) {
        BigDecimal before = (BigDecimal) balance.get("on_hand_qty");
        BigDecimal after = before.add(delta);
        long id = number(balance, "id");
        db.sql(
                        "UPDATE wms_inventory_balance SET on_hand_qty=?,available_qty=IF(active_freeze_id IS NULL,available_qty+?,0) WHERE id=?")
                .params(after, delta, id)
                .update();
        db.sql(
                        """
            INSERT INTO wms_inventory_transaction(balance_id,before_qty,change_qty,after_qty,created_by,
            created_at,transaction_type,transfer_id) VALUES (?,?,?,?,?,?,?,?)
            """)
                .params(
                        id,
                        before,
                        delta,
                        after,
                        actor.currentActor(),
                        Timestamp.from(clock.instant()),
                        type,
                        transfer)
                .update();
    }

    private Map<String, Object> balance(long id, boolean lock) {
        return db
                .sql("SELECT * FROM wms_inventory_balance WHERE id=?" + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "STOCK_NOT_FOUND", "库存记录不存在"));
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private LocalDate date(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : LocalDate.parse(value.toString());
    }

    private String hash(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            json.writeValueAsString(value)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(409, "TRANSFER_CONFLICT", message);
    }
}
