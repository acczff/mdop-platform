package io.github.acczff.mdop.wms.receiving;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class QualityModels {
    private QualityModels() {}

    public record ResultInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @Positive long receiptId,
            @PositiveOrZero long version,
            @NotBlank @Size(max = 64) String referenceNo,
            @NotBlank @Size(max = 500) String reason,
            @NotEmpty @Size(max = 100) List<@Valid ResultLine> items) {}

    public record ResultLine(
            @Positive long receiptItemId,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal qualifiedQty,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal rejectedQty) {}

    public record PutawayInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String idempotencyKey,
            @Positive long locationId) {}
}
