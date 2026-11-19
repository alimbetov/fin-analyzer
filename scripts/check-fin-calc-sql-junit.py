#!/usr/bin/env python3
"""Fail closed if any mandatory native PostgreSQL F02 parity vector was skipped."""
import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

parser=argparse.ArgumentParser()
parser.add_argument("--out-dir",type=Path,default=Path("target/calc-sql-f02"))
args=parser.parse_args()
source=Path("form-library/target/surefire-reports/TEST-kz.fin.formlib.calc.kernel.SqlF02ParityPostgresqlTest.xml")
try:
    suite=ET.parse(source).getroot()
    cases=suite.findall(".//testcase")
    names=[c.attrib.get("name","") for c in cases]
    if len(cases)!=20 or len(set(names))!=20:
        raise ValueError(f"SQL_F02_CASE_COUNT_OR_DUPLICATE expected=20 got={len(cases)}")
    if any(int(suite.attrib.get(key,"0"))!=0 for key in ("failures","errors","skipped")):
        raise ValueError("SQL_F02_TESTS_FAILED_OR_SKIPPED")
    if any(c.find(tag) is not None for c in cases for tag in ("failure","error","skipped")):
        raise ValueError("SQL_F02_CASE_FAILED_OR_SKIPPED")
    for prefix in ["sql01ReferenceVectorsMatchAllValuesStatesAndSha",
                   "sql02DeterministicRandomizedDecimalParity",
                   "sql03NativeRoundHalfEvenDiffersFromPostgresDefaultRound",
                   "sql04RejectMalformedInputWithoutSilentlyApproving",
                   "sql05PureFunctionsAreImmutableAndParallelSafe"]:
        expected=16 if prefix.startswith("sql01") else 1
        actual=sum(n.startswith(prefix) for n in names)
        if actual!=expected:
            raise ValueError(f"SQL_F02_REQUIRED_{prefix} expected={expected} got={actual}")
    args.out_dir.mkdir(parents=True,exist_ok=True)
    report={"contract":"FIN-CALC-SQL-01-PG16-JAVA-SHA-PARITY-V1",
            "mode":"TEST_ONLY_NOT_FINANCIAL_S2","vectorCases":16,
            "randomizedCases":256,"total":20,"passed":20,
            "failed":0,"skipped":0,"caseNames":sorted(names)}
    (args.out_dir/"calc-sql-f02-coverage.json").write_text(
        json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print("[PASS] CALC-SQL-01 native PostgreSQL/Java F02 parity: 20/20 + 256 random vectors")
except (OSError,ValueError,ET.ParseError) as ex:
    print("[FAIL] "+str(ex),file=sys.stderr)
    sys.exit(1)
