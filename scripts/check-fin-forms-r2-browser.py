#!/usr/bin/env python3
"""Require one independently executed and successful real-browser visit per form."""
import csv
import json
import sys
from pathlib import Path

manifest=Path("form-library/src/main/resources/catalog/fin-fact-ingestion-strategy-r0.tsv")
report=Path("frontend/test-results/fin-forms-r2-166-browser.json")
try:
    with manifest.open(encoding="utf-8",newline="") as fh:
        expected={r["form_code"]:r["content_mode"] for r in csv.DictReader(fh,delimiter="\t")}
    data=json.loads(report.read_text(encoding="utf-8"))
    rows=data["results"]
    if data["contract"]!="FIN-FORMS-QA-R2-D-V1" or len(expected)!=166 or len(rows)!=166:
        raise ValueError("R2_BROWSER_CASE_COUNT_MISMATCH")
    actual={}
    for row in rows:
        code=row["formCode"]
        if code in actual or code not in expected:
            raise ValueError("R2_BROWSER_UNKNOWN_OR_DUPLICATE_FORM:"+code)
        if row["mode"]!=expected[code] or row["status"]!="PASS" or not row["rendered"] or not row["edited"]:
            raise ValueError("R2_BROWSER_NOT_QUALIFIED:"+code+":"+str(row.get("reason","")))
        if row["fieldCount"]<1:
            raise ValueError("R2_BROWSER_EMPTY_FORM:"+code)
        actual[code]=row
    if set(actual)!=set(expected):
        raise ValueError("R2_BROWSER_MANIFEST_DRIFT")
    print("[PASS] FIN-FORMS-QA-R2.D: 166/166 real Chromium renders + editable field checks")
    print("[INFO] This is UI interaction coverage, NOT financial S2 or attachment upload.")
except (OSError,ValueError,KeyError,TypeError,json.JSONDecodeError) as e:
    print("[FAIL] FIN-FORMS-QA-R2.D: "+str(e),file=sys.stderr)
    sys.exit(1)
