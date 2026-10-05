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
        if ((long) (page - 1) * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("分页偏移超出支持范围，请缩小查询范围");
        }
    }

    private static String normalizeKeyword(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
