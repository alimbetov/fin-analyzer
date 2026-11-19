#!/usr/bin/env python3
"""CALC-166 P0: fail closed on candidate form/field drift. Standard library only.

Does not grant financial S2 authorization or execute computations.
Run from repository root: python3 scripts/check-fin-calc166-candidate-inventory.py
"""
import csv
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORMS = ROOT / "docs/FIN-CALC-R0-COVERAGE-166-BASELINE.tsv"
FIELDS = ROOT / "docs/FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv"
SCRIPT = ROOT / "scripts/fin-calc-166-merge-candidate-registry.sql"


def load(path):
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def require(condition, code):
    if not condition:
        raise SystemExit(f"FAIL {code}")


def main():
    forms = load(FORMS)
    fields = load(FIELDS)
    codes = [f["form_code"] for f in forms]
    pairs = [(f["form_code"], f["field_code"]) for f in fields]
    require(len(forms) == 166 and len(set(codes)) == 166, "166_FORM_IDENTITY")
    require(len(fields) == 794 and len(set(pairs)) == 794, "794_METRIC_IDENTITY")
    require(all(f["form_code"] in codes for f in fields), "UNKNOWN_FIELD_FORM")
    mode_count = Counter(f["bootstrap_content_mode"] for f in forms)
    require(mode_count == {"ROWSET": 151, "SINGLE_RECORD": 3, "EVIDENCE": 12},
            f"FORM_MODES {mode_count}")
    require(not any(f["form_code"] in {f2["form_code"] for f2 in forms if f2["bootstrap_content_mode"] == "EVIDENCE"}
                    for f in fields), "EVIDENCE_WITH_METRIC")
    require(("F02", "netProfit") in pairs, "DERIVED_EXCEPTION_MISSING")
    require(len(pairs) - 1 == 793, "PHYSICAL_FIELD_COUNT")
    for code in codes:
        row = next(f for f in forms if f["form_code"] == code)
        actual = len([p for p in pairs if p[0] == code])
        require(actual == int(row["presentation_r6_field_count"]),
                f"FIELD_COUNT_MISMATCH {code}: {actual} vs {row['presentation_r6_field_count']}")
    sql = SCRIPT.read_text(encoding="utf-8")
    require("FORM_DERIVED_CANDIDATE" in sql
            and "CALC166_UNEXPECTED_DERIVED_CANDIDATE" in sql
            and "n_physical<>793 OR n_derived<>1" in sql, "SQL_DERIVED_GUARD")
    require("MERGE INTO fin_calc.form_calc_candidate_inventory" in sql
            and "MERGE INTO fin_calc.form_field_calc_candidate_inventory" in sql, "SQL_MERGE")
    require("fin_calc.calc_result" not in sql.split("BEGIN;", 1)[-1].split("COMMIT;", 1)[0],
            "IMMUTABLE_RESULT_MUTATION")
    print("PASS CALC166 source inventory: 166 forms, 151 rowsets, 3 single records, 12 evidence")
    print("PASS CALC166 field coverage: 793 physical source fields + 1 F02 derived candidate")
    print("PASS CALC166 mutable MERGE candidate only; no qualified-result mutation")
    print("NOTE: This is STATIC validation, not PostgreSQL E2E and not S2 qualification.")


if __name__ == "__main__":
    main()
