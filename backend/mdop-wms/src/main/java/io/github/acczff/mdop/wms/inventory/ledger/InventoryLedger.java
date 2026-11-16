package io.github.acczff.mdop.wms.inventory.ledger;

import io.github.acczff.mdop.common.BusinessException;
import io.github.acczff.mdop.common.audit.CurrentActorProvider;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存余额唯一写入口。
 *
 * <p>在调用方事务内执行：按「仓库 → 单据 → 余额」加锁顺序锁定 {@code wms_inventory_balance} 行，
 * 校验非负与可用量约束后更新余额并同步写入 {@code wms_inventory_transaction} 流水。
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryLedger {
    public static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999999.999999");

    private final JdbcClient db;
    private final CurrentActorProvider actor;
    private final Clock clock;

    public InventoryLedger(JdbcClient db, CurrentActorProvider actor, Clock clock) {
        this.db = db;
        this.actor = actor;
        this.clock = clock;
    }

    /** 确保存货维度对应的余额行存在，并以 {@code FOR UPDATE} 锁定返回。 */
    public Map<String, Object> ensureAndLock(BalanceSpec spec) {
        Objects.requireNonNull(spec, "spec");
        String quality = spec.qualityStatus() != null ? spec.qualityStatus() : "PENDING_INSPECTION";
        String ownerType = spec.ownerType() != null ? spec.ownerType() : "ENTERPRISE";
        String originType = spec.originType() != null ? spec.originType() : "PURCHASE";
        db.sql(
                        """
                        INSERT INTO wms_inventory_balance(
                            stock_key,warehouse_id,location_id,material_id,supplier_id,
                            batch_no,date_code,production_date,expiry_date,
                            quality_status,owner_type,owner_id,origin_type)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                        ON DUPLICATE KEY UPDATE stock_key=stock_key
                        """)
                .params(
                        spec.stockKey(),
                        spec.warehouseId(),
                        spec.locationId(),
                        spec.materialId(),
                        spec.supplierId(),
                        spec.batchNo(),
                        spec.dateCode(),
                        spec.productionDate(),
                        spec.expiryDate(),
                        quality,
                        ownerType,
                        spec.ownerId(),
                        originType)
                .update();
        return db
                .sql("SELECT * FROM wms_inventory_balance WHERE stock_key=? FOR UPDATE")
                .param(spec.stockKey())
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("库存余额行在写入后不可见"));
    }

    /** 锁定并返回指定余额行。 */
    public Map<String, Object> lock(long balanceId) {
        return db
                .sql("SELECT * FROM wms_inventory_balance WHERE id=? FOR UPDATE")
                .param(balanceId)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "STOCK_NOT_FOUND", "库存记录不存在"));
    }

    /**
     * 执行库存数量变动并写入流水。
     *
     * <p>调用方须已按「仓库 → 单据 → 余额」顺序持有上游锁；本方法再次以 {@code FOR UPDATE}
     * 读取最新余额，校验冻结、非负、上限与线边归属数量后原子更新余额并记账。
     */
    public void postMovement(MovementCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Map<String, Object> balance = lock(cmd.balanceId());
        if (cmd.requireUnfrozen()) {
            StockFreeze.requireUnfrozen(balance);
        }
        BigDecimal before = qty(balance, "on_hand_qty");
        BigDecimal onHandDelta = nonNull(cmd.onHandDelta());
        BigDecimal after = before.add(onHandDelta);
        if (after.signum() < 0) {
            throw new BusinessException(409, cmd.conflictCode(), cmd.insufficientMessage());
        }
        if (onHandDelta.signum() > 0 && after.compareTo(MAX_QUANTITY) > 0) {
            throw new BusinessException(400, "VALIDATION_FAILED", "库存数量超出支持范围");
        }

        BigDecimal availableDelta = nonNull(cmd.availableDelta());
        if (!cmd.preserveFreezeOnAvailable() && availableDelta.signum() < 0) {
            BigDecimal beforeAvailable = qty(balance, "available_qty");
            if (beforeAvailable.add(availableDelta).signum() < 0) {
                throw new BusinessException(409, cmd.conflictCode(), cmd.insufficientMessage());
            }
        }

        BigDecimal productionDelta = nonNull(cmd.productionDelta());
        if (productionDelta.signum() < 0) {
            BigDecimal beforeProduction = qty(balance, "production_qty");
            if (beforeProduction.add(productionDelta).signum() < 0) {
                throw new BusinessException(409, cmd.conflictCode(), "线边归属库存不足，请核对库存");
            }
        }

        updateBalanceQuantities(
                cmd.balanceId(),
                after,
                availableDelta,
                cmd.preserveFreezeOnAvailable(),
                productionDelta);
        if (cmd.ref() != null) {
            insertTransaction(
                    cmd.balanceId(), before, onHandDelta, after, cmd.transactionType(), cmd.ref());
        }
    }

    /** 盘点过账：将现存量与可用量同时调整为实盘数量，并在有差异时写入盘盈/盘亏流水。 */
    public void postCount(long balanceId, BigDecimal countedQty, long countId) {
        Map<String, Object> balance = lock(balanceId);
        BigDecimal before = qty(balance, "on_hand_qty");
        BigDecimal delta = countedQty.subtract(before);
        if (delta.signum() == 0) {
            return;
        }
        if (countedQty.signum() < 0) {
            throw new BusinessException(409, "STOCK_CONFLICT", "盘点数量不能为负数");
        }
        if (countedQty.compareTo(MAX_QUANTITY) > 0) {
            throw new BusinessException(400, "VALIDATION_FAILED", "库存数量超出支持范围");
        }
        db.sql("UPDATE wms_inventory_balance SET on_hand_qty=?,available_qty=? WHERE id=?")
                .params(countedQty, countedQty, balanceId)
                .update();
        insertTransaction(
                balanceId,
                before,
                delta,
                countedQty,
                delta.signum() > 0 ? "COUNT_GAIN" : "COUNT_LOSS",
                LedgerRef.count(countId));
    }

    /** 预占、释放或核销预占库存（不直接写流水，由调用方按业务决定是否写预占事件或出库流水）。 */
    public void applyReservation(ReservationCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Map<String, Object> balance = lock(cmd.balanceId());
        BigDecimal reservedDelta = nonNull(cmd.reservedDelta());
        BigDecimal availableDelta = nonNull(cmd.availableDelta());
        BigDecimal onHandDelta = nonNull(cmd.onHandDelta());

        if (qty(balance, "reserved_qty").add(reservedDelta).signum() < 0) {
            throw new BusinessException(409, cmd.conflictCode(), "预占库存不足");
        }
        if (qty(balance, "on_hand_qty").add(onHandDelta).signum() < 0) {
            throw new BusinessException(409, cmd.conflictCode(), "库存不足");
        }
        if (!cmd.preserveFreezeOnAvailable()
                && availableDelta.signum() < 0
                && qty(balance, "available_qty").add(availableDelta).signum() < 0) {
            throw new BusinessException(409, cmd.conflictCode(), "可用库存不足");
        }

        String availableExpr =
                availableDelta.signum() == 0
                        ? "available_qty"
                        : (cmd.preserveFreezeOnAvailable()
                                ? "IF(active_freeze_id IS NULL,available_qty+?,0)"
                                : "available_qty+?");
        String sql =
                "UPDATE wms_inventory_balance SET reserved_qty=reserved_qty+?,"
                        + "available_qty="
                        + availableExpr
                        + ",on_hand_qty=on_hand_qty+?,reservation_version=reservation_version+1 WHERE id=?";
        List<Object> params = new ArrayList<>();
        params.add(reservedDelta);
        if (availableDelta.signum() != 0) {
            params.add(availableDelta);
        }
        params.add(onHandDelta);
        params.add(cmd.balanceId());
        db.sql(sql).params(params.toArray()).update();
    }

    /** 冻结库存余额：记录冻结单号并将可用量清零。 */
    public void freeze(long balanceId, long freezeId) {
        lock(balanceId);
        db.sql(
                        "UPDATE wms_inventory_balance SET active_freeze_id=?,available_qty=0,reservation_version=reservation_version+1 WHERE id=?")
                .params(freezeId, balanceId)
                .update();
    }

    /** 解除库存冻结：清空冻结单号并恢复可用量。 */
    public void unfreeze(long balanceId, BigDecimal availableQty) {
        lock(balanceId);
        if (availableQty.signum() < 0) {
            throw new BusinessException(409, "FREEZE_CONFLICT", "解冻可用量不能为负数");
        }
        db.sql(
                        "UPDATE wms_inventory_balance SET active_freeze_id=NULL,available_qty=?,reservation_version=reservation_version+1 WHERE id=?")
                .params(availableQty, balanceId)
                .update();
    }

    private void updateBalanceQuantities(
            long balanceId,
            BigDecimal afterOnHand,
            BigDecimal availableDelta,
            boolean preserveFreezeOnAvailable,
            BigDecimal productionDelta) {
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        sets.add("on_hand_qty=?");
        params.add(afterOnHand);

        if (availableDelta.signum() != 0) {
            if (preserveFreezeOnAvailable) {
                sets.add("available_qty=IF(active_freeze_id IS NULL,available_qty+?,0)");
            } else {
                sets.add("available_qty=available_qty+?");
            }
            params.add(availableDelta);
        }
        if (productionDelta.signum() != 0) {
            sets.add("production_qty=production_qty+?");
            params.add(productionDelta);
        }
        params.add(balanceId);
        String sql = "UPDATE wms_inventory_balance SET " + String.join(",", sets) + " WHERE id=?";
        db.sql(sql).params(params.toArray()).update();
    }

    private void insertTransaction(
            long balanceId,
            BigDecimal before,
            BigDecimal delta,
            BigDecimal after,
            String transactionType,
            LedgerRef ref) {
        List<String> cols = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        cols.add("balance_id");
        vals.add(balanceId);
        cols.add("before_qty");
        vals.add(before);
        cols.add("change_qty");
        vals.add(delta);
        cols.add("after_qty");
        vals.add(after);
        cols.add("created_by");
        vals.add(actor.currentActor());
        cols.add("created_at");
        vals.add(Timestamp.from(clock.instant()));

        if (transactionType != null) {
            cols.add("transaction_type");
            vals.add(transactionType);
        }
        addRef(cols, vals, "receipt_id", ref.receiptId());
        addRef(cols, vals, "receipt_item_id", ref.receiptItemId());
        addRef(cols, vals, "reversed_transaction_id", ref.reversedTransactionId());
        addRef(cols, vals, "purchase_return_id", ref.purchaseReturnId());
        addRef(cols, vals, "transfer_id", ref.transferId());
        addRef(cols, vals, "count_id", ref.countId());
        addRef(cols, vals, "issue_id", ref.issueId());
        addRef(cols, vals, "consumption_id", ref.consumptionId());
        addRef(cols, vals, "production_return_id", ref.productionReturnId());
        addRef(cols, vals, "production_reversal_id", ref.productionReversalId());
        addRef(cols, vals, "finished_receipt_id", ref.finishedReceiptId());
        addRef(cols, vals, "sales_order_id", ref.salesOrderId());
        addRef(cols, vals, "cross_transfer_id", ref.crossTransferId());
        addRef(cols, vals, "cross_transfer_receipt_id", ref.crossTransferReceiptId());

        String placeholders = String.join(",", cols.stream().map(c -> "?").toList());
        String sql =
                "INSERT INTO wms_inventory_transaction("
                        + String.join(",", cols)
                        + ") VALUES("
                        + placeholders
                        + ")";
        db.sql(sql).params(vals.toArray()).update();
    }

    private static void addRef(List<String> cols, List<Object> vals, String column, Long value) {
        if (value != null) {
            cols.add(column);
            vals.add(value);
        }
    }

    private static BigDecimal qty(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? BigDecimal.ZERO : (BigDecimal) value;
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 库存维度规格，用于 {@link #ensureAndLock(BalanceSpec)}。 */
    public record BalanceSpec(
            String stockKey,
            long warehouseId,
            long locationId,
            long materialId,
            Long supplierId,
            String batchNo,
            String dateCode,
            LocalDate productionDate,
            LocalDate expiryDate,
            String qualityStatus,
            String ownerType,
            long ownerId,
            String originType) {}

    /** 库存变动指令。 */
    public record MovementCommand(
            long balanceId,
            BigDecimal onHandDelta,
            BigDecimal availableDelta,
            boolean preserveFreezeOnAvailable,
            BigDecimal productionDelta,
            boolean requireUnfrozen,
            String transactionType,
            LedgerRef ref,
            String conflictCode,
            String insufficientMessage) {

        public static MovementCommand of(
                long balanceId,
                BigDecimal onHandDelta,
                BigDecimal availableDelta,
                boolean preserveFreezeOnAvailable,
                boolean requireUnfrozen,
                String transactionType,
                LedgerRef ref,
                String conflictCode,
                String insufficientMessage) {
            return new MovementCommand(
                    balanceId,
                    onHandDelta,
                    availableDelta,
                    preserveFreezeOnAvailable,
                    BigDecimal.ZERO,
                    requireUnfrozen,
                    transactionType,
                    ref,
                    conflictCode,
                    insufficientMessage);
        }
    }

    /** 预占库存变动指令。 */
    public record ReservationCommand(
            long balanceId,
            BigDecimal reservedDelta,
            BigDecimal availableDelta,
            BigDecimal onHandDelta,
            boolean preserveFreezeOnAvailable,
            String conflictCode) {}

    /** 流水关联单据外键。 */
    public record LedgerRef(
            Long receiptId,
            Long receiptItemId,
            Long reversedTransactionId,
            Long purchaseReturnId,
            Long transferId,
            Long countId,
            Long issueId,
            Long consumptionId,
            Long productionReturnId,
            Long productionReversalId,
            Long finishedReceiptId,
            Long salesOrderId,
            Long crossTransferId,
            Long crossTransferReceiptId) {

        public static LedgerRef receipt(long receiptId, long receiptItemId) {
            return new LedgerRef(
                    receiptId,
                    receiptItemId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public static LedgerRef reversal(
                long receiptId, long receiptItemId, long reversedTransactionId) {
            return new LedgerRef(
                    receiptId,
                    receiptItemId,
                    reversedTransactionId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public static LedgerRef purchaseReturn(
                long receiptId, long receiptItemId, long purchaseReturnId) {
            return new LedgerRef(
                    receiptId,
                    receiptItemId,
                    null,
                    purchaseReturnId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public static LedgerRef transfer(long transferId) {
            return new LedgerRef(
                    null,
                    null,
                    null,
                    null,
                    transferId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public static LedgerRef count(long countId) {
            return new LedgerRef(
                    null, null, null, null, null, countId, null, null, null, null, null, null, null,
                    null);
        }

        public static LedgerRef issue(long issueId) {
            return new LedgerRef(
                    null, null, null, null, null, null, issueId, null, null, null, null, null, null,
                    null);
        }

        public static LedgerRef production(Long consumptionId, Long productionReturnId) {
            return new LedgerRef(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    consumptionId,
                    productionReturnId,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public static LedgerRef productionReversal(
                long productionReversalId, long reversedTransactionId) {
            return new LedgerRef(
                    null,
                    null,
                    reversedTransactionId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    productionReversalId,
                    null,
                    null,
                    null,
                    null);
        }

        public static LedgerRef finishedReceipt(long finishedReceiptId) {
            return new LedgerRef(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    finishedReceiptId,
                    null,
                    null,
                    null);
        }

        public static LedgerRef crossTransfer(long crossTransferId, Long crossTransferReceiptId) {
            return new LedgerRef(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    crossTransferId,
                    crossTransferReceiptId);
        }
    }
}
