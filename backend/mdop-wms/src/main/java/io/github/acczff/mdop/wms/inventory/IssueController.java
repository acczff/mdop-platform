package io.github.acczff.mdop.wms.inventory;

import io.github.acczff.mdop.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms/issues")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:issue:read')")
public class IssueController {
    private final IssueService service;

    public IssueController(IssueService service) {
        this.service = service;
    }

    @GetMapping
    public InventoryService.Page list(
            @RequestParam @Positive long warehouseId,
            @RequestParam @Positive long targetWarehouseId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(warehouseId, targetWarehouseId, page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable @Positive long id) {
        return service.detail(id);
    }

    @GetMapping("/{id}/events")
    public List<Map<String, Object>> events(@PathVariable @Positive long id) {
        return service.events(id);
    }

    @PostMapping("/{id}/reserve")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:issue:reserve')")
    public Map<String, Object> reserve(
            @PathVariable @Positive long id, @Valid @RequestBody IssueService.Reserve input) {
        return write(() -> service.reserve(id, input));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:issue:cancel')")
    public Map<String, Object> cancel(
            @PathVariable @Positive long id, @Valid @RequestBody IssueService.Cancel input) {
        return write(() -> service.cancel(id, input.reason()));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:issue:confirm')")
    public Map<String, Object> confirm(@PathVariable @Positive long id) {
        return write(() -> service.confirm(id));
    }

    private Map<String, Object> write(Supplier<Map<String, Object>> action) {
        try {
            return action.get();
        } catch (DataAccessException e) {
            throw new BusinessException(503, "ISSUE_STORAGE_FAILURE", "领料记账暂未完成，请刷新核对后重试原单");
        }
    }
}
