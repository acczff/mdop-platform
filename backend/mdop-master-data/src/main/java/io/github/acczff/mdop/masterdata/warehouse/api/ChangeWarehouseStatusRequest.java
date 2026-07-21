package io.github.acczff.mdop.masterdata.warehouse.api;

import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChangeWarehouseStatusRequest(
        @NotNull(message = "目标状态不能为空") WarehouseStatus status,
        @NotNull(message = "版本号不能为空") @PositiveOrZero(message = "版本号不能小于0") Long version) {}
