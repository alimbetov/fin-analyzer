import { describe,expect,it } from "vitest";
import formSource from "../../backend/src/main/resources/fin/form-compatibility.tsv?raw";
import relationSource from "../../backend/src/main/resources/fin/indicator-relationships-v1.tsv?raw";
import problemSource from "../../backend/src/main/resources/fin/diagnostic-problem-routes-v1.tsv?raw";
import { GRAPH_REGISTRY_VERSION, RELATIONSHIP_REGISTRY, parseGraphRegistry, parseProblemRoutes, graphPlan } from "./registry";

describe("FIN R4.6 versioned relationship graph",()=>{
  it("registers all 166 forms without pretending all are numerically qualified",()=>{
    expect(GRAPH_REGISTRY_VERSION).toBe("INDICATOR_RELATIONSHIPS_V1");
    expect(RELATIONSHIP_REGISTRY.forms).toHaveLength(166);
    expect(RELATIONSHIP_REGISTRY.relations).toHaveLength(6);
    const status=(kind:string)=>RELATIONSHIP_REGISTRY.forms.filter(x=>x.disposition===kind).length;
    expect(status("PILOT_NUMERIC_SOURCE")).toBe(5);
    expect(status("NUMERIC_CANDIDATE_UNQUALIFIED")).toBe(149);
    expect(status("EVIDENCE_ONLY")).toBe(12);
    expect(new Set(RELATIONSHIP_REGISTRY.relations.flatMap(r=>r.inputs.map(x=>x.formCode))))
      .toEqual(new Set(["F01","F02","F03","DF-CSH-01","DF-CSH-02"]));
  });
  it("does not turn an unqualified form or document into a fabricated method",()=>{
    expect(graphPlan(RELATIONSHIP_REGISTRY,{formCode:"SF-G01"},2).status)
      .toBe("NO_REVIEWED_RELATIONSHIP");
    expect(graphPlan(RELATIONSHIP_REGISTRY,{formCode:"D01"},2).studies).toEqual([]);
    expect(graphPlan(RELATIONSHIP_REGISTRY,{formCode:"T03"},2).rules).toEqual([]);
    expect(graphPlan(RELATIONSHIP_REGISTRY,{problemCode:"NOT_A_FINANCIAL_METHOD"},2).rules)
      .toEqual([]);
  });
  it("discovers linked evidence at increasing depth without misrepresenting source approval",()=>{
    const one=graphPlan(RELATIONSHIP_REGISTRY,{problemCode:"PROFITABILITY"},1);
    expect(one.rules.map(r=>r.code)).toEqual([
      "OPERATING_RESULT_TO_CFO","RECEIVABLES_CASH_STRESS","GROSS_MARGIN"
    ]);
    expect(new Set(one.studies.map(s=>s.formCode))).toEqual(new Set(["F01","F02","F03"]));
    expect(one.studies.flatMap(s=>s.needed).every(d=>d.binding.formCode!=="" )).toBe(true);
    const broad=graphPlan(RELATIONSHIP_REGISTRY,{problemCode:"PROFITABILITY"},3);
    expect(broad.rules).toHaveLength(5);
    expect(new Set(broad.studies.map(s=>s.formCode)))
      .toEqual(new Set(["F01","F02","F03","DF-CSH-01","DF-CSH-02"]));
    expect(new Set(broad.studies.flatMap(s=>s.needed).map(d=>d.binding.metric+":"+d.binding.role)).size)
      .toBe(broad.studies.flatMap(s=>s.needed).length);
    expect(graphPlan(RELATIONSHIP_REGISTRY,{problemCode:"PROFITABILITY"},3))
      .toEqual(broad);
    expect(()=>graphPlan(RELATIONSHIP_REGISTRY,{formCode:"F02"},4)).toThrow("GRAPH_INVALID_DEPTH");
  });
  it("uses reviewed problem anchors from one versioned resource",()=>{
    const routes=parseProblemRoutes(problemSource,RELATIONSHIP_REGISTRY);
    expect(Object.keys(routes)).toHaveLength(4);
    expect(routes["PROFITABILITY"]).toEqual(["REV_NET:CURRENT_FLOW","COGS:CURRENT_FLOW"]);
    expect(()=>parseProblemRoutes(problemSource.replace(
      "CFO:CURRENT_FLOW","UNKNOWN:CURRENT_FLOW"),RELATIONSHIP_REGISTRY)).toThrow();
    expect(()=>parseProblemRoutes(problemSource.replace(
      "SYNTHETIC_PILOT_UNQUALIFIED","PRODUCTION_QUALIFIED"),RELATIONSHIP_REGISTRY)).toThrow();
  });
  it("rejects unknown sources, fake production approvals and duplicate inputs",()=>{
    expect(()=>parseGraphRegistry(formSource,relationSource.replace(
      "ASSETS:CURRENT_STOCK:F01","ASSETS:CURRENT_STOCK:D01"))).toThrow();
    expect(()=>parseGraphRegistry(formSource,relationSource.replace(
      "ASSETS:CURRENT_STOCK:F01","ASSETS:CURRENT_STOCK:SF-G01"))).toThrow();
    expect(()=>parseGraphRegistry(formSource,relationSource.replace(
      "SYNTHETIC_PILOT_UNQUALIFIED","PRODUCTION_QUALIFIED"))).toThrow();
    expect(()=>parseGraphRegistry(formSource,relationSource.replace(
      "ASSETS:CURRENT_STOCK:F01","ASSETS:CURRENT_FLOW:F01"))).toThrow();
    expect(()=>parseGraphRegistry(formSource.replace("F01\t","F99\t"),relationSource)).toThrow();
    expect(()=>parseGraphRegistry(formSource,relationSource+"\n"+relationSource.split("\n")[1]))
      .toThrow();
  });
});
