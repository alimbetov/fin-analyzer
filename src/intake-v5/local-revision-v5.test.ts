import {describe,it,expect} from "vitest";
import {validateF02,type EntryContext} from "./form-schema";
import {planLocalRevisionV5,commitLocalRevisionV5} from "./local-revision-v5";

const ctx:EntryContext={projectCode:"FIN_UAT",formCode:"F02",periodCode:"2026.10",
  locale:"ru",accountingCurrency:"KZT",reportCurrency:"KZT",amountScale:1};
const cells=(revenue:string)=>[
  {rowId:"pnl",fieldCode:"netRevenue",rawValue:revenue},
  {rowId:"pnl",fieldCode:"cogs",rawValue:"260"},
  {rowId:"pnl",fieldCode:"operatingExpenses",rawValue:"90"}
];
const uploaded="2026-11-16T07:00:00.000Z";
const saved="2026-11-16T07:01:00.000Z";

describe("FIN V5 local session revision UX invariants",()=>{
  it("creates first version with distinct upload and save timestamps",()=>{
    const entry=validateF02(ctx,cells("400"));
    const result=commitLocalRevisionV5([],entry,uploaded,"XLSX","",saved);
    expect(result.revision.revisionNumber).toBe(1);
    expect(result.revision.uploadedAt).toBe(uploaded);
    expect(result.revision.savedAt).toBe(saved);
    expect(result.revision.sourceKind).toBe("XLSX");
    expect(result.revision.rows[0]?.rawValue).toBe("400");
  });
  it("same report on another language does not generate a new revision",()=>{
    const first=commitLocalRevisionV5([],validateF02(ctx,cells("400")),uploaded,"MANUAL","",saved);
    const duplicate=validateF02({...ctx,locale:"en"},cells("00400.00"));
    expect(planLocalRevisionV5(first.history,duplicate).status).toBe("DUPLICATE");
    const second=commitLocalRevisionV5(first.history,duplicate,uploaded,"XLSX","",saved);
    expect(second.result).toBe("DUPLICATE");
    expect(second.history).toHaveLength(1);
    expect(second.revision.revisionNumber).toBe(1);
  });
  it("changed amount requires reason; previous version retains original value",()=>{
    const old=commitLocalRevisionV5([],validateF02(ctx,cells("400")),uploaded,"MANUAL","",saved);
    const changed=validateF02(ctx,cells("450"));
    const plan=planLocalRevisionV5(old.history,changed);
    expect(plan.nextRevision).toBe(2);
    expect(plan.changes[0]).toEqual({
      fieldCode:"netRevenue",previous:"400",current:"450",changed:true
    });
    expect(()=>commitLocalRevisionV5(old.history,changed,uploaded,"MANUAL","",saved))
      .toThrow("LOCAL_V5_CHANGE_REASON_REQUIRED");
    const next=commitLocalRevisionV5(old.history,changed,uploaded,"MANUAL","Correction",saved);
    expect(next.revision.revisionNumber).toBe(2);
    expect(next.history[0]?.rows[0]?.rawValue).toBe("400");
    expect(next.history[1]?.rows[0]?.rawValue).toBe("450");
  });
  it("scale mismatch for the same report cannot silently create another version",()=>{
    const old=commitLocalRevisionV5([],validateF02(ctx,cells("400")),uploaded,"MANUAL","",saved);
    const changed=validateF02({...ctx,amountScale:1000},cells("400"));
    expect(planLocalRevisionV5(old.history,changed).status).toBe("MONEY_CONTEXT_MISMATCH");
    expect(()=>commitLocalRevisionV5(old.history,changed,uploaded,"MANUAL","x",saved))
      .toThrow("LOCAL_V5_MONEY_CONTEXT_MISMATCH");
  });
  it("other project or period has its own revision number",()=>{
    const old=commitLocalRevisionV5([],validateF02(ctx,cells("400")),uploaded,"MANUAL","",saved);
    const separate=validateF02({...ctx,projectCode:"FIN_OTHER"},cells("420"));
    expect(planLocalRevisionV5(old.history,separate).nextRevision).toBe(1);
    const otherMonth=validateF02({...ctx,periodCode:"2026.11"},cells("420"));
    expect(planLocalRevisionV5(old.history,otherMonth).status).toBe("NEW");
  });
});
