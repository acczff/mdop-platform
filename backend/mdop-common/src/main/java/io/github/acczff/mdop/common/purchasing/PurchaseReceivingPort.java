package io.github.acczff.mdop.common.purchasing;

import java.math.BigDecimal;
import java.util.List;

/** Local transactional contract. Callers own procurement facts; WMS owns receiving facts. */
public interface PurchaseReceivingPort {
    record Line(
            long arrangementLineId,
            long orderLineId,
            long materialId,
            String code,
            String name,
            String unit,
            BigDecimal quantity) {}

    record Notice(
            long arrangementId,
            String number,
            String orderNumber,
            long warehouseId,
            long supplierId,
            String supplierName,
            List<Line> lines) {}

    record Mapping(
            long arrangementLineId, long arrivalItemId, String noticeQty, String receivedQty) {}

    record Receipt(long id, String number, String status) {}

    record Delivery(long arrivalId, String status, List<Mapping> lines, List<Receipt> receipts) {}

    record Fact(
            long arrangementLineId,
            long receiptId,
            String receiptNumber,
            long receiptItemId,
            String status,
            String correctionStatus,
            String stage,
            String quantity,
            String qualityReference,
            String qualified,
            String rejected,
            Long putawayLocation) {}

    record CaseFact(long id, String kind, Long receiptId, String status, String reason) {}

    record ReturnFact(
            long id, long receiptItemId, String quantity, String status, String handover) {}

    record Fulfillment(List<Fact> receipts, List<CaseFact> cases, List<ReturnFact> returns) {}

    Fulfillment fulfillment(long warehouseId, long arrangementId);

    Delivery deliver(Notice notice);

    Delivery view(long warehouseId, long arrangementId);

    void withdraw(long warehouseId, long arrangementId, String reason);
}
