#!/usr/bin/env python3
"""Mandatory X3/X4 structural render proof from the real PostgreSQL suite.

Requires one snapshot readback per form, four HTML and four parsed PDF outputs
per form, a Java JUnit GREEN suite, immutable revision provenance, and no
financial S2 approval claim. It must not accept a catalog-only checklist.
"""
import csv
import json
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "backend"
CATALOG = BACKEND / "src/main/resources/fin/form-compatibility.tsv"
MANIFEST = BACKEND / "target/fin-evidence/fin-be-r1-x3-x4-render-coverage.json"
REPORTS = BACKEND / "target/surefire-reports"
LOCALES = ("ru", "kk", "en", "zh-Hans")
FORMATS = ("HTML", "PDF")


def require(ok, message):
    if not ok:
        raise ValueError("FIN_BE_R1_X3_X4_PROOF_REJECTED: " + message)


def check_junit(name, minimum):
    path = REPORTS / ("TEST-kz.fin.backend.intake." + name + ".xml")
    require(path.is_file(), "missing JUnit XML " + name)
    suite = ET.parse(path).getroot()
    tests = int(suite.get("tests", "0"))
    bad = [suite.get(f, "-1") for f in ("failures", "errors", "skipped")]
    require(tests >= minimum and all(int(x) == 0 for x in bad),
            name + " JUnit red or skipped " + str(tests) + " " + str(bad))
    return tests


def main():
    with CATALOG.open(encoding="utf-8", newline="") as f:
        forms = list(csv.DictReader(f, delimiter="\t"))
    require(len(forms) == 166 and len({r["formCode"] for r in forms}) == 166,
            "server catalog shape")
    form_codes = {r["formCode"] for r in forms}

    real = check_junit("CatalogXlsxRoundTripIntegrationTest", 179)
    unit = check_junit("FinancialReportHtmlPdfRendererTest", 5)

    require(MANIFEST.is_file(), "missing Java-emitted 1328-case proof")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    require(manifest.get("schemaVersion") == "FIN_BE_R1_X3_X4_RENDERER_COVERAGE_V1",
            "unknown schema version")
    if os.getenv("GITHUB_SHA"):
        require(manifest.get("gitSha") == os.getenv("GITHUB_SHA"), "wrong commit")
    require(manifest.get("forms") == 166
            and manifest.get("displayLocales") == list(LOCALES)
            and manifest.get("formats") == list(FORMATS)
            and manifest.get("casesPassed") == 1328
            and manifest.get("financialQualified") is False,
            "summary missing or false approved claim")
    data = manifest.get("cases")
    require(isinstance(data, list) and len(data) == 1328, "not 1328 output cases")
    unique = set()
    each_revision = {}
    for row in data:
        require(isinstance(row, dict), "invalid case object")
        code = row.get("formCode")
        locale = row.get("displayLocale")
        fmt = row.get("format")
        key = (code, locale, fmt)
        require(code in form_codes and locale in LOCALES and fmt in FORMATS,
                "unknown form/display/format " + str(key))
        require(key not in unique, "duplicate form/locale/format " + str(key))
        unique.add(key)
        require(row.get("testCaseId") == "FIN-X-" + fmt + "-" + code + "-" + locale,
                "test case not uniquely identified " + str(key))
        require(row.get("sourceState") == "CONFIRMED_UNQUALIFIED_DRAFT",
                "unapproved source promoted " + str(key))
        require(row.get("generated") is True and row.get("validated") is True
                and row.get("valuesEqual") is True and row.get("verdict") == "PASS",
                "output was not actually validated " + str(key))
        revision = row.get("sourceRevisionId")
        fp = row.get("snapshotFingerprint")
        fp_v2 = row.get("contentFingerprintV2")
        digest = row.get("artifactDigest")
        require(isinstance(revision, str) and len(revision) == 36
                and isinstance(fp, str) and len(fp) == 64
                and isinstance(fp_v2, str) and len(fp_v2) == 64
                and all(ch in "0123456789abcdef" for ch in fp_v2)
                and isinstance(digest, str) and len(digest) == 64
                and all(c in "0123456789abcdef" for c in digest),
                "missing source provenance/digest " + str(key))
        require(row.get("translationStatus") == "PROVISIONAL_OR_MISSING_ONLY",
                "translation falsely approved " + str(key))
        require(row.get("fields", 0) > 0 and row.get("rows", 0) > 0,
                "no real persisted source fields/rows " + str(key))
        if fmt == "PDF":
            require(1 <= row.get("pageCount", 0) <= 120,
                    "PDF not rendered with bounded pages " + str(key))
        else:
            require(row.get("pageCount") == 0,
                    "HTML has spurious PDF page count " + str(key))
        prior = each_revision.setdefault(code, (revision, fp, fp_v2))
        require(prior == (revision, fp, fp_v2), "HTML/PDF not bound to same snapshot " + code)
    require(unique == {(c, l, f) for c in form_codes for l in LOCALES for f in FORMATS},
            "some form/locale outputs missing")
    print("FIN_BE_R1_X3_X4_RENDERERS_GREEN",
          f"sha={manifest.get('gitSha')}", "formats=HTML,PDF",
          "forms=166", "locales=4", "cases=1328",
          f"postgres_junit={real}", f"security_unicode_junit={unit}",
          "financial_s2=HOLD")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError) as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)
