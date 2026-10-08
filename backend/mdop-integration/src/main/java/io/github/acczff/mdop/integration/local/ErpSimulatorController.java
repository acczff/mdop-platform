package io.github.acczff.mdop.integration.local;

import io.github.acczff.mdop.wms.receiving.ReceivingModels.*;
import io.github.acczff.mdop.wms.receiving.ReceivingService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Local input adapter only; production ERP events must enter through an Inbox consumer. */
@RestController
@Profile({"local", "test"})
@RequestMapping("/api/local/erp-arrivals")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('integration:simulate')")
public class ErpSimulatorController {
    private final ReceivingService service;

    public ErpSimulatorController(ReceivingService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public Map<String, Boolean> capabilities() {
        return Map.of("enabled", true);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ArrivalDetail create(@Valid @RequestBody ArrivalInput input) {
        return service.simulateArrival(input);
    }
}
