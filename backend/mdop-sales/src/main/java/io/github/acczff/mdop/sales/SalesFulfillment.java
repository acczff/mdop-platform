package io.github.acczff.mdop.sales;

import io.github.acczff.mdop.common.sales.SalesShippingPort;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class SalesFulfillment {
    private final JdbcClient db;
    private final SalesShippingPort shipping;
    private final ObjectMapper json;

    public SalesFulfillment(JdbcClient db, SalesShippingPort shipping, ObjectMapper json) {
        this.db = db;
        this.shipping = shipping;
        this.json = json;
    }

    public record Line(
            long orderLineId,
            String code,
            String unit,
            String ordered,
            String allocated,
            String shipped,
            String remaining) {}

    public record Summary(
            List<Line> lines,
            List<Map<String, Object>> arrangements,
            List<String> blockers,
            boolean canClose,
            String factHash) {}

    public Summary read(long order, long warehouse) {
        var source =
                db.sql("SELECT * FROM sal_line WHERE document_id=? ORDER BY material_id")
                        .param(order)
                        .query()
                        .listOfRows();
        var arranged = new HashMap<Long, BigDecimal>();
        var shipped = new HashMap<Long, BigDecimal>();
        var byId = new HashMap<Long, Map<String, Object>>();
        source.forEach(l -> byId.put(n(l, "id"), l));
        var blockers = new LinkedHashSet<String>();
        var seen = new HashSet<Long>();
        var arrangements =
                db.sql(
                                "SELECT id,order_line_id,CAST(quantity AS CHAR) quantity,expected_date,status,wms_sales_id,last_error FROM sal_arrangement WHERE order_id=? ORDER BY id")
                        .param(order)
                        .query()
                        .listOfRows();
        for (var a : arrangements) {
            if (a.get("expected_date") instanceof java.sql.Date date)
                a.put("expected_date", date.toLocalDate().toString());
            long lineId = n(a, "order_line_id"), id = n(a, "id");
            var line = byId.get(lineId);
            String label = "发货安排 #" + id;
            BigDecimal qty = new BigDecimal(a.get("quantity").toString());
            if (line == null) {
                blockers.add(label + "授权行缺失");
                continue;
            }
            if (!"WITHDRAWN".equals(a.get("status"))) arranged.merge(lineId, qty, BigDecimal::add);
            if ("PENDING".equals(a.get("status"))) blockers.add(label + "尚未送达（失败或未知结果仍占额度）");
            if (a.get("wms_sales_id") == null) {
                if ("DELIVERED".equals(a.get("status"))) blockers.add(label + "缺少执行映射");
                continue;
            }
            var e = shipping.view(warehouse, id);
            a.put("wms", e);
            if (e.id() != n(a, "wms_sales_id")
                    || !seen.add(e.id())
                    || e.orderLineId() != lineId
                    || e.warehouseId() != warehouse
                    || e.materialId() != n(line, "material_id")
                    || !e.code().equals(line.get("material_code"))
                    || !e.unit().equals(line.get("unit"))
                    || new BigDecimal(e.quantity()).compareTo(qty) != 0)
                blockers.add(label + "来源或数量不一致");
            boolean sent = "SHIPPED".equals(e.status());
            if (sent) {
                shipped.merge(lineId, new BigDecimal(e.shippedQuantity()), BigDecimal::add);
                if (e.ledgerCount() != 1
                        || !e.ledgerSourceMatches()
                        || new BigDecimal(e.shippedQuantity()).compareTo(qty) != 0
                        || e.balanceId() == null
                        || e.closedAt() == null
                        || e.closedBy() == null
                        || e.pickedBy() == null
                        || e.reviewedBy() == null
                        || e.pickedBy().equals(e.reviewedBy())) blockers.add(label + "出库事实与流水不一致");
            } else if (e.ledgerCount() != 0 || new BigDecimal(e.shippedQuantity()).signum() != 0)
                blockers.add(label + "未出库状态已有出库流水");
            if ("WITHDRAWN".equals(a.get("status"))) {
                if (!"CANCELLED".equals(e.status())) blockers.add(label + "未确认安全取消");
            } else if (!sent)
                blockers.add(
                        label
                                + ("CANCELLED".equals(e.status())
                                        ? "WMS 已取消，请确认撤回安排"
                                        : "尚未实际出库：" + executionState(e.status())));
        }
        var lines = new ArrayList<Line>();
        for (var l : source) {
            long id = n(l, "id");
            var qty = (BigDecimal) l.get("quantity");
            var done = shipped.getOrDefault(id, BigDecimal.ZERO);
            var used = arranged.getOrDefault(id, BigDecimal.ZERO);
            if (used.compareTo(qty) > 0) blockers.add(l.get("material_code") + "累计安排超过授权");
            if (done.compareTo(qty) < 0)
                blockers.add(
                        l.get("material_code")
                                + " 尚有 "
                                + text(qty.subtract(done))
                                + " "
                                + l.get("unit")
                                + " 未发");
            if (done.compareTo(qty) > 0) blockers.add(l.get("material_code") + " 实际已发超过授权，请核对出库事实");
            lines.add(
                    new Line(
                            id,
                            l.get("material_code").toString(),
                            l.get("unit").toString(),
                            text(qty),
                            text(used),
                            text(done),
                            text(qty.subtract(done))));
        }
        if (source.isEmpty()) blockers.add("订单缺少授权行");
        String hash;
        try {
            hash =
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    json.writeValueAsBytes(
                                                            List.of(lines, arrangements))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new Summary(lines, arrangements, List.copyOf(blockers), blockers.isEmpty(), hash);
    }

    private long n(Map<String, Object> d, String key) {
        return ((Number) d.get(key)).longValue();
    }

    private String text(BigDecimal qty) {
        return qty.setScale(6).toPlainString();
    }

    private String executionState(String state) {
        return switch (state) {
            case "OPEN" -> "待预占";
            case "RESERVED" -> "待拣货";
            case "PICKED" -> "待独立复核";
            case "VERIFIED" -> "待实际出库";
            default -> "状态待核对";
        };
    }
}
