package io.github.acczff.mdop.integration.messaging;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mdop.messaging.enabled", havingValue = "true")
public class MessagingConfiguration {
    public static final String EXCHANGE = "mdop.events.v1";
    public static final String ARRIVALS = "mdop.wms.arrivals.v1";
    public static final String ERP = "mdop.simulator.erp.v1";
    public static final String MES = "mdop.simulator.mes.v1";
    public static final String QMS = "mdop.simulator.qms.v1";

    @Bean
    Declarables arrivalTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var dead = new DirectExchange("mdop.dead.v1", true, false);
        var queue =
                QueueBuilder.durable(ARRIVALS)
                        .deadLetterExchange("mdop.dead.v1")
                        .deadLetterRoutingKey("arrival")
                        .build();
        var deadQueue = QueueBuilder.durable(ARRIVALS + ".dead").build();
        return new Declarables(
                exchange,
                dead,
                queue,
                deadQueue,
                BindingBuilder.bind(queue).to(exchange).with("ArrivalNoticeCreated"),
                BindingBuilder.bind(deadQueue).to(dead).with("arrival"));
    }

    @Bean
    @Profile({"local", "test"})
    Declarables simulatorTopology() {
        var exchange = new DirectExchange(EXCHANGE, true, false);
        var erp = QueueBuilder.durable(ERP).build();
        var qms = QueueBuilder.durable(QMS).build();
        var mes =
                QueueBuilder.durable(MES)
                        .deadLetterExchange("mdop.dead.v1")
                        .deadLetterRoutingKey("mes-result")
                        .build();
        var mesDead = QueueBuilder.durable(MES + ".dead").build();
        return new Declarables(
                mes,
                mesDead,
                BindingBuilder.bind(mesDead)
                        .to(new DirectExchange("mdop.dead.v1", true, false))
                        .with("mes-result"),
                BindingBuilder.bind(mes).to(exchange).with("MaterialIssued"),
                BindingBuilder.bind(mes).to(exchange).with("ProductionConsumed"),
                BindingBuilder.bind(mes).to(exchange).with("ProductionMaterialReturned"),
                BindingBuilder.bind(mes).to(exchange).with("ProductionConsumptionReversed"),
                BindingBuilder.bind(mes).to(exchange).with("ProductionReturnReversed"),
                erp,
                qms,
                BindingBuilder.bind(erp).to(exchange).with("PurchaseReceiptConfirmed"),
                BindingBuilder.bind(erp).to(exchange).with("PurchaseReceiptReversed"),
                BindingBuilder.bind(erp).to(exchange).with("PurchaseReturnConfirmed"),
                BindingBuilder.bind(qms).to(exchange).with("IncomingInspectionCancelled"),
                BindingBuilder.bind(qms).to(exchange).with("IncomingInspectionRequested"));
    }
}
