package io.github.acczff.mdop.wms.inventory;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/local/mes-demands")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('integration:simulate')")
public class LocalMesController {
    private final IssueService service;

    public LocalMesController(IssueService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public Map<String, Boolean> capabilities() {
        return Map.of("enabled", true);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody IssueService.Demand input) {
        try {
            return service.create(input);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new io.github.acczff.mdop.common.BusinessException(
                    409, "MES_DEMAND_CONFLICT", "需求号已被接收，请刷新核对原单");
        } catch (org.springframework.dao.DataAccessException e) {
            throw new io.github.acczff.mdop.common.BusinessException(
                    503, "MES_DEMAND_STORAGE_FAILURE", "需求暂未处理完成，请使用原需求号核对后重试");
        }
    }
}
