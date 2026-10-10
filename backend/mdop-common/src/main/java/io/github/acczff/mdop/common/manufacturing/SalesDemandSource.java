package io.github.acczff.mdop.common.manufacturing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Sales owns eligibility and locks; callers already hold the target warehouse lock. */
public interface SalesDemandSource {
    record Source(
            long lineId,
            long orderId,
            long materialId,
            String documentNo,
            String code,
            String name,
            String unit,
            BigDecimal quantity,
            String neededDate) {}

    Source require(long warehouseId, long lineId);

    List<Map<String, Object>> candidates(long warehouseId);
}
