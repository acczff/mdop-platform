package io.github.acczff.mdop.manufacturing;

import io.github.acczff.mdop.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/manufacturing/orders/{id}/closure")
@PreAuthorize("hasAuthority('manufacturing:read')")
public class ProductionClosureController {
    private final ProductionClosureService service;

    public ProductionClosureController(ProductionClosureService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> detail(@PathVariable @Positive long id) {
        return service.detail(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('manufacturing:review')")
    public Map<String, Object> close(
            @PathVariable @Positive long id,
            @Valid @RequestBody ProductionClosureService.Close in) {
        try {
            return service.close(id, in);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(409, "PRODUCTION_CLOSURE_CONFLICT", "结束记录已存在或来源冲突，请核对原记录");
        } catch (DataAccessException e) {
            throw new BusinessException(
                    503, "PRODUCTION_CLOSURE_STORAGE_FAILURE", "结果待核对，请保留原请求并原样重试");
        }
    }
}
