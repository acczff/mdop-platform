package io.github.acczff.mdop.wms.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms/cross-transfers")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:cross-transfer:read')")
public class CrossTransferController {
    private final CrossTransferService service;

    public CrossTransferController(CrossTransferService service) {
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

    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(@PathVariable @Positive long id) {
        return service.history(id);
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:cross-transfer:create')")
    public Map<String, Object> create(@Valid @RequestBody TransferService.Input in) {
        return ProductionController.write(() -> service.create(in));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:cross-transfer:review')")
    public Map<String, Object> approve(
            @PathVariable @Positive long id, @Valid @RequestBody CrossTransferService.Action in) {
        return ProductionController.write(() -> service.act(id, "APPROVE", in));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:cross-transfer:review')")
    public Map<String, Object> reject(
            @PathVariable @Positive long id, @Valid @RequestBody CrossTransferService.Action in) {
        return ProductionController.write(() -> service.act(id, "REJECT", in));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:cross-transfer:cancel')")
    public Map<String, Object> cancel(
            @PathVariable @Positive long id, @Valid @RequestBody CrossTransferService.Action in) {
        return ProductionController.write(() -> service.act(id, "CANCEL", in));
    }

    @PostMapping("/{id}/ship")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:cross-transfer:ship')")
    public Map<String, Object> ship(
            @PathVariable @Positive long id, @Valid @RequestBody CrossTransferService.Action in) {
        return ProductionController.write(() -> service.act(id, "SHIP", in));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:cross-transfer:receive')")
    public Map<String, Object> receive(
            @PathVariable @Positive long id, @Valid @RequestBody CrossTransferService.Action in) {
        return ProductionController.write(() -> service.act(id, "RECEIVE", in));
    }
}
