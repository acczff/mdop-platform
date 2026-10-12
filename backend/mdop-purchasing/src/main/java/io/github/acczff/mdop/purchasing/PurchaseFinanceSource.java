package io.github.acczff.mdop.purchasing;

import io.github.acczff.mdop.common.finance.FinanceSourcePort;
import io.github.acczff.mdop.common.purchasing.PurchaseReceivingPort;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class PurchaseFinanceSource implements FinanceSourcePort {
    private final JdbcClient db;
    private final PurchaseReceivingPort receiving;

    public PurchaseFinanceSource(JdbcClient db, PurchaseReceivingPort receiving) {
        this.db = db;
        this.receiving = receiving;
    }

    public String direction() {
        return "PAYABLE";
    }

    public List<Fact> facts(long warehouse, String number) {
        var orders =
                db.sql(
                                "SELECT * FROM pur_document WHERE warehouse_id=? AND document_no=? AND kind='ORDER' AND status IN ('APPROVED','FULFILLING','CLOSED')")
                        .params(warehouse, number)
                        .query()
                        .listOfRows();
        var result = new ArrayList<Fact>();
        for (var o : orders) {
            for (var a :
                    db.sql("SELECT id FROM pur_arrangement WHERE order_id=? AND status='DELIVERED'")
                            .param(n(o, "id"))
                            .query()
                            .listOfRows()) {
                var delivery = receiving.view(warehouse, n(a, "id"));
                var facts = receiving.fulfillment(warehouse, n(a, "id"));
                for (var f : facts.receipts()) {
                    if (!"SUBMITTED".equals(f.status())
                            || "REVERSED".equals(f.correctionStatus())
                            || f.putawayLocation() == null
                            || f.qualityReference() == null
                            || f.qualified() == null
                            || new BigDecimal(f.qualified()).signum() <= 0
                            || facts.cases().stream()
                                    .anyMatch(
                                            c ->
                                                    "PENDING".equals(c.status())
                                                            && (c.receiptId() == null
                                                                    || c.receiptId()
                                                                            == f.receiptId())))
                        continue;
                    var lines =
                            db.sql(
                                            "SELECT l.* FROM pur_arrangement_line a JOIN pur_line l ON l.id=a.order_line_id WHERE a.id=? AND a.arrangement_id=? AND l.document_id=?")
                                    .params(f.arrangementLineId(), n(a, "id"), n(o, "id"))
                                    .query()
                                    .listOfRows();
                    if (lines.size() != 1
                            || delivery.lines().stream()
                                    .noneMatch(m -> m.arrangementLineId() == f.arrangementLineId()))
                        continue;
                    var l = lines.getFirst();
                    result.add(
                            new Fact(
                                    n(o, "id"),
                                    o.get("document_no").toString(),
                                    n(l, "id"),
                                    f.receiptItemId(),
                                    f.receiptNumber(),
                                    warehouse,
                                    n(o, "supplier_id"),
                                    o.get("supplier_name").toString(),
                                    l.get("material_code").toString(),
                                    l.get("material_name").toString(),
                                    l.get("unit").toString(),
                                    new BigDecimal(f.qualified()).setScale(6).toPlainString()));
                }
            }
        }
        return List.copyOf(result);
    }

    private long n(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
