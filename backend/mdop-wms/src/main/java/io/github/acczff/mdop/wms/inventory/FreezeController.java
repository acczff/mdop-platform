package io.github.acczff.mdop.wms.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms/freezes")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:freeze:read')")
public class FreezeController {
    private final FreezeService service;

    public FreezeController(FreezeService service) {
        this.service = service;
    }

    @GetMapping
    public InventoryService.Page list(
            @RequestParam @Positive long warehouseId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(warehouseId, page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable @Positive long id) {
        return service.detail(id);
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:freeze:create')")
    public Map<String, Object> create(@Valid @RequestBody FreezeService.Input in) {
        return ProductionController.write(() -> service.create(in));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:freeze:review')")
    public Map<String, Object> release(
            @PathVariable @Positive long id, @Valid @RequestBody CrossTransferService.Action in) {
        return ProductionController.write(() -> service.release(id, in));
    }
}
