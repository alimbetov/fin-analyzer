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
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORM_PATH = ROOT / "docs/FIN-CALC-R0-COVERAGE-166-BASELINE.tsv"
FIELD_PATH = ROOT / "docs/FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv"
MODEL_PATH = ROOT / "form-library/src/main/resources/catalog/analytics-model-catalog.tsv"
CONTRACT_PATH = ROOT / "form-library/src/main/resources/catalog/calc154-formula-contracts-v1.tsv"

# Formula proposals live ONLY in calc154-formula-contracts-v1.tsv. Do not
# author new source-to-output dependencies inside this generator.
CONTRACT_COLUMNS = [
    "form_code", "output_metric_code", "source_metrics", "operation",
    "formula_version", "status", "blocker", "period_semantics",
    "dimension_policy", "currency_policy", "unit_policy",
    "rounding_policy", "missing_policy", "zero_policy", "source_evidence",
]
ALLOWED_OPERATIONS = {
    "RECONCILE", "SUBTRACT", "RATIO", "ROLL_FORWARD", "GROUP_BY", "RATIO_OF_SUMS"
}


def contracts():
    with CONTRACT_PATH.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream, delimiter="\t")
        require(reader.fieldnames == CONTRACT_COLUMNS, "formula contract columns changed")
        records = list(reader)
    require(len(records) == 10, "formula registry count drift: expected ten existing candidates")
    for record in records:
        require(all(record.get(key) is not None and record[key] != ""
                    for key in CONTRACT_COLUMNS), "empty formula contract member")
        require(record["operation"] in ALLOWED_OPERATIONS, "unrecognized operation")
        require(record["status"] in {"CANDIDATE_UNQUALIFIED", "UAT_TEST_ONLY"},
                "invalid finance status")
        require(record["status"] != "UAT_TEST_ONLY" or
                (record["form_code"] == "F02" and
                 record["formula_version"] == "F02_TEST_ONLY_V1"),
                "unapproved UAT reference")
        require(record["blocker"].find("FINANCE_S2") >= 0,
                "contract cannot erase the financial S2 blocker")
        require(record["period_semantics"] in {
            "AS_OF_CANDIDATE", "INTERVAL_CANDIDATE", "INTERVAL_REQUIRED"},
            "unexpected period policy")
        require(record["dimension_policy"] == "UNREVIEWED"
                and record["currency_policy"] == "UNREVIEWED"
                and record["rounding_policy"] == "UNREVIEWED",
                "unreviewed formula policy falsely approved")
        require(record["missing_policy"] == "REQUIRE_PRESENT_CANDIDATE"
                and record["zero_policy"] in {"INVALID_CANDIDATE", "NOT_APPLICABLE"},
                "missing/zero policy cannot be inferred")
        if record["operation"] in {"RATIO", "RATIO_OF_SUMS"}:
            require(record["zero_policy"] == "INVALID_CANDIDATE", "ratio zero policy required")
        else:
            require(record["zero_policy"] == "NOT_APPLICABLE", "unexpected zero policy")
    return records


def formula_digest(record):
    canonical = "\t".join(record[key] for key in CONTRACT_COLUMNS)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


METRIC_HEADERS = [
    "formCode", "metricCode", "kind", "sourceMetrics", "operation",
    "formulaVersion", "status", "blocker", "periodSemantics",
    "unitPolicy", "sourceEvidence", "contractSha256",
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
    specifications = contracts()
    require(len(specifications) == 10, "unexpected candidate formula count")

    formula_names = {r["output_metric_code"] for r in specifications}
    require(len(formula_names) == 10, "duplicate formula identifiers")
    for spec in specifications:
        form, metric, sources = (spec["form_code"], spec["output_metric_code"], spec["source_metrics"])
        require(form in codes and metric.startswith(form + "."),
                "unknown formula form: " + metric)
        for source in sources.split(";"):
            sf, _, field = source.partition(".")
            require(source != "F02.netProfit" and
                    ((sf, field) in pairs or source in formula_names),
                    "unknown or unbound formula dependency: " + source)
        require(metric not in sources.split(";"), "self-reference: " + metric)

    # Fail closed on cycles; no topological sort over unreviewed identifiers.
    dependencies = {s["output_metric_code"]:
                    [d for d in s["source_metrics"].split(";") if d in formula_names]
                    for s in specifications}
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
            "FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv", "",
        ])
    for spec in specifications:
        rows.append([
            spec["form_code"], spec["output_metric_code"], "CALCULATED_CANDIDATE",
            spec["source_metrics"], spec["operation"], spec["formula_version"],
            spec["status"], spec["blocker"], spec["period_semantics"],
            spec["unit_policy"], spec["source_evidence"], formula_digest(spec),
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
