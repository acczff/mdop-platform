package io.github.acczff.mdop.finance;

import static io.github.acczff.mdop.finance.FinanceModels.*;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.BusinessText;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import io.github.acczff.mdop.common.finance.FinanceSourcePort;
import java.math.*;
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
public class FinanceService {
    private final JdbcClient db;
    private final List<FinanceSourcePort> sources;
    private final CurrentActorProvider actor;
    private final Clock clock;
    private final ObjectMapper json;

    public FinanceService(
            JdbcClient db,
            List<FinanceSourcePort> sources,
            CurrentActorProvider actor,
            Clock clock,
            ObjectMapper json) {
        this.db = db;
        this.sources = sources;
        this.actor = actor;
        this.clock = clock;
        this.json = json;
    }

    public record Page(List<Map<String, Object>> items, long total, int page, int size) {}

    @Transactional(readOnly = true)
    public Map<String, Object> baseline() {
        var d = db.sql("SELECT * FROM fin_baseline WHERE id=1").query().singleRow();
        d.put(
                "history",
                db.sql("SELECT * FROM fin_audit WHERE basis_id IS NULL ORDER BY id DESC LIMIT 1000")
                        .query()
                        .listOfRows());
        for (String k : List.of("start_date", "end_date"))
            if (d.get(k) != null) d.put(k, d.get(k).toString());
        return d;
    }

    public Map<String, Object> setup(Setup in) {
        lock();
        String hash = hash("SETUP", 0, in);
        if (replay(in.idempotencyKey(), hash) != null) return baseline();
        var d = baseline();
        version(d, in.version());
        if (!Set.of("UNINITIALIZED", "REJECTED").contains(d.get("status")))
            throw conflict("基线已提交或启用，不能覆盖");
        if (in.endDate().isBefore(in.startDate())
                || in.endDate().isAfter(in.startDate().plusYears(1).minusDays(1)))
            throw invalid("演示开放期间不得超过一年，结束日期不能早于启用日");
        db.sql(
                        "UPDATE fin_baseline SET status='PENDING',organization=?,start_date=?,end_date=?,reason=?,version=version+1,created_by=?,created_at=?,reviewed_by=NULL,reviewed_at=NULL WHERE id=1")
                .params(
                        text(in.organization(), 100),
                        in.startDate(),
                        in.endDate(),
                        text(in.reason(), 500),
                        actor.currentActor(),
                        now())
                .update();
        audit(null, "SETUP", in.reason());
        remember(in.idempotencyKey(), hash, 0);
        return baseline();
    }

    public Map<String, Object> reviewSetup(String action, Action in) {
        lock();
        String hash = hash("BASELINE-" + action, 0, in);
        if (replay(in.idempotencyKey(), hash) != null) return baseline();
        var d = baseline();
        version(d, in.version());
        state(d, "PENDING");
        independent(d);
        if (!Set.of("approve", "reject").contains(action)) throw invalid("不支持的初始化动作");
        db.sql(
                        "UPDATE fin_baseline SET status=?,version=version+1,reviewed_by=?,reviewed_at=? WHERE id=1")
                .params(
                        action.equals("approve") ? "ACTIVE" : "REJECTED",
                        actor.currentActor(),
                        now())
                .update();
        audit(null, action.equals("approve") ? "ACTIVATE" : "REJECT_SETUP", in.reason());
        remember(in.idempotencyKey(), hash, 0);
        return baseline();
    }

    @Transactional(readOnly = true)
    public List<FinanceSourcePort.Fact> sources(long warehouse, String direction, String number) {
        scope(warehouse);
        return port(direction).facts(warehouse, text(number, 40));
    }

