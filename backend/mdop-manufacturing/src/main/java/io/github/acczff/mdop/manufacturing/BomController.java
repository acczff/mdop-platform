package io.github.acczff.mdop.manufacturing;

import static io.github.acczff.mdop.manufacturing.BomModels.*;

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
@RequestMapping("/api/v1/manufacturing/boms")
@PreAuthorize("hasAuthority('bom:read')")
public class BomController {
    private final BomService service;

    public BomController(BomService service) {
        this.service = service;
    }

    @GetMapping
    public BomService.Page list(
            @RequestParam(defaultValue = "") @Size(max = 100) String q,
            @RequestParam(defaultValue = "") @Pattern(regexp = "|DRAFT|PUBLISHED|DISABLED")
                    String status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(q, status, page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable @Positive long id) {
        return service.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('bom:write')")
    public Map<String, Object> create(@Valid @RequestBody Draft in) {
        return write(() -> service.create(in));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('bom:write')")
    public Map<String, Object> edit(@PathVariable @Positive long id, @Valid @RequestBody Draft in) {
        return write(() -> service.edit(id, in));
    }

    @PostMapping("/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('bom:write')")
    public Map<String, Object> copy(@PathVariable @Positive long id, @Valid @RequestBody Copy in) {
        return write(() -> service.copy(id, in));
    }

    @PostMapping("/{id}/actions/{action}")
    @PreAuthorize("hasAuthority('bom:write')")
    public Map<String, Object> action(
            @PathVariable @Positive long id,
            @PathVariable String action,
            @Valid @RequestBody Action in) {
        return write(() -> service.action(id, action, in));
    }

    private Map<String, Object> write(Supplier<Map<String, Object>> operation) {
        try {
            return operation.get();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(409, "BOM_CONFLICT", "产品版本或请求键已存在，请核对原记录");
        } catch (DataAccessException e) {
            throw new BusinessException(503, "BOM_STORAGE_FAILURE", "处理结果待核对，请保留原请求并原样重试");
        }
    }
}
