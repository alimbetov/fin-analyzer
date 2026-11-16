import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../../../");
const RES = resolve(ROOT, "backend/src/main/resources/fin");
const PILOT = [
  "F01","F02","F03",
  "DF-CSH-01","DF-CSH-02","DF-CSH-03","DF-CSH-04","DF-CSH-05","DF-CSH-06",
  "SF-G01","SF-G02","SF-G03","SF-M01","SF-M02","SF-S01",
  "SF-C01","SF-C02","SF-C03"
];
const TYPES = new Set(["REF","CODE","DATE","MONEY","DECIMAL","PERIOD","ENUM","INTEGER","TEXT","BOOLEAN"]);
function tsv(name,header) {
  const lines=readFileSync(resolve(RES,name),"utf8").trimEnd().split(/\r?\n/);
  assert.equal(lines.shift(),header,name+" header drift");
  return lines.filter(Boolean).map((line,index)=>{
    const cells=line.split("\t");
    assert.equal(cells.length,header.split("\t").length,name+" row "+(index+2));
    return cells;
  });
}
function unique(rows,selector,label) {
  const values=rows.map(selector);
  assert.equal(new Set(values).size,values.length,label+" duplicate");
}
function fields(contract) {
  return contract.split(";").map(token=>{
    const p=token.split(":");
    assert.equal(p.length,2,"invalid field "+token);
    assert.match(p[0],/^[A-Za-z][A-Za-z0-9_]*$/);
    assert.ok(TYPES.has(p[1]),"unsupported type "+p[1]);
    return p;
  });
}
const forms=tsv("form-compatibility.tsv",
  "formCode\tcatalog\tformKind\taggregateRoot\tgrain\tfieldContract\tfieldEvidence\tqualificationStatus");
const specialized=tsv("specialized-schema-candidates-r6.tsv",
  "formCode\tlayout\tfields\tpreliminaryInvariant\tqualification");
const presentation=tsv("form-field-presentation-review-r6.tsv",
  "formCode\tfieldCode\tfieldType\tlabelRu\tlabelKk\tlabelEn\tlabelZhHans\treviewStatus\trequiredness\tunitPolicy");
const extension=tsv("form-extension-sheets-v2.tsv",
  "sheetCode\tdomain\tparentFormCode\tsheetMode\tsourceGrain\tnaturalKey\tfieldContract\teconomicSemantics\tvalidationContract\tsourcePolicy\tqualificationStatus");
const policy=tsv("diagnostic-sheet-policy-v1.tsv",
  "sheetCode\ttraitGate\tpriorityClass\tqualificationStatus");
const byForm=new Map(forms.map(f=>[f[0],f]));
const byCandidate=new Map(specialized.map(f=>[f[0],f]));
const byPresentation=new Map(presentation.map(f=>[f[0]+":"+f[1],f]));

test("CAT-A1: 166 unique source forms, category and qualification consistency",()=>{
  assert.equal(forms.length,166);
  unique(forms,f=>f[0],"formCode");
  assert.equal(forms.filter(f=>f[1]==="DF").length,59);
  assert.equal(forms.filter(f=>f[1]==="SF").length,63);
  assert.equal(forms.filter(f=>f[1]==="FCP_TD").length,44);
  assert.equal(forms.filter(f=>["DOCUMENT","EXTERNAL_TAX"].includes(f[2])).length,12);
  assert.equal(forms.filter(f=>f[6]==="TYPED_FIELD_HINTS").length,122);
  for(const f of forms) {
    assert.ok(["DESIGN_ONLY","PILOT_UNQUALIFIED"].includes(f[7]),f[0]);
    if(f[6]==="TYPED_FIELD_HINTS"){
      assert.ok(["STRUCTURED_INPUT","SECTOR_INPUT"].includes(f[2]),f[0]);
      unique(fields(f[5]),p=>p[0],f[0]+" field");
    } else assert.equal(f[6],"CATALOG_SUMMARY",f[0]);
  }
});

