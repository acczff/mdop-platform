package io.github.acczff.mdop.masterdata.warehouse.api;

import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseForm;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseManagementCategory;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehousePurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateWarehouseRequest(
        @NotBlank(message = "仓库名称不能为空") @Size(max = 100, message = "仓库名称不能超过100个字符") String name,
        @NotNull(message = "主要用途不能为空") WarehousePurpose purpose,
        @NotNull(message = "仓库形态不能为空") WarehouseForm form,
        @NotNull(message = "管理类别不能为空") WarehouseManagementCategory managementCategory,
        @Size(max = 500, message = "备注不能超过500个字符") String remark,
        @NotNull(message = "版本号不能为空") @PositiveOrZero(message = "版本号不能小于0") Long version) {}
