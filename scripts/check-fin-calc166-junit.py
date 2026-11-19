#!/usr/bin/env python3
"""PG16 CALC-166.A fail-closed gate: each current form has an actual dynamic test.

Neither this gate nor the 877 structural mappings constitute financial S2.
"""
import argparse
import csv
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument("--out-dir",type=Path,default=Path("form-library/target/fin-calc166"))
args=parser.parse_args()
xml=Path("form-library/target/surefire-reports/TEST-kz.fin.formlib.calc.readiness.Calc166ReadinessPostgresqlTest.xml")

def read_tsv(file):
    with file.open(encoding="utf-8",newline="") as fp:
        return list(csv.DictReader(fp,delimiter="\t"))

try:
    root=ET.parse(xml).getroot()
    cases=root.findall(".//testcase")
    names=[x.get("name","") for x in cases]
    if len(cases)!=168:
        raise ValueError(f"CALC_166_INDIVIDUAL_JUNIT_COUNT: expected 168 got {len(cases)}")
    if int(root.get("failures","0")) or int(root.get("errors","0")) or int(root.get("skipped","0")):
        raise ValueError("CALC_166_FAILED_OR_SKIPPED")
    if any(t.find(tag) is not None for t in cases for tag in ("failure","error","skipped")):
        raise ValueError("CALC_166_FAILED_OR_SKIPPED_CASE")
    forms=read_tsv(args.out_dir/"166-form-calculation-readiness.tsv")
    fields=read_tsv(args.out_dir/"877-field-calculation-contracts.tsv")
    codes=[f["form_code"] for f in forms]
    if len(codes)!=166 or len(set(codes))!=166:
        raise ValueError(f"CALC_166_FORM_REGISTRY_INCOMPLETE: {len(codes)}")
    if len(fields)!=877:
        raise ValueError(f"CALC_166_FIELD_COUNT_DRIFT: {len(fields)}")
    if sum(int(f["physical_fields"]) for f in forms)!=len(fields):
        raise ValueError("CALC_166_FIELD_FORM_COUNTS_INCONSISTENT")
    if any(f["financial_qualified"]!="false" for f in forms):
        raise ValueError("CALC_166_UNAUTHORIZED_FINANCIAL_QUALIFICATION")
    pilot=[f for f in forms if f["readiness"]=="UAT_F02_TEST_ONLY"]
    if len(pilot)!=1 or pilot[0]["form_code"]!="F02":
        raise ValueError("CALC_166_TEST_ONLY_PILOT_INVALID")
    if sum(f["content_mode"]=="ROWSET" for f in forms)!=151:
        raise ValueError("CALC_166_ROWSET_MISMATCH")
    if sum(f["content_mode"]=="SINGLE_RECORD" for f in forms)!=3:
        raise ValueError("CALC_166_SINGLE_RECORD_MISMATCH")
    if sum(f["content_mode"]=="EVIDENCE" for f in forms)!=12:
        raise ValueError("CALC_166_EVIDENCE_MISMATCH")
    if any(f["missing"]!="0" or f["drift"]!="0" for f in forms):
        raise ValueError("CALC_166_PHYSICAL_FIELD_DRIFT")
    if any(f["binding_state"]!="CANDIDATE_UNQUALIFIED" for f in fields):
        raise ValueError("CALC_166_MISLEADING_FIELD_APPROVAL")
    if any(not any("R16A_FORM_"+code in n for n in names) for code in codes):
        raise ValueError("CALC_166_ONE_OR_MORE_FORM_TESTS_NOT_RUN")
    manifest={
        "contract":"FIN-CALC-166.A-PG16-LIVE-CURRENT-VERSIONS-V1",
        "status":"PHYSICAL_CONTRACT_CANDIDATES_NOT_FINANCIAL_S2",
        "forms":166,"physicalFields":877,"testOnlyExecutable":1,
        "financiallyQualified":0,"separatelyExecutedFormCases":166,
        "totalJunitCases":168,"failed":0,"skipped":0,
        "formCodes":sorted(codes)
    }
    (args.out_dir/"coverage-check.json").write_text(
        json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print("[PASS] CALC-166.A PG16 166/166 live-form tests, 877/877 field contracts;"
          " only F02 UAT pilot; 0 production S2")
except (OSError,ValueError,ET.ParseError,KeyError) as ex:
    print("[FAIL] "+str(ex),file=sys.stderr)
    sys.exit(1)
