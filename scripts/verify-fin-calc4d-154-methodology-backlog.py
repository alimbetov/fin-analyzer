#!/usr/bin/env python3
"""Deterministic fail-closed check of 154-form methodology authoring backlog.

This file validates provenance ONLY, not financial correctness or S2 approval.
It forbids inventing formulas by auto-assigning SUM to physical fields.
"""
from __future__ import annotations

import csv
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"


def load(name: str) -> list[dict[str, str]]:
    with (DOCS / name).open(encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


def fail(message: str) -> None:
    raise SystemExit("CALC4D_154_BACKLOG_INVALID: " + message)


def join(values: list[str]) -> str:
    return ";".join(sorted(set(values)))


def verify() -> None:
    forms = load("FIN-CALC-R0-COVERAGE-166-BASELINE.tsv")
    fields = load("FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv")
    metrics = load("FIN-CALC154-METRIC-COVERAGE.tsv")
    models = load("FIN-CALC154-MODEL-OUTPUT-COVERAGE.tsv")
    backlog = load("FIN-CALC4D-154-METHODOLOGY-AUTHORING-BACKLOG.tsv")
    expected = {
        f["form_code"]: f for f in forms
        if f["bootstrap_content_mode"] in {"ROWSET", "SINGLE_RECORD"}
    }
    if len(expected) != 154 or len(backlog) != 154:
        fail("catalog and backlog must contain 154 distinct numeric-data forms")
    seen: set[str] = set()
    counts = Counter()
    for row in backlog:
        code = row["formCode"]
        if code not in expected or code in seen:
            fail("unknown or duplicated form: " + code)
        seen.add(code)
        f = expected[code]
        if row["functionalDomain"] != f["functional_domain"]:
            fail(code + " domain drift")
        if row["contentMode"] != f["bootstrap_content_mode"]:
            fail(code + " content mode drift")

        own_fields = [x for x in fields if x["form_code"] == code]
        numeric = join([
            code + "." + x["field_code"] for x in own_fields
            if x["field_type_r6_candidate"] in {"MONEY", "DECIMAL", "INTEGER"}
        ])
        reference = join([
            x["field_code"] + ":" + x["field_type_r6_candidate"]
            for x in own_fields
            if x["field_type_r6_candidate"] not in
               {"MONEY", "DECIMAL", "INTEGER"}
        ])
        if row["numericSourceMetrics"] != numeric:
            fail(code + " numeric field contract mismatch")
        if row["referenceFields"] != reference:
            fail(code + " reference field contract mismatch")
        if numeric:
            counts["with_numeric"] += 1
        else:
            counts["reference_only"] += 1

        if (row["candidateOperationFamiliesNotApproved"] !=
                f["candidate_operations_not_approved"]):
            fail(code + " unsupported operation-family claim")

        own_formulas = [
            x for x in metrics
            if x["formCode"] == code and x["kind"] == "CALCULATED_CANDIDATE"
        ]
        claims = ";".join(
            x["metricCode"] + "=" + x["operation"] + "(" +
            x["sourceMetrics"] + ")@" + x["formulaVersion"]
            for x in own_formulas
        )
        if row["formulaCandidatesUnapproved"] != claims:
            fail(code + " formula candidate provenance mismatch")
        counts["candidate_formulas"] += len(own_formulas)

        unbound = [
            x["metricCode"] for x in metrics if x["formCode"] == code
            and x["kind"] == "DERIVED_UNBOUND"
        ]
        if row["unboundDerivedMetrics"] != ";".join(unbound):
            fail(code + " unbound derived metric mismatch")
        counts["unbound"] += len(unbound)

        own_models = [
            x for x in models if code in x["inputFormCodes"].split(";")
        ]
        if row["consumerModelsDesignOnly"] != join(
                [x["modelCode"] for x in own_models]):
            fail(code + " analytic model linkage drift")
        if row["consumerOutputsDesignOnly"] != join(
                [x["outputMetricCode"] for x in own_models]):
            fail(code + " analytic output linkage drift")

        hold = {
            "mandatoryOutputDecisionState": "FINANCIAL_METHOD_REVIEW_REQUIRED",
            "timeCurrencyDimensionPolicyState": "UNREVIEWED",
            "independentControlExampleState": "NOT_APPROVED",
            "financialApprovalState": "NOT_S2_APPROVED",
        }
        for field, value in hold.items():
            if row[field] != value:
                fail(code + " forbidden financial approval: " + field)

    if seen != set(expected):
        fail("some form codes are missing from authoring backlog")
    if (counts["with_numeric"] != 145 or counts["reference_only"] != 9
            or counts["candidate_formulas"] != 10 or counts["unbound"] != 1):
        fail("form/field/candidate baseline drift: " + str(counts))
    print(
        "CALC4D_154_METHODOLOGY_BACKLOG PASS:",
        "forms=154 numeric=145 reference=9",
        "candidateFormulas=10 unboundDerived=1",
        "financiallyApproved=0",
    )


if __name__ == "__main__":
    verify()
