#!/usr/bin/env python3
"""CALC-3B review manifests from actual form/field/formula/model contracts.

The output is an editorial qualification backlog, not an approval, a financial
formula inference, or a runnable CalcPlan. Source/form version SHA bindings
are persisted by PostgreSQL post-bootstrap. Python --check detects drift.
"""
import argparse
import csv
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORMS = ROOT / "docs/FIN-CALC-R0-COVERAGE-166-BASELINE.tsv"
FIELDS = ROOT / "docs/FIN-CALC-R0-FIELD-COVERAGE-794-BASELINE.tsv"
FORMULAS = ROOT / "form-library/src/main/resources/catalog/calc154-formula-contracts-v1.tsv"
MODELS = ROOT / "form-library/src/main/resources/catalog/analytics-model-catalog.tsv"
BLOCKERS = ("MANDATORY_OUTPUT_SET_NOT_AUTHORED;FIELD_REQUIREDNESS_UNREVIEWED;"
            "PERIOD_AND_AGGREGATION_POLICY_UNREVIEWED;FINANCE_S2")

def parse(path):
    with path.open(encoding="utf-8", newline="") as fp:
        return list(csv.DictReader(fp, delimiter="\t"))

def require(value, message):
    if not value:
        raise SystemExit("FAIL CALC3B: " + message)

