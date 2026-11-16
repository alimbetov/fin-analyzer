/**
 * CAT-A2 isolated economic-semantics proof candidates.
 * Not connected to production, publication, financial advice, or ACTUAL facts.
 * S2 approval and trusted source evidence are required before promotion.
 */
export const VERSION = "CAT-A2-PROOF-CANDIDATE-1";
export const QUALIFICATION = "CANDIDATE_NOT_APPROVED";
const DECIMAL = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]{1,12})?$/;
const SCALE=10n**12n;
const CODES=new Set(["F01","F02","F03","DF-CSH-01","DF-CSH-02","DF-CSH-03",
 "DF-CSH-04","DF-CSH-05","DF-CSH-06","SF-G01","SF-G02","SF-G03",
 "SF-M01","SF-M02","SF-S01","SF-C01","SF-C02","SF-C03"]);
const REQUIRED={
 F01:["assets","liabilities","equity"],
 F02:["netRevenue","cogs","operatingExpenses","netProfit"],
 F03:["openingCash","cfo","cfi","cff","closingCash"],
 "DF-CSH-01":["amount"],"DF-CSH-02":["amount"],
 "DF-CSH-03":["balance","restricted","reserve"],
 "DF-CSH-04":["recognized","received"],"DF-CSH-05":["amount","paid"],
 "DF-CSH-06":["principal","interest","balance"],
 "SF-G01":["netRevenue","cogs"],"SF-G02":["periodCogs","avgStockValue"],
 "SF-G03":["purchasePrice","receivedQty","freight"],
 "SF-M01":["billableHours","availableHours"],
 "SF-M02":["recognizedFee","directHours","hourCost","otherCost"],
 "SF-S01":["netRevenue","directLaborCost","materialsCost","completedJobs"],
 "SF-C01":["unitsStarted","goodUnits","reworkedUnits","wasteUnits"],
 "SF-C02":["materialUsed","directLabor","overhead","goodUnits"],
 "SF-C03":["plannedHours","runHours","downtimeHours"]
};
export function decimal(value) {
 if(typeof value!=="string"||!DECIMAL.test(value))throw new Error("INVALID_EXACT_DECIMAL");
 const neg=value.startsWith("-");
 const v=neg?value.slice(1):value;
 const [whole,fraction=""]=v.split(".");
 if(whole.length>26)throw new Error("DECIMAL_MAGNITUDE_LIMIT");
 const n=BigInt(whole)*SCALE+BigInt(fraction.padEnd(12,"0"));
 return neg?-n:n;
}
export function format(value) {
 const neg=value<0n;
 const n=neg?-value:value;
 const whole=n/SCALE;
 const fraction=(n%SCALE).toString().padStart(12,"0").replace(/0+$/,"");
 return (neg&&n!==0n?"-":"")+whole.toString()+(fraction?"."+fraction:"");
}
function at(values,key){return decimal(values[key]);}
function assertNonnegative(values,names){
 for(const k of names)if(at(values,k)<0n)throw new Error("NONNEGATIVE_EXPECTED:"+k);
}
/**
 * The caller must provide synthetic, scope-pinned data.
 * Only narrow arithmetic observations are emitted, never approved method outputs.
 */
