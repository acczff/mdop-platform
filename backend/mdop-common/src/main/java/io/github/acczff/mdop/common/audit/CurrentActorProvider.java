package io.github.acczff.mdop.common.audit;

@FunctionalInterface
public interface CurrentActorProvider {

    String currentActor();
}
