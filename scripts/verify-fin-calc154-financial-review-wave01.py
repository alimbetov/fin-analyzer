#!/usr/bin/env python3
"""Validate FIN CALC-154 financial review proposals C01-C10.

Verifies that proposed sources and existing formula labels originate from the
REAL form library; does not claim any financial formula or signed S2 is approved.
"""
from __future__ import annotations

import csv
import re
from collections import Counter
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
PROPOSALS=ROOT/"docs/FIN-CALC154-FINANCIAL-REVIEW-WAVE-01-C01-C10.tsv"
FORMS=ROOT/"docs/FIN-CALC-R0-COVERAGE-166-BASELINE.tsv"
FIELDS=ROOT/"form-library/src/main/resources/catalog/form-field-presentation-review-r6.tsv"
FORMULAS=ROOT/"form-library/src/main/resources/catalog/calc154-formula-contracts-v1.tsv"
TITLES=ROOT/"form-library/src/main/resources/catalog/editorial-titles-finance-r15.tsv"

def read(path:Path)->list[dict[str,str]]:
    with path.open(encoding="utf-8",newline="") as fh:
        return list(csv.DictReader(fh,delimiter="\t"))

def fail(message:str)->None:
    raise SystemExit("FIN_CALC154_REVIEW_WAVE01_INVALID: "+message)

def main()->None:
    proposals=read(PROPOSALS)
    form_rows={r["form_code"]:r for r in read(FORMS)}
    titles={r["formCode"]:r["titleRu"] for r in read(TITLES)}
    fields={}
    for f in read(FIELDS):
        fields.setdefault(f["formCode"],{})[f["fieldCode"]]=f
    formulas={
        (r["form_code"],r["output_metric_code"]):r
        for r in read(FORMULAS)
    }
    expected_forms={f"C{i:02d}" for i in range(1,11)}
    if len(proposals)!=19 or set(r["formCode"] for r in proposals)!=expected_forms:
        fail("expected exactly 19 proposals for distinct C01-C10 forms")
    statuses=Counter()
    outputs=set()
    for p in proposals:
        form=p["formCode"]
        if form not in form_rows or form_rows[form]["bootstrap_content_mode"]!="ROWSET":
            fail(form+" is not an actual ROWSET form")
        if p["formTitleRu"]!=titles.get(form):
            fail(form+" title not grounded in published RU editorial catalog")
        out=p["outputMetric"]
        if not out.startswith(form+".") or (form,out) in outputs:
            fail(form+" output prefix/uniqueness drift: "+out)
        outputs.add((form,out))
        if not re.fullmatch(r"[A-Za-z0-9_.-]{3,160}",out):
            fail(form+" unsupported output metric characters")
        requiredness=p["requirednessProposal"]
        if requiredness not in {"REQUIRED","OPTIONAL"}:
            fail(form+" requiredness must be a proposal, not an approval")
        statuses[requiredness]+=1
        kind=p["kindProposal"]
        if kind not in {
            "AGGREGATED_SOURCE","DERIVED","BLOCKED_DERIVED",
            "EXISTING_FORMULA_CANDIDATE"
        }:
            fail(form+" unknown output kind")
        if not p["formulaOrOperationProposal"] or not p["decisionBlockers"]:
            fail(form+" no grounded operation / unresolved financial questions")
        if p["financialDecisionStatus"]!="AWAITING_EXPLICIT_DOMAIN_REVIEW":
            fail(form+" attempts to assign unverified financial review state")
        if p["provenance"]!="FIN_FORM_R0_CATALOG_FIELDS_ONLY":
            fail(form+" unsupported proposal evidence")
        if not p["syntheticIllustrationNotApproved"]:
            fail(form+" no synthetic illustration or explicit unavailability")
        if p["timeSemanticsProposal"] not in {
            "AS_OF","INTERVAL","INTERVAL_AND_AS_OF_MIXED_REQUIRES_ALIGNMENT"
        }:
            fail(form+" period semantics unspecified")
        if not p["dimensionGrainProposal"] or not p["currencyRuleProposal"]:
            fail(form+" missing dimensional or currency proposal")
        actual_fields=fields[form]
        source_names=p["catalogSourceMetrics"].split(";")
        if not source_names or len(set(source_names))!=len(source_names):
            fail(form+" source list missing/duplicate")
        for source in source_names:
            if not source.startswith(form+"."):
                fail(form+" unintended cross-form source or malformed input: "+source)
            field=source[len(form)+1:]
            if field not in actual_fields:
                fail(form+" invented input field: "+field)
        if kind=="EXISTING_FORMULA_CANDIDATE":
            formula=formulas.get((form,out))
            if not formula:
                fail(form+" existing formula proposal has no real revision: "+out)
            declared=set(formula["source_metrics"].split(";"))
            numeric={s for s in source_names}
            if declared!=numeric:
                fail(form+" candidate inputs are not pinned to exact catalog formula")
            actual_operation=formula["operation"]
            if not p["formulaOrOperationProposal"].startswith(actual_operation+"("):
                fail(form+" existing candidate operation drift")
    if statuses!=Counter({"REQUIRED":11,"OPTIONAL":8}):
        fail("required/optional proposal balance drift: "+str(statuses))
    print("FIN CALC154 REVIEW WAVE01 PASS: 10 C01-C10 forms, 19 proposals,"
          " REQUIRED=11 OPTIONAL=8, 3 existing exact candidate formulas,"
          " financial approvals=0")

if __name__=="__main__":
    main()
