package io.github.acczff.mdop.wms.receiving;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wms/quality")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:quality:read')")
public class QualityController {
    private final QualityService service;

    public QualityController(QualityService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam long warehouseId) {
        return service.list(warehouseId);
    }

    @GetMapping("/{receiptId}/items")
    public List<Map<String, Object>> detail(@PathVariable long receiptId) {
        return service.detail(receiptId);
    }

    @PostMapping("/items/{itemId}/putaway")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('wms:putaway:confirm')")
    public List<Map<String, Object>> putaway(
            @PathVariable long itemId, @Valid @RequestBody QualityModels.PutawayInput input) {
        return service.putaway(itemId, input);
    }
}
