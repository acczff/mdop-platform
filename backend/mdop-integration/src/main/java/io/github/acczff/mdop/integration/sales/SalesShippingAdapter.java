package io.github.acczff.mdop.integration.sales;

import io.github.acczff.mdop.common.sales.SalesShippingPort;
import io.github.acczff.mdop.wms.inventory.SalesDispatchService;
import org.springframework.stereotype.Component;

@Component
public class SalesShippingAdapter implements SalesShippingPort {
    private final SalesDispatchService service;

    public SalesShippingAdapter(SalesDispatchService service) {
        this.service = service;
    }

    public Execution deliver(Dispatch input) {
        return service.deliver(input);
    }

    public Execution view(long warehouse, long arrangement) {
        return service.view(warehouse, arrangement);
    }

    public void withdraw(long warehouse, long arrangement, String reason) {
        service.withdraw(warehouse, arrangement, reason);
    }
}
