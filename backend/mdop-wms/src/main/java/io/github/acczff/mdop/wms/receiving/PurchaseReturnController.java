package io.github.acczff.mdop.wms.receiving;

import static io.github.acczff.mdop.wms.receiving.PurchaseReturnModels.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms/purchase-returns")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:return:read')")
public class PurchaseReturnController {
    private final PurchaseReturnService service;

    public PurchaseReturnController(PurchaseReturnService service) {
        this.service = service;
    }

    @GetMapping
    public List<ReturnRecord> list(@RequestParam long warehouseId) {
        return service.list(warehouseId);
    }

    @GetMapping("/candidates")
    public List<Map<String, Object>> candidates(@RequestParam long warehouseId) {
        return service.candidates(warehouseId);
    }

    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(@PathVariable long id) {
        return service.history(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:return:create')")
    public ReturnRecord create(@Valid @RequestBody CreateInput input) {
        return service.create(input);
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:return:approve')")
    public ReturnRecord decide(@PathVariable long id, @Valid @RequestBody DecisionInput input) {
        return service.decide(id, input);
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:return:confirm')")
    public ReturnRecord confirm(@PathVariable long id, @Valid @RequestBody ConfirmInput input) {
        return service.confirm(id, input);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:return:create')")
    public ReturnRecord cancel(@PathVariable long id, @Valid @RequestBody CancelInput input) {
        return service.cancel(id, input);
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    @PreAuthorize("isAuthenticated()")
    public ProblemDetail storageFailure() {
        var p =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE, "处理结果未确认，请刷新退货记录并使用原请求重试");
        p.setProperty("code", "RETURN_STORAGE_FAILURE");
        return p;
    }
}
