package io.github.acczff.mdop.wms.inventory;

import io.github.acczff.mdop.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms/production")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:production:read')")
public class ProductionController {
    private final ProductionService service;

    public ProductionController(ProductionService service) {
        this.service = service;
    }

    @GetMapping("/issues/{id}")
    public Map<String, Object> detail(@PathVariable @Positive long id) {
        return service.detail(id);
    }

    @GetMapping("/issues/{id}/records")
    public InventoryService.Page records(
            @PathVariable @Positive long id,
            @RequestParam(defaultValue = "false") boolean returns,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.records(id, returns, page, size);
    }

    @PostMapping("/returns/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:production:confirm')")
    public Map<String, Object> confirm(@PathVariable @Positive long id) {
        return write(() -> service.confirm(id));
    }

    @PostMapping("/returns/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:production:cancel')")
    public Map<String, Object> cancel(
            @PathVariable @Positive long id, @Valid @RequestBody IssueService.Cancel input) {
        return write(() -> service.cancel(id, input.reason()));
    }

    static Map<String, Object> write(Supplier<Map<String, Object>> action) {
        try {
            return action.get();
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(409, "PRODUCTION_EVENT_CONFLICT", "事件编号已被接收，请核对原记录");
        } catch (DataAccessException e) {
            throw new BusinessException(
                    503, "PRODUCTION_STORAGE_FAILURE", "业务暂未处理完成，请保留原事件编号核对后重试");
        }
    }
}
