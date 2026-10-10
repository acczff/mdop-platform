package io.github.acczff.mdop.integration.purchasing;

import io.github.acczff.mdop.common.purchasing.PurchaseReceivingPort;
import io.github.acczff.mdop.wms.receiving.PurchaseArrivalService;
import org.springframework.stereotype.Component;

/** In-process bridge; both module writes participate in the purchasing transaction. */
@Component
public class PurchaseReceivingAdapter implements PurchaseReceivingPort {
    private final PurchaseArrivalService service;

    public PurchaseReceivingAdapter(PurchaseArrivalService service) {
        this.service = service;
    }

    public Delivery deliver(Notice notice) {
        return service.deliver(notice);
    }

    public Delivery view(long warehouseId, long arrangementId) {
        return service.view(warehouseId, arrangementId);
    }

    public void withdraw(long warehouseId, long arrangementId, String reason) {
        service.withdraw(warehouseId, arrangementId, reason);
    }
}
