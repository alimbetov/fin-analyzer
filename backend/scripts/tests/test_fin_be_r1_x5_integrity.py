"""Independent adversarial tests for FIN-BE-R1-X5 merge validator.

Run with:
python3 -m unittest discover -s backend/scripts/tests -p test_fin_be_r1_x5_integrity.py
"""
import copy
import importlib.util
from pathlib import Path
import unittest
import uuid


SCRIPT = Path(__file__).resolve().parents[1] / "verify_fin_be_r1_x5_integrity.py"
SPEC = importlib.util.spec_from_file_location("fin_be_r1_x5_integrity", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def fixture():
    forms = {"DF-AST-01": ("TYPED_DRAFT", 2, 6),
             "D01": ("EVIDENCE_ONLY", 1, 7)}
    locales = ("ru", "zh-Hans")
    sha = "abcdef0" * 5 + "a" * 5
    assert len(sha) == 40  # only a workflow SHA shape, not approval
    source = []
    rendered = []
    for code, (mode, rows, fields) in forms.items():
        revision = str(uuid.uuid5(uuid.NAMESPACE_DNS, "FIN-R1-" + code))
        for lang in locales:
            label = code + "-" + lang
            x = {
                "formCode": code, "format": "XLSX", "displayLocale": lang,
                "sourceRevisionId": revision,
                "snapshotFingerprint": "a" * 64,
                "artifactSha256": "b" * 64,
                "sourceInputLocale": "ru", "mode": mode,
                "rowCount": rows, "fieldCount": fields,
                "sourceStatus": "CONFIRMED_UNQUALIFIED_DRAFT",
                "testCaseId": "FIN-X2-" + label,
                "issuedPopulated": True, "strictV2Parsed": True,
                "valuesEqual": True, "sourceContextUnchanged": True,
                "manifestDigestVerified": True, "receiptVerified": True,
                "verdict": "PASS"
            }
            source.append(x)
            for fmt in ("HTML", "PDF"):
                rendered.append({
                    "formCode": code, "displayLocale": lang, "format": fmt,
                    "sourceRevisionId": revision,
                    "snapshotFingerprint": "a" * 64,
                    "artifactDigest": "c" * 64,
                    "sourceState": "CONFIRMED_UNQUALIFIED_DRAFT",
                    "translationStatus": "PROVISIONAL_OR_MISSING_ONLY",
                    "testCaseId": "FIN-X-" + fmt + "-" + label,
                    "rows": rows, "fields": fields, "pageCount": 2 if fmt == "PDF" else 0,
                    "generated": True, "validated": True, "valuesEqual": True,
                    "verdict": "PASS"
                })
    xlsx = {
        "schemaVersion": "FIN_BE_R1_X2_ISSUED_XLSX_EXPORT_V1",
        "gitSha": sha, "displayLocales": list(locales),
        "financialQualified": False, "format": "XLSX", "formCount": 2,
        "casesPassed": 4, "cases": source,
    }
    render = {
        "schemaVersion": "FIN_BE_R1_X3_X4_RENDERER_COVERAGE_V1",
        "gitSha": sha, "displayLocales": list(locales),
        "financialQualified": False, "formats": ["HTML", "PDF"],
        "forms": 2, "casesPassed": 8, "cases": rendered,
    }
    return xlsx, render, forms, locales


class X5ThreeFormatIntegrityValidatorTest(unittest.TestCase):
    def green(self, xlsx, render, forms, locales):
        return MODULE.combine(xlsx, render, forms, locales)

    def assert_rejected(self, mutate):
        case = fixture()
        xlsx, render, forms, locales = copy.deepcopy(case)
        mutate(xlsx, render)
        with self.assertRaisesRegex(ValueError, "FIN_BE_R1_X5_INTEGRITY_REJECTED"):
            self.green(xlsx, render, forms, locales)

    def test_all_twelve_verified_outputs_join_with_one_revision(self):
        self.assertEqual(12, len(self.green(*fixture())))

    def test_pdf_revision_swap_is_rejected(self):
        self.assert_rejected(lambda x, r: r["cases"][1].update(
            sourceRevisionId=str(uuid.uuid4())))

    def test_pdf_fingerprint_swap_is_rejected(self):
        self.assert_rejected(lambda x, r: r["cases"][1].update(
            snapshotFingerprint="d" * 64))

    def test_same_form_mixed_revision_between_languages_is_rejected(self):
        self.assert_rejected(lambda x, r: x["cases"][1].update(
            sourceRevisionId=str(uuid.uuid4())))

    def test_duplicate_html_case_is_rejected(self):
        self.assert_rejected(lambda x, r: r["cases"].__setitem__(
            1, copy.deepcopy(r["cases"][0])))

    def test_missing_pdf_case_is_rejected(self):
        self.assert_rejected(lambda x, r: r["cases"].pop())

    def test_unparsed_pdf_or_skipped_validation_is_rejected(self):
        self.assert_rejected(lambda x, r: r["cases"][1].update(validated=False))

    def test_provisional_translation_cannot_be_claimed_approved(self):
        self.assert_rejected(lambda x, r: r["cases"][0].update(
            translationStatus="APPROVED"))

    def test_actual_financial_state_is_rejected(self):
        self.assert_rejected(lambda x, r: x["cases"][0].update(
            sourceStatus="ACTUAL"))

    def test_invalid_or_missing_digest_is_rejected(self):
        self.assert_rejected(lambda x, r: r["cases"][0].update(
            artifactDigest="not-a-digest"))

    def test_wrong_row_count_is_rejected(self):
        self.assert_rejected(lambda x, r: r["cases"][0].update(rows=999))

    def test_wrong_field_count_is_rejected(self):
        self.assert_rejected(lambda x, r: r["cases"][0].update(fields=999))

    def test_unpinned_or_ill_formed_revision_is_rejected(self):
        self.assert_rejected(lambda x, r: x["cases"][0].update(
            sourceRevisionId="latest"))

    def test_manifest_from_different_git_sha_is_rejected(self):
        self.assert_rejected(lambda x, r: r.update(gitSha="f" * 40))

    def test_missing_source_validation_flag_is_rejected(self):
        self.assert_rejected(lambda x, r: x["cases"][0].update(
            strictV2Parsed=False))

    def test_pdf_pages_outside_budget_is_rejected(self):
        self.assert_rejected(lambda x, r: r["cases"][1].update(pageCount=200))


if __name__ == "__main__":
    unittest.main()
