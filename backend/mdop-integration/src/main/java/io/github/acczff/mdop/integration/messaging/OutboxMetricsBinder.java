package io.github.acczff.mdop.integration.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 注册 Outbox 积压量与最老待投递消息年龄等业务监控指标。 */
@Component
public class OutboxMetricsBinder implements MeterBinder {

    private final JdbcClient db;

    public OutboxMetricsBinder(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("mdop.outbox.backlog", this, OutboxMetricsBinder::backlogCount)
                .description("Number of pending, failed, or publishing outbox messages")
                .register(registry);
        Gauge.builder(
                        "mdop.outbox.oldest.pending.age.seconds",
                        this,
                        OutboxMetricsBinder::oldestPendingAgeSeconds)
                .description("Age in seconds of the oldest pending or failed outbox message")
                .register(registry);
    }

    double backlogCount() {
        try {
            Long count =
                    db.sql(
                                    "SELECT COUNT(*) FROM wms_outbox WHERE status IN ('PENDING','FAILED','PUBLISHING')")
                            .query(Long.class)
                            .single();
            return count == null ? 0.0 : count.doubleValue();
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    double oldestPendingAgeSeconds() {
        try {
            Long age =
                    db.sql(
                                    "SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(occurred_at), CURRENT_TIMESTAMP(6)), 0) FROM wms_outbox WHERE status IN ('PENDING','FAILED')")
                            .query(Long.class)
                            .single();
            return age == null ? 0.0 : Math.max(0.0, age.doubleValue());
        } catch (Exception ignored) {
            return 0.0;
        }
    }
}
