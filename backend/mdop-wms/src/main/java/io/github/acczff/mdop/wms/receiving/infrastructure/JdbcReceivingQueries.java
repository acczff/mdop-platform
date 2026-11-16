package io.github.acczff.mdop.wms.receiving.infrastructure;

import static io.github.acczff.mdop.wms.receiving.application.ReceivingModels.*;

import io.github.acczff.mdop.wms.receiving.application.ReceivingQueries;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ReceivingQueries} 的 JDBC 实现。
 *
 * <p>这里集中收货域的读取与加锁语句：显式列名、参数绑定、行到记录的映射。
 * 语句按表分组，便于新增索引后逐条核对执行计划；不包含业务判断。
 */
@Repository
public class JdbcReceivingQueries implements ReceivingQueries {

    private static final String ARRIVAL_COLUMNS =
            "id,source_system,external_notice_no,purchase_order_no,supplier_id,warehouse_id,"
                    + "status,version,supplier_code,supplier_name";
    private static final String ARRIVAL_ITEM_COLUMNS =
            "id,arrival_id,material_id,material_code,material_name,unit,notice_qty,received_qty";
    private static final String RECEIPT_COLUMNS =
            "id,receipt_no,arrival_id,status,creation_key,creation_hash,submit_key,version,"
                    + "correction_status,downstream_stage";
    private static final String RECEIPT_ITEM_COLUMNS =
            "id,receipt_id,arrival_item_id,material_id,location_id,quantity,batch_no,date_code,"
                    + "production_date,expiry_date";
    private static final String BALANCE_COLUMNS =
            "id,warehouse_id,location_id,material_id,supplier_id,batch_no,date_code,"
                    + "quality_status,on_hand_qty,available_qty";
    private static final String LEDGER_COLUMNS =
            "id,receipt_id,receipt_item_id,balance_id,before_qty,change_qty,after_qty,"
                    + "transaction_type,reversed_transaction_id,purchase_return_id";

    private final JdbcClient db;

    public JdbcReceivingQueries(JdbcClient db) {
        this.db = db;
    }

    @Override
    public List<Arrival> arrivals(long warehouseId) {
        return db.sql(
                        "SELECT "
                                + ARRIVAL_COLUMNS
                                + " FROM wms_arrival_notice WHERE warehouse_id=? ORDER BY id DESC LIMIT 100")
                .param(warehouseId)
                .query(Arrival.class)
                .list();
    }

    @Override
    public List<ArrivalItem> arrivalItems(long arrivalId) {
        return db.sql(
                        "SELECT "
                                + ARRIVAL_ITEM_COLUMNS
                                + " FROM wms_arrival_notice_item WHERE arrival_id=? ORDER BY id")
                .param(arrivalId)
                .query(ArrivalItem.class)
                .list();
    }

    @Override
    public Optional<Arrival> findArrival(long id, boolean lock) {
        return db.sql(
                        "SELECT "
                                + ARRIVAL_COLUMNS
                                + " FROM wms_arrival_notice WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query(Arrival.class)
                .optional();
    }

    @Override
    public List<Receipt> receipts(long arrivalId) {
        return db.sql(
                        "SELECT "
                                + RECEIPT_COLUMNS
                                + " FROM wms_receipt WHERE arrival_id=? ORDER BY id DESC LIMIT 100")
                .param(arrivalId)
                .query(Receipt.class)
                .list();
    }

    @Override
    public Optional<Receipt> findReceipt(long id, boolean lock) {
        return db.sql(
                        "SELECT "
                                + RECEIPT_COLUMNS
                                + " FROM wms_receipt WHERE id=?"
                                + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query(Receipt.class)
                .optional();
    }

    @Override
    public Optional<Receipt> lockReceiptByCreationKey(String creationKey) {
        return db.sql(
                        "SELECT "
                                + RECEIPT_COLUMNS
                                + " FROM wms_receipt WHERE creation_key=? FOR UPDATE")
                .param(creationKey)
                .query(Receipt.class)
                .optional();
    }

    @Override
    public List<Item> items(long receiptId) {
        return db.sql(
                        "SELECT "
                                + RECEIPT_ITEM_COLUMNS
                                + " FROM wms_receipt_item WHERE receipt_id=? ORDER BY id")
                .param(receiptId)
                .query(Item.class)
                .list();
    }

    @Override
    public Optional<Item> findItem(long id) {
        return db.sql("SELECT " + RECEIPT_ITEM_COLUMNS + " FROM wms_receipt_item WHERE id=?")
                .param(id)
                .query(Item.class)
                .optional();
    }

    @Override
    public Optional<ArrivalItem> findArrivalItem(long id, long arrivalId) {
        return db.sql(
                        "SELECT "
                                + ARRIVAL_ITEM_COLUMNS
                                + " FROM wms_arrival_notice_item WHERE id=? AND arrival_id=?")
                .params(id, arrivalId)
                .query(ArrivalItem.class)
                .optional();
    }

    @Override
    public int countRemainingArrivalLines(long arrivalId) {
        return db.sql(
                        "SELECT COUNT(*) FROM wms_arrival_notice_item WHERE arrival_id=? AND received_qty<notice_qty")
                .param(arrivalId)
                .query(Integer.class)
                .single();
    }

    @Override
    public List<Balance> balances(long warehouseId, int limit) {
        return db.sql(
                        "SELECT "
                                + BALANCE_COLUMNS
                                + " FROM wms_inventory_balance WHERE warehouse_id=? ORDER BY id DESC LIMIT ?")
                .params(warehouseId, limit)
                .query(Balance.class)
                .list();
    }

    @Override
    public Optional<Balance> lockBalance(String stockKey) {
        return db.sql(
                        "SELECT "
                                + BALANCE_COLUMNS
                                + " FROM wms_inventory_balance WHERE stock_key=? FOR UPDATE")
                .param(stockKey)
                .query(Balance.class)
                .optional();
    }

    @Override
    public List<Ledger> ledger(long receiptId) {
        return db.sql(
                        "SELECT "
                                + LEDGER_COLUMNS
                                + " FROM wms_inventory_transaction WHERE receipt_id=? ORDER BY id")
                .param(receiptId)
                .query(Ledger.class)
                .list();
    }
}
