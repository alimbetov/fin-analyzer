#!/usr/bin/env python3
"""CI release guard: actual PG16 CALC-R0.1 TX-B cases must all run and pass."""
import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument("--out-dir",type=Path,default=Path("target/calc-r01"))
args=p.parse_args()
source=Path("form-library/target/surefire-reports/TEST-kz.fin.formlib.calc.fact.CalcR01PostgresqlTest.xml")
try:
    root=ET.parse(source).getroot()
    cases=root.findall(".//testcase")
    names=[case.get("name","") for case in cases]
    if len(cases)!=11:
        raise ValueError(f"CALC_R01_CASE_COUNT_MISMATCH: expected=11 actual={len(cases)}")
    if len(set(names))!=len(names):
        raise ValueError("CALC_R01_DUPLICATED_CASES")
    if any(int(root.get(k,"0"))!=0 for k in ("failures","errors","skipped")):
        raise ValueError("CALC_R01_SUITE_FAILED_OR_SKIPPED")
    if any(c.find("failure") is not None or c.find("error") is not None or
           c.find("skipped") is not None for c in cases):
        raise ValueError("CALC_R01_TEST_FAILED_OR_SKIPPED")
    for prefix in ("r01_01","r01_02","r01_03","r01_04","r01_05",
                   "r01_06","r01_07","r01_08","r01_09"):
        if not any(n.startswith(prefix) for n in names):
            raise ValueError(f"CALC_R01_MISSING_CASE_{prefix}")
    args.out_dir.mkdir(parents=True,exist_ok=True)
    manifest={"contract":"CALC-R0.1-PG16-TX-B-V1",
              "qualification":"UAT_TEST_ONLY_NOT_S2",
              "planned":11,"passed":11,"failed":0,"skipped":0,
              "cases":sorted(names)}
    (args.out_dir/"calc-r01-coverage.json").write_text(
        json.dumps(manifest,indent=2)+"\n",encoding="utf-8")
    print("[PASS] CALC-R0.1 PostgreSQL TX-B: 11/11 PASS, no errors/skipped")
except (OSError,ValueError,ET.ParseError) as exc:
    print(f"[FAIL] {exc}",file=sys.stderr)
    sys.exit(1)
