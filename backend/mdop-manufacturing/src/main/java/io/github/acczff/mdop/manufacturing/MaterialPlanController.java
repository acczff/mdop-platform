package io.github.acczff.mdop.manufacturing;

import io.github.acczff.mdop.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/manufacturing/orders/{id}/materials")
@PreAuthorize("hasAuthority('manufacturing:read')")
public class MaterialPlanController {
    private final MaterialPlanService service;

    public MaterialPlanController(MaterialPlanService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> detail(@PathVariable @Positive long id) {
        return service.detail(id);
    }

    @PostMapping("/calculate")
    @PreAuthorize("hasAuthority('manufacturing:write')")
    public Map<String, Object> calculate(
            @PathVariable @Positive long id, @Valid @RequestBody MaterialPlanService.Calculate in) {
        return write(() -> service.calculate(id, in));
    }

    @PostMapping("/confirm")
    @PreAuthorize("hasAuthority('manufacturing:write')")
    public Map<String, Object> confirm(
            @PathVariable @Positive long id, @Valid @RequestBody MaterialPlanService.Confirm in) {
        return write(() -> service.confirm(id, in));
    }

    private Map<String, Object> write(Supplier<Map<String, Object>> op) {
        try {
            return op.get();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(409, "MATERIAL_PLAN_CONFLICT", "建议或请求已处理，请核对当前记录");
        } catch (DataAccessException e) {
            throw new BusinessException(503, "MATERIAL_PLAN_STORAGE_FAILURE", "结果待核对，请保留原请求并原样重试");
        }
    }
}
