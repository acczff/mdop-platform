package io.github.acczff.mdop.wms.inventory;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/local/sales-orders")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('integration:simulate')")
public class LocalSalesController {
    private final SalesService service;

    public LocalSalesController(SalesService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public Map<String, Boolean> capabilities() {
        return Map.of("enabled", true);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody SalesService.Demand in) {
        return ProductionController.write(() -> service.create(in));
    }
}
