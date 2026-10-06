package io.github.acczff.mdop.wms.receiving;

import static io.github.acczff.mdop.wms.receiving.CorrectionModels.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms/corrections")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:correction:read')")
public class CorrectionController {
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    @PreAuthorize("isAuthenticated()")
    public org.springframework.http.ProblemDetail storageFailure() {
        var problem =
                org.springframework.http.ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE, "处理结果尚未确认，请刷新申请记录后使用原请求重试");
        problem.setProperty("code", "CORRECTION_STORAGE_FAILURE");
        return problem;
    }

    private final CorrectionService service;

    public CorrectionController(CorrectionService service) {
        this.service = service;
    }

    @GetMapping
    public List<CaseRecord> list(@RequestParam long warehouseId) {
        return service.list(warehouseId);
    }

    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(@PathVariable long id) {
        return service.history(id);
    }

    @PostMapping("/differences")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:correction:create')")
    public CaseRecord difference(@Valid @RequestBody DifferenceInput input) {
        return service.difference(input);
    }

    @PostMapping("/reversals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:correction:create')")
    public CaseRecord reversal(@Valid @RequestBody ReversalInput input) {
        return service.reversal(input);
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:correction:approve')")
    public CaseRecord decide(@PathVariable long id, @Valid @RequestBody DecisionInput input) {
        return service.decide(id, input);
    }
}
