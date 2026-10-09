package io.github.acczff.mdop.wms.inventory;

import static io.github.acczff.mdop.common.sales.SalesShippingPort.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.masterdata.catalog.CatalogService;
import io.github.acczff.mdop.wms.receiving.ReceivingAccess;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class SalesDispatchService {
    private final JdbcClient db;
    private final SalesService sales;
    private final ReceivingAccess access;
    private final CatalogService catalog;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public SalesDispatchService(
            JdbcClient db,
            SalesService sales,
            ReceivingAccess access,
            CatalogService catalog,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json) {
        this.db = db;
        this.sales = sales;
        this.access = access;
        this.catalog = catalog;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
    }

    public Execution deliver(Dispatch in) {
        access.requireWarehouse(in.warehouseId());
        db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                .param(in.warehouseId())
                .query(Long.class)
                .single();
        String digest;
        try {
            digest =
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(json.writeValueAsBytes(in)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var old =
                db.sql(
                                "SELECT sales_payload_hash,status FROM wms_sales_order WHERE sales_arrangement_id=? FOR UPDATE")
                        .param(in.arrangementId())
                        .query()
                        .listOfRows();
        if (!old.isEmpty()) {
            if (!digest.equals(old.getFirst().get("sales_payload_hash")))
                throw conflict("发货安排载荷不一致");
            if ("CANCELLED".equals(old.getFirst().get("status"))) throw conflict("发货执行单已取消");
            return view(in.warehouseId(), in.arrangementId());
        }
        catalog.requireReceivingWarehouse(in.warehouseId());
        if (!"FINISHED_GOODS"
                .equals(
                        db.sql("SELECT purpose FROM mdm_warehouse WHERE id=?")
                                .param(in.warehouseId())
                                .query(String.class)
                                .single())) throw conflict("销售只支持成品仓");
        var m = catalog.referenceMaterial(in.materialId());
        if (!m.unit().equals(in.unit())) throw conflict("物料单位与销售授权不一致");
        db.sql(
                        "INSERT INTO wms_sales_order(demand_no,sales_order_no,customer_reference,warehouse_id,material_id,quantity,created_by,created_at,material_code,material_name,unit,source_system,sales_arrangement_id,sales_order_line_id,sales_payload_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,'MDOP_SALES',?,?,?)")
                .params(
                        "MDOP-SALES-" + in.arrangementId(),
                        in.orderNumber(),
                        in.customerReference(),
                        in.warehouseId(),
                        in.materialId(),
                        in.quantity(),
                        actor.currentActor(),
                        Timestamp.from(clock.instant()),
                        in.code(),
                        in.name(),
                        in.unit(),
                        in.arrangementId(),
                        in.orderLineId(),
                        digest)
                .update();
        return view(in.warehouseId(), in.arrangementId());
    }

    public Execution view(long warehouse, long arrangement) {
        access.requireWarehouse(warehouse);
        var d =
                db
                        .sql(
                                "SELECT * FROM wms_sales_order WHERE sales_arrangement_id=? AND warehouse_id=? AND source_system='MDOP_SALES'")
                        .params(arrangement, warehouse)
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> conflict("正式销售执行映射缺失"));
        long id = n(d, "id");
        var ledger =
                db.sql(
                                "SELECT COUNT(*) total,COALESCE(SUM(-change_qty),0) quantity,COUNT(CASE WHEN balance_id <=> ? THEN 1 END) matching FROM wms_inventory_transaction WHERE sales_order_id=? AND transaction_type='SALES_OUT'")
                        .params(d.get("source_balance_id"), id)
                        .query()
                        .singleRow();
        return new Execution(
                id,
                arrangement,
                n(d, "sales_order_line_id"),
                warehouse,
                n(d, "material_id"),
                s(d, "material_code"),
                s(d, "unit"),
                s(d, "quantity"),
                s(d, "status"),
                d.get("source_balance_id") == null ? null : n(d, "source_balance_id"),
                d.get("source_balance_id") == null
                        ? null
                        : (String) sales.detail(id).get("batch_no"),
                s(d, "picked_by"),
                s(d, "reviewed_by"),
                s(d, "closed_by"),
                s(d, "closed_at"),
                s(d, "cancel_reason"),
                n(ledger, "total"),
                n(ledger, "total") == n(ledger, "matching"),
                ((BigDecimal) ledger.get("quantity")).setScale(6).toPlainString());
    }

    public void withdraw(long warehouse, long arrangement, String reason) {
        var d = view(warehouse, arrangement);
        if (!"CANCELLED".equals(d.status())) sales.cancel(d.id(), reason);
        var checked = view(warehouse, arrangement);
        if (!"CANCELLED".equals(checked.status()) || checked.ledgerCount() != 0)
            throw conflict("执行单未安全取消，不能释放安排");
    }

    private long n(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private String s(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : row.get(key).toString();
    }

    private BusinessException conflict(String message) {
        return new BusinessException(409, "SALES_DISPATCH_CONFLICT", message);
    }
}
