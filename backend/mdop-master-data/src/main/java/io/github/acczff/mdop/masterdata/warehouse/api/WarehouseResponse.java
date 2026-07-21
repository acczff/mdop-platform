package io.github.acczff.mdop.masterdata.warehouse.api;

import io.github.acczff.mdop.masterdata.warehouse.domain.Warehouse;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseForm;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseManagementCategory;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehousePurpose;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseStatus;
import java.time.Instant;

public record WarehouseResponse(
        Long id,
        String code,
        String name,
        WarehousePurpose purpose,
        WarehouseForm form,
        WarehouseManagementCategory managementCategory,
        WarehouseStatus status,
        String remark,
        long version,
        String createdBy,
        Instant createdAt,
        String updatedBy,
        Instant updatedAt) {

    public static WarehouseResponse from(Warehouse warehouse) {
        return new WarehouseResponse(
                warehouse.getId(),
                warehouse.getCode(),
                warehouse.getName(),
                warehouse.getPurpose(),
                warehouse.getForm(),
                warehouse.getManagementCategory(),
                warehouse.getStatus(),
                warehouse.getRemark(),
                warehouse.getVersion(),
                warehouse.getCreatedBy(),
                warehouse.getCreatedAt(),
                warehouse.getUpdatedBy(),
                warehouse.getUpdatedAt());
    }
}
