#!/usr/bin/env python3
"""Release fail-closed: count independent FACT-1.B PostgreSQL JUnit cases."""
import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument("--out-dir",type=Path,default=Path("target/fin-fact1b"))
args=parser.parse_args()
file=Path("form-library/target/surefire-reports/TEST-kz.fin.formlib.calc.fact.Fact1BUatCommitterPostgresqlTest.xml")
try:
    root=ET.parse(file).getroot()
    cases=root.findall(".//testcase")
    if len(cases)!=10:
        raise ValueError(f"FACT1B_JUNIT_CASE_COUNT: expected=10 actual={len(cases)}")
    names=[x.get("name","") for x in cases]
    if len(set(names))!=len(names):
        raise ValueError("FACT1B_JUNIT_DUPLICATE_CASE")
    fail=[x for x in cases if list(x.findall("failure")) or list(x.findall("error"))
          or list(x.findall("skipped"))]
    if fail or any(int(root.get(k,"0"))!=0 for k in ("failures","errors","skipped")):
        raise ValueError("FACT1B_JUNIT_FAILURE_OR_SKIP")
    mandatory=("qf11","qf12","qf13","qf14","qf18","qf07","qf19","qf24")
    if any(not any(n.startswith(prefix) for n in names) for prefix in mandatory):
        raise ValueError("FACT1B_JUNIT_MISSING_MANDATORY_CASE")
    report={"contract":"FACT-1.B-UAT-PG16-V1","scope":"UAT_TEST_ONLY_NOT_S2",
        "expected":10,"actual":len(cases),"failed":0,"skipped":0,
        "cases":sorted(names)}
    args.out_dir.mkdir(parents=True,exist_ok=True)
    (args.out_dir/"fact1b-coverage.json").write_text(
        json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print("[PASS] FACT-1.B PostgreSQL 10/10, zero errors/skips; TEST_ONLY, not S2")
except (OSError,ValueError,ET.ParseError) as e:
    print("[FAIL] "+str(e),file=sys.stderr)
    sys.exit(1)
