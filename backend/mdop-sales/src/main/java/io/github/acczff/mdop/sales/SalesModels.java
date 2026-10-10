package io.github.acczff.mdop.sales;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class SalesModels {
    private SalesModels() {}

    public record Line(
            @Positive long materialId,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity) {}

    public record Draft(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @Positive long warehouseId,
            @Positive long customerId,
            @PositiveOrZero Long version,
            @NotBlank @Size(max = 500) String purpose,
            @Size(max = 128) String customerReference,
            @NotNull LocalDate neededDate,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid Line> lines,
            @Size(max = 500) String reason) {}

    public record Action(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 500) String reason) {}

    public record ArrangementInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotNull @PositiveOrZero Long version,
            @Positive long orderLineId,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity,
            @NotNull LocalDate expectedDate,
            @NotBlank @Size(max = 500) String reason) {}
}
