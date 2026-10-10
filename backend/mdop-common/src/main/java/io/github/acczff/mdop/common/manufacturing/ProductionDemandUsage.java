package io.github.acczff.mdop.common.manufacturing;

/** Invoked under the sales warehouse lock so cancellation and demand creation serialize. */
public interface ProductionDemandUsage {
    boolean hasActiveSalesDemand(long salesOrderId);
}
