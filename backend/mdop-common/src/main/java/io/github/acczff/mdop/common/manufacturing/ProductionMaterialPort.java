package io.github.acczff.mdop.common.manufacturing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** WMS owns issue, reservation and line-side inventory facts. */
public interface ProductionMaterialPort {
    record Input(
            long orderId,
            long planLineId,
            String orderNo,
            String demandNo,
            long warehouseId,
            long targetWarehouseId,
            long materialId,
            BigDecimal quantity) {}

    long create(Input input);

    List<Map<String, Object>> references(long orderId);

    boolean hasActive(long orderId);

    List<Map<String, Object>> closureReferences(long orderId);
}
