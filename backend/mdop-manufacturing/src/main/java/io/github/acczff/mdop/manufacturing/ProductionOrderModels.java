package io.github.acczff.mdop.manufacturing;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class ProductionOrderModels {
    private ProductionOrderModels() {}

    public record Demand(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @Positive long warehouseId,
            @NotBlank @Pattern(regexp = "MANUAL|SALES") String sourceType,
            @Size(max = 100) String sourceReference,
            @Positive Long salesLineId,
            @Positive Long productId,
            @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            LocalDate neededDate,
            @NotBlank @Size(max = 500) String purpose) {}

    public record Order(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotNull @PositiveOrZero Long version,
            @Positive long siteId,
            @Positive long bomId,
            @NotNull LocalDate plannedDate,
            @NotBlank @Size(max = 500) String reason) {}

    public record Action(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 500) String reason) {}
}
