#!/usr/bin/env python3
"""FIN-BE-R1-X / X0: mandatory cross-checked four-language catalog CI gate.

The Java @TestFactory must execute 166 * 4 real presentation views and write
its manifest. This script independently reads both field-schema TSVs, the
794-slot translation inventory, the Java JUnit XML and the emitted manifest.
No S2 approval or Chinese accounting translation is inferred or granted.
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
SPECIALIZED = BACKEND / "src/main/resources/fin/specialized-schema-candidates-r6.tsv"
TRANSLATIONS = BACKEND / "src/main/resources/fin/form-field-presentation-review-r6.tsv"
MANIFEST = BACKEND / "target/fin-evidence/fin-be-r1-x0-four-language-coverage.json"
JUNIT = (BACKEND / "target/surefire-reports/"
         "TEST-kz.fin.backend.intake.CatalogFourLanguagePresentationTest.xml")
DISPLAY = ["ru", "kk", "en", "zh-Hans"]
INPUT = ["ru", "kk", "en"]
LABEL_COL = {"ru": "labelRu", "kk": "labelKk", "en": "labelEn", "zh-Hans": "labelZhHans"}


def require(condition, explanation):
    if not condition:
        raise ValueError("FIN_BE_R1_X0_PROOF_REJECTED: " + explanation)


def tsv(path):
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def main():
    catalog = tsv(CATALOG)
    specialized = tsv(SPECIALIZED)
    translations = tsv(TRANSLATIONS)
    require(len(catalog) == 166 and len(specialized) == 32 and
            len(translations) == 794, "catalog count drift")

    fields = {}
    modes = {}
    candidate = {r["formCode"]: r for r in specialized}
    require(len(candidate) == 32, "duplicate specialized form")
    for form in catalog:
        code = form["formCode"]
        require(code not in modes, "duplicate form " + code)
        if form["fieldEvidence"] == "TYPED_FIELD_HINTS":
            mode = "TYPED_DRAFT"
            raw_fields = form["fieldContract"].split(";")
        elif form["formKind"] in ("DOCUMENT", "EXTERNAL_TAX"):
            mode = "EVIDENCE_ONLY"
            raw_fields = []
        else:
            mode = "SCHEMA_NOT_APPROVED"
            require(code in candidate, "candidate missing: " + code)
            raw_fields = candidate[code]["fields"].split(";")
        modes[code] = mode
        for field in raw_fields:
            field_code, field_type = field.split(":")
            key = (code, field_code)
            require(key not in fields, "duplicate field " + str(key))
            fields[key] = field_type
    require(len(fields) == 794, "structured field count != 794")
    require([sum(mode == m for mode in modes.values()) for m in (
        "TYPED_DRAFT", "SCHEMA_NOT_APPROVED", "EVIDENCE_ONLY")] ==
            [122, 32, 12], "capture mode partition")

    translated = {locale: 0 for locale in DISPLAY}
    available = {}
    for row in translations:
        key = (row["formCode"], row["fieldCode"])
        require(key in fields and key not in available, "wrong or duplicate review key " + str(key))
        require(fields[key] == row["fieldType"], "field type drift " + str(key))
        require(row["reviewStatus"] in ("PROVISIONAL_NOT_S2", "PENDING_TRANSLATION"),
                "forbidden APPROVED without S2 evidence " + str(key))
        require(row["requiredness"] == "PENDING_METHOD_REVIEW", "invented requiredness")
        require(bool(row["unitPolicy"]), "missing unit policy")
        for locale in DISPLAY:
            label = row[LABEL_COL[locale]]
            if label:
                require(row["reviewStatus"] == "PROVISIONAL_NOT_S2",
                        "unreviewed translated label " + str(key))
                translated[locale] += 1
        if row["reviewStatus"] == "PENDING_TRANSLATION":
            require(not any(row[LABEL_COL[locale]] for locale in DISPLAY),
                    "unreviewed label " + str(key))
        available[key] = row
    require(set(available) == set(fields), "review registry keys differ from schema")
    require(translated == {"ru": 9, "kk": 9, "en": 9, "zh-Hans": 6},
            "unreviewed locale coverage changed: " + repr(translated))

    require(JUNIT.is_file(), "missing actual JUnit XML")
    suite = ET.parse(JUNIT).getroot()
    count = int(suite.get("tests", "0"))
    require(count >= 668 and all(int(suite.get(key, "-1")) == 0
                                 for key in ("errors", "failures", "skipped")),
            f"JUnit failed/skipped: tests={count} "
            f"failures={suite.get('failures')} errors={suite.get('errors')} "
            f"skipped={suite.get('skipped')}")
    require(MANIFEST.is_file(), "missing Java-generated X0 manifest")
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    require(manifest.get("schemaVersion") == "FIN_BE_R1_X0_FOUR_LANGUAGE_CATALOG_V1",
            "unknown manifest version")
    if os.environ.get("GITHUB_SHA"):
        require(manifest.get("gitSha") == os.environ["GITHUB_SHA"], "wrong manifest SHA")
    require(manifest.get("catalogForms") == 166 and
            manifest.get("structuredFieldSlots") == 794 and
            manifest.get("formLocaleCasesPassed") == 664,
            "manifest count incomplete")
    require(manifest.get("sourceInputLocales") == INPUT, "source locale contract changed")
    require(manifest.get("displayLocales") == DISPLAY, "display locale contract changed")
    require(manifest.get("financialS2Approved") is False and
            manifest.get("translationStatus") == "PROVISIONAL_OR_MISSING_ONLY",
            "unapproved financial/translation status promoted")

    coverage = manifest.get("coverage")
    require(isinstance(coverage, dict) and set(coverage) == set(DISPLAY),
            "coverage missing display locale")
    for locale in DISPLAY:
        row = coverage[locale]
        require(row.get("totalFields") == 794 and
                row.get("translatedFields") == translated[locale] and
                row.get("provisionalFields") == translated[locale] and
                row.get("approvedFields") == 0 and
                row.get("missingFields") == 794 - translated[locale] and
                row.get("translatedFormTitles") == 0 and
                row.get("missingFormTitles") == 166,
                "fabricated locale readiness: " + locale)

    forms = manifest.get("forms")
    require(isinstance(forms, list) and len(forms) == 664, "not 664 real form-language proofs")
    seen = set()
    for row in forms:
        code, locale = row.get("formCode"), row.get("displayLocale")
        key = (code, locale)
        require(code in modes and locale in DISPLAY and key not in seen,
                "duplicate/unknown form-locale " + str(key))
        seen.add(key)
        source_fields = [field for field in fields if field[0] == code]
        expected_count = len(source_fields) if modes[code] != "EVIDENCE_ONLY" else 7
        actual_translated = sum(bool(available[field][LABEL_COL[locale]])
                                for field in source_fields)
        require(row.get("schemaFieldCount") == expected_count and
                row.get("translatedFields") == actual_translated and
                row.get("missingFields") == expected_count - actual_translated and
                row.get("approvedFields") == 0 and
                row.get("sourceInputLocale") == "ru" and
                row.get("formTitleStatus") == "MISSING" and
                row.get("state") == "PROVISIONAL_OR_MISSING_ONLY",
                "inconsistent form/locale provenance " + str(key))
    require(seen == {(code, locale) for code in modes for locale in DISPLAY},
            "uncovered form/locale combinations")
    print("FIN_BE_R1_X0_FOUR_LANGUAGE_CATALOG_GREEN",
          f"sha={manifest.get('gitSha')}", "cases=664", "fields=794",
          "source_input=ru,kk,en", "display=ru,kk,en,zh-Hans",
          "translated=9,9,9,6", "approved=0", "financial_s2=HOLD",
          f"junit_tests={count}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
