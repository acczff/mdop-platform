package io.github.acczff.mdop.masterdata.warehouse.api;

import io.github.acczff.mdop.masterdata.warehouse.application.WarehouseSearchCriteria;
import io.github.acczff.mdop.masterdata.warehouse.application.WarehouseService;
import io.github.acczff.mdop.masterdata.warehouse.domain.Warehouse;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseForm;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseManagementCategory;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehousePurpose;
import io.github.acczff.mdop.masterdata.warehouse.domain.WarehouseStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/master-data/warehouses")
public class WarehouseController {

    private static final String BASE_PATH = "/api/master-data/warehouses";

    private final WarehouseService service;

    public WarehouseController(WarehouseService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WarehouseResponse> create(
            @Valid @RequestBody CreateWarehouseRequest request) {
        Warehouse warehouse =
                service.create(
                        request.code(),
                        request.name(),
                        request.purpose(),
                        request.form(),
                        request.managementCategory(),
                        request.remark());

        WarehouseResponse response = WarehouseResponse.from(warehouse);

        return ResponseEntity.created(URI.create(BASE_PATH + "/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public WarehouseResponse get(@PathVariable @Positive Long id) {
        return WarehouseResponse.from(service.get(id));
    }

    @GetMapping
    public WarehousePageResponse search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) WarehousePurpose purpose,
            @RequestParam(required = false) WarehouseForm form,
            @RequestParam(required = false) WarehouseManagementCategory managementCategory,
            @RequestParam(required = false) WarehouseStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        WarehouseSearchCriteria criteria =
                new WarehouseSearchCriteria(
                        keyword, purpose, form, managementCategory, status, page, size);

        return WarehousePageResponse.from(service.search(criteria));
    }

    @PutMapping("/{id}")
    public WarehouseResponse update(
            @PathVariable @Positive Long id, @Valid @RequestBody UpdateWarehouseRequest request) {
        return WarehouseResponse.from(
                service.update(
                        id,
                        request.name(),
                        request.purpose(),
                        request.form(),
                        request.managementCategory(),
                        request.remark(),
                        request.version()));
    }

    @PutMapping("/{id}/status")
    public WarehouseResponse changeStatus(
            @PathVariable @Positive Long id,
            @Valid @RequestBody ChangeWarehouseStatusRequest request) {
        return WarehouseResponse.from(
                service.changeStatus(id, request.status(), request.version()));
    }
}
