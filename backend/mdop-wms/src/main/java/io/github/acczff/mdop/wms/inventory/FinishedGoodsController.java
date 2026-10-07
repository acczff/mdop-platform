package io.github.acczff.mdop.wms.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms/finished-receipts")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:finished:read')")
public class FinishedGoodsController {
    private final FinishedGoodsService service;

    public FinishedGoodsController(FinishedGoodsService service) {
        this.service = service;
    }

    public record Location(@Positive long locationId) {}

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

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:finished:receive')")
    public Map<String, Object> receive(
            @PathVariable @Positive long id, @Valid @RequestBody Location input) {
        return ProductionController.write(() -> service.receive(id, input.locationId()));
    }

    @PostMapping("/{id}/putaway")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:finished:putaway')")
    public Map<String, Object> putaway(
            @PathVariable @Positive long id, @Valid @RequestBody Location input) {
        return ProductionController.write(() -> service.putaway(id, input.locationId()));
    }
}
