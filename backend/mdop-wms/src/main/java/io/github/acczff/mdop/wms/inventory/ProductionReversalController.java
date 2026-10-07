package io.github.acczff.mdop.wms.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms/production-reversals")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:production:read')")
public class ProductionReversalController {
    private final ProductionReversalService service;

    public ProductionReversalController(ProductionReversalService service) {
        this.service = service;
    }

    public record Decision(@NotBlank @Size(max = 500) String reason) {}

    @GetMapping
    public InventoryService.Page list(
            @RequestParam @Positive long issueId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(issueId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:production:reverse')")
    public Map<String, Object> create(@Valid @RequestBody ProductionReversalService.Input input) {
        return ProductionController.write(() -> service.create(input));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:production:review')")
    public Map<String, Object> approve(
            @PathVariable @Positive long id, @Valid @RequestBody Decision input) {
        return ProductionController.write(() -> service.decide(id, "APPROVED", input.reason()));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:production:review')")
    public Map<String, Object> reject(
            @PathVariable @Positive long id, @Valid @RequestBody Decision input) {
        return ProductionController.write(() -> service.decide(id, "REJECTED", input.reason()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:production:reverse')")
    public Map<String, Object> cancel(
            @PathVariable @Positive long id, @Valid @RequestBody Decision input) {
        return ProductionController.write(() -> service.decide(id, "CANCELLED", input.reason()));
    }
}