    @Transactional(readOnly = true)
    public Page list(long warehouse, String direction, String orderNumber, int page, int size) {
        scope(warehouse);
        port(direction);
        var rows =
                db.sql(
                                "SELECT b.*,o.amount AS open_amount FROM fin_basis b LEFT JOIN fin_open_item o ON o.basis_id=b.id WHERE b.warehouse_id=? AND b.direction=? AND (?='' OR b.order_number=?) ORDER BY b.id DESC LIMIT ? OFFSET ?")
                        .params(
                                warehouse,
                                direction,
                                orderNumber,
                                orderNumber,
                                size,
                                (long) page * size)
                        .query()
                        .listOfRows();
        return new Page(
                rows.stream().map(this::present).toList(),
                db.sql(
                                "SELECT COUNT(*) FROM fin_basis WHERE warehouse_id=? AND direction=? AND (?='' OR order_number=?)")
                        .params(warehouse, direction, orderNumber, orderNumber)
                        .query(Long.class)
                        .single(),
                page,
                size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(long id) {
        var d = row(id);
        scope(n(d, "warehouse_id"));
        var item =
                db.sql("SELECT * FROM fin_open_item WHERE basis_id=?")
                        .param(id)
                        .query()
                        .listOfRows();
        d.put("open_amount", item.isEmpty() ? null : item.getFirst().get("amount"));
        d.put(
                "history",
                db.sql(
                                "SELECT id,action,reason,actor,created_at FROM fin_audit WHERE basis_id=? ORDER BY id DESC LIMIT 1000")
                        .param(id)
                        .query()
                        .listOfRows());
        boolean matched =
                sources(
                                n(d, "warehouse_id"),
                                d.get("direction").toString(),
                                d.get("order_number").toString())
                        .stream()
                        .anyMatch(
                                f ->
                                        f.factId() == n(d, "fact_id")
                                                && f.equals(
                                                        json.readValue(
                                                                d.get("source_snapshot").toString(),
                                                                FinanceSourcePort.Fact.class)));
        d.put("sourceMatches", matched);
        d.put("selfReview", selfReview(d));
        d.put(
                "balanceMatches",
                "CONFIRMED".equals(d.get("status"))
                        ? item.size() == 1
                                && ((BigDecimal) d.get("amount"))
                                                .compareTo(
                                                        (BigDecimal) item.getFirst().get("amount"))
                                        == 0
                        : item.isEmpty());
        return present(d);
    }

    public Map<String, Object> create(Draft in) {
        scope(in.warehouseId());
        lock();
        String hash = hash("CREATE", 0, in);
        Long old = replay(in.idempotencyKey(), hash);
        if (old != null) return detail(old);
        active(in.businessDate());
        var f =
                sources(in.warehouseId(), in.direction(), in.orderNumber()).stream()
                        .filter(s -> s.factId() == in.factId())
                        .findFirst()
                        .orElseThrow(() -> conflict("来源不存在或尚未完成合格上架/实际出库，请核对订单与收发事实"));
        var amount =
                new BigDecimal(f.quantity())
                        .multiply(in.unitPrice())
                        .setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0 || amount.precision() > 18 || amount.compareTo(in.amount()) != 0)
            throw invalid("金额须等于本次事实数量乘单价、按行四舍五入至两位，且大于零、在支持范围内");
        String activeSource = in.direction() + ":" + f.factId();
        if (db.sql("SELECT COUNT(*) FROM fin_basis WHERE active_source=?")
                        .param(activeSource)
                        .query(Long.class)
                        .single()
                != 0) throw conflict("该收发事实已有有效金额依据，请查看原记录");
        var key = new GeneratedKeyHolder();
        db.sql(
                        "INSERT INTO fin_basis(direction,warehouse_id,order_id,order_number,order_line_id,fact_id,fact_number,party_id,party_name,material_code,material_name,unit,quantity,unit_price,amount,reference,business_date,reason,source_snapshot,active_source,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .params(
                        in.direction(),
                        f.warehouseId(),
                        f.orderId(),
                        f.orderNumber(),
                        f.orderLineId(),
                        f.factId(),
                        f.factNumber(),
                        f.partyId(),
                        f.partyName(),
                        f.materialCode(),
                        f.materialName(),
                        f.unit(),
                        new BigDecimal(f.quantity()),
                        in.unitPrice(),
                        amount,
                        text(in.reference(), 100),
                        in.businessDate(),
                        text(in.reason(), 500),
                        json.writeValueAsString(f),
                        activeSource,
                        actor.currentActor(),
                        now())
                .update(key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        audit(id, "CREATE", in.reason());
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    public Map<String, Object> action(long id, String action, Action in) {
        scope(n(row(id), "warehouse_id"));
        lock();
        String hash = hash(action, id, in);
        Long old = replay(in.idempotencyKey(), hash);
        if (old != null) return detail(old);
        var d = row(id);
        version(d, in.version());
        String next;
        switch (action) {
            case "submit" -> {
                state(d, "DRAFT");
                active(((java.sql.Date) d.get("business_date")).toLocalDate());
                verifySource(d);
                next = "PENDING";
            }
            case "approve" -> {
                state(d, "PENDING");
                independent(d);
                active(((java.sql.Date) d.get("business_date")).toLocalDate());
                verifySource(d);
                next = "CONFIRMED";
                db.sql(
                                "INSERT INTO fin_open_item(basis_id,amount,confirmed_by,confirmed_at) VALUES(?,?,?,?)")
                        .params(id, d.get("amount"), actor.currentActor(), now())
                        .update();
            }
            case "reject" -> {
                state(d, "PENDING");
                independent(d);
                next = "REJECTED";
            }
            case "cancel" -> {
                if (!Set.of("DRAFT", "PENDING").contains(d.get("status")))
                    throw conflict("已确认依据不能普通取消，请保留原事实");
                next = "CANCELLED";
            }
            default -> throw invalid("不支持的金额依据动作");
        }
        db.sql(
                        "UPDATE fin_basis SET status=?,active_source=CASE WHEN ? IN ('REJECTED','CANCELLED') THEN NULL ELSE active_source END,version=version+1 WHERE id=?")
                .params(next, next, id)
                .update();
        if (Set.of("CONFIRMED", "REJECTED").contains(next))
            db.sql("UPDATE fin_basis SET reviewed_by=?,reviewed_at=? WHERE id=?")
                    .params(actor.currentActor(), now(), id)
                    .update();
        audit(id, action.toUpperCase(Locale.ROOT), in.reason());
        remember(in.idempotencyKey(), hash, id);
        return detail(id);
    }

    private void verifySource(Map<String, Object> d) {
        var facts =
                sources(
                        n(d, "warehouse_id"),
                        d.get("direction").toString(),
                        d.get("order_number").toString());
        if (facts.stream()
                .noneMatch(
                        f ->
                                f.factId() == n(d, "fact_id")
                                        && f.equals(
                                                json.readValue(
                                                        d.get("source_snapshot").toString(),
                                                        FinanceSourcePort.Fact.class))))
            throw conflict("来源事实变化或失效，禁止确认金额，请核对原单");
    }

    private FinanceSourcePort port(String direction) {
        return sources.stream()
                .filter(p -> p.direction().equals(direction))
                .findFirst()
                .orElseThrow(() -> invalid("不支持的往来方向"));
    }

    private Map<String, Object> row(long id) {
        return db.sql("SELECT * FROM fin_basis WHERE id=?").param(id).query().listOfRows().stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "FINANCE_NOT_FOUND", "金额依据不存在"));
    }

    private Map<String, Object> present(Map<String, Object> d) {
        d.put("business_date", d.get("business_date").toString());
        d.put("number", "FB-" + String.format("%08d", n(d, "id")));
        for (String k : List.of("quantity", "unit_price", "amount", "open_amount"))
            if (d.get(k) instanceof BigDecimal b) d.put(k, b.toPlainString());
        return d;
    }

    private void lock() {
        db.sql("SELECT id FROM fin_baseline WHERE id=1 FOR UPDATE").query(Long.class).single();
    }

    private void active(java.time.LocalDate date) {
        var d = baseline();
        state(d, "ACTIVE");
        var start = java.time.LocalDate.parse(d.get("start_date").toString());
        var end = java.time.LocalDate.parse(d.get("end_date").toString());
        if (date.isBefore(start) || date.isAfter(end)) throw invalid("日期不在已复核的演示开放期间内");
    }

    private void scope(long warehouse) {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null
                || a.getAuthorities().stream()
                        .noneMatch(p -> p.getAuthority().equals("wms:warehouse:" + warehouse)))
            throw new BusinessException(403, "FINANCE_SCOPE", "没有该仓库的数据权限");
    }