export function evaluateCandidate({formCode,values,dataSpace="SYNTHETIC",currency="KZT",period="2026-10"}) {
 if(!CODES.has(formCode))throw new Error("UNSUPPORTED_CANDIDATE_FORM");
 if(dataSpace!=="SYNTHETIC")throw new Error("ACTUAL_REQUIRES_INDEPENDENT_S2_APPROVAL");
 if(!/^[A-Z]{3}$/.test(currency)||!/^20\d\d-(0[1-9]|1[0-2])$/.test(period))
   throw new Error("SCOPE_REQUIRED");
 if(!values||typeof values!=="object"||Array.isArray(values))throw new Error("VALUES_REQUIRED");
 const required=REQUIRED[formCode];
 for(const key of required)if(!Object.hasOwn(values,key)||values[key]===null||values[key]===undefined)
   throw new Error("MISSING_EVIDENCE:"+key);
 for(const key of required)decimal(values[key]); // Validate even operands not yet qualified for arithmetic.\n const computed={};const limitations=[];
 const put=(key,v)=>{computed[key]=format(v);};
 const identity=(target,expected,code)=>{
   const diff=target-expected;put(code+"Difference",diff);
   if(diff!==0n)limitations.push("RECONCILIATION_DIFFERENCE_REVIEW:"+code);
 };
 switch(formCode){
  case "F01":
   assertNonnegative(values,["assets","liabilities"]);
   identity(at(values,"assets"),at(values,"liabilities")+at(values,"equity"),"balance");
   limitations.push("ROUNDING_MATERIALITY_POLICY_PENDING");
   break;
  case "F02":
   assertNonnegative(values,["netRevenue","cogs","operatingExpenses"]);
   put("grossProfit",at(values,"netRevenue")-at(values,"cogs"));
   limitations.push("NET_PROFIT_NOT_RECONCILABLE_WITHOUT_OTHER_INCOME_TAX_FINANCE_ITEMS");
   break;
  case "F03":
   identity(at(values,"closingCash"),at(values,"openingCash")+
     at(values,"cfo")+at(values,"cfi")+at(values,"cff"),"cash");
   limitations.push("FX_EFFECTS_AND_OTHER_CASH_RECONCILIATION_ITEMS_NOT_MODELED");
   break;
  case "DF-CSH-01":case "DF-CSH-02":
   assertNonnegative(values,["amount"]);
   put("eventAmount",at(values,"amount"));
   limitations.push("PAYMENT_DIRECTION_AND_INTERNAL_TRANSFERS_NEED_REVIEW");
   break;
  case "DF-CSH-03":
   assertNonnegative(values,["balance","restricted","reserve"]);
   if(at(values,"restricted")>at(values,"balance"))throw new Error("RESTRICTED_EXCEEDS_BALANCE");
   limitations.push("RESERVE_RESTRICTED_OVERLAP_NOT_QUALIFIED");
   break;
  case "DF-CSH-04":
   assertNonnegative(values,["recognized","received"]);
   put("recognizedLessReceived",at(values,"recognized")-at(values,"received"));
   limitations.push("ADVANCES_CREDIT_NOTES_AND_AGING_NOT_ASSESSED");
   break;
  case "DF-CSH-05":
   assertNonnegative(values,["amount","paid"]);
   put("amountLessPaid",at(values,"amount")-at(values,"paid"));
   limitations.push("ADVANCES_RETURNS_AND_AGING_NOT_ASSESSED");
   break;
  case "DF-CSH-06":
   assertNonnegative(values,["principal","interest","balance"]);
   limitations.push("NO_OPENING_DEBT_OR_DRAWDOWNS_TO_RECONCILE_CLOSING_BALANCE");
   break;
  case "SF-G01":
   assertNonnegative(values,required);
   put("grossProfit",at(values,"netRevenue")-at(values,"cogs"));
   limitations.push("RETURN_TAX_DISCOUNT_AND_SKU_SCOPE_PENDING");
   break;
  case "SF-G02":
   assertNonnegative(values,required);
   limitations.push("AVERAGE_STOCK_BASIS_AND_PERIOD_ALIGNMENT_PENDING");
   break;
  case "SF-G03":
   assertNonnegative(values,required);
   limitations.push("PURCHASE_PRICE_PER_UNIT_AND_FREIGHT_TOTAL_BASIS_PENDING");
   break;
  case "SF-M01":
   assertNonnegative(values,required);
   if(at(values,"billableHours")>at(values,"availableHours"))
     throw new Error("BILLABLE_EXCEEDS_AVAILABLE");
   break;
  case "SF-M02":
   assertNonnegative(values,required);
   // directHours * hourCost, both scaled 1e12.
   put("contribution",at(values,"recognizedFee")-
       at(values,"directHours")*at(values,"hourCost")/SCALE-at(values,"otherCost"));
   limitations.push("REVENUE_RECOGNITION_AND_COST_ALLOCATION_PENDING");
   break;
  case "SF-S01":
   assertNonnegative(values,required);
   put("grossContribution",at(values,"netRevenue")-
       at(values,"directLaborCost")-at(values,"materialsCost"));
   limitations.push("INDIRECT_COSTS_AND_SERVICE_SCOPE_PENDING");
   break;
  case "SF-C01":
   assertNonnegative(values,required);
   if(at(values,"goodUnits")>at(values,"unitsStarted"))
     throw new Error("GOOD_EXCEEDS_STARTED");
   limitations.push("REWORK_AND_SCRAP_MAY_OVERLAP_GOOD_OUTPUT");
   break;
  case "SF-C02":
   assertNonnegative(values,required);
   put("recordedManufacturingCost",at(values,"materialUsed")+
       at(values,"directLabor")+at(values,"overhead"));
   limitations.push("OVERHEAD_ALLOCATION_AND_WIP_ADJUSTMENTS_PENDING");
   break;
  case "SF-C03":
   assertNonnegative(values,required);
   if(at(values,"runHours")>at(values,"plannedHours"))
     throw new Error("RUN_EXCEEDS_PLANNED");
   limitations.push("DOWNTIME_MAY_OVERLAP_PLAN_AND_RUN_HOURS");
   break;
 }
 return Object.freeze({formCode,period,currency,qualification:QUALIFICATION,
   publishable:false,diagnosticEligible:false,computed:Object.freeze(computed),
   limitations:Object.freeze(limitations)});
}
export function assertDiagnosticEligible(result) {
 if(!result||result.qualification!== "INDEPENDENT_S2_APPROVED" ||
    result.publishable!==true || result.diagnosticEligible!==true)
   throw new Error("METHOD_NOT_QUALIFIED_FOR_TRUSTED_DIAGNOSIS");
 // Even an arbitrary caller claim cannot cross the boundary through this
 // candidate module. A separate trusted server-side eligibility service is required.
 throw new Error("CANDIDATE_MODULE_CANNOT_AUTHORIZE_DIAGNOSIS");
}
