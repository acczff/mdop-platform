package io.github.acczff.mdop.common.manufacturing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface ProductionCompletionPort {
    record Input(
            long reportId,
            long orderId,
            String reportNo,
            String orderNo,
            long warehouseId,
            long materialId,
            BigDecimal quantity,
            String batchNo,
            String dateCode,
            LocalDate productionDate,
            LocalDate expiryDate,
            String materialCode,
            String materialName,
            String unit) {}

    long create(Input input);

    List<Map<String, Object>> references(long orderId);
}
