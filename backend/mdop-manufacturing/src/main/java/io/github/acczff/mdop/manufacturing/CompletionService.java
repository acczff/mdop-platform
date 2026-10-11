package io.github.acczff.mdop.manufacturing;

import static io.github.acczff.mdop.common.BusinessText.required;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.common.manufacturing.ProductionCompletionPort;
import io.github.acczff.mdop.masterdata.catalog.CatalogService;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class CompletionService {
    public record Report(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String reportNo,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity,
            @NotBlank @Size(max = 64) String batchNo,
            @Size(max = 32) String dateCode,
            @NotNull LocalDate productionDate,
            LocalDate expiryDate,
            @NotBlank @Size(max = 500) String reason) {}

    public record Action(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 500) String reason) {}

    private final JdbcClient db;
    private final CatalogService catalog;
    private final ProductionCompletionPort port;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public CompletionService(
            JdbcClient db,
            CatalogService catalog,
            ProductionCompletionPort port,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json) {
        this.db = db;
        this.catalog = catalog;
        this.port = port;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
    }

    private Map<String, Object> order(long id) {
        return db
                .sql(
                        "SELECT o.id,o.order_no,o.status,o.demand_id,d.warehouse_id,d.product_id,d.product_code,d.product_name,d.unit,d.unit_id,d.quantity FROM mfg_order o JOIN mfg_demand d ON d.id=o.demand_id WHERE o.id=?")
                .param(id)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "COMPLETION_NOT_FOUND", "工单不存在"));
    }

    private void access(long id) {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null
                || a.getAuthorities().stream()
                        .noneMatch(p -> p.getAuthority().equals("wms:warehouse:" + id)))
            throw new BusinessException(403, "WAREHOUSE_ACCESS_DENIED", "没有工单成品仓权限");
    }

    private Map<String, Object> lock(long id) {
        var o = order(id);
        access(n(o, "warehouse_id"));
        db.sql("SELECT id FROM mdm_warehouse WHERE id=? FOR UPDATE")
                .param(n(o, "warehouse_id"))
                .query(Long.class)
                .single();
        db.sql("SELECT id FROM mfg_bom_guard WHERE id=1 FOR UPDATE").query(Integer.class).single();
        return order(id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(long id) {
        var o = order(id);
        access(n(o, "warehouse_id"));
        var reports =
                db.sql("SELECT * FROM mfg_completion WHERE order_id=? ORDER BY id DESC")
                        .param(id)
                        .query()
                        .listOfRows();
        var receipts = port.references(id);
        BigDecimal pending = BigDecimal.ZERO, approved = BigDecimal.ZERO;
        for (var r : reports) {
            if ("SUBMITTED".equals(r.get("status"))) pending = pending.add(q(r, "quantity"));
            if ("APPROVED".equals(r.get("status"))) approved = approved.add(q(r, "quantity"));
            receipts.stream()
                    .filter(f -> n(f, "completion_report_id") == n(r, "id"))
                    .findFirst()
                    .ifPresent(f -> r.put("receipt", f));
            r.put("quantity", q(r, "quantity").toPlainString());
            r.put("production_date", date(r, "production_date").toString());
            if (r.get("expiry_date") != null)
                r.put("expiry_date", date(r, "expiry_date").toString());
        }
        return Map.of(
                "orderId",
                id,
                "orderNo",
                o.get("order_no"),
                "orderStatus",
                o.get("status"),
                "authorized",
                q(o, "quantity").toPlainString(),
                "unit",
                o.get("unit"),
                "pending",
                pending.toPlainString(),
                "approved",
                approved.toPlainString(),
                "remaining",
                q(o, "quantity").subtract(pending).subtract(approved).toPlainString(),
                "reports",
                reports);
    }

    public Map<String, Object> report(long id, Report in) {
        var o = lock(id);
        String hash = hash("REPORT", id, in);
        if (replay(in.idempotencyKey(), hash)) return detail(id);
        active(o);
        validate(o, in.dateCode(), in.productionDate(), in.expiryDate());
        BigDecimal used =
                db.sql(
                                "SELECT COALESCE(SUM(quantity),0) FROM mfg_completion WHERE order_id=? AND status IN('SUBMITTED','APPROVED')")
                        .param(id)
                        .query(BigDecimal.class)
                        .single();
        if (used.add(in.quantity()).compareTo(q(o, "quantity")) > 0)
            throw conflict("累计完工登记超过工单授权，待复核数量也占用额度");
        db.sql(
                        "INSERT INTO mfg_completion(order_id,report_no,quantity,batch_no,date_code,production_date,expiry_date,reason,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)")
                .params(
                        id,
                        in.reportNo(),
                        in.quantity(),
                        required(in.batchNo(), "批次", 64),
                        in.dateCode() == null ? "" : in.dateCode().trim(),
                        in.productionDate(),
                        in.expiryDate(),
                        required(in.reason(), "登记依据", 500),
                        actor.currentActor(),
                        now())
                .update();
        long rid =
                db.sql("SELECT id FROM mfg_completion WHERE report_no=?")
                        .param(in.reportNo())
                        .query(Long.class)
                        .single();
        remember(in.idempotencyKey(), hash, rid);
        return detail(id);
    }

    public Map<String, Object> action(long id, long reportId, String action, Action in) {
        var o = lock(id);
        String hash = hash(action, List.of(id, reportId), in);
        if (replay(in.idempotencyKey(), hash)) return detail(id);
        var r =
                db
                        .sql("SELECT * FROM mfg_completion WHERE id=? AND order_id=? FOR UPDATE")
                        .params(reportId, id)
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                404, "COMPLETION_NOT_FOUND", "完工报告不属于此工单"));
        if (n(r, "version") != in.version() || !"SUBMITTED".equals(r.get("status")))
            throw conflict("报告状态或版本已变化，请刷新核对");
        if (action.equals("cancel")) {
            if (!actor.currentActor().equals(r.get("created_by")))
                throw new BusinessException(403, "COMPLETION_ACTOR_DENIED", "仅登记人可撤销未复核报告");
        } else if (actor.currentActor().equals(r.get("created_by")))
            throw new BusinessException(403, "COMPLETION_SELF_REVIEW", "登记人不能复核本人报告");
        if (action.equals("approve")) {
            active(o);
            validate(
                    o,
                    r.get("date_code").toString(),
                    date(r, "production_date"),
                    date(r, "expiry_date"));
            port.create(
                    new ProductionCompletionPort.Input(
                            reportId,
                            id,
                            "MFG-C-" + reportId,
                            o.get("order_no").toString(),
                            n(o, "warehouse_id"),
                            n(o, "product_id"),
                            q(r, "quantity"),
                            r.get("batch_no").toString(),
                            r.get("date_code").toString(),
                            date(r, "production_date"),
                            date(r, "expiry_date"),
                            o.get("product_code").toString(),
                            o.get("product_name").toString(),
                            o.get("unit").toString()));
        }
        String next =
                switch (action) {
                    case "approve" -> "APPROVED";
                    case "reject" -> "REJECTED";
                    case "cancel" -> "CANCELLED";
                    default -> throw conflict("不支持的动作");
                };
        db.sql(
                        "UPDATE mfg_completion SET status=?,version=version+1,reviewed_by=?,reviewed_at=?,review_reason=? WHERE id=?")
                .params(
                        next,
                        actor.currentActor(),
                        now(),
                        required(in.reason(), "处理依据", 500),
                        reportId)
                .update();
        remember(in.idempotencyKey(), hash, reportId);
        return detail(id);
    }

    private void active(Map<String, Object> o) {
        if (!"APPROVED".equals(o.get("status"))) throw conflict("只有已批准工单允许登记或批准完工");
    }

    private void validate(
            Map<String, Object> o, String code, LocalDate production, LocalDate expiry) {
        catalog.requireReceivingWarehouse(n(o, "warehouse_id"));
        var m = catalog.referenceMaterial(n(o, "product_id"));
        if (m.unitId() != n(o, "unit_id") || !m.unit().equals(o.get("unit")))
            throw conflict("产品单位与工单快照不一致");
        if (m.requireDateCode() && (code == null || code.isBlank()))
            throw conflict("此产品必须填写 Date Code");
        if (m.requireExpiry() && expiry == null) throw conflict("此产品必须填写有效期");
        if (production.isAfter(LocalDate.now(clock))
                || (expiry != null && expiry.isBefore(production))) throw conflict("生产日期或有效期不合法");
    }

    private boolean replay(String key, String hash) {
        var old =
                db.sql("SELECT request_hash FROM mfg_completion_command WHERE request_key=?")
                        .param(key)
                        .query(String.class)
                        .optional();
        if (old.isPresent() && !old.get().equals(hash)) throw conflict("请求键已用于其他载荷、动作或操作者");
        return old.isPresent();
    }

    private void remember(String key, String hash, long id) {
        db.sql(
                        "INSERT INTO mfg_completion_command(request_key,request_hash,report_id,created_at) VALUES(?,?,?,?)")
                .params(key, hash, id, now())
                .update();
    }

    private String hash(String action, Object id, Object in) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            json.writeValueAsBytes(
                                                    List.of(
                                                            action,
                                                            id,
                                                            actor.currentActor(),
                                                            in))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long n(Map<String, Object> r, String k) {
        return ((Number) r.get(k)).longValue();
    }

    private BigDecimal q(Map<String, Object> r, String k) {
        return (BigDecimal) r.get(k);
    }

    private LocalDate date(Map<String, Object> r, String k) {
        return r.get(k) == null ? null : LocalDate.parse(r.get(k).toString());
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private BusinessException conflict(String message) {
        return new BusinessException(409, "COMPLETION_CONFLICT", message);
    }
}