    private void independent(Map<String, Object> d) {
        if (selfReview(d)) throw conflict("录入人或提交人不能复核自己的依据或初始化");
    }

    private boolean selfReview(Map<String, Object> d) {
        if (actor.currentActor().equals(d.get("created_by"))) return true;
        return d.containsKey("direction")
                && db.sql(
                                        "SELECT COUNT(*) FROM fin_audit WHERE basis_id=? AND action='SUBMIT' AND actor=?")
                                .params(n(d, "id"), actor.currentActor())
                                .query(Long.class)
                                .single()
                        > 0;
    }

    private void version(Map<String, Object> d, Long v) {
        if (v == null || n(d, "version") != v) throw conflict("记录已变化，请刷新核对");
    }

    private void state(Map<String, Object> d, String expected) {
        if (!expected.equals(d.get("status"))) throw conflict("当前状态不支持此操作");
    }

    private String text(String s, int max) {
        return BusinessText.required(s, "业务依据", max);
    }

    private long n(Map<String, Object> d, String k) {
        return ((Number) d.get(k)).longValue();
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private void audit(Long id, String action, String reason) {
        db.sql("INSERT INTO fin_audit(basis_id,action,reason,actor,created_at) VALUES(?,?,?,?,?)")
                .params(id, action, text(reason, 500), actor.currentActor(), now())
                .update();
    }

    private String hash(String action, long id, Object in) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            json.writeValueAsBytes(
                                                    List.of(
                                                            actor.currentActor(),
                                                            action,
                                                            id,
                                                            in))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Long replay(String key, String hash) {
        if (!key.matches("[A-Za-z0-9_-]{1,64}")) throw invalid("请求键格式不合法");
        var rows =
                db.sql("SELECT * FROM fin_command WHERE request_key=?")
                        .param(key)
                        .query()
                        .listOfRows();
        if (rows.isEmpty()) return null;
        if (!hash.equals(rows.getFirst().get("request_hash"))) throw conflict("请求键已用于不同动作、载荷或操作者");
        return n(rows.getFirst(), "result_id");
    }

    private void remember(String key, String hash, long id) {
        db.sql(
                        "INSERT INTO fin_command(request_key,request_hash,result_id,created_at) VALUES(?,?,?,?)")
                .params(key, hash, id, now())
                .update();
    }

    private BusinessException conflict(String s) {
        return new BusinessException(409, "FINANCE_CONFLICT", s);
    }

    private BusinessException invalid(String s) {
        return new BusinessException(400, "INVALID_FINANCE", s);
    }
}
