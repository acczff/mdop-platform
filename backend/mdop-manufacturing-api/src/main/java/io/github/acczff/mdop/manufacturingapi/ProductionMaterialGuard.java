package io.github.acczff.mdop.manufacturingapi;

/** Called after warehouse locks, before an issue lock, by every formal inventory writer. */
public interface ProductionMaterialGuard {
    void lock();

    void requireOpen(long orderId);
}
