package io.github.acczff.mdop.common.finance;

import java.util.List;

/** Read-only, committed source facts owned by purchasing/sales. Finance never writes stock. */
public interface FinanceSourcePort {
    record Fact(
            long orderId,
            String orderNumber,
            long orderLineId,
            long factId,
            String factNumber,
            long warehouseId,
            long partyId,
            String partyName,
            String materialCode,
            String materialName,
            String unit,
            String quantity) {}

    String direction();

    List<Fact> facts(long warehouse, String orderNumber);
}
