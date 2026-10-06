package io.github.acczff.mdop.wms.receiving;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ReceivingModels {
    private ReceivingModels() {}

    public record ArrivalInput(
            @NotBlank @Size(max = 64) String externalNoticeNo,
            @NotBlank @Size(max = 64) String purchaseOrderNo,
            @Positive long supplierId,
            @Positive long warehouseId,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid ArrivalLine> items) {}

    public record ArrivalLine(
            @Positive long materialId,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity) {}

    public record DraftInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String idempotencyKey,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid ReceiptLine> items) {}

    public record UpdateDraft(
            @PositiveOrZero long version,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid ReceiptLine> items) {}

    public record SubmitInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String idempotencyKey,
            @PositiveOrZero long version) {}

    public record ReceiptLine(
            @Positive long arrivalItemId,
            @Positive long locationId,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6)
                    BigDecimal quantity,
            @Size(max = 64) String batchNo,
            @Size(max = 32) String dateCode,
            LocalDate productionDate,
            LocalDate expiryDate) {}

    public record Arrival(
            long id,
            String sourceSystem,
            String externalNoticeNo,
            String purchaseOrderNo,
            long supplierId,
            long warehouseId,
            String status,
            long version) {}

    public record ArrivalItem(
            long id,
            long arrivalId,
            long materialId,
            String materialCode,
            String materialName,
            String unit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal noticeQty,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal receivedQty) {}

    public record ArrivalDetail(Arrival arrival, List<ArrivalItem> items) {}

    public record Receipt(
            long id,
            String receiptNo,
            long arrivalId,
            String status,
            String creationKey,
            String creationHash,
            String submitKey,
            long version,
            String correctionStatus,
            String downstreamStage) {}

    public record Item(
            long id,
            long receiptId,
            long arrivalItemId,
            long materialId,
            long locationId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            String batchNo,
            String dateCode,
            LocalDate productionDate,
            LocalDate expiryDate) {}

    public record ReceiptDetail(Receipt receipt, List<Item> items) {}

    public record Balance(
            long id,
            long warehouseId,
            long locationId,
            long materialId,
            long supplierId,
            String batchNo,
            String dateCode,
            String qualityStatus,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal onHandQty,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal availableQty) {}

    public record Ledger(
            long id,
            long receiptId,
            long receiptItemId,
            long balanceId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal beforeQty,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal changeQty,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal afterQty,
            String transactionType,
            Long reversedTransactionId) {}
}
