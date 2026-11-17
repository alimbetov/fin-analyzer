package kz.fin.backend.execution;

/**
 * A separate, explicitly authorized execution boundary for the pre-R2
 * scheduler. The registry, metric qualification, feature flags and method S2
 * review alone are NEVER an R2 ExecutionPermit.
 *
 * There is deliberately no production implementation which grants a permit.
 */
@FunctionalInterface
public interface LegacyExecutionPermitGuard {
    void requirePermit();
}
