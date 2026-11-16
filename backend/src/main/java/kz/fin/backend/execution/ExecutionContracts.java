package kz.fin.backend.execution;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * P0-ENG-01: versioned contract types. These are NOT authorization tokens or
 * evidence of published/approved facts: repository services must verify both.
 * v1 deliberately has no JSON/JPA annotations until the existing API mapping
 * and schema migrations have been independently reviewed.
 */
public final class ExecutionContracts {
    private ExecutionContracts() {}

    public enum ReportMode { CORE, CORE_WITH_ML }
    public enum ExecutionStatus {
        QUEUED, PLANNING, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED
    }
    public enum CoreReadiness { COMPLETE, PARTIAL, BLOCKED }
    public enum MlReadiness { NOT_REQUESTED, PENDING, AVAILABLE, UNAVAILABLE, FAILED }
    public enum TaskStatus {
        PENDING, READY, RUNNING, COMPUTED, SKIPPED_NOT_APPLICABLE,
        BLOCKED_MISSING, BLOCKED_CONFLICT, DISABLED_POLICY,
        FAILED_RETRYABLE, FAILED_FINAL
    }
    public enum DependencyKind { REQUIRED, OPTIONAL, EVIDENCE_ONLY }

    /**
     * Manifest is immutable and captures the semantic coordinates needed to
     * reproduce a CORE result. Cryptographic content fingerprinting belongs
     * to a canonical serializer, not to record.toString().
     */
    public record AnalysisManifest(
            UUID organizationId,
            UUID factSetRevisionId,
            String factSetFingerprint,
            String analysisProfileCode,
            String analysisProfileVersion,
            String methodologyCatalogVersion,
            String accountingPolicyVersion,
            String unitPolicyVersion,
            String assumptionsFingerprint,
            String executionPlanFingerprint,
            String executorBuildSha,
            Map<String, String> methodVariantVersions
    ) {
        public AnalysisManifest {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(factSetRevisionId, "factSetRevisionId");
            requireNonBlank(factSetFingerprint, "factSetFingerprint");
            requireNonBlank(analysisProfileCode, "analysisProfileCode");
            requireNonBlank(analysisProfileVersion, "analysisProfileVersion");
            requireNonBlank(methodologyCatalogVersion, "methodologyCatalogVersion");
            requireNonBlank(accountingPolicyVersion, "accountingPolicyVersion");
            requireNonBlank(unitPolicyVersion, "unitPolicyVersion");
            requireNonBlank(assumptionsFingerprint, "assumptionsFingerprint");
            requireNonBlank(executionPlanFingerprint, "executionPlanFingerprint");
            requireNonBlank(executorBuildSha, "executorBuildSha");
            methodVariantVersions = Map.copyOf(
                    Objects.requireNonNull(methodVariantVersions, "methodVariantVersions"));
            methodVariantVersions.forEach((key, value) -> {
                requireNonBlank(key, "methodCode");
                requireNonBlank(value, "methodVersion");
            });
        }
    }

    public record AnalysisProfile(
            String profileCode,
            String profileVersion,
            Set<String> requiredCapabilities,
            Set<String> conditionalCapabilities,
            Set<String> optionalCapabilities
    ) {
        public AnalysisProfile {
            requireNonBlank(profileCode, "profileCode");
            requireNonBlank(profileVersion, "profileVersion");
            requiredCapabilities = Set.copyOf(
                    Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
            conditionalCapabilities = Set.copyOf(
                    Objects.requireNonNull(conditionalCapabilities, "conditionalCapabilities"));
            optionalCapabilities = Set.copyOf(
                    Objects.requireNonNull(optionalCapabilities, "optionalCapabilities"));
            if (requiredCapabilities.isEmpty()) {
                throw new IllegalArgumentException("PROFILE_REQUIREMENTS_UNDEFINED");
            }
            if (!disjoint(requiredCapabilities, conditionalCapabilities)
                    || !disjoint(requiredCapabilities, optionalCapabilities)
                    || !disjoint(conditionalCapabilities, optionalCapabilities)) {
                throw new IllegalArgumentException("PROFILE_CAPABILITY_OVERLAP");
            }
        }
    }

    public record ReportReadiness(
            ReportMode requestedMode,
            ReportMode deliveredMode,
            ExecutionStatus executionStatus,
            CoreReadiness coreReadiness,
            MlReadiness mlReadiness
    ) {
        public ReportReadiness {
            Objects.requireNonNull(requestedMode);
            Objects.requireNonNull(deliveredMode);
            Objects.requireNonNull(executionStatus);
            Objects.requireNonNull(coreReadiness);
            Objects.requireNonNull(mlReadiness);
            if (deliveredMode == ReportMode.CORE_WITH_ML
                    && (requestedMode != ReportMode.CORE_WITH_ML
                        || coreReadiness != CoreReadiness.COMPLETE
                        || mlReadiness != MlReadiness.AVAILABLE)) {
                throw new IllegalArgumentException("INVALID_ML_REPORT_PROMOTION");
            }
            if (requestedMode == ReportMode.CORE && mlReadiness != MlReadiness.NOT_REQUESTED) {
                throw new IllegalArgumentException("ML_MUST_NOT_RUN_FOR_CORE");
            }
        }
    }

    private static boolean disjoint(Set<String> a, Set<String> b) {
        for (var value : a) if (b.contains(value)) return false;
        return true;
    }
    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "_MISSING");
    }
}
