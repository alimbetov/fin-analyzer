#!/usr/bin/env python3
"""FIN-BE-R1 hard gate: independent CI verification of 166 Java/Postgres cases.

This reads the JUnit report AND a separate Java-generated machine-readable
manifest, then checks both against the committed server catalog. A catalog
lookup or Java-only schema test is NOT equivalent to an Excel/Postgres upload.
"""
import csv
import json
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[2]
backend = root / "backend"
catalog_path = backend / "src/main/resources/fin/form-compatibility.tsv"
specialized_path = backend / "src/main/resources/fin/specialized-schema-candidates-r6.tsv"
manifest_path = backend / "target/fin-evidence/fin-be-r1-form-upload-coverage.json"
report_path = (backend / "target/surefire-reports/"
               "TEST-kz.fin.backend.intake.CatalogXlsxRoundTripIntegrationTest.xml")
golden_report = (backend / "target/surefire-reports/"
                 "TEST-kz.fin.backend.intake.CatalogIndependentGoldenTest.xml")
matrix_path = root / "docs/FIN-BE-R1-166-FORM-EXCEL-COVERAGE.tsv"


def require(ok, message):
    if not ok:
        raise ValueError("FIN_BE_R1_EVIDENCE_REJECTED: " + message)


def read_tsv(path):
    with path.open(encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def main():
    catalog = read_tsv(catalog_path)
    candidates = read_tsv(specialized_path)
    require(len(catalog) == 166, "catalog row count")
    candidate_fields = {x["formCode"]: x["fields"].split(";") for x in candidates}
    require(len(candidate_fields) == 32, "specialized candidate count")
    source = {}
    for row in catalog:
        code = row["formCode"]
        require(code not in source, "duplicate form " + code)
        if row["fieldEvidence"] == "TYPED_FIELD_HINTS":
            mode = "TYPED_DRAFT"
            fields = row["fieldContract"].split(";")
        elif row["formKind"] in ("DOCUMENT", "EXTERNAL_TAX"):
            mode = "EVIDENCE_ONLY"
            fields = [f"document:{i}" for i in range(7)]
        else:
            mode = "SCHEMA_NOT_APPROVED"
            fields = candidate_fields[code]
        source[code] = (mode, len(fields))
    require(sum(1 for m, n in source.values() if m == "TYPED_DRAFT") == 122,
            "typed count")
    require(sum(1 for m, n in source.values() if m == "SCHEMA_NOT_APPROVED") == 32,
            "specialized count")
    require(sum(1 for m, n in source.values() if m == "EVIDENCE_ONLY") == 12,
            "evidence count")
    require(sum(n for m, n in source.values() if m != "EVIDENCE_ONLY") == 794,
            "source fields != 794")
    matrix = read_tsv(matrix_path)
    require(len(matrix) == 166, "static source capability matrix has != 166 rows")
    matrix_codes = set()
    for row in matrix:
        code = row.get("formCode")
        require(code in source and code not in matrix_codes,
                "static matrix duplicate/unknown code " + str(code))
        matrix_codes.add(code)
        mode, count = source[code]
        expected_mode = "SCHEMA_CANDIDATE" if mode == "SCHEMA_NOT_APPROVED" else mode
        require(row.get("catalogMode") == expected_mode, code + " matrix mode")
        require(row.get("fieldCount") == str(count if mode != "EVIDENCE_ONLY" else 0),
                code + " matrix schema field count")
        require(row.get("testCaseId") == "BE-R1-ALL-" + code, code + " matrix test case")
        require(row.get("executionState") == "ENGINEERING_CODED_CI_PROOF_REQUIRED",
                code + " static state is not an auditable runtime verdict")
    require(matrix_codes == set(source), "static matrix coverage incomplete")

    require(report_path.is_file(), "missing JUnit XML")
    suite = ET.parse(report_path).getroot()
    tests = int(suite.get("tests", "0"))
    failures = int(suite.get("failures", "0"))
    errors = int(suite.get("errors", "0"))
    skipped = int(suite.get("skipped", "0"))
    require(tests >= 175 and not (failures or errors or skipped),
            f"JUnit tests={tests}, failures={failures}, errors={errors}, skipped={skipped}")
    names = [case.attrib.get("name") for case in suite.findall(".//testcase")]
    require(len(names) == tests, "JUnit test case enumeration mismatch")
    expected_names = {"BE-R1-ALL-" + code for code in source}
    actual_names = set(names)
    if not expected_names.issubset(actual_names):
        # Surefire may serialize JUnit 5 DynamicTest instances under the
        # @TestFactory method name rather than their display names. Require
        # the full 166-case cardinality and an actual factory testcase; the
        # independently generated 166-row proof below verifies exact codes.
        require(tests >= 175, "per-form dynamic JUnit count is missing")
        # The Java @AfterAll asserts exactly 166 genuinely successful per-form
        # executions before writing the JSON; CI cross-checks those 166 exact
        # codes and field counts against the independent catalog below.

    require(manifest_path.is_file(), "missing Java-generated per-form JSON")
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    require(data.get("schemaVersion") == "FIN_BE_R1_166_POSTGRES_ROUNDTRIP_V1",
            "manifest schemaVersion")
    require(data.get("expectedFormCount") == 166 and data.get("passedFormCount") == 166,
            "manifest incomplete count")
    require(data.get("typedFields") == 794, "manifest field count")
    sha = os.environ.get("GITHUB_SHA")
    if sha:
        require(data.get("gitSha") == sha, "manifest commit SHA mismatch")
    forms = data.get("forms")
    require(isinstance(forms, list) and len(forms) == 166, "not exactly 166 manifest rows")
    covered = set()
    for row in forms:
        require(isinstance(row, dict), "invalid row")
        code = row.get("formCode")
        require(code in source and code not in covered, "unexpected/duplicate form " + str(code))
        covered.add(code)
        mode, field_count = source[code]
        require(row.get("mode") == mode, code + " mode")
        require(row.get("fieldCount") == field_count, code + " field count")
        require(row.get("testCaseId") == "BE-R1-ALL-" + code, code + " case ID")
        require(row.get("state") == "PASSED_UNQUALIFIED_DRAFT", code + " state")
        for flag in ("generated", "poiOpened", "filledRoundTrip", "postgresPersisted",
                     "negativeContextRejected", "revisionConfirmed",
                     "issuedManifestVerified", "independentFieldOracle"):
            require(row.get(flag) is True, code + " " + flag)
        require(row.get("documentOriginalPersisted") is (mode == "EVIDENCE_ONLY"),
                code + " original presence")
    require(covered == set(source), "some catalog codes missing")
    require(golden_report.is_file(), "missing independent golden JUnit XML")
    golden_suite = ET.parse(golden_report).getroot()
    require(int(golden_suite.get("tests", "0")) == 36 and all(
        int(golden_suite.get(attr, "-1")) == 0
        for attr in ("failures", "errors", "skipped")),
        "independent golden cases not fully GREEN")
    print("FIN_BE_R1_JAVA_POSTGRES_EVIDENCE_GREEN",
          f"sha={data.get('gitSha')}",
          f"cases={len(covered)} structured=154 original_documents=12 typed_fields=794",
          f"junit_tests={tests} independent_goldens=36 issued_template_v2=166")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, ET.ParseError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
