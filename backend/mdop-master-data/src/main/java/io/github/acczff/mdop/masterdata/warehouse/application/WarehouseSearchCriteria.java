package io.github.acczff.mdop.masterdata.warehouse.application;

import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseForm;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseManagementCategory;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehousePurpose;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseStatus;

public record WarehouseSearchCriteria(
        String keyword,
        WarehousePurpose purpose,
        WarehouseForm form,
        WarehouseManagementCategory managementCategory,
        WarehouseStatus status,
        int page,
        int size) {

    public WarehouseSearchCriteria {
        keyword = normalizeKeyword(keyword);

        if (page < 1) {
            throw new IllegalArgumentException("页码必须从1开始");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("每页数量必须在1到100之间");
        }
    }

    private static String normalizeKeyword(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
