#!/usr/bin/env python3
"""FIN CALC-154 WAVE02: verify editorial worklists against exact repository baseline.
No database writes, accounting approval, or source-standards certification.
"""
from __future__ import annotations

import csv
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "docs/FIN-CALC154-FORM-STATUS.tsv"
REG = ROOT / "docs/FIN-CALC154-WAVE02-METHODOLOGY-DECISION-REGISTRY.tsv"
WORK = ROOT / "docs/FIN-CALC154-WAVE02-ALL154-EVIDENCE-WORKLIST.tsv"
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def load(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def fail(message: str) -> None:
    raise SystemExit("FIN_CALC154_WAVE02_INVALID: " + message)


def main() -> None:
    base, registry, work = map(load, (BASE, REG, WORK))
    if len(base) != 154 or len(work) != 154:
        fail(f"expected 154 form rows, got {len(base)} and {len(work)}")
    if len(registry) != 23:
        fail(f"expected 23 decisions, got {len(registry)}")
    baseline_codes = [r["formCode"] for r in base]
    result_codes = [r["formCode"] for r in work]
    if len(set(baseline_codes)) != 154 or result_codes != baseline_codes:
        fail("worklist must contain exactly the versioned baseline form order")
    index = {r["formCode"]: r for r in base}
    decision_ids = [r["decisionId"] for r in registry]
    if len(set(decision_ids)) != len(decision_ids):
        fail("duplicate methodology decision")
    for r in registry:
        if r["status"] != "EVIDENCE_PENDING" or r["financialS2"] != "NOT_S2_APPROVED":
            fail("no methodology may be automatically approved from this user memo")
        if not HEX64.fullmatch(r["sourceSha256"]):
            fail(f"invalid source digest for {r['decisionId']}")
        if r["sourceSection"] not in {str(n) for n in range(1, 8)}:
            fail(f"unknown source section {r['decisionId']}")
        if not r["policyKey"] or not r["requiredEvidence"] or not r["goldenControlDescription"]:
            fail(f"unqualified contract content {r['decisionId']}")
    if len(set(r["sourceSha256"] for r in registry)) != 1:
        fail("multiple source document hashes require new registry revision")
    expected: dict[str, list[str]] = defaultdict(list)
    for decision in registry:
        targets = baseline_codes if decision["formCodes"] == "ALL_154" else decision["formCodes"].split(";")
        for code in targets:
            if code not in index:
                fail(f"decision {decision['decisionId']} references unknown form {code}")
            if decision["decisionId"] in expected[code]:
                fail(f"duplicate binding {code}, {decision['decisionId']}")
            expected[code].append(decision["decisionId"])
    cross_ids = [r["decisionId"] for r in registry if r["formCodes"] == "ALL_154"]
    if len(cross_ids) != 4:
        fail("four mandatory cross-cutting decisions required")
    scoped_count = 0
    for row in work:
        code = row["formCode"]
        source = index[code]
        for src, dst in (("contentMode", "contentMode"),
                         ("physicalSourceFields", "physicalSourceFields"),
                         ("calculatedCandidates", "existingFormulaCandidates")):
            if row[dst] != source[src]:
                fail(f"baseline drift {code} {src}")
        scoped = [name for name in expected[code] if name not in cross_ids]
        cross = [name for name in expected[code] if name in cross_ids]
        if row["wave02ScopedDecisionIds"] != (";".join(scoped) if scoped else "NONE"):
            fail(f"scoped-methodology binding drift for {code}")
        if row["crossCuttingDecisionIds"] != ";".join(cross):
            fail(f"missing cross-cutting control for {code}")
        want = "POLICY_EVIDENCE_PENDING" if scoped else "DOMAIN_SCOPE_MAPPING_NOT_AUTHORED"
        if row["domainDecisionState"] != want:
            fail(f"false domain qualification for {code}")
        if row["financialS2"] != "NOT_S2_APPROVED":
            fail(f"false S2 qualification for {code}")
        if row["baselineBlockers"] != source["blockers"]:
            fail(f"historical blocker lost for {code}")
        if scoped:
            scoped_count += 1
    if scoped_count != 20:
        fail(f"scoped wave02 coverage changed: {scoped_count}")
    print("FIN_CALC154_WAVE02_WORKLIST_OK",
          f"forms={len(work)}", f"decisions={len(registry)}",
          f"scoped={scoped_count}", f"unscoped={len(work)-scoped_count}",
          f"cross_cutting={len(cross_ids)}", "financial_s2=HOLD")


if __name__ == "__main__":
    main()
