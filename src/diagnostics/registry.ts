import catalogText from "../../backend/src/main/resources/fin/form-compatibility.tsv?raw";
import relationText from "../../backend/src/main/resources/fin/indicator-relationships-v1.tsv?raw";

const FORM_HEADER = "formCode\tcatalog\tformKind\taggregateRoot\tgrain\tfieldContract\tfieldEvidence\tqualificationStatus";
const RELATION_HEADER = "relationCode\trelationVersion\trelationType\tmethodCode\tqualificationStatus\taccountingPolicy\tperiodPolicy\tsourceBindings";
export const GRAPH_REGISTRY_VERSION = "INDICATOR_RELATIONSHIPS_V1";
export type TimeRole = "CURRENT_FLOW" | "CURRENT_STOCK" | "PRIOR_STOCK";
export type FormDisposition = "EVIDENCE_ONLY" | "PILOT_NUMERIC_SOURCE" | "NUMERIC_CANDIDATE_UNQUALIFIED";
export interface RegisteredForm {
  code: string;
  kind: string;
  disposition: FormDisposition;
  qualification: string;
}
export interface InputBinding {
  metric: string;
  role: TimeRole;
  formCode: string;
}
export interface Relationship {
  code: string;
  version: string;
  type: "ACCOUNTING_IDENTITY" | "CROSS_REPORT_RECONCILIATION" | "ECONOMIC_BRIDGE" | "QUALITATIVE_PATTERN";
  qualification: "SYNTHETIC_PILOT_UNQUALIFIED";
  accountingPolicy: "SAME_PUBLISHED_SCOPE";
  periodPolicy: "MONTH_AND_PRIOR_CLOSE";
  inputs: readonly InputBinding[];
}
export interface Registry {
  forms: readonly RegisteredForm[];
  relations: readonly Relationship[];
}
export interface GraphDemand {
  binding: InputBinding;
  unlocks: readonly string[];
}
export interface GraphStudy {
  formCode: string;
  disposition: FormDisposition;
  needed: readonly GraphDemand[];
}
export interface GraphPlan {
  status: "RELATED_METHODS_FOUND" | "NO_REVIEWED_RELATIONSHIP";
  rules: readonly Relationship[];
  studies: readonly GraphStudy[];
}
const ROLES = new Set<TimeRole>(["CURRENT_FLOW","CURRENT_STOCK","PRIOR_STOCK"]);
const TYPES = new Set<Relationship["type"]>([
  "ACCOUNTING_IDENTITY","CROSS_REPORT_RECONCILIATION","ECONOMIC_BRIDGE","QUALITATIVE_PATTERN"
]);
function rows(source: string, header: string, count: number): string[][] {
  const data = source.trimEnd().split(/\r?\n/);
  if (data.shift() !== header) throw new Error("GRAPH_REGISTRY_HEADER_DRIFT");
  if (!data.length) throw new Error("GRAPH_REGISTRY_EMPTY");
  return data.map(line => {
    const parts = line.split("\t");
    if (parts.length !== count || parts.some(part => !part.trim()))
      throw new Error("GRAPH_REGISTRY_INVALID_ROW");
    return parts;
  });
}
const key = (binding: InputBinding): string => binding.metric + ":" + binding.role;
export function parseGraphRegistry(formsText: string, policyText: string): Registry {
  const forms = rows(formsText, FORM_HEADER, 8).map(r => {
    const code = r[0]!;
    const kind = r[2]!;
    const qualification = r[7]!;
    if (!/^[A-Z][A-Z0-9-]{0,127}$/.test(code) ||
        !["STRUCTURED","STRUCTURED_INPUT","COMPOSITE","SECTOR_INPUT","DOCUMENT","EXTERNAL_TAX"].includes(kind) ||
        !["DESIGN_ONLY","PILOT_UNQUALIFIED"].includes(qualification))
      throw new Error("GRAPH_UNKNOWN_CATALOG_STATUS");
    return {
      code, kind,
      disposition: (kind === "DOCUMENT" || kind === "EXTERNAL_TAX"
        ? "EVIDENCE_ONLY" : "NUMERIC_CANDIDATE_UNQUALIFIED") as FormDisposition,
      qualification
    };
  });
  const catalog = new Map(forms.map(f => [f.code, f]));
  if (forms.length !== 166 || catalog.size !== 166) throw new Error("GRAPH_CATALOG_COUNT_DRIFT");
  const codes = new Set<string>();
  const metricOwners = new Map<string,string>();
  const relations = rows(policyText, RELATION_HEADER, 8).map(r => {
    const [code, version, type, method, qualification, policy, period, rawInputs] = r;
    if (!code || !/^[A-Z][A-Z0-9_]{1,127}$/.test(code) ||
        codes.has(code) || code !== method || !/^\d+\.\d+\.\d+$/.test(version!) ||
        !TYPES.has(type as Relationship["type"]) ||
        qualification !== "SYNTHETIC_PILOT_UNQUALIFIED" ||
        policy !== "SAME_PUBLISHED_SCOPE" || period !== "MONTH_AND_PRIOR_CLOSE")
      throw new Error("GRAPH_UNQUALIFIED_OR_DUPLICATE_RELATION");
    codes.add(code);
    const bindings = rawInputs!.split(";").map(part => {
      const item = part.split(":");
      if (item.length !== 3 || !/^[A-Z][A-Z0-9_]+$/.test(item[0]!) ||
          !ROLES.has(item[1] as TimeRole)) throw new Error("GRAPH_BAD_METRIC_ROLE");
      const owner = catalog.get(item[2]!);
      if (!owner || owner.disposition === "EVIDENCE_ONLY")
        throw new Error("GRAPH_EVIDENCE_ONLY_OR_UNKNOWN_SOURCE");
      if (item[1] === "CURRENT_FLOW" && !["F02","F03","DF-CSH-01","DF-CSH-02"].includes(item[2]!) ||
          item[1] !== "CURRENT_FLOW" && item[2] !== "F01")
        throw new Error("GRAPH_TIME_ROLE_MISMATCH");
      const previousOwner = metricOwners.get(item[0]!);
      if (previousOwner && previousOwner !== item[2])
        throw new Error("GRAPH_METRIC_AMBIGUOUS_SOURCE");
      metricOwners.set(item[0]!,item[2]!);
      return { metric: item[0]!, role: item[1] as TimeRole, formCode: item[2]! };
    });
    if (bindings.length < 2 || new Set(bindings.map(key)).size !== bindings.length)
      throw new Error("GRAPH_DUPLICATE_OR_EMPTY_LINK");
    return { code, version: version!, type: type as Relationship["type"],
      qualification: "SYNTHETIC_PILOT_UNQUALIFIED" as const,
      accountingPolicy: "SAME_PUBLISHED_SCOPE" as const,
      periodPolicy: "MONTH_AND_PRIOR_CLOSE" as const, inputs: bindings };
  });
  if (relations.length !== 6) throw new Error("GRAPH_UNREVIEWED_RELATION_COUNT");
  return {
    forms: forms.map(f => [...new Set(metricOwners.values())].includes(f.code)
      ? { ...f, disposition: "PILOT_NUMERIC_SOURCE" } : f),
    relations
  };
}
export const RELATIONSHIP_REGISTRY = parseGraphRegistry(catalogText,relationText);
const PROBLEMS: Readonly<Record<string,readonly string[]>> = {
  CASH_SHORTFALL: ["CASH_END:CURRENT_STOCK","CFO:CURRENT_FLOW"],
  PROFITABILITY: ["REV_NET:CURRENT_FLOW","COGS:CURRENT_FLOW"],
  BALANCE_RECONCILIATION: ["ASSETS:CURRENT_STOCK","EQUITY:CURRENT_STOCK"],
  RECEIVABLES: ["AR_END:CURRENT_STOCK","REV_NET:CURRENT_FLOW"]
};
export const BUSINESS_PROBLEMS = [
  { code: "CASH_SHORTFALL", label: "Недостаток денежных средств" },
  { code: "PROFITABILITY", label: "Снижение прибыльности" },
  { code: "BALANCE_RECONCILIATION", label: "Расхождения в балансе" },
  { code: "RECEIVABLES", label: "Рост дебиторской задолженности" }
] as const;
export function graphPlan(
  registry: Registry, anchor: { problemCode?: string; formCode?: string },
  depth: number
): GraphPlan {
  if (!Number.isInteger(depth) || depth < 1 || depth > 3)
    throw new Error("GRAPH_INVALID_DEPTH");
  const anchors = anchor.problemCode
    ? (PROBLEMS[anchor.problemCode] ?? [])
    : (registry.relations.flatMap(r => r.inputs)
        .filter(b => b.formCode === anchor.formCode).map(key));
  if (!anchors.length) return { status: "NO_REVIEWED_RELATIONSHIP", rules: [], studies: [] };
  const discovered = new Set(anchors);
  let frontier = new Set(anchors);
  const reachable: Relationship[] = [];
  for (let hop = 0; hop < depth && frontier.size > 0; hop++) {
    const newlyDiscovered = new Set<string>();
    for (const relation of registry.relations) {
      if (reachable.some(x => x.code === relation.code) ||
          !relation.inputs.some(binding => frontier.has(key(binding)))) continue;
      reachable.push(relation);
      for (const binding of relation.inputs) {
        const id = key(binding);
        if (!discovered.has(id)) { discovered.add(id); newlyDiscovered.add(id); }
      }
    }
    frontier = newlyDiscovered;
  }
  if (!reachable.length) return { status: "NO_REVIEWED_RELATIONSHIP", rules: [], studies: [] };
  // The planning screen has NO independently verified source facts. Every
  // requested metric remains missing, even when the user selected that form.
  const requests = new Map<string,GraphDemand>();
  for (const relation of reachable) for (const binding of relation.inputs) {
    const k = key(binding);
    const existing = requests.get(k);
    requests.set(k,{ binding, unlocks: [...(existing?.unlocks ?? []),relation.code] });
  }
  const byForm = new Map<string,GraphDemand[]>();
  for (const request of requests.values()) {
    const group = byForm.get(request.binding.formCode) ?? [];
    group.push(request);
    byForm.set(request.binding.formCode,group);
  }
  const studies: GraphStudy[] = [...byForm].map(([formCode,needed]) => ({
    formCode,
    disposition: registry.forms.find(f => f.code === formCode)!.disposition,
    needed: needed.sort((a,b) => key(a.binding).localeCompare(key(b.binding)))
  })).sort((a,b) =>
    new Set(b.needed.flatMap(x => x.unlocks)).size -
    new Set(a.needed.flatMap(x => x.unlocks)).size ||
    a.formCode.localeCompare(b.formCode));
  return { status: "RELATED_METHODS_FOUND", rules: reachable, studies };
}
