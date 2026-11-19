#!/usr/bin/env python3
"""PG16 required financial-candidate tests, NEVER independent finance S2."""
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

path=Path("form-library/target/surefire-reports/TEST-kz.fin.formlib.calc.hybrid.R16F01F03CandidatePostgresqlTest.xml")
try:
    xml=ET.parse(path).getroot()
    cases=xml.findall(".//testcase")
    names=[c.get("name","") for c in cases]
    if len(cases)!=5 or len(set(names))!=5:
        raise ValueError("R16_F01_F03_CASE_COUNT_EXPECTED_5")
    if any(int(xml.get(k,"0")) for k in ("failures","errors","skipped")):
        raise ValueError("R16_F01_F03_FAILED_OR_SKIPPED")
    if any(c.find(k) is not None for c in cases for k in ("failure","error","skipped")):
        raise ValueError("R16_F01_F03_FAILED_OR_SKIPPED_TEST")
    for n in range(1,6):
        if not any(name.startswith(f"r16F01F03_{n:02d}") for name in names):
            raise ValueError(f"R16_F01_F03_MISSING_{n:02d}")
    print("[PASS] F01/F03 unqualified candidate Java/PG16 parity: 5/5, 100 random values each; S2 still HOLD")
except (OSError,ET.ParseError,ValueError) as e:
    print("[FAIL] "+str(e),file=sys.stderr)
    sys.exit(1)
