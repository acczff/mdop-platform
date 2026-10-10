package io.github.acczff.mdop.sales;

import static io.github.acczff.mdop.sales.SalesModels.*;

import io.github.acczff.mdop.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sales/documents")
@PreAuthorize("hasAuthority('sales:read')")
public class SalesOrderController {
    private final SalesOrderService service;

    public SalesOrderController(SalesOrderService service) {
        this.service = service;
    }

    @GetMapping
    public SalesOrderService.Page list(
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
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('sales:write')")
    public Map<String, Object> create(@Valid @RequestBody Draft input) {
        return write(() -> service.create(input));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('sales:write')")
    public Map<String, Object> edit(
            @PathVariable @Positive long id, @Valid @RequestBody Draft input) {
        return write(() -> service.edit(id, input));
    }

    @PostMapping("/{id}/actions/{action}")
    @PreAuthorize(
            "(#action == 'approve' or #action == 'reject') ? hasAuthority('sales:review') : hasAuthority('sales:write')")
    public Map<String, Object> action(
            @PathVariable @Positive long id,
            @PathVariable String action,
            @Valid @RequestBody Action input) {
        return write(() -> service.action(id, action, input));
    }

    @PostMapping("/{id}/arrangements")
    @PreAuthorize("hasAuthority('sales:write')")
    public Map<String, Object> arrange(
            @PathVariable @Positive long id, @Valid @RequestBody ArrangementInput input) {
        return write(() -> service.arrange(id, input));
    }

    @PostMapping("/{id}/arrangements/{arrangementId}/{action}")
    @PreAuthorize("hasAuthority('sales:write')")
    public Map<String, Object> arrangementAction(
            @PathVariable @Positive long id,
            @PathVariable @Positive long arrangementId,
            @PathVariable String action,
            @Valid @RequestBody Action input) {
        try {
            return write(() -> service.arrangementAction(id, arrangementId, action, input));
        } catch (BusinessException failure) {
            if (action.equals("deliver")) {
                // Failure recording is best effort after rollback, never mask the original outcome.
                try {
                    service.recordDeliveryFailure(
                            id, arrangementId, input.version(), failure.getMessage());
                } catch (RuntimeException ignored) {
                    /* Original failure remains authoritative. */
                }
            }
            throw failure;
        }
    }

    private Map<String, Object> write(Supplier<Map<String, Object>> operation) {
        try {
            return operation.get();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(409, "SALES_ORDER_CONFLICT", "单据或请求键发生冲突，请核对原单据");
        } catch (DataAccessException e) {
            throw new BusinessException(
                    503, "SALES_ORDER_STORAGE_FAILURE", "处理结果待核对，请保留原请求键查证或原样重试");
        }
    }
}
