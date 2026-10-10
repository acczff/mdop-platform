package io.github.acczff.mdop.purchasing;

import io.github.acczff.mdop.common.purchasing.PurchaseReceivingPort;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Derives procurement quantities from immutable source identities, never from stock balances. */
@Service
public class PurchaseFulfillment {
    private static final int RECEIVED = 0,
            REVERSED = 1,
            PENDING_INSPECTION = 2,
            PENDING_PUTAWAY = 3,
            PUTAWAY = 4,
            REJECTED_PENDING_RETURN = 5,
            RETURNED = 6;
    private final JdbcClient db;
    private final PurchaseReceivingPort receiving;
    private final ObjectMapper json;

    public PurchaseFulfillment(JdbcClient db, PurchaseReceivingPort receiving, ObjectMapper json) {
        this.db = db;
        this.receiving = receiving;
        this.json = json;
    }

    public record Line(
            long orderLineId,
            String code,
            String unit,
            String ordered,
            String received,
            String reversed,
            String netReceived,
            String remaining,
            String pendingInspection,
            String pendingPutaway,
            String putaway,
            String rejectedPendingReturn,
            String returned) {}

    public record Notice(
            long arrangementId,
            PurchaseReceivingPort.Delivery delivery,
            PurchaseReceivingPort.Fulfillment facts) {}

    public record Summary(
            List<Line> lines,
            List<Notice> notices,
            List<String> blockers,
            String factHash,
            boolean canClose,
            String outcome) {}

