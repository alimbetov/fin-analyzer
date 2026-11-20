#!/usr/bin/env python3
"""Evidence-only method-operation gap report across all 154 FIN data forms.

Operator names in the R0 catalog are DESIGN PROPOSALS, not approved formulas.
The report must never conflate source fields, proposals, approved calculations.
Usage:
    python3 scripts/generate-fin-calc154-methodology-operation-gaps.py
    python3 scripts/generate-fin-calc154-methodology-operation-gaps.py --check
"""
from __future__ import annotations

import csv
import io
import sys
from collections import defaultdict
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
INPUT=ROOT / "docs/FIN-CALC4D-154-METHODOLOGY-AUTHORING-BACKLOG.tsv"
METRICS=ROOT / "docs/FIN-CALC154-METRIC-COVERAGE.tsv"
OUTPUT=ROOT / "docs/FIN-CALC154-METHODOLOGY-OPERATION-GAPS.tsv"
SUPPORTED={"SUBTRACT","RATIO","RECONCILE","ROLL_FORWARD","GROUP_BY","RATIO_OF_SUMS"}
HEADER=["operatorFamily","designCandidateFormCount","registeredCandidateFormCount",
        "registeredUnapprovedFormulaCount","designFormCodes","formulaFormCodes",
        "technicalCapability","financialReviewState","recommendedNextAction"]


def read(path:Path):
    with path.open(encoding="utf-8",newline="") as fh:
        return list(csv.DictReader(fh,delimiter="\t"))


def build()->str:
    backlog=read(INPUT)
    metrics=read(METRICS)
    if len(backlog)!=154 or len(set(x["formCode"] for x in backlog))!=154:
        raise SystemExit("CALC154_GAPS_BAD_BACKLOG_FORM_COUNT")
    design=defaultdict(set)
    actual=defaultdict(list)
    for item in backlog:
        for op in item["candidateOperationFamiliesNotApproved"].split(";"):
            if op:
                design[op].add(item["formCode"])
    candidates=[r for r in metrics if r["kind"]=="CALCULATED_CANDIDATE"]
    if len(candidates)!=10:
        raise SystemExit("CALC154_GAPS_REAL_CANDIDATE_COUNT_DRIFT")
    for item in candidates:
        actual[item["operation"]].append(item["formCode"])
    operators=sorted(set(design)|set(actual))
    output=io.StringIO()
    writer=csv.writer(output,delimiter="\t",lineterminator="\n")
    writer.writerow(HEADER)
    for op in operators:
        forms=sorted(design[op])
        authored=sorted(set(actual[op]))
        capability=("SUPPORTED_IN_SYNTHETIC_UAT_ENGINE" if op in SUPPORTED
          else "NOT_UNIFIED_AS_TYPED_UAT_OPERATOR")
        action=("AUTHOR_REQUIRED_OUTPUTS_PERIOD_CURRENCY_DIMENSIONS_AND_FINANCIAL_CONTROLS"
          if op in SUPPORTED
          else "AUTHOR_FINANCIAL_OPERATION_SEMANTICS_AND_CONTROL_CASES_BEFORE_EXTENDING_ENGINE")
        writer.writerow([
            op,str(len(forms)),str(len(authored)),str(len(actual[op])),
            ";".join(forms),";".join(authored),capability,
            "DESIGN_ONLY_OR_CANDIDATE_UNQUALIFIED_NOT_S2_APPROVED",action
        ])
    return output.getvalue()


def main()->None:
    computed=build()
    if len(sys.argv)==2 and sys.argv[1]=="--check":
        if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8")!=computed:
            raise SystemExit("CALC154_OPERATION_GAPS_STALE")
        print("CALC154_OPERATION_GAPS PASS: 154 forms, 10 candidate formulas")
    elif len(sys.argv)==1:
        OUTPUT.write_text(computed,encoding="utf-8")
        print("CALC154_OPERATION_GAPS CREATED")
    else:
        raise SystemExit("Usage: script [--check]")


if __name__=="__main__":
    main()
