import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {dirname,resolve} from "node:path";
import {fileURLToPath} from "node:url";

const root=resolve(dirname(fileURLToPath(import.meta.url)),"../../../");
function rows(relative,header){
  const text=readFileSync(resolve(root,relative),"utf8").trimEnd().split(/\r?\n/);
  assert.equal(text.shift(),header,"CAT_A2_HEADER_DRIFT "+relative);
  return text.map(line=>line.split("\t"));
}
const candidates=rows("docs/CAT-A2-PILOT-FIELD-REVIEW.tsv",
  "formCode\tfieldCode\tfieldType\tcandidateSemanticRole\tunitPolicy\trequirednessReview\tqualificationStatus\treviewerQuestion");
const presentation=rows("backend/src/main/resources/fin/form-field-presentation-review-r6.tsv",
  "formCode\tfieldCode\tfieldType\tlabelRu\tlabelKk\tlabelEn\tlabelZhHans\treviewStatus\trequiredness\tunitPolicy");
const PILOT=new Set(["F01","F02","F03","DF-CSH-01","DF-CSH-02","DF-CSH-03","DF-CSH-04","DF-CSH-05","DF-CSH-06",
"SF-G01","SF-G02","SF-G03","SF-M01","SF-M02","SF-S01","SF-C01","SF-C02","SF-C03"]);

test("CAT-A2: candidate register covers exactly 18 forms and 92 field pairs",()=>{
  assert.equal(candidates.length,92);
  assert.equal(new Set(candidates.map(x=>x[0])).size,18);
  const keys=candidates.map(x=>x[0]+":"+x[1]);
  assert.equal(new Set(keys).size,92,"duplicate CAT-A2 field");
  for(const row of candidates){
    assert.equal(row.length,8);
    assert.ok(PILOT.has(row[0]),"unscoped candidate "+row[0]);
    assert.ok(row[3]&&row[4]&&row[7],"candidate without proposed semantic review");
    assert.equal(row[5],"PENDING_METHOD_REVIEW");
    assert.equal(row[6],"CANDIDATE_NOT_APPROVED");
  }
  const canonical=presentation.filter(x=>PILOT.has(x[0]));
  assert.deepEqual(keys.sort(),canonical.map(x=>x[0]+":"+x[1]).sort(),"pilot field drift");
  const types=new Map(canonical.map(x=>[x[0]+":"+x[1],x[2]]));
  for(const row of candidates)assert.equal(row[2],types.get(row[0]+":"+row[1]));
});
