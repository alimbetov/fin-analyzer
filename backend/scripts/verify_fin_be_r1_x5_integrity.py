#!/usr/bin/env python3
"""FIN-BE-R1-X5 pre-acceptance gate: immutable XLSX/HTML/PDF source parity.

This gate *joins* two independently created Java/PostgreSQL evidence manifests
on form+displayLocale+sourceRevisionId+snapshotFingerprint. It fails closed on
duplicates, omissions, unpinned/mismatched revisions, fake approvals, incomplete
format validation and SHA mismatches. It does NOT approve accounting semantics,
language translations, document issuer authenticity or a production release.
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
X2 = BACKEND / "target/fin-evidence/fin-be-r1-x2-xlsx-export-coverage.json"
X34 = BACKEND / "target/fin-evidence/fin-be-r1-x3-x4-render-coverage.json"
OUT = BACKEND / "target/fin-evidence/fin-be-r1-x-export-coverage.json"
JUNIT = (BACKEND / "target/surefire-reports/"
         "TEST-kz.fin.backend.intake.CatalogXlsxRoundTripIntegrationTest.xml")
LOCALES = ("ru", "kk", "en", "zh-Hans")
FORMATS = ("XLSX", "HTML", "PDF")
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def require(condition, message):
    if not condition:
        raise ValueError("FIN_BE_R1_X5_INTEGRITY_REJECTED: " + str(message))


def string_hash(value, subject):
    require(isinstance(value, str) and HEX64.fullmatch(value), subject + " SHA256")


def identity(value, subject):
    require(isinstance(value, str), subject + " missing UUID")
    try:
        assert str(uuid.UUID(value)) == value
    except (ValueError, AssertionError, AttributeError) as exc:
        raise ValueError("FIN_BE_R1_X5_INTEGRITY_REJECTED: " + subject + " UUID") from exc


def read_catalog():
    with CATALOG.open(encoding="utf-8", newline="") as source:
        rows = list(csv.DictReader(source, delimiter="\t"))
    forms = {}
    for row in rows:
        code = row["formCode"]
        require(code not in forms and code, "duplicate/empty catalog code")
        if row["fieldEvidence"] == "TYPED_FIELD_HINTS":
            count = len(row["fieldContract"].split(";"))
            mode, row_count = "TYPED_DRAFT", 2
        elif row["formKind"] in ("DOCUMENT", "EXTERNAL_TAX"):
            mode, row_count, count = "EVIDENCE_ONLY", 1, 7
        else:
            # Existing X2 verifier checks matching specialized schema registry;
            # this independent gate validates the whole required coordinate set.
            mode, row_count, count = "SCHEMA_NOT_APPROVED", 2, None
        forms[code] = (mode, row_count, count)
    require(len(rows) == 166 and len(forms) == 166, "catalog must contain exactly 166 forms")
    return forms


def combine(xlsx, rendered, forms, locales=LOCALES):
    """Pure validation helper for adversarial unittest cases; no I/O."""
    require(xlsx.get("schemaVersion") == "FIN_BE_R1_X2_ISSUED_XLSX_EXPORT_V1",
            "XLSX proof version")
    require(rendered.get("schemaVersion") == "FIN_BE_R1_X3_X4_RENDERER_COVERAGE_V1",
            "render proof version")
    require(xlsx.get("financialQualified") is False and
            rendered.get("financialQualified") is False,
            "financial approval fabricated")
    require(xlsx.get("displayLocales") == list(locales) and
            rendered.get("displayLocales") == list(locales),
            "locale contract drift")
    require(xlsx.get("format") == "XLSX" and
            rendered.get("formats") == ["HTML", "PDF"], "renderer contract drift")
    require(xlsx.get("gitSha") == rendered.get("gitSha")
            and isinstance(xlsx.get("gitSha"), str) and xlsx["gitSha"],
            "manifest SHA mismatch")
    expected_per_format = len(forms) * len(locales)
    require(xlsx.get("formCount") == len(forms)
            and rendered.get("forms") == len(forms)
            and xlsx.get("casesPassed") == expected_per_format
            and rendered.get("casesPassed") == expected_per_format * 2,
            "incorrect manifest counts")

    source = {}
    for item in xlsx.get("cases", []):
        require(isinstance(item, dict), "XLSX case shape")
        code, locale = item.get("formCode"), item.get("displayLocale")
        key = (code, locale)
        require(code in forms and locale in locales and key not in source,
                "unexpected/duplicate XLSX " + repr(key))
        expected_mode, expected_rows, expected_fields = forms[code]
        require(item.get("format") == "XLSX" and
                item.get("testCaseId") == "FIN-X2-" + code + "-" + locale
                and item.get("mode") == expected_mode
                and item.get("rowCount") == expected_rows
                and isinstance(item.get("fieldCount"), int)
                and item["fieldCount"] > 0
                and (expected_fields is None or item["fieldCount"] == expected_fields),
                "XLSX schema/rows mismatch " + repr(key))
        require(item.get("sourceStatus") == "CONFIRMED_UNQUALIFIED_DRAFT"
                and item.get("verdict") == "PASS"
                and item.get("sourceInputLocale") in ("ru", "kk", "en"),
                "XLSX source provenance " + repr(key))
        require(all(item.get(flag) is True for flag in (
            "issuedPopulated", "strictV2Parsed", "valuesEqual",
            "sourceContextUnchanged", "manifestDigestVerified", "receiptVerified")),
            "XLSX proof not actually passed " + repr(key))
        identity(item.get("sourceRevisionId"), "XLSX revision " + repr(key))
        string_hash(item.get("snapshotFingerprint"), "XLSX legacy content " + repr(key))
        string_hash(item.get("contentFingerprintV2"), "XLSX V2 content " + repr(key))
        string_hash(item.get("artifactSha256"), "XLSX artifact " + repr(key))
        source[key] = item
    require(len(source) == expected_per_format, "XLSX coverage missing")

    target = {}
    for item in rendered.get("cases", []):
        require(isinstance(item, dict), "HTML/PDF case shape")
        code, locale, fmt = (item.get("formCode"), item.get("displayLocale"),
                             item.get("format"))
        key = (code, locale, fmt)
        require(code in forms and locale in locales and fmt in ("HTML", "PDF")
                and key not in target, "duplicate/unknown render " + repr(key))
        _, expected_rows, expected_fields = forms[code]
        require(item.get("testCaseId") == "FIN-X-" + fmt + "-" + code + "-" + locale
                and item.get("rows") == expected_rows
                and isinstance(item.get("fields"), int)
                and item["fields"] > 0
                and (expected_fields is None or item["fields"] == expected_fields),
                "render field/row count " + repr(key))
        require(item.get("sourceState") == "CONFIRMED_UNQUALIFIED_DRAFT"
                and item.get("translationStatus") == "PROVISIONAL_OR_MISSING_ONLY"
                and item.get("verdict") == "PASS"
                and all(item.get(flag) is True for flag in (
                    "generated", "validated", "valuesEqual")),
                "render proof incomplete " + repr(key))
        identity(item.get("sourceRevisionId"), "render revision " + repr(key))
        string_hash(item.get("snapshotFingerprint"), "render legacy content " + repr(key))
        string_hash(item.get("contentFingerprintV2"), "render V2 content " + repr(key))
        string_hash(item.get("artifactDigest"), "render artifact " + repr(key))
        pages = item.get("pageCount")
        require(1 <= pages <= 120 if fmt == "PDF" and isinstance(pages, int)
                else pages == 0 if fmt == "HTML" else False,
                "PDF page count or HTML metadata " + repr(key))
        target[key] = item
    require(len(target) == expected_per_format * 2, "HTML/PDF coverage missing")

    merged = []
    revision_by_form = {}
    for code in forms:
        for locale in locales:
            x = source.get((code, locale))
            require(x is not None, "missing XLSX " + code + "/" + locale)
            pair = (x["sourceRevisionId"], x["snapshotFingerprint"],
                    x["contentFingerprintV2"])
            prior = revision_by_form.setdefault(code, pair)
            require(prior == pair, "mixed source revisions across languages " + code)
            for fmt in FORMATS:
                r = x if fmt == "XLSX" else target.get((code, locale, fmt))
                require(r is not None, "missing " + fmt + " " + code + "/" + locale)
                revision = r["sourceRevisionId"]
                fp = r["snapshotFingerprint"]
                fp_v2 = r["contentFingerprintV2"]
                require((revision, fp, fp_v2) == pair,
                        "cross-format source revision/fingerprint drift "
                        + repr((code, locale, fmt)))
                if fmt != "XLSX":
                    require(r["rows"] == x["rowCount"] and
                            r["fields"] == x["fieldCount"],
                            "cross-format rows/fields drift " + repr((code, locale, fmt)))
                merged.append({
                    "formCode": code, "displayLocale": locale, "format": fmt,
                    "sourceRevisionId": revision, "snapshotFingerprint": fp,
                    "contentFingerprintV2": fp_v2,
                    "sourceStatus": "CONFIRMED_UNQUALIFIED_DRAFT",
                    "rowCount": x["rowCount"], "fieldCount": x["fieldCount"],
                    "artifactDigest": (x["artifactSha256"] if fmt == "XLSX"
                                       else r["artifactDigest"]),
                    "testCaseId": r["testCaseId"], "generated": True,
                    "validated": True, "valuesEqual": True,
                    "verdict": "PASS", "financialQualified": False
                })
    require(len(merged) == len(forms) * len(locales) * 3,
            "not exactly three outputs per form and locale")
    return merged


def main():
    forms = read_catalog()
    require(JUNIT.is_file(), "missing JUnit proof of real PostgreSQL tests")
    suite = ET.parse(JUNIT).getroot()
    tests = int(suite.get("tests", "-1"))
    require(tests >= 186 and all(int(suite.get(k, "-1")) == 0 for k in (
            "failures", "errors", "skipped")), "source JUnit not fully green")
    xlsx = json.loads(X2.read_text(encoding="utf-8"))
    rendered = json.loads(X34.read_text(encoding="utf-8"))
    github_sha = os.environ.get("GITHUB_SHA")
    if github_sha:
        require(xlsx.get("gitSha") == github_sha and
                rendered.get("gitSha") == github_sha,
                "evidence generated on a different commit")
    merged = combine(xlsx, rendered, forms)
    report = {
        "schemaVersion": "FIN_BE_R1_X5_THREE_FORMAT_INTEGRITY_V1",
        "gitSha": xlsx["gitSha"],
        "source": "PostgreSQL/Testcontainers/JUnit + POI + JSoup + PDFBox",
        "forms": 166, "displayLocales": list(LOCALES),
        "formats": list(FORMATS), "casesPassed": 1992,
        "financialQualified": False, "releaseDecision": "ENGINEERING_HOLD",
        "cases": merged
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")
    print("FIN_BE_R1_X5_THREE_FORMAT_INTEGRITY_GREEN",
          "forms=166", "languages=4", "formats=3", "cases=1992",
          "single_revision_per_form=true", "financial_s2=HOLD",
          "release=ENGINEERING_HOLD", "sha=" + xlsx["gitSha"])


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, ET.ParseError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
