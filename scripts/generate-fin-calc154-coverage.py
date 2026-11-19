#!/usr/bin/env python3
"""FIN CALC-154 CALC-1/2: deterministic audit-derived coverage, zero S2 promotion.

This script only reads source inventories, then writes/checks coverage TSVs.
It NEVER executes calculation SQL or changes fin_intake/fin_fact.
Usage:
    python3 scripts/generate-fin-calc154-coverage.py
    python3 scripts/generate-fin-calc154-coverage.py --check
"""
import argparse
import csv
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORM_PATH = ROOT / "docs/FIN-CALC-R0-COVERAGE-166-BASELINE.tsv"
FIELD_PATH = ROOT / "docs/FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv"
MODEL_PATH = ROOT / "form-library/src/main/resources/catalog/analytics-model-catalog.tsv"

# Only reviewed, pinned source-derived method proposals documented in
# migrations 030 and 034, plus existing F02_TEST_ONLY_V1 outputs.
# No formula or version is inferred from unreviewed field names.
SPECIFICATIONS = [
    ("F01", "F01.balanceResidual", "F01.assets;F01.liabilities;F01.equity", "RECONCILE",
     "F01_CANDIDATE_V1", "CANDIDATE_UNQUALIFIED",
     "FINANCE_S2;INPUT_MAPPING;AS_OF_SEMANTICS;CURRENCY"),
    ("F02", "F02.grossProfit", "F02.netRevenue;F02.cogs", "SUBTRACT",
     "F02_TEST_ONLY_V1", "UAT_TEST_ONLY", "FINANCE_S2;PRODUCTION_NOT_ADMITTED"),
    ("F02", "F02.grossMargin", "F02.grossProfit;F02.netRevenue", "RATIO",
     "F02_TEST_ONLY_V1", "UAT_TEST_ONLY", "FINANCE_S2;PRODUCTION_NOT_ADMITTED"),
    ("F02", "F02.operatingResult", "F02.grossProfit;F02.operatingExpenses", "SUBTRACT",
     "F02_TEST_ONLY_V1", "UAT_TEST_ONLY", "FINANCE_S2;PRODUCTION_NOT_ADMITTED"),
    ("F03", "F03.cashResidual", "F03.openingCash;F03.cfo;F03.cfi;F03.cff;F03.closingCash",
     "ROLL_FORWARD", "F03_CANDIDATE_V1", "CANDIDATE_UNQUALIFIED",
     "FINANCE_S2;INPUT_MAPPING;SIGNED_FLOW_SEMANTICS;FX_POLICY"),
    ("F02", "F02.cogsToRevenueCandidate", "F02.cogs;F02.netRevenue",
     "RATIO_OF_SUMS", "UNVERSIONED_CANDIDATE", "CANDIDATE_UNQUALIFIED",
     "FINANCE_S2;PERIOD_POLICY;DIMENSION_POLICY;INPUT_BINDINGS;PLAN_ADMISSION"),
    ("C07", "C07.grossSalesGroupedCandidate", "C07.grossSales",
     "GROUP_BY", "UNVERSIONED_CANDIDATE", "CANDIDATE_UNQUALIFIED",
     "FINANCE_S2;PERIOD_POLICY;DIMENSION_POLICY;INPUT_BINDINGS;PLAN_ADMISSION"),
    ("C07", "C07.returnsGroupedCandidate", "C07.returns",
     "GROUP_BY", "UNVERSIONED_CANDIDATE", "CANDIDATE_UNQUALIFIED",
     "FINANCE_S2;PERIOD_POLICY;DIMENSION_POLICY;INPUT_BINDINGS;PLAN_ADMISSION"),
    ("C07", "C07.returnsShareCandidate", "C07.returns;C07.grossSales",
     "RATIO_OF_SUMS", "UNVERSIONED_CANDIDATE", "CANDIDATE_UNQUALIFIED",
     "FINANCE_S2;PERIOD_POLICY;DIMENSION_POLICY;INPUT_BINDINGS;PLAN_ADMISSION"),
    ("C09", "C09.expenseAmountGroupedCandidate", "C09.amount",
     "GROUP_BY", "UNVERSIONED_CANDIDATE", "CANDIDATE_UNQUALIFIED",
     "FINANCE_S2;PERIOD_POLICY;DIMENSION_POLICY;INPUT_BINDINGS;PLAN_ADMISSION"),
]

