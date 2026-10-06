package io.github.acczff.mdop.wms.receiving;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public final class PurchaseReturnModels {
    private PurchaseReturnModels() {}

    public record ReturnRecord(
            long id,
            long receiptId,
            long receiptItemId,
            @com.fasterxml.jackson.annotation.JsonFormat(
                            shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
                    BigDecimal quantity,
            String reason,
            String status,
            long version,
            String requestedBy,
            String decidedBy,
            String decisionReason,
            String handoverNo,
            String confirmedBy) {}

    public record CreateInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @Positive long receiptItemId,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity,
            @NotBlank @Size(max = 500) String reason) {}

    public record DecisionInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @PositiveOrZero long version,
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(max = 500) String reason) {}

    public record ConfirmInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @PositiveOrZero long version,
            @NotBlank @Size(max = 64) String handoverNo) {}

    public record CancelInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @PositiveOrZero long version,
            @NotBlank @Size(max = 500) String reason) {}
}
