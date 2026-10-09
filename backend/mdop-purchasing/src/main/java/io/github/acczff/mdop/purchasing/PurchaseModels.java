package io.github.acczff.mdop.purchasing;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class PurchaseModels {
    private PurchaseModels() {}

    public record Line(
            @Positive long materialId,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity) {}

    public record Draft(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @Positive long warehouseId,
            @PositiveOrZero Long version,
            @NotBlank @Size(max = 500) String purpose,
            @NotNull LocalDate neededDate,
            @Positive Long supplierId,
            @Positive @JsonInclude(JsonInclude.Include.NON_NULL) Long originalOrderId,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid Line> lines,
            @Size(max = 500) String reason) {}

    public record Action(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 500) String reason) {}

    public record Conversion(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotNull @PositiveOrZero Long version,
            @Positive long supplierId,
            @NotNull LocalDate neededDate,
            @NotBlank @Size(max = 500) String reason) {}

    public record ArrangementLine(
            @Positive long orderLineId,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity) {}

    public record ArrangementInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @NotNull @PositiveOrZero Long version,
            @NotNull LocalDate expectedDate,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid ArrangementLine> lines,
            @NotBlank @Size(max = 500) String reason) {}
}