METRIC_HEADERS = [
    "formCode", "metricCode", "kind", "sourceMetrics", "operation",
    "formulaVersion", "status", "blocker", "periodSemantics",
    "unitPolicy", "sourceEvidence",
]
FORM_HEADERS = [
    "formCode", "contentMode", "physicalSourceFields", "unboundDerivedCandidates",
    "calculatedCandidates", "uatOnlyOutputs", "financiallyQualifiedOutputs",
    "complete", "blockers", "proposedOperationsNotApproved", "consumerModelCodes",
]
MODEL_HEADERS = [
    "modelCode", "outputMetricCode", "inputFormCodes", "formulaVersion",
    "status", "blocker", "calcPlanId",
]


def parse(path):
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def require(condition, message):
    if not condition:
        raise SystemExit("FAIL CALC154: " + message)


def build():
    all_forms = parse(FORM_PATH)
    fields = parse(FIELD_PATH)
    models = parse(MODEL_PATH)
    require(len(all_forms) == 166, "form catalogue != 166")
    forms = sorted(
        (f for f in all_forms if f["bootstrap_content_mode"] != "EVIDENCE"),
        key=lambda f: f["form_code"],
    )
    require(len(forms) == 154, "data form catalogue != 154")
    require(sum(f["bootstrap_content_mode"] == "ROWSET" for f in forms) == 151,
            "ROWSET != 151")
    require(sum(f["bootstrap_content_mode"] == "SINGLE_RECORD" for f in forms) == 3,
            "SINGLE_RECORD != 3")
    require(len(fields) == 794 and len(models) == 42, "field/model catalogue drift")
    codes = {f["form_code"] for f in forms}
    require(len(codes) == 154, "duplicate form codes")
    pairs = {(f["form_code"], f["field_code"]) for f in fields}
    require(len(pairs) == 794, "duplicate field identities")
    require(all(f["form_code"] in codes for f in fields), "field linked to evidence/unknown form")
    require(("F02", "netProfit") in pairs, "derived exception missing")
    require(len(pairs - {("F02", "netProfit")}) == 793, "physical sources != 793")
    for form in forms:
        n = sum(f["form_code"] == form["form_code"] for f in fields)
        require(n == int(form["presentation_r6_field_count"]),
                "presentation field count drift: " + form["form_code"])
    require(len(SPECIFICATIONS) == 10, "unexpected candidate formula count")

    formula_names = {r[1] for r in SPECIFICATIONS}
    require(len(formula_names) == 10, "duplicate formula identifiers")
    for form, metric, sources, _, _, _, _ in SPECIFICATIONS:
        require(form in codes and metric.startswith(form + "."),
                "unknown formula form: " + metric)
        for source in sources.split(";"):
            sf, _, field = source.partition(".")
            require((sf, field) in pairs or source in formula_names,
                    "unknown formula dependency: " + source)
        require(metric not in sources.split(";"), "self-reference: " + metric)

    # Fail closed on cycles; no topological sort over unreviewed identifiers.
    dependencies = {s[1]: [d for d in s[2].split(";") if d in formula_names]
                    for s in SPECIFICATIONS}
    visiting, visited = set(), set()

    def visit(name):
        require(name not in visiting, "formula cycle: " + name)
        if name in visited:
            return
        visiting.add(name)
        for child in dependencies[name]:
            visit(child)
        visiting.remove(name)
        visited.add(name)

    for name in dependencies:
        visit(name)

    rows = []
    for f in fields:
        code, field = f["form_code"], f["field_code"]
        derived = code == "F02" and field == "netProfit"
        rows.append([
            code, code + "." + field,
            "DERIVED_UNBOUND" if derived else "SOURCE_FIELD",
            "F02.netRevenue;F02.cogs;F02.operatingExpenses" if derived else "",
            "UNAPPROVED_SUBTRACT" if derived else "SOURCE_INPUT", "NOT_AUTHORED",
            "BLOCKED_UNQUALIFIED" if derived else "SOURCE_UNQUALIFIED",
            "FORMULA_NOT_APPROVED;NOT_PHYSICAL_INPUT" if derived else
            "METHOD_REVIEW;PERIOD_UNREVIEWED;AGGREGATION_UNREVIEWED",
            f["time_semantics_state"], f["unit_policy_r6_candidate"],
            "FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv",
        ])
    for form, metric, sources, operation, version, status, blocker in SPECIFICATIONS:
        semantic = ("AS_OF_CANDIDATE" if operation == "RECONCILE" else
                    "INTERVAL_CANDIDATE" if operation == "ROLL_FORWARD" else
                    "INTERVAL_REQUIRED")
        evidence = ("034_candidate_metric_operation_binding" if version == "UNVERSIONED_CANDIDATE"
                    else "FIN-R16-F01-F03 / F02_TEST_ONLY")
        rows.append([
            form, metric, "CALCULATED_CANDIDATE", sources, operation, version,
            status, blocker, semantic, "FORM_CURRENCY_REVIEW_REQUIRED", evidence,
        ])
    rows.sort(key=lambda r: (r[0].casefold(), r[1].casefold(), r[2].casefold()))
    require(len(rows) == 804, "metric coverage != 804")

    summary = []
    for form in forms:
        subset = [r for r in rows if r[0] == form["form_code"]]
        source = sum(r[2] == "SOURCE_FIELD" for r in subset)
        unbound = sum(r[2] == "DERIVED_UNBOUND" for r in subset)
        calculated = [r for r in subset if r[2] == "CALCULATED_CANDIDATE"]
        uat = sum(r[6] == "UAT_TEST_ONLY" for r in calculated)
        consumers = sorted(m["modelCode"] for m in models
                           if form["form_code"] in m["inputFormCodes"].split(";"))
        blocker = ("FORMULA_CONTRACT_NOT_AUTHORED;PERIOD_POLICY_UNREVIEWED;"
                   "AGGREGATION_POLICY_UNREVIEWED;S2_NOT_APPROVED"
                   if form["formula_contract_state"] == "NOT_AUTHORED" else "S2_NOT_APPROVED")
        summary.append([
            form["form_code"], form["bootstrap_content_mode"], str(source),
            str(unbound), str(len(calculated)), str(uat), "0", "false",
            blocker, form["candidate_operations_not_approved"],
            ";".join(consumers) or "NONE_DECLARED",
        ])
    require(len(summary) == 154 and sum(int(r[4]) for r in summary) == 10,
            "summary coverage drift")

    model_rows = []
    for model in models:
        for metric in model["outputMetricCodes"].split(";"):
            model_rows.append([
                model["modelCode"], metric, model["inputFormCodes"],
                "NOT_AUTHORED", "DESIGN_ONLY",
                "MODEL_FORMULA_MISSING;FORM_DEPENDENCY_UNREVIEWED;FINANCE_S2", "NONE",
            ])
    model_rows.sort(key=lambda r: (r[0], r[1]))
    require(len(model_rows) == 46, "analytics output count != 46")

    return [
        (ROOT / "docs/FIN-CALC154-METRIC-COVERAGE.tsv", METRIC_HEADERS, rows),
        (ROOT / "docs/FIN-CALC154-FORM-STATUS.tsv", FORM_HEADERS, summary),
        (ROOT / "docs/FIN-CALC154-MODEL-OUTPUT-COVERAGE.tsv", MODEL_HEADERS, model_rows),
    ]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail on non-regenerated artifacts")
    args = parser.parse_args()
    for path, columns, rows in build():
        text = "\t".join(columns) + "\n" + "".join("\t".join(row) + "\n" for row in rows)
        if args.check:
            require(path.is_file(), "coverage artifact missing: " + str(path))
            actual = path.read_text(encoding="utf-8")
            if actual != text:
                old_lines, expected_lines = actual.splitlines(), text.splitlines()
                mismatch = next((i for i, (old, new) in
                                 enumerate(zip(old_lines, expected_lines), start=1)
                                 if old != new), min(len(old_lines), len(expected_lines)) + 1)
                old = old_lines[mismatch - 1] if mismatch <= len(old_lines) else "<EOF>"
                new = expected_lines[mismatch - 1] if mismatch <= len(expected_lines) else "<EOF>"
                raise SystemExit("FAIL CALC154: stale " + str(path) +
                                 " line " + str(mismatch) +
                                 " actual=" + repr(old[:500]) +
                                 " expected=" + repr(new[:500]))
        else:
            path.write_text(text, encoding="utf-8")
        print(f"PASS {path.name}: {len(rows)} rows")
    print("CALC154 inventory only: financial qualification and E2E remain BLOCKED")


if __name__ == "__main__":
    main()
