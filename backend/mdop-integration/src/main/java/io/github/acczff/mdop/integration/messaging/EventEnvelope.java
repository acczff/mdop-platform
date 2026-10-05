package io.github.acczff.mdop.integration.messaging;

import jakarta.validation.constraints.*;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

public record EventEnvelope(
        @NotBlank
                @Pattern(
                        regexp =
                                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                String messageId,
        @NotBlank @Size(max = 64) String eventType,
        @Min(1) @Max(1) int eventVersion,
        @NotBlank @Size(max = 32) String sourceSystem,
        @NotNull Instant occurredAt,
        @NotBlank @Size(max = 64) String traceId,
        @NotBlank @Size(max = 32) String aggregateType,
        @NotBlank @Size(max = 64) String aggregateId,
        @NotNull JsonNode payload) {}