    public Summary read(long order, long warehouse) {
        var source =
                db.sql(
                                "SELECT id,material_code,unit,quantity FROM pur_line WHERE document_id=? ORDER BY id")
                        .param(order)
                        .query()
                        .listOfRows();
        var totals = new LinkedHashMap<Long, BigDecimal[]>();
        for (var l : source) {
            var values = new BigDecimal[7];
            Arrays.fill(values, BigDecimal.ZERO);
            totals.put(id(l, "id"), values);
        }
        var blockers = new LinkedHashSet<String>();
        var notices = new ArrayList<Notice>();
        var seen = new HashSet<Long>();
        for (var a :
                db.sql("SELECT * FROM pur_arrangement WHERE order_id=? ORDER BY id")
                        .param(order)
                        .query()
                        .listOfRows()) {
            long arrangement = id(a, "id");
            String label = "到货安排 #" + arrangement;
            if ("PENDING".equals(a.get("status"))) blockers.add(label + "尚未送达 WMS（失败或未知结果仍占额度）");
            if (a.get("wms_arrival_id") == null) {
                if ("DELIVERED".equals(a.get("status"))) blockers.add(label + "缺少 WMS 映射");
                continue;
            }
            var delivery = receiving.view(warehouse, arrangement);
            var facts = receiving.fulfillment(warehouse, arrangement);
            notices.add(new Notice(arrangement, delivery, facts));
            if (delivery.arrivalId() != id(a, "wms_arrival_id")
                    || ("WITHDRAWN".equals(a.get("status"))
                            != "WITHDRAWN".equals(delivery.status())))
                blockers.add(label + "来源状态或通知映射不一致");
            var mapping = new HashMap<Long, Map<String, Object>>();
            for (var l :
                    db.sql("SELECT * FROM pur_arrangement_line WHERE arrangement_id=? ORDER BY id")
                            .param(arrangement)
                            .query()
                            .listOfRows()) mapping.put(id(l, "id"), l);
            var netByLine = new HashMap<Long, BigDecimal>();
            for (var c : facts.cases())
                if ("PENDING".equals(c.status()))
                    blockers.add("差异/冲正 #" + c.id() + "尚未审核：" + c.reason());
            for (var r : facts.returns())
                if (Set.of("PENDING", "APPROVED").contains(r.status()))
                    blockers.add("退供 #" + r.id() + "尚未完成实际交接");
            for (var f : facts.receipts()) {
                var link = mapping.get(f.arrangementLineId());
                if (link == null
                        || !totals.containsKey(id(link, "order_line_id"))
                        || !seen.add(f.receiptItemId())) {
                    blockers.add(label + "收货行来源缺失或重复");
                    continue;
                }
                var v = totals.get(id(link, "order_line_id"));
                String ref = f.receiptNumber() + " / 行 #" + f.receiptItemId();
                if (!"SUBMITTED".equals(f.status())) {
                    blockers.add(ref + "仍为收货草稿");
                    continue;
                }
                BigDecimal qty = number(f.quantity());
                v[RECEIVED] = v[RECEIVED].add(qty);
                if ("REVERSED".equals(f.correctionStatus())) {
                    v[REVERSED] = v[REVERSED].add(qty);
                    if (facts.cases().stream()
                            .noneMatch(
                                    c ->
                                            "REVERSAL".equals(c.kind())
                                                    && "APPROVED".equals(c.status())
                                                    && Objects.equals(
                                                            c.receiptId(), f.receiptId())))
                        blockers.add(ref + "缺少已批准的冲正来源");
                    if (f.qualityReference() != null) blockers.add(ref + "冲正与质检事实冲突");
                    continue;
                }
                netByLine.merge(f.arrangementLineId(), qty, BigDecimal::add);
                if (f.qualified() == null || f.rejected() == null || f.qualityReference() == null) {
                    v[PENDING_INSPECTION] = v[PENDING_INSPECTION].add(qty);
                    if (!"NONE".equals(f.stage())) blockers.add(ref + "下游状态与质检事实不一致");
                    continue;
                }
                BigDecimal good = number(f.qualified()), bad = number(f.rejected());
                if (good.signum() < 0 || bad.signum() < 0 || good.add(bad).compareTo(qty) != 0)
                    blockers.add(ref + "质检数量不守恒");
                int index = f.putawayLocation() == null ? PENDING_PUTAWAY : PUTAWAY;
                v[index] = v[index].add(good);
                BigDecimal returned = BigDecimal.ZERO;
                for (var r : facts.returns())
                    if (r.receiptItemId() == f.receiptItemId() && "RETURNED".equals(r.status())) {
                        returned = returned.add(number(r.quantity()));
                        if (r.handover() == null || r.handover().isBlank())
                            blockers.add("退供 #" + r.id() + "缺少实物交接凭据");
                    }
                if (returned.compareTo(bad) > 0) blockers.add(ref + "实际退供超过不合格数量");
                v[REJECTED_PENDING_RETURN] = v[REJECTED_PENDING_RETURN].add(bad.subtract(returned));
                v[RETURNED] = v[RETURNED].add(returned);
            }
            var mapped = new HashSet<Long>();
            for (var m : delivery.lines()) {
                var link = mapping.get(m.arrangementLineId());
                if (link == null
                        || !mapped.add(m.arrangementLineId())
                        || link.get("wms_arrival_item_id") == null
                        || id(link, "wms_arrival_item_id") != m.arrivalItemId()
                        || number(link.get("quantity").toString()).compareTo(number(m.noticeQty()))
                                != 0) {
                    blockers.add(label + "通知行映射或授权数量不一致");
                    continue;
                }
                if (number(m.receivedQty())
                                .compareTo(
                                        netByLine.getOrDefault(
                                                m.arrangementLineId(), BigDecimal.ZERO))
                        != 0) blockers.add(label + "通知累计与净收货事实不一致");
            }
            if (mapped.size() != mapping.size()) blockers.add(label + "通知行映射不完整");
        }
        var lines = new ArrayList<Line>();
        boolean returned = false;
        for (var l : source) {
            var v = totals.get(id(l, "id"));
            var qty = number(l.get("quantity").toString());
            var net = v[RECEIVED].subtract(v[REVERSED]);
            String code = l.get("material_code").toString();
            if (net.compareTo(qty) < 0) blockers.add(code + "尚欠收货 " + text(qty.subtract(net)));
            if (net.compareTo(qty) > 0) blockers.add(code + "净收货超过订单授权");
            if (v[PENDING_INSPECTION].signum() != 0)
                blockers.add(code + "待检 " + text(v[PENDING_INSPECTION]));
            if (v[PENDING_PUTAWAY].signum() != 0)
                blockers.add(code + "合格待上架 " + text(v[PENDING_PUTAWAY]));
            if (v[REJECTED_PENDING_RETURN].signum() != 0)
                blockers.add(code + "不合格待实际退供 " + text(v[REJECTED_PENDING_RETURN]));
            if (Arrays.stream(v, PENDING_INSPECTION, v.length)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .compareTo(net)
                    != 0) blockers.add(code + "收货与处置数量不守恒");
            returned |= v[RETURNED].signum() > 0;
            lines.add(
                    new Line(
                            id(l, "id"),
                            code,
                            l.get("unit").toString(),
                            text(qty),
                            text(v[RECEIVED]),
                            text(v[REVERSED]),
                            text(net),
                            text(qty.subtract(net)),
                            text(v[PENDING_INSPECTION]),
                            text(v[PENDING_PUTAWAY]),
                            text(v[PUTAWAY]),
                            text(v[REJECTED_PENDING_RETURN]),
                            text(v[RETURNED])));
        }
        if (source.isEmpty()) blockers.add("订单缺少授权明细");
        return new Summary(
                lines,
                notices,
                List.copyOf(blockers),
                digest(List.of(lines, physicalFacts(notices))),
                blockers.isEmpty(),
                returned ? "WITH_RETURNS" : "QUALIFIED");
    }

    private List<Notice> physicalFacts(List<Notice> notices) {
        // Pending cases block closure independently. A rejected late report does not alter the
        // physical result; preserve it in the displayed/saved evidence without invalidating it
        // forever.
        return notices.stream()
                .map(
                        n ->
                                new Notice(
                                        n.arrangementId(),
                                        n.delivery(),
                                        new PurchaseReceivingPort.Fulfillment(
                                                n.facts().receipts(),
                                                n.facts().cases().stream()
                                                        .filter(
                                                                c ->
                                                                        "REVERSAL".equals(c.kind())
                                                                                && "APPROVED"
                                                                                        .equals(
                                                                                                c
                                                                                                        .status()))
                                                        .toList(),
                                                n.facts().returns().stream()
                                                        .filter(r -> "RETURNED".equals(r.status()))
                                                        .toList())))
                .toList();
    }

    private long id(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private BigDecimal number(String value) {
        return new BigDecimal(value);
    }

    private String text(BigDecimal value) {
        return value.setScale(6).toPlainString();
    }

    private String digest(Object value) {
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
}
