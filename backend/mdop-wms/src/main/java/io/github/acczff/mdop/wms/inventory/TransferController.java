package io.github.acczff.mdop.wms.inventory;

import io.github.acczff.mdop.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms/transfers")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:transfer:read')")
public class TransferController {
    private final TransferService service;

    public TransferController(TransferService service) {
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:transfer:confirm')")
    public Map<String, Object> confirm(@Valid @RequestBody TransferService.Input input) {
        try {
            return service.confirm(input);
        } catch (DataAccessException e) {
            throw new BusinessException(503, "TRANSFER_STORAGE_FAILURE", "移库暂未完成，请用原请求重试或核对移库记录");
        }
    }
}
