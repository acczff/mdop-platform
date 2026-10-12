package io.github.acczff.mdop.sales;

import io.github.acczff.mdop.common.finance.FinanceSourcePort;
import io.github.acczff.mdop.common.sales.SalesShippingPort;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SalesFinanceSource implements FinanceSourcePort {
    private final JdbcClient db;
    private final SalesShippingPort shipping;

    public SalesFinanceSource(JdbcClient db, SalesShippingPort shipping) {
        this.db = db;
        this.shipping = shipping;
    }

    public String direction() {
        return "RECEIVABLE";
    }

    public List<Fact> facts(long warehouse, String number) {
        var result = new ArrayList<Fact>();
        for (var o :
                db.sql(
                                "SELECT * FROM sal_document WHERE warehouse_id=? AND document_no=? AND status IN ('APPROVED','FULFILLING','CLOSED')")
                        .params(warehouse, number)
                        .query()
                        .listOfRows()) {
            for (var a :
                    db.sql(
                                    "SELECT a.*,l.material_code,l.material_name,l.unit,l.material_id FROM sal_arrangement a JOIN sal_line l ON a.order_line_id=l.id AND l.document_id=a.order_id WHERE a.order_id=? AND a.status='DELIVERED'")
                            .param(n(o, "id"))
                            .query()
                            .listOfRows()) {
                var e = shipping.view(warehouse, n(a, "id"));
                if (!"SHIPPED".equals(e.status())
                        || e.ledgerCount() != 1
                        || !e.ledgerSourceMatches()
                        || e.closedAt() == null
                        || e.closedBy() == null
                        || e.pickedBy() == null
                        || e.reviewedBy() == null
                        || e.pickedBy().equals(e.reviewedBy())
                        || e.id() != n(a, "wms_sales_id")
                        || e.orderLineId() != n(a, "order_line_id")
                        || e.materialId() != n(a, "material_id")
                        || e.warehouseId() != warehouse
                        || new BigDecimal(e.shippedQuantity())
                                        .compareTo((BigDecimal) a.get("quantity"))
                                != 0) continue;
                result.add(
                        new Fact(
                                n(o, "id"),
                                o.get("document_no").toString(),
                                e.orderLineId(),
                                e.id(),
                                o.get("document_no") + " / 发货安排 " + a.get("id"),
                                warehouse,
                                n(o, "customer_id"),
                                o.get("customer_name").toString(),
                                a.get("material_code").toString(),
                                a.get("material_name").toString(),
                                a.get("unit").toString(),
                                new BigDecimal(e.shippedQuantity()).setScale(6).toPlainString()));
            }
        }
        return List.copyOf(result);
    }

    private long n(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
