package kz.fin.backend.execution;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static kz.fin.backend.execution.ExecutionContracts.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionContractsTest {

    @Test void coreReportDoesNotRequireMl() {
        var s = new ReportReadiness(ReportMode.CORE, ReportMode.CORE,
                ExecutionStatus.COMPLETED, CoreReadiness.COMPLETE,
                MlReadiness.NOT_REQUESTED);
        assertEquals(CoreReadiness.COMPLETE, s.coreReadiness());
    }

    @Test void failedMlLeavesCoreReportDeliverable() {
        var s = new ReportReadiness(ReportMode.CORE_WITH_ML, ReportMode.CORE,
                ExecutionStatus.PARTIAL, CoreReadiness.COMPLETE, MlReadiness.FAILED);
        assertEquals(ReportMode.CORE, s.deliveredMode());
        assertThrows(IllegalArgumentException.class, () -> new ReportReadiness(
                ReportMode.CORE_WITH_ML, ReportMode.CORE_WITH_ML,
                ExecutionStatus.COMPLETED, CoreReadiness.COMPLETE,
                MlReadiness.FAILED));
    }

    @Test void mlCannotRunForCoreOnlyRequest() {
        assertThrows(IllegalArgumentException.class, () -> new ReportReadiness(
                ReportMode.CORE, ReportMode.CORE, ExecutionStatus.COMPLETED,
                CoreReadiness.COMPLETE, MlReadiness.AVAILABLE));
    }

    @Test void completeMlReportRequiresQualifiedAvailableMlAndCompleteCore() {
        assertDoesNotThrow(() -> new ReportReadiness(
                ReportMode.CORE_WITH_ML, ReportMode.CORE_WITH_ML,
                ExecutionStatus.COMPLETED, CoreReadiness.COMPLETE, MlReadiness.AVAILABLE));
        assertThrows(IllegalArgumentException.class, () -> new ReportReadiness(
                ReportMode.CORE_WITH_ML, ReportMode.CORE_WITH_ML,
                ExecutionStatus.PARTIAL, CoreReadiness.PARTIAL, MlReadiness.AVAILABLE));
    }

    @Test void emptyOrOverlappingProfileCannotBeDeclaredComplete() {
        assertThrows(IllegalArgumentException.class, () -> new AnalysisProfile(
                "COMPREHENSIVE", "1", Set.of(), Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisProfile(
                "BUSINESS_HEALTH", "1", Set.of("FIN-04"), Set.of("FIN-04"), Set.of()));
    }

    @Test void analysisManifestDefensivelyCopiesVersions() {
        var versions = new java.util.HashMap<String, String>();
        versions.put("FIN-04/CURRENT_RATIO", "1.0");
        var manifest = new AnalysisManifest(UUID.randomUUID(), UUID.randomUUID(),
                "fixture-hash", "BUSINESS_HEALTH", "1", "M60-v1",
                "KZ-management-v1", "unit-policy-v1", "assumptions-v1",
                "plan-v1", "build-sha", versions);
        versions.put("FIN-04/CURRENT_RATIO", "2.0");
        assertEquals("1.0", manifest.methodVariantVersions().get("FIN-04/CURRENT_RATIO"));
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.methodVariantVersions().put("FIN-01", "1.0"));
    }
}
