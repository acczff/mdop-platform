package io.github.acczff.mdop.manufacturing;

import static io.github.acczff.mdop.manufacturing.ProductionOrderModels.*;

import io.github.acczff.mdop.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/manufacturing")
@PreAuthorize("hasAuthority('manufacturing:read')")
public class ProductionOrderController {
    private final ProductionOrderService service;

    public ProductionOrderController(ProductionOrderService service) {
        this.service = service;
    }

    @GetMapping("/demands")
    public ProductionOrderService.Page list(
            @RequestParam @Positive long warehouseId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(warehouseId, page, size);
    }

    @GetMapping("/sales-sources")
    public List<Map<String, Object>> sources(@RequestParam @Positive long warehouseId) {
        return service.sources(warehouseId);
    }

    @GetMapping("/demands/{id}")
    public Map<String, Object> detail(@PathVariable @Positive long id) {
        return service.detail(id);
    }

    @PostMapping("/demands")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('manufacturing:write')")
    public Map<String, Object> create(@Valid @RequestBody Demand in) {
        return write(() -> service.create(in));
    }

    @PostMapping("/demands/{id}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('manufacturing:write')")
    public Map<String, Object> order(
            @PathVariable @Positive long id, @Valid @RequestBody Order in) {
        return write(() -> service.createOrder(id, in));
    }

    @PutMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('manufacturing:write')")
    public Map<String, Object> edit(@PathVariable @Positive long id, @Valid @RequestBody Order in) {
        return write(() -> service.editOrder(id, in));
    }

    @PostMapping("/orders/{id}/actions/{action}")
    @PreAuthorize(
            "(#action == 'approve' or #action == 'reject') ? hasAuthority('manufacturing:review') : hasAuthority('manufacturing:write')")
    public Map<String, Object> action(
            @PathVariable @Positive long id,
            @PathVariable String action,
            @Valid @RequestBody Action in) {
        return write(() -> service.action(id, action, in));
    }

    @PostMapping("/demands/{id}/cancel")
    @PreAuthorize("hasAuthority('manufacturing:write')")
    public Map<String, Object> cancel(
            @PathVariable @Positive long id, @Valid @RequestBody Action in) {
        return write(() -> service.cancelDemand(id, in));
    }

    private Map<String, Object> write(Supplier<Map<String, Object>> op) {
        try {
            return op.get();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(409, "PRODUCTION_ORDER_CONFLICT", "来源或有效工单已存在，请核对原记录");
        } catch (DataAccessException e) {
            throw new BusinessException(
                    503, "PRODUCTION_ORDER_STORAGE_FAILURE", "结果待核对，请保留原请求并原样重试");
        }
    }
}
