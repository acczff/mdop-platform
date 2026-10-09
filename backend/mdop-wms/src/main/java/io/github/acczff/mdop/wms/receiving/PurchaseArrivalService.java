package io.github.acczff.mdop.wms.receiving;

import static io.github.acczff.mdop.common.purchasing.PurchaseReceivingPort.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.masterdata.catalog.CatalogService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class PurchaseArrivalService {
    private final JdbcClient db;
    private final CatalogService catalog;
    private final ReceivingAccess access;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public PurchaseArrivalService(
            JdbcClient db,
            CatalogService catalog,
            ReceivingAccess access,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json) {
        this.db = db;
        this.catalog = catalog;
        this.access = access;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
    }

    public Delivery deliver(Notice input) {
        access.requireWarehouse(input.warehouseId());
        // The adapter joins the caller's transaction and warehouse lock. Replays are identity
        // based.
        var old =
                db.sql(
                                "SELECT * FROM wms_arrival_notice WHERE purchase_arrangement_id=? FOR UPDATE")
                        .param(input.arrangementId())
                        .query()
                        .listOfRows();
        String hash = digest(input);
        if (!old.isEmpty()) {
            if (!hash.equals(old.getFirst().get("purchase_payload_hash")))
                throw conflict("到货安排载荷不一致");
            if ("WITHDRAWN".equals(old.getFirst().get("status"))) throw conflict("到货安排已经撤回");
            return view(input.warehouseId(), input.arrangementId());
        }
        catalog.requireReceivingWarehouse(input.warehouseId());
        var supplier = catalog.referenceSupplier(input.supplierId());
        var key = new GeneratedKeyHolder();
        db.sql(
                        "INSERT INTO wms_arrival_notice(source_system,external_notice_no,purchase_order_no,supplier_id,warehouse_id,created_by,created_at,supplier_code,supplier_name,purchase_arrangement_id,purchase_payload_hash) VALUES('MDOP_PURCHASING',?,?,?,?,?,?,?,?,?,?)")
                .params(
                        input.number(),
                        input.orderNumber(),
                        input.supplierId(),
                        input.warehouseId(),
                        actor.currentActor(),
                        Timestamp.from(clock.instant()),
                        supplier.code(),
                        input.supplierName(),
                        input.arrangementId(),
                        hash)
                .update(key);
        long id = key.getKey().longValue();
        for (var line :
                input.lines().stream()
                        .sorted(Comparator.comparingLong(Line::materialId))
                        .toList()) {
            var material = catalog.referenceMaterial(line.materialId());
            if (!material.unit().equals(line.unit())) throw conflict("采购物料单位与授权快照不一致");
            db.sql(
                            "INSERT INTO wms_arrival_notice_item(arrival_id,material_id,material_code,material_name,unit,notice_qty,purchase_arrangement_line_id,purchase_order_line_id) VALUES(?,?,?,?,?,?,?,?)")
                    .params(
                            id,
                            line.materialId(),
                            line.code(),
                            line.name(),
                            line.unit(),
                            line.quantity(),
                            line.arrangementLineId(),
                            line.orderLineId())
                    .update();
        }
        return view(input.warehouseId(), input.arrangementId());
    }

    @Transactional(readOnly = true)
    public Delivery view(long warehouse, long arrangement) {
        access.requireWarehouse(warehouse);
        var row = notice(warehouse, arrangement, false);
        long id = ((Number) row.get("id")).longValue();
        var lines =
                db.sql(
                                "SELECT purchase_arrangement_line_id, id, CAST(notice_qty AS CHAR), CAST(received_qty AS CHAR) FROM wms_arrival_notice_item WHERE arrival_id=? ORDER BY id")
                        .param(id)
                        .query(
                                (rs, i) ->
                                        new Mapping(
                                                rs.getLong(1),
                                                rs.getLong(2),
                                                rs.getString(3),
                                                rs.getString(4)))
                        .list();
        var receipts =
                db.sql(
                                "SELECT id,receipt_no,status FROM wms_receipt WHERE arrival_id=? ORDER BY id")
                        .param(id)
                        .query(
                                (rs, i) ->
                                        new Receipt(
                                                rs.getLong(1), rs.getString(2), rs.getString(3)))
                        .list();
        return new Delivery(id, row.get("status").toString(), lines, receipts);
    }

    public void withdraw(long warehouse, long arrangement, String reason) {
        access.requireWarehouse(warehouse);
        var row = notice(warehouse, arrangement, true);
        if ("WITHDRAWN".equals(row.get("status"))) return;
        long id = ((Number) row.get("id")).longValue();
        if (db.sql("SELECT COUNT(*) FROM wms_receipt WHERE arrival_id=?")
                        .param(id)
                        .query(Long.class)
                        .single()
                > 0) throw conflict("WMS 已存在收货记录（含草稿），不能撤回到货安排");
        if (!"PENDING_RECEIPT".equals(row.get("status"))) throw conflict("WMS 已进入收货处理，不能撤回");
        db.sql(
                        "UPDATE wms_arrival_notice SET status='WITHDRAWN',version=version+1,withdrawn_by=?,withdrawn_reason=?,withdrawn_at=? WHERE id=?")
                .params(actor.currentActor(), reason, Timestamp.from(clock.instant()), id)
                .update();
    }

    @Transactional(readOnly = true)
    public Fulfillment fulfillment(long warehouse, long arrangement) {
        access.requireWarehouse(warehouse);
        long arrival = ((Number) notice(warehouse, arrangement, false).get("id")).longValue();
        var facts =
                db.sql(
                                """
            SELECT a.purchase_arrangement_line_id,r.id,r.receipt_no,i.id,r.status,
                   r.correction_status,r.downstream_stage,CAST(i.quantity AS CHAR),
                   q.reference_no,CAST(qi.qualified_qty AS CHAR),CAST(qi.rejected_qty AS CHAR),
                   qi.putaway_location_id
            FROM wms_receipt r JOIN wms_receipt_item i ON i.receipt_id=r.id
            JOIN wms_arrival_notice_item a ON a.id=i.arrival_item_id
            LEFT JOIN wms_quality_result q ON q.receipt_id=r.id
            LEFT JOIN wms_quality_item qi ON qi.receipt_item_id=i.id
            WHERE r.arrival_id=? ORDER BY i.id
            """)
                        .param(arrival)
                        .query(
                                (rs, n) ->
                                        new Fact(
                                                rs.getLong(1),
                                                rs.getLong(2),
                                                rs.getString(3),
                                                rs.getLong(4),
                                                rs.getString(5),
                                                rs.getString(6),
                                                rs.getString(7),
                                                rs.getString(8),
                                                rs.getString(9),
                                                rs.getString(10),
                                                rs.getString(11),
                                                rs.getObject(12, Long.class)))
                        .list();
        var cases =
                db.sql(
                                "SELECT id,kind,receipt_id,status,reason FROM wms_receiving_case WHERE arrival_id=? ORDER BY id")
                        .param(arrival)
                        .query(
                                (rs, n) ->
                                        new CaseFact(
                                                rs.getLong(1),
                                                rs.getString(2),
                                                rs.getObject(3, Long.class),
                                                rs.getString(4),
                                                rs.getString(5)))
                        .list();
        var returns =
                db.sql(
                                """
            SELECT t.id,t.receipt_item_id,CAST(t.quantity AS CHAR),t.status,t.handover_no
            FROM wms_purchase_return t JOIN wms_receipt r ON r.id=t.receipt_id
            WHERE r.arrival_id=? ORDER BY t.id
            """)
                        .param(arrival)
                        .query(
                                (rs, n) ->
                                        new ReturnFact(
                                                rs.getLong(1),
                                                rs.getLong(2),
                                                rs.getString(3),
                                                rs.getString(4),
                                                rs.getString(5)))
                        .list();
        return new Fulfillment(facts, cases, returns);
    }

    private Map<String, Object> notice(long warehouse, long arrangement, boolean lock) {
        return db
                .sql(
                        "SELECT * FROM wms_arrival_notice WHERE warehouse_id=? AND purchase_arrangement_id=? AND source_system='MDOP_PURCHASING'"
                                + (lock ? " FOR UPDATE" : ""))
                .params(warehouse, arrangement)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> conflict("采购到货映射不存在，请核对"));
    }

    private String digest(Notice notice) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            json.writeValueAsString(notice)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(409, "PURCHASE_ARRIVAL_CONFLICT", message);
    }
}
