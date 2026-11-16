import test from "node:test";
import assert from "node:assert/strict";
import {evaluateCandidate,assertDiagnosticEligible}
 from "../src/cat-a2-candidate-semantics.mjs";

/**
 * A deliberately isolated synthetic-only test of method eligibility boundaries.
 * These cases do NOT satisfy the real authenticated React/Java/PostgreSQL E2E gate.
 */
const examples={
 F01:{assets:"100",liabilities:"30",equity:"70"},
 F02:{netRevenue:"100",cogs:"40",operatingExpenses:"30",netProfit:"20"},
 F03:{openingCash:"100",cfo:"10",cfi:"-5",cff:"-2",closingCash:"103"},
 "DF-CSH-01":{amount:"10"},
 "DF-CSH-02":{amount:"10"},
 "DF-CSH-03":{balance:"20",restricted:"5",reserve:"2"},
 "DF-CSH-04":{recognized:"30",received:"20"},
 "DF-CSH-05":{amount:"30",paid:"20"},
 "DF-CSH-06":{principal:"5",interest:"1",balance:"40"},
 "SF-G01":{netRevenue:"50",cogs:"20"},
 "SF-G02":{periodCogs:"20",avgStockValue:"10"},
 "SF-G03":{purchasePrice:"10",receivedQty:"2",freight:"3"},
 "SF-M01":{billableHours:"5",availableHours:"8"},
 "SF-M02":{recognizedFee:"50",directHours:"3",hourCost:"7",otherCost:"4"},
 "SF-S01":{netRevenue:"50",directLaborCost:"10",materialsCost:"5",completedJobs:"4"},
 "SF-C01":{unitsStarted:"10",goodUnits:"8",reworkedUnits:"1",wasteUnits:"1"},
 "SF-C02":{materialUsed:"25",directLabor:"10",overhead:"5",goodUnits:"8"},
 "SF-C03":{plannedHours:"8",runHours:"6",downtimeHours:"2"}
};
for(const [formCode,values] of Object.entries(examples)){
 test("CAT-A2 fail-closed missing, malformed and ACTUAL for "+formCode,()=>{
   const candidate=evaluateCandidate({formCode,values});
   assert.equal(candidate.diagnosticEligible,false);
   assert.equal(candidate.publishable,false);
   assert.equal(candidate.qualification,"CANDIDATE_NOT_APPROVED");
   assert.throws(()=>assertDiagnosticEligible(candidate),/METHOD_NOT_QUALIFIED/);
   assert.throws(()=>evaluateCandidate({formCode,values,dataSpace:"ACTUAL"}),/ACTUAL_REQUIRES/);
   for(const field of Object.keys(values)){
     const missing={...values}; delete missing[field];
     assert.throws(()=>evaluateCandidate({formCode,values:missing}),/MISSING_EVIDENCE:/,
       "missing "+formCode+"."+field);
     const malformed={...values,[field]:"1e3"};
     assert.throws(()=>evaluateCandidate({formCode,values:malformed}),/INVALID_EXACT_DECIMAL/,
       "malformed "+formCode+"."+field);
   }
 });
}
test("CAT-A2 external status spoofing cannot authorize diagnostic execution",()=>{
 assert.throws(()=>assertDiagnosticEligible({
   qualification:"INDEPENDENT_S2_APPROVED",publishable:true,diagnosticEligible:true
 }),/CANDIDATE_MODULE_CANNOT_AUTHORIZE_DIAGNOSIS/);
});
