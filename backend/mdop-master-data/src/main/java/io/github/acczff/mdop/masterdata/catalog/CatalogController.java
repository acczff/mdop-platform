package io.github.acczff.mdop.masterdata.catalog;

import io.github.acczff.mdop.masterdata.catalog.CatalogService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/master-data")
public class CatalogController {
    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    public record SupplierInput(
            @NotBlank @Size(max = 32) String code, @NotBlank @Size(max = 100) String name) {}

    public record MaterialInput(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 16) String unit,
            @Positive Long unitId,
            @NotNull Tracking trackingMode,
            boolean requireDateCode,
            boolean requireExpiry) {}

    public record LocationInput(
            @Positive long warehouseId,
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull Area areaType) {}

    @GetMapping("/suppliers")
    public List<Supplier> suppliers() {
        return service.suppliers();
    }

    @GetMapping("/materials")
    public List<Material> materials() {
        return service.materials();
    }

    @GetMapping("/locations")
    public List<Location> locations() {
        return service.locations();
    }

    @PostMapping("/suppliers")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('masterdata:write')")
    public Supplier supplier(@Valid @RequestBody SupplierInput request) {
        return service.createSupplier(request.code(), request.name());
    }

    @PostMapping("/materials")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('masterdata:write')")
    public Material material(@Valid @RequestBody MaterialInput request) {
        if (request.unitId() != null)
            return service.createMaterialWithUnit(
                    request.code(),
                    request.name(),
                    request.unitId(),
                    request.trackingMode(),
                    request.requireDateCode(),
                    request.requireExpiry());
        return service.createMaterial(
                request.code(),
                request.name(),
                request.unit(),
                request.trackingMode(),
                request.requireDateCode(),
                request.requireExpiry());
    }

    @GetMapping("/{kind}")
    public List<Map<String, Object>> directory(@PathVariable String kind) {
        return service.directory(kind);
    }

    @PostMapping("/{kind}")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('masterdata:write')")
    public Map<String, Object> create(
            @PathVariable String kind, @Valid @RequestBody SupplierInput input) {
        return service.createDirectory(kind, input.code(), input.name());
    }

    @PutMapping("/{kind}/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('masterdata:write')")
    public Object edit(
            @PathVariable String kind,
            @PathVariable @Positive long id,
            @Valid @RequestBody Edit input) {
        return service.edit(kind, id, input);
    }

    @GetMapping("/{kind}/{id}/history")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('masterdata:write')")
    public List<Map<String, Object>> history(
            @PathVariable String kind, @PathVariable @Positive long id) {
        return service.history(kind, id);
    }

    @PostMapping("/locations")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('masterdata:write')")
    public Location location(@Valid @RequestBody LocationInput request) {
        return service.createLocation(
                request.warehouseId(), request.code(), request.name(), request.areaType());
    }
}
