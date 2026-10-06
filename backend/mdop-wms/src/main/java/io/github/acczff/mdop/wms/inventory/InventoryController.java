package io.github.acczff.mdop.wms.inventory;

import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms/stock")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:inventory:read')")
public class InventoryController {
    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping
    public InventoryService.Page search(
            @RequestParam @Positive long warehouseId,
            @RequestParam(defaultValue = "") @Size(max = 100) String keyword,
            @RequestParam(defaultValue = "") @Size(max = 64) String batch,
            @RequestParam(defaultValue = "")
                    @Pattern(regexp = "|PENDING_INSPECTION|QUALIFIED|REJECTED")
                    String quality,
            @RequestParam(required = false) @Positive Long locationId,
            @RequestParam(defaultValue = "false") boolean includeZero,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.search(
                warehouseId, keyword, batch, quality, locationId, includeZero, page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable @Positive long id) {
        return service.detail(id);
    }

    @GetMapping("/{id}/transactions")
    public InventoryService.Page transactions(
            @PathVariable @Positive long id,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.transactions(id, page, size);
    }
}
