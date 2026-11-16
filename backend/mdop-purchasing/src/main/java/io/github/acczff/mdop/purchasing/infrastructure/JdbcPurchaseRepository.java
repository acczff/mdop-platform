package io.github.acczff.mdop.purchasing.infrastructure;

import io.github.acczff.mdop.purchasing.domain.PurchaseRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 采购单据的 JDBC 读取实现。
 *
 * <p>SQL 集中在此处，显式列出列名（不依赖 {@code SELECT *}），便于审查查询计划与
 * 控制返回字段。列的顺序不影响 JSON 输出，字段名保持与既有接口一致。
 */
@Repository
public class JdbcPurchaseRepository implements PurchaseRepository {

    private static final String DOCUMENT_COLUMNS =
            """
            SELECT id,document_no,kind,source_type,request_id,active_order_id,
                   warehouse_id,warehouse_name,supplier_id,supplier_name,purpose,
                   needed_date,status,version,created_by,last_edited_by,submitted_by,
                   created_at,updated_at,production_order_id,production_order_no,
                   production_plan_line_id,original_order_id
            FROM pur_document
            """;

    private static final String LINE_COLUMNS =
            """
            SELECT id,document_id,source_line_id,material_id,material_code,material_name,
                   unit,CAST(quantity AS CHAR) quantity
            FROM pur_line
            """;

    private static final String ARRANGEMENT_COLUMNS =
            """
            SELECT id,order_id,expected_date,created_by,created_at,status,
                   wms_arrival_id,last_error
            FROM pur_arrangement
            """;

    private static final String ARRANGEMENT_LINE_COLUMNS =
            """
            SELECT a.id,a.order_line_id,CAST(a.quantity AS CHAR) quantity,
                   a.wms_arrival_item_id,l.material_id,l.material_code,l.material_name,l.unit
            FROM pur_arrangement_line a
            JOIN pur_line l ON l.id=a.order_line_id
            """;

    private final JdbcClient db;

    public JdbcPurchaseRepository(JdbcClient db) {
        this.db = db;
    }

    @Override
    public Optional<Map<String, Object>> findDocument(long id) {
        return db.sql(DOCUMENT_COLUMNS + " WHERE id=?").param(id).query().listOfRows().stream()
                .findFirst();
    }

    @Override
    public Map<String, Object> lockDocument(long id) {
        return db.sql(DOCUMENT_COLUMNS + " WHERE id=? FOR UPDATE").param(id).query().singleRow();
    }

    @Override
    public List<Map<String, Object>> findLines(long documentId) {
        return db.sql(LINE_COLUMNS + " WHERE document_id=? ORDER BY material_id")
                .param(documentId)
                .query()
                .listOfRows();
    }

    @Override
    public long countDocuments(long warehouseId, String kind) {
        return db.sql("SELECT COUNT(*) FROM pur_document WHERE warehouse_id=? AND kind=?")
                .params(warehouseId, kind)
                .query(Long.class)
                .single();
    }

    @Override
    public List<Map<String, Object>> findDocumentPage(
            long warehouseId, String kind, int size, long offset) {
        return db.sql(
                        DOCUMENT_COLUMNS
                                + " WHERE warehouse_id=? AND kind=? ORDER BY id DESC LIMIT ? OFFSET ?")
                .params(warehouseId, kind, size, offset)
                .query()
                .listOfRows();
    }

    @Override
    public Optional<Map<String, Object>> findArrangement(long orderId, long arrangementId) {
        return db
                .sql(ARRANGEMENT_COLUMNS + " WHERE id=? AND order_id=?")
                .params(arrangementId, orderId)
                .query()
                .listOfRows()
                .stream()
                .findFirst();
    }

    @Override
    public List<Map<String, Object>> findArrangementLines(long arrangementId) {
        return db.sql(ARRANGEMENT_LINE_COLUMNS + " WHERE a.arrangement_id=? ORDER BY a.id")
                .param(arrangementId)
                .query()
                .listOfRows();
    }

    @Override
    public List<Map<String, Object>> findArrangements(long orderId) {
        return db.sql(ARRANGEMENT_COLUMNS + " WHERE order_id=? ORDER BY id DESC")
                .param(orderId)
                .query()
                .listOfRows();
    }
}
