package io.github.acczff.mdop.manufacturing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class BomModels {
    private BomModels() {}

    public record Component(
            @Positive long materialId,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity) {}

    public record Draft(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @PositiveOrZero Long version,
            @Positive long productId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,31}") String versionLabel,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
                    BigDecimal baseQuantity,
            @NotBlank @Size(max = 500) String description,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid Component> components,
            @NotBlank @Size(max = 500) String reason) {}

    public record Action(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 500) String reason) {}

    public record Copy(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,31}") String versionLabel,
            @NotBlank @Size(max = 500) String reason) {}
}
