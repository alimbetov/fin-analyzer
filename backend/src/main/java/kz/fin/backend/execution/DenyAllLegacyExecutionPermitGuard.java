package kz.fin.backend.execution;

import org.springframework.stereotype.Component;

/**
 * FIN-MR-R2.0.1 safety stop. Legacy SingleExecutor has no version-pinned R2
 * ExecutionPermit and therefore cannot consume publications, calculate or
 * persist results, even if fin.execution.enabled is enabled by mistake.
 *
 * A future explicitly reviewed R2 executor must use its own authorization
 * contract and must not replace this deny-all bean with a feature flag.
 */
@Component
public final class DenyAllLegacyExecutionPermitGuard implements LegacyExecutionPermitGuard {
    @Override
    public void requirePermit() {
        throw new IllegalStateException("FIN_R2_LEGACY_EXECUTION_PERMIT_UNAVAILABLE");
    }
}
