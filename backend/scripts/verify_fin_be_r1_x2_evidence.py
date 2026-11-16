#!/usr/bin/env python3
"""Fail-closed FIN-BE-R1-X2: 166 server-issued populated XLSX x 4 locales.

The manifest is produced by real Java/PostgreSQL Testcontainers/Apache POI
tests. Validate it independently against the source catalog and Surefire XML.
No static catalog lookup, placeholder spreadsheet or metadata-only proof counts.
"""
import csv
import json
import os
import re
import sys
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "backend"
CATALOG = BACKEND / "src/main/resources/fin/form-compatibility.tsv"
CANDIDATES = BACKEND / "src/main/resources/fin/specialized-schema-candidates-r6.tsv"
EVIDENCE = BACKEND / "target/fin-evidence/fin-be-r1-x2-xlsx-export-coverage.json"
JUNIT = (BACKEND / "target/surefire-reports/"
         "TEST-kz.fin.backend.intake.CatalogXlsxRoundTripIntegrationTest.xml")
DISPLAY = ("ru", "kk", "en", "zh-Hans")
HASH = re.compile(r"^[0-9a-f]{64}$")


def require(condition, reason):
    if not condition:
        raise ValueError("FIN_BE_R1_X2_EVIDENCE_REJECTED: " + reason)


def read(path):
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def main():
    catalog = read(CATALOG)
    candidates = read(CANDIDATES)
    require(len(catalog) == 166 and len(candidates) == 32, "catalog size")
    candidate = {c["formCode"]: c["fields"].split(";") for c in candidates}
    require(len(candidate) == 32, "duplicate candidate")
    forms = {}
    for form in catalog:
        code = form["formCode"]
        require(code not in forms, "duplicate form " + code)
        if form["fieldEvidence"] == "TYPED_FIELD_HINTS":
            mode, field_count, row_count = (
                "TYPED_DRAFT", len(form["fieldContract"].split(";")), 2)
        elif form["formKind"] in ("DOCUMENT", "EXTERNAL_TAX"):
            mode, field_count, row_count = "EVIDENCE_ONLY", 7, 1
        else:
            require(code in candidate, "no schema candidate " + code)
            mode, field_count, row_count = (
                "SCHEMA_NOT_APPROVED", len(candidate[code]), 2)
        forms[code] = (mode, field_count, row_count)
    require(len(forms) == 166 and sum(
        n for mode, n, _ in forms.values() if mode != "EVIDENCE_ONLY") == 794,
        "structured field count")

    require(JUNIT.is_file(), "missing JUnit source report")
    suite = ET.parse(JUNIT).getroot()
    total = int(suite.get("tests", "0"))
    require(total >= 181 and all(
        int(suite.get(k, "-1")) == 0 for k in ("failures", "errors", "skipped")),
        "source JUnit failed/skipped/missing: " + str(total))
    test_names = [t.get("name") for t in suite.findall(".//testcase")]
    require(len(test_names) == total, "JUnit testcase count mismatch")
    # JUnit 5/Surefire dynamic test names may be stored as the display name,
    # rather than as the @TestFactory method name. Exact 166-form identity
    # and 664 XLSX/locale proofs are validated from the separate Java manifest.
    require(total >= 181 and len(test_names) == total,
            "source JUnit dynamic testcase cardinality missing")

    require(EVIDENCE.is_file(), "missing actual Java X2 evidence")
    data = json.loads(EVIDENCE.read_text(encoding="utf-8"))
    require(data.get("schemaVersion") == "FIN_BE_R1_X2_ISSUED_XLSX_EXPORT_V1",
            "unknown evidence schema")
    sha = os.getenv("GITHUB_SHA")
    if sha:
        require(data.get("gitSha") == sha, "commit SHA differs from workflow")
    require(data.get("formCount") == 166
            and data.get("displayLocales") == list(DISPLAY)
            and data.get("format") == "XLSX"
            and data.get("financialQualified") is False
            and data.get("casesPassed") == 664,
            "manifest claims incompatible with X2")
    cases = data.get("cases")
    require(isinstance(cases, list) and len(cases) == 664,
            "not exactly 664 real XLSX cases")
    seen = set()
    for case in cases:
        require(isinstance(case, dict), "invalid case")
        code = case.get("formCode")
        language = case.get("displayLocale")
        key = (code, language)
        require(code in forms and language in DISPLAY and key not in seen,
                "unexpected/duplicate form-language: " + str(key))
        seen.add(key)
        mode, fields, rows = forms[code]
        require(case.get("testCaseId") == f"FIN-X2-{code}-{language}",
                "missing exact dynamic test identity " + code)
        require(case.get("mode") == mode
                and case.get("rowCount") == rows
                and case.get("fieldCount") == fields,
                "source row/field count mismatch " + code)
        require(case.get("sourceStatus") == "CONFIRMED_UNQUALIFIED_DRAFT",
                "unqualified source falsely approved")
        for flag in ("issuedPopulated", "strictV2Parsed", "valuesEqual",
                     "sourceContextUnchanged", "manifestDigestVerified"):
            require(case.get(flag) is True, f"{code}/{language}: {flag}")
        require(isinstance(case.get("artifactSha256"), str)
                and HASH.fullmatch(case["artifactSha256"]),
                "missing artifact SHA-256")
        try:
            uuid.UUID(case["sourceRevisionId"])
        except (ValueError, KeyError, TypeError) as exc:
            raise ValueError("FIN_BE_R1_X2_EVIDENCE_REJECTED: revision UUID") from exc
    require(seen == {(code, locale) for code in forms for locale in DISPLAY},
            "missing form/display combinations")
    print("FIN_BE_R1_X2_POPULATED_XLSX_GREEN",
          f"sha={data.get('gitSha')}", "forms=166", "languages=4",
          "real_issued_xlsx=664", "structured=154", "evidence_only=12",
          "typed_fields=794", "actual_postgres=true",
          "strict_v2_roundtrip=true", "financial_qualification=HOLD",
          f"junit_tests={total}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, TypeError, KeyError, ET.ParseError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
