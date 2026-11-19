#!/usr/bin/env python3
"""Fail-closed full CALC-R0.2 UAT PostgreSQL test execution gate."""
import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument("--out-dir",type=Path,default=Path("target/calc-r02"))
args=parser.parse_args()
source=Path("form-library/target/surefire-reports/TEST-kz.fin.formlib.calc.worker.CalcR02PostgresqlTest.xml")
try:
    suite=ET.parse(source).getroot()
    cases=suite.findall(".//testcase")
    names=[c.attrib.get("name","") for c in cases]
    if len(cases)!=21:
        raise ValueError(f"CALC_R02_CASE_COUNT expected=21 got={len(cases)}")
    if len(set(names))!=21:
        raise ValueError("CALC_R02_CASE_DUPLICATE")
    if any(int(suite.attrib.get(p,"0"))!=0 for p in ("failures","errors","skipped")):
        raise ValueError("CALC_R02_TESTS_FAILED_OR_SKIPPED")
    if any(c.find(tag) is not None for c in cases for tag in ("failure","error","skipped")):
        raise ValueError("CALC_R02_TEST_CASE_FAILED_OR_SKIPPED")
    for prefix in [f"r02_{n:02d}" for n in range(1,20)]:
        if not any(name.startswith(prefix) for name in names):
            raise ValueError("CALC_R02_REQUIRED_CASE_MISSING_"+prefix)
    args.out_dir.mkdir(parents=True,exist_ok=True)
    report={"contract":"CALC-R0.2-PG16-UAT-E2E-V1",
            "qualification":"TEST_ONLY_NOT_FINANCIAL_S2",
            "cases":sorted(names),"expected":21,"passed":21,
            "failed":0,"skipped":0}
    (args.out_dir/"calc-r02-coverage.json").write_text(
        json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print("[PASS] CALC-R0.2 PostgreSQL E2E: 21/21, no errors/skipped")
except (OSError,ValueError,ET.ParseError) as ex:
    print("[FAIL] "+str(ex),file=sys.stderr)
    sys.exit(1)
