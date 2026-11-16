package kz.fin.backend.execution;

import java.util.List;
import java.util.UUID;

/** Server-owned execution SPI. No adapter may publish an external report. */
public interface MethodTaskAdapter {
    String methodCode();
    default List<String> dependsOn() { return List.of(); }
    default boolean autoEligible() { return false; }
    default boolean applicable(TrustedRevision revision) { return true; }
    String execute(TrustedRevision revision) throws Exception;

    record TrustedRevision(UUID organizationId, UUID economicEntityId,
                           UUID businessScopeId, UUID factSetRevisionId,
                           String snapshotHash, String dataSpace) {}
}
