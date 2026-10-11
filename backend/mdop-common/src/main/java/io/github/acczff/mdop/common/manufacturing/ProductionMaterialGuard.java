package io.github.acczff.mdop.common.manufacturing;

/** Called after warehouse locks, before an issue lock, by every formal inventory writer. */
public interface ProductionMaterialGuard {
    void lock();
}
