package io.github.acczff.mdop.common.sales;

import java.math.BigDecimal;

/** Stable authorization and committed warehouse facts; no inventory writes by ERP. */
public interface SalesShippingPort {
    record Dispatch(
            long arrangementId,
            long orderLineId,
            String orderNumber,
            long warehouseId,
            String customerReference,
            long materialId,
            String code,
            String name,
            String unit,
            BigDecimal quantity) {}

    record Execution(
            long id,
            long arrangementId,
            long orderLineId,
            long warehouseId,
            long materialId,
            String code,
            String unit,
            String quantity,
            String status,
            Long balanceId,
            String batchNo,
            String pickedBy,
            String reviewedBy,
            String closedBy,
            String closedAt,
            String cancelReason,
            long ledgerCount,
            boolean ledgerSourceMatches,
            String shippedQuantity) {}

    Execution deliver(Dispatch input);

    Execution view(long warehouse, long arrangement);

    void withdraw(long warehouse, long arrangement, String reason);
}
