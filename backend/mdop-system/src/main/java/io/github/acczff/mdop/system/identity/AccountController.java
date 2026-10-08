package io.github.acczff.mdop.system.identity;

import io.github.acczff.mdop.common.security.WarehouseDirectory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountController {
    private final AccountService accounts;
    private final WarehouseDirectory warehouses;

    public AccountController(AccountService accounts, WarehouseDirectory warehouses) {
        this.accounts = accounts;
        this.warehouses = warehouses;
    }

    public record Create(
            @NotBlank String username,
            @NotBlank String displayName,
            @NotBlank String password,
            @NotNull Set<String> roles,
            @NotNull Set<Long> warehouseIds,
            @NotBlank String reason) {}

    public record Access(
            @Min(0) long version,
            @NotNull Set<String> roles,
            @NotNull Set<Long> warehouseIds,
            @NotBlank String reason) {}

    public record Status(@Min(0) long version, @NotNull Boolean enabled, @NotBlank String reason) {}

    public record Reset(@Min(0) long version, @NotBlank String password, @NotBlank String reason) {}

    public record Password(@NotBlank String oldPassword, @NotBlank String newPassword) {}

    @GetMapping("/api/iam/options")
    @PreAuthorize("hasAuthority('iam:manage')")
    public Map<String, Object> options() {
        return Map.of("roles", PermissionCatalog.roles(), "warehouses", warehouses.warehouses());
    }

    @GetMapping("/api/iam/users")
    @PreAuthorize("hasAuthority('iam:manage')")
    public AccountService.Page list(@RequestParam(defaultValue = "0") int page) {
        return accounts.list(page);
    }

    @GetMapping("/api/iam/users/{id}/audit")
    @PreAuthorize("hasAuthority('iam:manage')")
    public List<AccountService.Audit> audits(@PathVariable long id) {
        return accounts.audits(id);
    }

    @PostMapping("/api/iam/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('iam:manage')")
    public AccountService.Account create(@Valid @RequestBody Create r) {
        return accounts.create(
                r.username(),
                r.displayName(),
                r.password(),
                r.roles(),
                r.warehouseIds(),
                r.reason());
    }

    @PostMapping("/api/iam/users/{id}/access")
    @PreAuthorize("hasAuthority('iam:manage')")
    public AccountService.Account access(@PathVariable long id, @Valid @RequestBody Access r) {
        return accounts.access(id, r.version(), r.roles(), r.warehouseIds(), r.reason());
    }

    @PostMapping("/api/iam/users/{id}/status")
    @PreAuthorize("hasAuthority('iam:manage')")
    public AccountService.Account status(@PathVariable long id, @Valid @RequestBody Status r) {
        return accounts.status(id, r.version(), r.enabled(), r.reason());
    }

    @PostMapping("/api/iam/users/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('iam:manage')")
    public void reset(@PathVariable long id, @Valid @RequestBody Reset r) {
        accounts.reset(id, r.version(), r.password(), r.reason());
    }

    @PostMapping("/api/auth/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@Valid @RequestBody Password r, HttpServletRequest request) {
        accounts.changePassword(r.oldPassword(), r.newPassword());
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }
}
