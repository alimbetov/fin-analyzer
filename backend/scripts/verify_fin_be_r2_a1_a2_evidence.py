#!/usr/bin/env python3
"""Fail-closed JUnit/XML verification for FIN-BE-R2 A1+A2.

The integration suite uses real PostgreSQL 16 Testcontainers and actual
FactSetPublicationService, not mock-only source data. This script does not
assert a production method permit or completed R2 plan/CORE.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "backend/target"
CASES = {
    "kz.fin.backend.execution.MethodVariantRegistryTest": {
        "registersExactlyExistingSevenCandidatesAndKeepsQualificationOnHold",
        "canonicalFingerprintIsStableAcrossShuffledDescriptionsAndInputSetOrders",
        "rejectsDuplicateMethodAndDuplicateAdapterAndUnknownFamily",
        "rejectsMissingBindingsAndUnknownDependencyAndCycles",
        "invalidUnitsDuplicatesVersionsAndS2PromotionRejected",
        "registryFingerprintChangesWithCatalogContentsAndUnexpectedRolesAreRejected",
    },
    "kz.fin.backend.execution.MethodApplicabilityPostgresIntegrationTest": {
        "publishedSyntheticSourceProducesReasonCodesWithoutAnyExecution",
        "qualifiedMonthlyValuesNeverPromoteActualMethodWithoutS2Permit",
        "unqualifiedMetricContractBecomesBlockingConflict",
        "nonconsecutiveRevenueMonthsCannotPassInputValidation",
        "foreignTenantWrongSourceHashAndDisabledLabFailClosed",
        "conflictingApprovedSourceRevisionsMustNotBecomeComparableRevenueInput",
    },
}

def require(condition, message):
    if not condition:
        raise ValueError("FIN_BE_R2_A1_A2_EVIDENCE_REJECTED: " + message)

def main():
    test_names = []
    for cls, required in CASES.items():
        path = TARGET / "surefire-reports" / ("TEST-" + cls + ".xml")
        require(path.is_file(), "Missing JUnit XML: " + cls)
        suite = ET.parse(path).getroot()
        counts = {attr: int(suite.get(attr, "-1"))
                  for attr in ("tests", "failures", "errors", "skipped")}
        require(counts["tests"] == len(required) and all(
            counts[x] == 0 for x in ("failures", "errors", "skipped")),
            "JUnit not fully GREEN: " + cls + " " + str(counts))
        cases = suite.findall(".//testcase")
        actual = [c.get("name", "").split("(")[0] for c in cases]
        require(len(actual) == len(required) and set(actual) == required
                and len(actual) == len(set(actual)),
                "JUnit scenario coverage drift: " + cls)
        test_names.extend(cls + "#" + name for name in sorted(actual))

    java_test = (ROOT / "backend/src/test/java/kz/fin/backend/execution/"
                 "MethodApplicabilityPostgresIntegrationTest.java")
    code = java_test.read_text(encoding="utf-8")
    require("@Testcontainers" in code and
            "new PostgreSQLContainer<>(\"postgres:16-alpine\")" in code and
            "FactSetPublicationService" in code,
            "PostgreSQL published-source fixture removed")
    sha = os.environ.get("GITHUB_SHA", "LOCAL")
    require(sha == "LOCAL" or re.fullmatch("[0-9a-f]{40}", sha) is not None,
            "invalid build SHA")
    evidence = {
        "schemaVersion": "FIN_BE_R2_A1_A2_REGISTRY_APPLICABILITY_V1",
        "gitSha": sha,
        "unitAndPostgresCases": len(test_names),
        "testCases": test_names,
        "actualPermitGranted": False,
        "productionExecutionEnabled": False,
        "stage": "A1_A2_ONLY",
        "plannerEvidence": "NOT_IMPLEMENTED",
    }
    out = TARGET / "fin-evidence/fin-be-r2-a1-a2-evidence.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+"\n",
                   encoding="utf-8")
    print("FIN_BE_R2_A1_A2_GREEN",
          "cases=" + str(len(test_names)),
          "postgresql_testcontainers=true",
          "ACTUAL_method_s2=HOLD",
          "plan=NOT_IMPLEMENTED",
          "sha=" + sha)

if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, TypeError, ET.ParseError) as e:
        print(str(e), file=sys.stderr)
        raise SystemExit(1)
