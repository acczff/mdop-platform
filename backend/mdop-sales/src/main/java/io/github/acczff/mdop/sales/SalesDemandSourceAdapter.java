package io.github.acczff.mdop.sales;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.manufacturing.SalesDemandSource;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SalesDemandSourceAdapter implements SalesDemandSource {
    private final JdbcClient db;

    public SalesDemandSourceAdapter(JdbcClient db) {
        this.db = db;
    }

    private static final String SELECT =
            "SELECT l.*,d.document_no,d.status,d.needed_date FROM sal_line l JOIN sal_document d ON d.id=l.document_id WHERE d.warehouse_id=?";

    public Source require(long warehouse, long line) {
        var r =
                db
                        .sql(SELECT + " AND l.id=? FOR SHARE")
                        .params(warehouse, line)
                        .query()
                        .listOfRows()
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                404, "SALES_SOURCE_MISSING", "本仓销售行不存在"));
        if (!Set.of("APPROVED", "FULFILLING").contains(r.get("status")))
            throw new BusinessException(409, "SALES_SOURCE_INACTIVE", "销售订单须已批准且未结束");
        return new Source(
                line,
                ((Number) r.get("document_id")).longValue(),
                ((Number) r.get("material_id")).longValue(),
                r.get("document_no").toString(),
                r.get("material_code").toString(),
                r.get("material_name").toString(),
                r.get("unit").toString(),
                new BigDecimal(r.get("quantity").toString()),
                r.get("needed_date").toString());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> candidates(long warehouse) {
        var rows =
                db.sql(
                                SELECT
                                        + " AND d.status IN ('APPROVED','FULFILLING') ORDER BY l.id DESC LIMIT 1000")
                        .param(warehouse)
                        .query()
                        .listOfRows();
        rows.forEach(
                r ->
                        r.replaceAll(
                                (k, v) ->
                                        v instanceof BigDecimal || v instanceof java.sql.Date
                                                ? v.toString()
                                                : v));
        return rows;
    }
}
