#!/usr/bin/env python3
"""FIN-EDIT-R1: deterministic, read-only audit of all 166 source form contracts.

No migration, no database mutation, and NO automatic qualification of financial
metrics. A catalog hint is not an S2-approved field/metric contract.
"""
from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "form-library" / "src" / "main" / "resources" / "catalog"
EVIDENCE_KINDS = {"DOCUMENT", "EXTERNAL_TAX"}
PENDING_LABELS = {"PENDING_TRANSLATION", "PROVISIONAL_NOT_S2"}
REVIEWED_LABELS = {"S2_APPROVED", "APPROVED", "VERIFIED"}


def tsv(name: str, required: tuple[str, ...]) -> list[dict[str, str]]:
    path = CATALOG / name
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source, delimiter="\t")
        if reader.fieldnames != list(required):
            raise ValueError(f"{name}: expected {required}, got {reader.fieldnames}")
        data = list(reader)
    if any(None in record or None in record.values() for record in data):
        raise ValueError(f"{name}: uneven column count")
    return data


def audit() -> dict:
    forms = tsv("form-compatibility.tsv", (
        "formCode", "catalog", "formKind", "aggregateRoot", "grain",
        "fieldContract", "fieldEvidence", "qualificationStatus"))
    fields = tsv("form-field-presentation-review-r6.tsv", (
        "formCode", "fieldCode", "fieldType", "labelRu", "labelKk",
        "labelEn", "labelZhHans", "reviewStatus", "requiredness", "unitPolicy"))
    candidates = tsv("specialized-schema-candidates-r6.tsv", (
        "formCode", "layout", "fields", "preliminaryInvariant", "qualification"))
    if len(forms) != 166:
        raise ValueError(f"Expected 166 form identities, got {len(forms)}")
    indexed = {f["formCode"]: f for f in forms}
    if len(indexed) != len(forms):
        raise ValueError("Duplicate formCode")
    keys = [(f["formCode"], f["fieldCode"]) for f in fields]
    if len(set(keys)) != len(keys):
        raise ValueError("Duplicate formCode.fieldCode in field presentation")
    if any(code not in indexed for code, _ in keys):
        raise ValueError("Orphan field references a missing form")
    if len({f["formCode"] for f in candidates}) != len(candidates):
        raise ValueError("Duplicate specialized-schema candidate")
    if any(f["formCode"] not in indexed for f in candidates):
        raise ValueError("Orphan specialized-schema candidate")

    by_form = defaultdict(list)
    for f in fields:
        by_form[f["formCode"]].append(f)
    by_candidate = {c["formCode"]: c for c in candidates}
    output = []
    for code, definition in sorted(indexed.items()):
        own = by_form[code]
        kind = definition["formKind"]
        hint = definition["fieldEvidence"] == "TYPED_FIELD_HINTS"
        evidence = kind in EVIDENCE_KINDS
        state = "EVIDENCE_ONLY" if evidence else (
            "TYPED_CANDIDATE" if hint else "SCHEMA_CANDIDATE")
        blockers = []
        if not own and not evidence:
            blockers.append("NO_FIELD_PRESENTATION")
        if evidence:
            blockers.append("EVIDENCE_UPLOAD_NOT_IMPLEMENTED")
        if code in by_candidate:
            blockers.append("PRELIMINARY_SCHEMA_NOT_APPROVED")
        if own and any(x["reviewStatus"] not in REVIEWED_LABELS for x in own):
            blockers.append("FIELD_LABEL_NOT_S2_APPROVED")
        if own and any(x["requiredness"] == "PENDING_METHOD_REVIEW" for x in own):
            blockers.append("REQUIREDNESS_NOT_REVIEWED")
        if own and any(x["unitPolicy"].startswith("PENDING_") or
                       x["unitPolicy"].endswith("_PENDING") for x in own):
            blockers.append("UNIT_POLICY_NOT_REVIEWED")
        if definition["qualificationStatus"] not in {"APPROVED", "S2_APPROVED"}:
            blockers.append("FORM_CONTRACT_NOT_S2_APPROVED")
        if hint and not own:
            blockers.append("TYPED_HINT_FIELDS_NOT_IN_PRESENTATION")
        output.append({
            "formCode": code, "formKind": kind,
            "grain": definition["grain"], "category": definition["catalog"],
            "fieldEvidence": definition["fieldEvidence"],
            "catalogQualification": definition["qualificationStatus"],
            "captureClassification": state, "fieldCount": len(own),
            "labelReviewPending": sum(x["reviewStatus"] not in REVIEWED_LABELS for x in own),
            "requirednessPending": sum(x["requiredness"] == "PENDING_METHOD_REVIEW" for x in own),
            "unitPending": sum(x["unitPolicy"].startswith("PENDING_") or
                               x["unitPolicy"].endswith("_PENDING") for x in own),
            "s2Qualified": not blockers,
            "blockers": sorted(set(blockers)),
        })
    classification = Counter(r["captureClassification"] for r in output)
    if classification != Counter({
        "TYPED_CANDIDATE": 122, "SCHEMA_CANDIDATE": 32,
        "EVIDENCE_ONLY": 12,
    }):
        raise ValueError(f"Catalog classification drift: {classification}")
    if len(fields) != 794 or len(candidates) != 32:
        raise ValueError(f"Field/candidate source drift: {len(fields)}, {len(candidates)}")
    return {
        "contract": "FIN-EDIT-R1-INVENTORY-V1",
        "scope": "source inventory; NOT production qualification",
        "summary": {
            "forms": len(forms), "fieldPresentationRows": len(fields),
            "candidateSchemas": len(candidates),
            "formClassification": dict(sorted(classification.items())),
            "s2QualifiedFormsBySourceEvidence": sum(r["s2Qualified"] for r in output),
            "pendingRequirednessRows": sum(r["requirednessPending"] for r in output),
            "pendingUnitRows": sum(r["unitPending"] for r in output),
            "pendingLabelReviewRows": sum(r["labelReviewPending"] for r in output),
            "unqualifiedForms": sum(not r["s2Qualified"] for r in output),
        },
        "forms": output,
    }


def main() -> None:
    args = argparse.ArgumentParser()
    args.add_argument("--out-dir", type=Path, default=ROOT / "target" / "fin-r1")
    flags = args.parse_args()
    result = audit()
    flags.out_dir.mkdir(parents=True, exist_ok=True)
    (flags.out_dir / "form-contract-audit.json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    with (flags.out_dir / "form-contract-matrix.csv").open(
        "w", encoding="utf-8", newline="") as stream:
        keys = [k for k in result["forms"][0] if k != "blockers"] + ["blockers"]
        writer = csv.DictWriter(stream, fieldnames=keys)
        writer.writeheader()
        for form in result["forms"]:
            writer.writerow({**form, "blockers": ";".join(form["blockers"])})
    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    print(f"Audit: {flags.out_dir}")
    # Inventory success != S2 qualification. Formal review must clear blockers.


if __name__ == "__main__":
    main()
