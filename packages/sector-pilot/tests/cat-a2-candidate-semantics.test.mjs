import test from "node:test";
import assert from "node:assert/strict";
import {evaluateCandidate,decimal,format,assertDiagnosticEligible,QUALIFICATION}
  from "../src/cat-a2-candidate-semantics.mjs";
const base=(formCode,values,overrides={})=>({formCode,values,...overrides});
function proof(formCode,values,expected){
 const r=evaluateCandidate(base(formCode,values));
 assert.equal(r.qualification,QUALIFICATION);
 assert.equal(r.publishable,false);
 assert.equal(r.diagnosticEligible,false);
 assert.deepEqual(r.computed,expected);
 assert.throws(()=>assertDiagnosticEligible(r),/METHOD_NOT_QUALIFIED/);
 return r;
}
test("CAT-A2 exact decimals do not round through JS floating point",()=>{
 assert.equal(format(decimal("12345678901234.123456789012")),"12345678901234.123456789012");
 assert.equal(format(decimal("-0.250000")),"-0.25");
 assert.equal(format(decimal("0.000000000001")),"0.000000000001");
 for(const value of [1.5,"1e3","0.1234567890123","1,5","-01.5",null])
   assert.throws(()=>decimal(value),/INVALID_EXACT_DECIMAL/);
});
test("CAT-A2 F01 balance golden, mismatch and negative input",()=>{
 proof("F01",{assets:"1000",liabilities:"350",equity:"650"},{balanceDifference:"0"});
 const diff=proof("F01",{assets:"1000",liabilities:"350",equity:"649.99"},
   {balanceDifference:"0.01"});
 assert.ok(diff.limitations.some(x=>x.includes("RECONCILIATION_DIFFERENCE")));
 assert.throws(()=>evaluateCandidate(base("F01",{assets:"-1",liabilities:"0",equity:"-1"})),/NONNEGATIVE/);
});
test("CAT-A2 F02 profit is not deducible from abbreviated P&L",()=>{
 const r=proof("F02",{netRevenue:"400",cogs:"260",operatingExpenses:"90",netProfit:"35"},
   {grossProfit:"140"});
 assert.ok(r.limitations.some(x=>x.startsWith("NET_PROFIT_NOT_RECONCILABLE")));
 assert.equal(Object.hasOwn(r.computed,"netProfitDifference"),false);
});
test("CAT-A2 F03 rollforward golden and FX qualification",()=>{
 const r=proof("F03",{openingCash:"100",cfo:"35",cfi:"-10",cff:"-5",closingCash:"120"},
   {cashDifference:"0"});
 assert.ok(r.limitations.some(x=>x.includes("FX_EFFECTS")));
 proof("F03",{openingCash:"100",cfo:"35",cfi:"-10",cff:"-5",closingCash:"125"},
   {cashDifference:"5"});
});
test("CAT-A2 six DF-CSH contracts retain cash/event and liability semantics",()=>{
 proof("DF-CSH-01",{amount:"120"},{eventAmount:"120"});
 proof("DF-CSH-02",{amount:"40.1"},{eventAmount:"40.1"});
 const stock=proof("DF-CSH-03",{balance:"120",restricted:"30",reserve:"20"},{});
 assert.ok(stock.limitations.some(x=>x.includes("OVERLAP")));
 assert.throws(()=>evaluateCandidate(base("DF-CSH-03",
   {balance:"5",restricted:"6",reserve:"0"})),/RESTRICTED_EXCEEDS/);
 proof("DF-CSH-04",{recognized:"100",received:"120"},{recognizedLessReceived:"-20"});
 proof("DF-CSH-05",{amount:"100",paid:"40"},{amountLessPaid:"60"});
 const debt=proof("DF-CSH-06",{principal:"10",interest:"2",balance:"90"},{});
 assert.ok(debt.limitations.some(x=>x.includes("NO_OPENING_DEBT")));
});
test("CAT-A2 nine sector calculations and their qualification warnings",()=>{
 proof("SF-G01",{netRevenue:"100",cogs:"60"},{grossProfit:"40"});
 proof("SF-G02",{periodCogs:"90",avgStockValue:"30"},{});
 const purchase=proof("SF-G03",{purchasePrice:"10",receivedQty:"3",freight:"4"},{});
 assert.ok(purchase.limitations.some(x=>x.includes("PRICE_PER_UNIT")));
 proof("SF-M01",{billableHours:"8",availableHours:"10"},{});
 proof("SF-M02",{recognizedFee:"200",directHours:"10",hourCost:"8",otherCost:"20"},
   {contribution:"100"});
 proof("SF-S01",{netRevenue:"120",directLaborCost:"50",materialsCost:"10",completedJobs:"5"},
   {grossContribution:"60"});
 const production=proof("SF-C01",{unitsStarted:"100",goodUnits:"80",
   reworkedUnits:"30",wasteUnits:"20"},{});
 assert.ok(production.limitations.some(x=>x.includes("OVERLAP")));
 proof("SF-C02",{materialUsed:"100",directLabor:"60",overhead:"40",goodUnits:"20"},
   {recordedManufacturingCost:"200"});
 proof("SF-C03",{plannedHours:"12",runHours:"10",downtimeHours:"3"},{});
});
test("CAT-A2 strict negative and completeness gates for sector inputs",()=>{
 assert.throws(()=>evaluateCandidate(base("SF-M01",
   {billableHours:"11",availableHours:"10"})),/BILLABLE_EXCEEDS/);
 assert.throws(()=>evaluateCandidate(base("SF-C01",
   {unitsStarted:"9",goodUnits:"10",reworkedUnits:"0",wasteUnits:"0"})),/GOOD_EXCEEDS/);
 assert.throws(()=>evaluateCandidate(base("SF-C03",
   {plannedHours:"8",runHours:"9",downtimeHours:"1"})),/RUN_EXCEEDS/);
 assert.throws(()=>evaluateCandidate(base("SF-G01",
   {netRevenue:"5",cogs:null})),/MISSING_EVIDENCE/);
 assert.throws(()=>evaluateCandidate(base("F01",
   {assets:"1",liabilities:"1",equity:"0"},{dataSpace:"ACTUAL"})),/ACTUAL_REQUIRES/);
 assert.throws(()=>evaluateCandidate(base("F01",
   {assets:"1",liabilities:"1",equity:"0"},{period:"2026-13"})),/SCOPE_REQUIRED/);
});
test("CAT-A2 candidate module cannot be tricked into production approval",()=>{
 assert.throws(()=>assertDiagnosticEligible({qualification:"INDEPENDENT_S2_APPROVED",
   publishable:true,diagnosticEligible:true}),/CANDIDATE_MODULE_CANNOT_AUTHORIZE/);
});
