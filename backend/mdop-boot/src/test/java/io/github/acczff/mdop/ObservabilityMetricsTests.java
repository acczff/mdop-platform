package io.github.acczff.mdop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.acczff.mdop.integration.messaging.OutboxMetricsBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class ObservabilityMetricsTests {

    @Test
    void registersOutboxBacklogAndOldestPendingAgeGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetricsBinder binder = new OutboxMetricsBinder(mock(JdbcClient.class));

        binder.bindTo(registry);

        assertThat(registry.find("mdop.outbox.backlog").gauge()).isNotNull();
        assertThat(registry.find("mdop.outbox.oldest.pending.age.seconds").gauge()).isNotNull();
    }
}
