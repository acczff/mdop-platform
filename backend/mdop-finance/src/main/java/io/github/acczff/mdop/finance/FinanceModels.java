package io.github.acczff.mdop.finance;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class FinanceModels {
    private FinanceModels() {}

    public record Setup(
            @NotBlank @Size(max = 64) String idempotencyKey,
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 100) String organization,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotBlank @Size(max = 500) String reason) {}

    public record Draft(
            @NotBlank @Size(max = 64) String idempotencyKey,
            @Pattern(regexp = "PAYABLE|RECEIVABLE") @NotNull String direction,
            @Positive long warehouseId,
            @NotBlank @Size(max = 40) String orderNumber,
            @Positive long factId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6)
                    BigDecimal unitPrice,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 16, fraction = 2)
                    BigDecimal amount,
            @NotBlank @Size(max = 100) String reference,
            @NotNull LocalDate businessDate,
            @NotBlank @Size(max = 500) String reason) {}

    public record Action(
            @NotBlank @Size(max = 64) String idempotencyKey,
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 500) String reason) {}
}
