package io.github.acczff.mdop.masterdata.catalog;

import io.github.acczff.mdop.masterdata.catalog.CatalogService.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
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
            @NotBlank @Size(max = 16) String unit,
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
        return service.createMaterial(
                request.code(),
                request.name(),
                request.unit(),
                request.trackingMode(),
                request.requireDateCode(),
                request.requireExpiry());
    }

    @PostMapping("/locations")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('masterdata:write')")
    public Location location(@Valid @RequestBody LocationInput request) {
        return service.createLocation(
                request.warehouseId(), request.code(), request.name(), request.areaType());
    }
}
