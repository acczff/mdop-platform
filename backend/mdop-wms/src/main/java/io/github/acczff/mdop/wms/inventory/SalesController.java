package io.github.acczff.mdop.wms.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms/sales-orders")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:sales:read')")
public class SalesController {
    private final SalesService service;

    public SalesController(SalesService service) {
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

    @GetMapping("/{id}/history")
    public java.util.List<Map<String, Object>> history(@PathVariable @Positive long id) {
        return service.history(id);
    }

    @GetMapping("/{id}/reservations")
    public java.util.List<Map<String, Object>> reservations(@PathVariable @Positive long id) {
        return service.reservations(id);
    }

    @PostMapping("/{id}/reserve")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:sales:reserve')")
    public Map<String, Object> reserve(
            @PathVariable @Positive long id, @Valid @RequestBody SalesService.Reserve in) {
        return ProductionController.write(() -> service.reserve(id, in));
    }

    @PostMapping("/{id}/pick")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:sales:pick')")
    public Map<String, Object> pick(
            @PathVariable @Positive long id, @Valid @RequestBody SalesService.Pick in) {
        return ProductionController.write(() -> service.pick(id, in));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:sales:review')")
    public Map<String, Object> review(
            @PathVariable @Positive long id, @Valid @RequestBody SalesService.Review in) {
        return ProductionController.write(() -> service.review(id, in));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:sales:cancel')")
    public Map<String, Object> cancel(
            @PathVariable @Positive long id, @Valid @RequestBody SalesService.Cancel in) {
        return ProductionController.write(() -> service.cancel(id, in.reason()));
    }

    @PostMapping("/{id}/ship")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:sales:ship')")
    public Map<String, Object> ship(@PathVariable @Positive long id) {
        return ProductionController.write(() -> service.ship(id));
    }
}
