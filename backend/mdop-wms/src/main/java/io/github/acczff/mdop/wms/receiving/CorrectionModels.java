package io.github.acczff.mdop.wms.receiving;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class CorrectionModels {
    private CorrectionModels() {}

    public enum DifferenceType {
        SHORT,
        OVER,
        DAMAGED,
        WRONG_MATERIAL
    }

    public enum Decision {
        APPROVE,
        REJECT
    }

    public record DifferenceInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String idempotencyKey,
            @Positive long arrivalId,
            @Positive Long arrivalItemId,
            @NotNull DifferenceType type,
            @Size(max = 64) String observedMaterial,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity,
            @NotBlank @Size(max = 500) String reason) {}

    public record ReversalInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String idempotencyKey,
            @Positive long receiptId,
            @NotBlank @Size(max = 500) String reason) {}

    public record DecisionInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String idempotencyKey,
            @PositiveOrZero long version,
            @NotNull Decision decision,
            @NotBlank @Size(max = 500) String reason) {}

    public record CaseRecord(
            long id,
            String kind,
            long arrivalId,
            Long receiptId,
            Long arrivalItemId,
            String differenceType,
            String observedMaterial,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            String reason,
            String status,
            long version,
            String requestedBy,
            LocalDateTime requestedAt,
            String decidedBy,
            LocalDateTime decidedAt,
            String decisionReason) {}
}
