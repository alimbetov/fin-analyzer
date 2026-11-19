#!/usr/bin/env python3
"""Fail-closed CI audit: prove 166 individually executed PostgreSQL form lifecycles.

JUnit Surefire names dynamic invocations by index rather than form code. The
test factory iterates sorted PostgreSQL form_code values and asserts that the
live database exactly matches the immutable 166-form ingestion registry.
This script uses the same sorted registry to map [1..166] to named reports.
It is evidence for storage/REST/export, NOT financial S2 or 166 browser UAT.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

DYNAMIC = re.compile(r"all166FormLifecycleCases\(\)\[([1-9][0-9]*)\]\Z")
EXPECTED_EXTRA = {
    "f02ExactHistoricalRevisionIsVerifiedNotLatest",
    "rowsetOrderAndValueHashesAreIndependentlyReconstructed",
    "evidenceMetadataAndStoredBytesAreBothRequired",
}
EXPECTED_MODES = {"SINGLE_RECORD": 3, "ROWSET": 151, "EVIDENCE": 12}


def require(ok: bool, message: str) -> None:
    if not ok:
        raise ValueError(message)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--xml", type=Path, default=Path(
        "form-library/target/surefire-reports/"
        "TEST-kz.fin.formlib.calc.ingest.Ingest2PostgresqlIntegrityTest.xml"))
    parser.add_argument("--manifest", type=Path, default=Path(
        "form-library/src/main/resources/catalog/fin-fact-ingestion-strategy-r0.tsv"))
    parser.add_argument("--out-dir", type=Path, default=Path("target/fin-166"))
    args = parser.parse_args()

    raw_manifest = args.manifest.read_bytes()
    with args.manifest.open("r", encoding="utf-8", newline="") as f:
        catalog = list(csv.DictReader(f, delimiter="\t"))
    require(len(catalog) == 166, "FORM_MANIFEST_NOT_166")
    codes = [r["form_code"] for r in catalog]
    require(len(set(codes)) == 166, "FORM_MANIFEST_DUPLICATES")
    indexed = {r["form_code"]: r for r in catalog}
    for mode, number in EXPECTED_MODES.items():
        require(sum(r["content_mode"] == mode for r in catalog) == number,
                "FORM_MANIFEST_MODE_COUNT_" + mode)

    xml_data = args.xml.read_bytes()
    suite = ET.fromstring(xml_data)
    require(suite.tag == "testsuite", "JUNIT_SUITE_MISSING")
    require(suite.attrib.get("name") ==
            "kz.fin.formlib.calc.ingest.Ingest2PostgresqlIntegrityTest",
            "JUNIT_WRONG_SUITE")
    for field, expected in (("tests", 169), ("errors", 0),
                            ("failures", 0), ("skipped", 0)):
        require(int(suite.attrib.get(field, "-1")) == expected,
                "JUNIT_" + field.upper() + "_INVALID")
    cases = suite.findall("testcase")
    require(len(cases) == 169, "JUNIT_169_CASES_REQUIRED")

    dynamic_cases: dict[int, ET.Element] = {}
    extra_cases: set[str] = set()
    for case in cases:
        require(not any(case.find(t) is not None
                        for t in ("failure", "error", "skipped")),
                "JUNIT_CASE_NOT_PASS:" + case.attrib.get("name", "?"))
        name = case.attrib.get("name", "")
        match = DYNAMIC.fullmatch(name)
        if match:
            index = int(match.group(1))
            require(index not in dynamic_cases, "JUNIT_DUPLICATE_CASE_INDEX")
            dynamic_cases[index] = case
        else:
            require(name in EXPECTED_EXTRA, "JUNIT_UNEXPECTED_CASE:" + name)
            require(name not in extra_cases, "JUNIT_DUPLICATE_EXTRA_CASE")
            extra_cases.add(name)

    require(set(dynamic_cases) == set(range(1, 167)),
            "JUNIT_MISSING_OR_EXTRA_FORM_CASES")
    require(extra_cases == EXPECTED_EXTRA, "JUNIT_MISSING_INTEGRITY_CASES")

    out = args.out_dir
    out.mkdir(parents=True, exist_ok=True)
    rows = []
    for i, code in enumerate(sorted(codes), start=1):
        profile = indexed[code]
        mode = profile["content_mode"]
        rows.append({
            "form_code": code,
            "content_mode": mode,
            "catalog_review_state": profile["review_state"],
            "test_case": dynamic_cases[i].attrib["name"],
            "status": "PASS",
            "scope": ("PG16_SAVE_REOPEN_REVISIONS_SHA_HTTP_ATTACHMENT"
                      if mode == "EVIDENCE"
                      else "PG16_SAVE_REOPEN_REVISIONS_SHA_HTTP_XLSX_EXPORT"),
            "financial_s2": "NOT_APPROVED",
        })
    with (out / "form-coverage.csv").open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)

    metadata = {
        "commit_sha": os.environ.get("GITHUB_SHA", "LOCAL"),
        "report": "FIN-166-PG16-LIFECYCLE-V1",
        "tests": len(cases),
        "form_cases": len(rows),
        "extra_integrity_cases": len(extra_cases),
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "mode_counts": EXPECTED_MODES,
        "financial_s2": "NOT_APPROVED",
        "browser_166_e2e": "NOT_PROVEN",
        "xlsx_import_154": "NOT_PROVEN",
        "test_xml_sha256": hashlib.sha256(xml_data).hexdigest(),
        "catalog_manifest_sha256": hashlib.sha256(raw_manifest).hexdigest(),
        "per_form": rows,
    }
    (out / "form-coverage.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print("[PASS] FIN 166/166 PostgreSQL form lifecycles, "
          "154 XLSX exports, 12 evidence attachments; 3 extra integrity tests")
    print("[INFO] Saved " + str(out / "form-coverage.csv"))
    print("[INFO] Production S2 and full 166-form browser E2E: NOT PROVEN")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, ET.ParseError, KeyError) as exc:
        print("[FAIL] FIN 166 form coverage: " + str(exc), file=sys.stderr)
        sys.exit(1)
