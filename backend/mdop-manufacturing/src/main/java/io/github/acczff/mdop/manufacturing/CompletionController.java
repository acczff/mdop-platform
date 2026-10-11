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
@RequestMapping("/api/v1/manufacturing/orders/{id}/completions")
public class CompletionController {
    private final CompletionService service;

    public CompletionController(CompletionService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('manufacturing:read')")
    public Map<String, Object> detail(@PathVariable @Positive long id) {
        return service.detail(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('manufacturing:completion:report')")
    public Map<String, Object> report(
            @PathVariable @Positive long id, @Valid @RequestBody CompletionService.Report in) {
        return write(() -> service.report(id, in));
    }

    @PostMapping("/{reportId}/approve")
    @PreAuthorize("hasAuthority('manufacturing:completion:review')")
    public Map<String, Object> approve(
            @PathVariable @Positive long id,
            @PathVariable @Positive long reportId,
            @Valid @RequestBody CompletionService.Action in) {
        return write(() -> service.action(id, reportId, "approve", in));
    }

    @PostMapping("/{reportId}/reject")
    @PreAuthorize("hasAuthority('manufacturing:completion:review')")
    public Map<String, Object> reject(
            @PathVariable @Positive long id,
            @PathVariable @Positive long reportId,
            @Valid @RequestBody CompletionService.Action in) {
        return write(() -> service.action(id, reportId, "reject", in));
    }

    @PostMapping("/{reportId}/cancel")
    @PreAuthorize("hasAuthority('manufacturing:completion:report')")
    public Map<String, Object> cancel(
            @PathVariable @Positive long id,
            @PathVariable @Positive long reportId,
            @Valid @RequestBody CompletionService.Action in) {
        return write(() -> service.action(id, reportId, "cancel", in));
    }

    private Map<String, Object> write(Supplier<Map<String, Object>> op) {
        try {
            return op.get();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(409, "COMPLETION_CONFLICT", "报告号或请求已使用，请核对现有报告");
        } catch (DataAccessException e) {
            throw new BusinessException(503, "COMPLETION_STORAGE_FAILURE", "结果待核对，请保留原请求并原样重试");
        }
    }
}
