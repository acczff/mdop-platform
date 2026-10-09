package io.github.acczff.mdop.common.manufacturing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface ProductionPurchasePort {
    record Input(
            long planLineId,
            long orderId,
            String orderNo,
            long warehouseId,
            long materialId,
            String unit,
            BigDecimal quantity,
            LocalDate neededDate,
            String reason) {}

    long create(Input input);

    List<Map<String, Object>> references(long orderId);

    boolean hasActive(long orderId);
}
