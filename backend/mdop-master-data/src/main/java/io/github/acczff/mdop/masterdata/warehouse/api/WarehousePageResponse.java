package io.github.acczff.mdop.masterdata.warehouse.api;

import io.github.acczff.mdop.masterdata.warehouse.domain.Warehouse;
import java.util.List;
import org.springframework.data.domain.Page;

public record WarehousePageResponse(
        List<WarehouseResponse> items, int page, int size, long totalElements, int totalPages) {

    public WarehousePageResponse {
        items = List.copyOf(items);
    }

    public static WarehousePageResponse from(Page<Warehouse> result) {
        return new WarehousePageResponse(
                result.getContent().stream().map(WarehouseResponse::from).toList(),
                result.getNumber() + 1,
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }
}