def data():
    forms = parse(FORMS)
    fields = parse(FIELDS)
    formulas = parse(FORMULAS)
    models = parse(MODELS)
    require(len(forms)==166 and len(fields)==794 and len(formulas)==10 and len(models)==42,
            "upstream contract drift")
    by_form = {f["form_code"]: f for f in forms}
    require(len(by_form)==166,"duplicate forms")
    numeric = sorted((f for f in forms if f["bootstrap_content_mode"]!="EVIDENCE"),
                     key=lambda f:f["form_code"])
    require(len(numeric)==154 and
            sum(f["bootstrap_content_mode"]=="ROWSET" for f in numeric)==151,
            "154 numeric-capable forms expected")
    field_by_id = {(x["form_code"],x["field_code"]):x for x in fields}
    require(len(field_by_id)==794,"duplicate form/field")
    formula_by_output = {x["output_metric_code"]:x for x in formulas}
    require(len(formula_by_output)==10,"duplicate formula output")
    consumer_map = {f["form_code"]: [] for f in numeric}
    for m in models:
        for form_code in m["inputFormCodes"].split(";"):
            require(form_code in by_form, "model unknown input: "+form_code)
            if form_code in consumer_map:
                consumer_map[form_code].append(m["modelCode"])

    required_rows=[]
    for f in numeric:
        code=f["form_code"]
        candidates=[x["output_metric_code"] for x in formulas if x["form_code"]==code]
        physical=sum(x["form_code"]==code and x["field_code"]!="netProfit"
                     for x in fields)
        # F02.netProfit is the only nonphysical field, not a NULL-valued input.
        required_rows.append([
            code,f["bootstrap_content_mode"],str(physical),
            "REVIEW_REQUIRED","UNKNOWN","NOT_DETERMINED",
            str(len(candidates)),";".join(candidates) or "NONE",
            ";".join(sorted(consumer_map[code])) or "NONE",BLOCKERS,
            "NOT_S2_APPROVED"
        ])
    require(len(required_rows)==154,"one review ticket per numeric form")

    formula_input_rows=[]
    for formula in formulas:
        code=formula["form_code"]
        raw = "\t".join(formula[key] for key in [
            "form_code","output_metric_code","source_metrics","operation",
            "formula_version","status","blocker","period_semantics",
            "dimension_policy","currency_policy","unit_policy",
            "rounding_policy","missing_policy","zero_policy","source_evidence"
        ])
        sha=hashlib.sha256(raw.encode("utf-8")).hexdigest()
        for i,metric in enumerate(formula["source_metrics"].split(";")):
            require(metric.startswith(code+"."),"cross-form typed input not authored: "+metric)
            if metric in formula_by_output:
                kind="FORMULA_CANDIDATE"
                field_type="DERIVED_UNQUALIFIED"
            else:
                kind="PHYSICAL_NUMERIC_FIELD"
                key=(code,metric[len(code)+1:])
                require(key in field_by_id,"missing input field: "+metric)
                field_type=field_by_id[key]["field_type_r6_candidate"]
                require(field_type in {"MONEY","DECIMAL","INTEGER"},
                        "non-numeric input: "+metric)
                require(metric!="F02.netProfit","unbound derived candidate used as input")
            formula_input_rows.append([
                code,formula["output_metric_code"],formula["formula_version"],
                str(i),metric,kind,field_type,formula["period_semantics"],
                "REVIEW_REQUIRED",sha,"LIVE_FORM_VERSION_PIN_REQUIRED",
                "NOT_S2_APPROVED"
            ])
    require(len(formula_input_rows)==21 and
            sum(x[5]=="FORMULA_CANDIDATE" for x in formula_input_rows)==2,
            "21 typed inputs, 2 derived expected")
    formula_input_rows.sort(key=lambda x:(x[0],x[1],int(x[3])))

    model_input_rows=[]
    for m in models:
        for i,form_code in enumerate(m["inputFormCodes"].split(";")):
            mode=by_form[form_code]["bootstrap_content_mode"]
            model_input_rows.append([
                m["modelCode"],str(i),form_code,mode,
                str(mode!="EVIDENCE").lower(),m["outputMetricCodes"],
                m["qualificationStatus"],"MODEL_INPUT_CANDIDATE",
                "NOT_AUTHORED","REVIEW_REQUIRED","NOT_S2_APPROVED",
                "LIVE_FORM_VERSION_PIN_REQUIRED"
            ])
    require(len(model_input_rows)==97,"97 model-input dependencies expected")
    require(sum(x[3]=="EVIDENCE" for x in model_input_rows)==1,
            "exactly one evidence dependency expected")
    require(any(x[0]=="CST-07" and x[2]=="D05" and x[4]=="false"
                for x in model_input_rows),"document dependency lost")
    model_input_rows.sort(key=lambda x:(x[0],int(x[1])))
    return [
        (ROOT/"docs/FIN-CALC3B-REQUIREDNESS-REVIEW.tsv",[
            "formCode","contentMode","physicalSourceFields",
            "mandatoryOutputState","mandatoryOutputCount","mandatoryOutputMetricCodes",
            "knownCandidateFormulaCount","knownCandidateFormulaCodes",
            "modelConsumers","blockerCodes","financialApprovalState"
        ],required_rows),
        (ROOT/"docs/FIN-CALC3B-FORMULA-TYPED-INPUTS.tsv",[
            "formCode","outputMetricCode","formulaVersion","inputOrdinal",
            "sourceMetricCode","sourceKind","fieldTypeCandidate","periodSemanticsCandidate",
            "inputRequirednessState","formulaDefinitionSha256","formVersionBinding",
            "financialApprovalState"
        ],formula_input_rows),
        (ROOT/"docs/FIN-CALC3B-MODEL-FORM-DEPENDENCIES.tsv",[
            "modelCode","sourceOrdinal","sourceFormCode","sourceMode",
            "numericEligible","proposedModelOutputs","catalogState",
            "dependencyKind","formulaState","requirednessState",
            "financialApprovalState","formVersionBinding"
        ],model_input_rows),
    ]

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--check",action="store_true")
    args=parser.parse_args()
    for path,header,rows in data():
        expected="\t".join(header)+"\n"+"".join("\t".join(row)+"\n" for row in rows)
        if args.check:
            require(path.exists() and path.read_text(encoding="utf-8")==expected,
                    "missing or stale file: "+path.name)
        else:
            path.write_text(expected,encoding="utf-8")
        print(f"PASS {path.name}: {len(rows)} rows")
    print("CALC3B review backlog only: mandatory outputs and financial S2 NOT APPROVED")

if __name__=="__main__":
    main()
