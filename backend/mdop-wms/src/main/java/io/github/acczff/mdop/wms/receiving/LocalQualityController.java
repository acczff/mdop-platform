package io.github.acczff.mdop.wms.receiving;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile({"local", "test"})
@RequestMapping("/api/local/qms-results")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('integration:simulate')")
public class LocalQualityController {
    private final QualityService service;

    public LocalQualityController(QualityService service) {
        this.service = service;
    }

    @PostMapping
    public List<Map<String, Object>> inspect(@Valid @RequestBody QualityModels.ResultInput input) {
        return service.inspect(input);
    }
}
