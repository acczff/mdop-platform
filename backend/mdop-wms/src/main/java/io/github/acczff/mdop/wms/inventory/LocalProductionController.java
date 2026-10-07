package io.github.acczff.mdop.wms.inventory;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/local/mes-production")
@PreAuthorize("hasRole('ADMIN')")
public class LocalProductionController {
    private final ProductionService service;

    public LocalProductionController(ProductionService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public Map<String, Boolean> capabilities() {
        return Map.of("enabled", true);
    }

    @PostMapping("/consumptions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> consume(@Valid @RequestBody ProductionService.Consumption input) {
        return ProductionController.write(() -> service.consume(input));
    }

    @PostMapping("/returns")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> requestReturn(
            @Valid @RequestBody ProductionService.ReturnInput input) {
        return ProductionController.write(() -> service.requestReturn(input));
    }
}
