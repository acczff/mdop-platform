package io.github.acczff.mdop.finance;

import static io.github.acczff.mdop.finance.FinanceModels.*;

import io.github.acczff.mdop.common.finance.FinanceSourcePort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/finance")
@PreAuthorize("hasAuthority('finance:read')")
public class FinanceController {
    private final FinanceService service;

    public FinanceController(FinanceService service) {
        this.service = service;
    }

    @GetMapping("/baseline")
    public Map<String, Object> baseline() {
        return service.baseline();
    }

    @PostMapping("/baseline")
    @PreAuthorize("hasAuthority('finance:write')")
    public Map<String, Object> setup(@Valid @RequestBody Setup in) {
        return service.setup(in);
    }

    @PostMapping("/baseline/{action}")
    @PreAuthorize("hasAuthority('finance:review')")
    public Map<String, Object> reviewSetup(
            @PathVariable String action, @Valid @RequestBody Action in) {
        return service.reviewSetup(action, in);
    }

    @GetMapping("/sources")
    public List<FinanceSourcePort.Fact> sources(
            @RequestParam @Positive long warehouseId,
            @RequestParam String direction,
            @RequestParam @Size(max = 40) String orderNumber) {
        return service.sources(warehouseId, direction, orderNumber);
    }

    @GetMapping("/bases")
    public FinanceService.Page list(
            @RequestParam @Positive long warehouseId,
            @RequestParam String direction,
            @RequestParam(defaultValue = "") @Size(max = 40) String orderNumber,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(warehouseId, direction, orderNumber.trim(), page, size);
    }

    @GetMapping("/bases/{id}")
    public Map<String, Object> detail(@PathVariable @Positive long id) {
        return service.detail(id);
    }

    @PostMapping("/bases")
    @PreAuthorize("hasAuthority('finance:write')")
    public Map<String, Object> create(@Valid @RequestBody Draft in) {
        return service.create(in);
    }

    @PostMapping("/bases/{id}/{action}")
    @PreAuthorize(
            "(#action == 'approve' or #action == 'reject') ? hasAuthority('finance:review') : hasAuthority('finance:write')")
    public Map<String, Object> action(
            @PathVariable @Positive long id,
            @PathVariable String action,
            @Valid @RequestBody Action in) {
        return service.action(id, action, in);
    }
}
