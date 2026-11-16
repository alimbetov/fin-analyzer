import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { fileURLToPath } from "node:url";
import { loadP0Sources, verifyP0Contract } from "./fin-p0-contract-check.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
function registry() {
  return JSON.parse(fs.readFileSync(root + "/docs/FIN-P0-ACCEPTANCE-MATRIX-V1.json","utf8"));
}
function sources() {
  return loadP0Sources();
}
test("P0 registry resolves real 92 (43+49) fields, 166 forms, 64 variants and 60 unqualified families",()=>{
  const report=verifyP0Contract(registry(),sources());
  assert.equal(report.result,"STRUCTURAL_CONTRACT_ONLY_NOT_CI_GREEN_NOT_S2");
  assert.equal(report.candidateFields,92);
  assert.equal(report.financeFields,43);
  assert.equal(report.industryFields,49);
  assert.equal(report.registeredForms,166);
  assert.equal(report.methodFamilies,60);
  assert.equal(report.businessModelVariants,64);
  assert.equal(report.releaseDecision,"HOLD");
  assert.equal(report.stages.length,7);
});
test("P0 contract refuses a fabricated green milestone or release",()=>{
  const r=registry();
  r.stages[0].status="ACCEPTED";
  assert.throws(()=>verifyP0Contract(r,sources()),/STAGE_ACCEPTED_WITHOUT_GREEN_EVIDENCE/);
  const r2=registry();r2.releaseDecision="READY";
  assert.throws(()=>verifyP0Contract(r2,sources()),/RELEASE_MUST_HOLD/);
});
test("P0 contract refuses unreviewed method or field promotion",()=>{
  const s=sources();
  s.methods.methods[0].autoRunAllowed=true;
  assert.throws(()=>verifyP0Contract(registry(),s),/UNREVIEWED_METHOD_PROMOTED/);
  const x=sources();
  x.fields=x.fields.replace("PENDING_METHOD_REVIEW","APPROVED");
  assert.throws(()=>verifyP0Contract(registry(),x),/FIELD_WITHOUT_INDEPENDENT_REVIEW_MARKED_APPROVED/);
});
test("P0 contract rejects arbitrary form, field-count or workflow drift",()=>{
  const s=sources();
  s.fields=s.fields.trimEnd().split(/\r?\n/).slice(0,-1).join("\n")+"\n";
  assert.throws(()=>verifyP0Contract(registry(),s),/PILOT_92_DRIFT/);
  const s2=sources();
  s2.workflow=s2.workflow.replace("  acceptance-gate:", "  not-an-acceptance-gate:");
  assert.throws(()=>verifyP0Contract(registry(),s2),/WORKFLOW_MISSING/);
});
test("P0 registry rejects missing dependency and blocks optional CON/PRO as mandatory CORE prerequisites",()=>{
  const r=registry();
  r.stages[2].dependsOn=["P0.4"];
  assert.throws(()=>verifyP0Contract(r,sources()),/STAGE_INVALID_DEPENDENCY/);
  const r2=registry();
  r2.stages[6].dependsOn.push("P0.5");
  assert.throws(()=>verifyP0Contract(r2,sources()),/CORE_INCORRECTLY_BLOCKED_BY_OPTIONAL_METHOD/);
});
test("P0 registry rejects nonexistent source code reference",()=>{
  const r=registry();
  r.stages[0].sourceRefs.push("fake/nonexistent/finance.java");
  assert.throws(()=>verifyP0Contract(r,sources()),/STAGE_SOURCE_MISSING/);
});
