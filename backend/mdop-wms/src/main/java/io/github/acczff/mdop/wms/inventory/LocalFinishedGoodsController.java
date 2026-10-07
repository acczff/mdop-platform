package io.github.acczff.mdop.wms.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/local/finished-receipts")
@PreAuthorize("hasRole('ADMIN')")
public class LocalFinishedGoodsController {
    private final FinishedGoodsService service;

    public LocalFinishedGoodsController(FinishedGoodsService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public Map<String, Boolean> capabilities() {
        return Map.of("enabled", true);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody FinishedGoodsService.Demand in) {
        return ProductionController.write(() -> service.create(in));
    }

    @PostMapping("/{id}/quality")
    public Map<String, Object> quality(
            @PathVariable @Positive long id, @Valid @RequestBody FinishedGoodsService.Quality in) {
        return ProductionController.write(() -> service.quality(id, in));
    }
}
