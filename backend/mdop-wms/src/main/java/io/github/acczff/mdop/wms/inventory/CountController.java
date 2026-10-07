package io.github.acczff.mdop.wms.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms/counts")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:count:read')")
public class CountController {
    private final CountService service;

    public CountController(CountService service) {
        this.service = service;
    }

    public record Create(
            @Positive long balanceId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey) {}

    public record Submit(
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            @NotBlank @Size(max = 500) String reason) {}

    public record Decision(@NotBlank @Size(max = 500) String reason) {}

    @GetMapping
    public InventoryService.Page list(
            @RequestParam @Positive long warehouseId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(warehouseId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:count:create')")
    public Map<String, Object> create(@Valid @RequestBody Create input) {
        return service.create(input);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:count:submit')")
    public Map<String, Object> submit(
            @PathVariable @Positive long id, @Valid @RequestBody Submit input) {
        return service.submit(id, input);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:count:review')")
    public Map<String, Object> approve(@PathVariable @Positive long id) {
        try {
            return service.approve(id);
        } catch (org.springframework.dao.DataAccessException e) {
            throw new io.github.acczff.mdop.common.BusinessException(
                    503, "COUNT_STORAGE_FAILURE", "盘点过账暂未完成，请刷新核对后重试原单");
        }
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:count:review')")
    public Map<String, Object> reject(
            @PathVariable @Positive long id, @Valid @RequestBody Decision input) {
        return service.close(id, input.reason(), false);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:count:cancel')")
    public Map<String, Object> cancel(
            @PathVariable @Positive long id, @Valid @RequestBody Decision input) {
        return service.close(id, input.reason(), true);
    }
}