test("CAT-A1: 32 candidates match exactly the structural catalog codes",()=>{
  assert.equal(specialized.length,32);
  unique(specialized,f=>f[0],"candidate");
  const expected=forms.filter(f=>f[6]==="CATALOG_SUMMARY" &&
      !["DOCUMENT","EXTERNAL_TAX"].includes(f[2])).map(f=>f[0]).sort();
  assert.deepEqual([...byCandidate.keys()].sort(),expected);
  for(const row of specialized){
    assert.equal(row[4],"SCHEMA_CANDIDATE_NOT_APPROVED",row[0]);
    unique(fields(row[2]),p=>p[0],row[0]+" candidate field");
  }
});

test("CAT-A1: all 794 presentation slots exactly match source schema fields",()=>{
  assert.equal(presentation.length,794);
  unique(presentation,f=>f[0]+":"+f[1],"presentation field");
  const expected=new Map();
  for(const form of forms) {
    const contract=form[6]==="TYPED_FIELD_HINTS"
      ?form[5]:byCandidate.get(form[0])?.[2];
    if(!contract)continue;
    for(const [name,type] of fields(contract)){
      const key=form[0]+":"+name;
      assert.ok(!expected.has(key),"duplicate schema field "+key);
      expected.set(key,type);
    }
  }
  assert.equal(expected.size,794,"field-count drift: update catalog and reviewed inventory together");
  assert.deepEqual([...byPresentation.keys()].sort(),[...expected.keys()].sort(),
    "orphan or missing form field presentation");
  for(const [key,row] of byPresentation){
    assert.equal(row[2],expected.get(key),"field type drift "+key);
    assert.ok(["PENDING_TRANSLATION","PROVISIONAL_NOT_S2"].includes(row[7]),key);
    assert.equal(row[8],"PENDING_METHOD_REVIEW",key);
    assert.ok(row[9],"missing unit policy "+key);
    if(row[7]==="PENDING_TRANSLATION")assert.ok(row.slice(3,7).every(x=>!x),
      "unreviewed labels must not be presented as approved: "+key);
  }
});

test("CAT-A1: 30 EX sheets and policies are bijective, parents valid, all unqualified",()=>{
  assert.equal(extension.length,30);
  assert.equal(policy.length,30);
  unique(extension,x=>x[0],"extension sheet");
  unique(policy,x=>x[0],"sheet policy");
  assert.deepEqual(extension.map(x=>x[0]).sort(),policy.map(x=>x[0]).sort());
  const policies=new Map(policy.map(p=>[p[0],p]));
  for(const e of extension){
    assert.ok(byForm.has(e[2]),"unknown EX parent "+e[0]+":"+e[2]);
    assert.equal(e[10],"DESIGN_ONLY",e[0]);
    assert.equal(policies.get(e[0])[3],"DESIGN_ONLY",e[0]);
    assert.ok(["STRUCTURED","COMPOSITE","DERIVED"].includes(e[3]),e[0]);
    assert.equal(e[3]==="DERIVED",policies.get(e[0])[2]==="DERIVED",
      "derived sheet should never request manual input "+e[0]);
    unique(fields(e[6]),p=>p[0],e[0]+" extension field");
  }
});

test("CAT-A2: all 92 priority source fields have exact registry identity, not approval",()=>{
  assert.equal(PILOT.length,18);
  const rows=presentation.filter(r=>PILOT.includes(r[0]));
  assert.equal(rows.length,92);
  for(const code of PILOT){
    assert.ok(byForm.has(code),"pilot form missing "+code);
    const row=byForm.get(code);
    const contract=row[6]==="TYPED_FIELD_HINTS"?row[5]:byCandidate.get(code)?.[2];
    assert.ok(contract,"pilot has no typed candidate "+code);
    for(const [field,type] of fields(contract)){
      const slot=byPresentation.get(code+":"+field);
      assert.ok(slot,"pilot field untracked: "+code+":"+field);
      assert.equal(slot[2],type);
      assert.equal(slot[8],"PENDING_METHOD_REVIEW",
        "must not silently qualify source field "+code+":"+field);
    }
  }
});
