import type {GoalFormLink,IndicatorGoal,IndicatorModel} from './api.ts';

/** Catalog relationships, not verified financial requirement contracts. */
export type DecisionLinkKind='MODEL_INPUT_CANDIDATE'|'GOAL_RECOMMENDATION_CANDIDATE';
export type DecisionContext={
  goalCode:string;goalTitle:string|null;metricCode:string;
  modelCode:string|null;formCode:string;linkKind:DecisionLinkKind;
  role:GoalFormLink['role']|null;
};
export type ModelBranch={
  model:IndicatorModel;
  inputs:{formCode:string;goalRole:GoalFormLink['role']|null;linkKind:'MODEL_INPUT_CANDIDATE'}[];
};
export type DecisionTree={
  metricCode:string;
  branches:ModelBranch[];
  recommendations:GoalFormLink[];
};
export function goalMetricCodes(goal:IndicatorGoal):string[]{
  return [...new Set(goal.models.flatMap(model=>model.outputMetricCodes))];
}
export function buildDecisionTree(goal:IndicatorGoal,metricCode:string):DecisionTree{
  if(!goalMetricCodes(goal).includes(metricCode))
    return {metricCode,branches:[],recommendations:[]}; // fail closed
  const goalRoles=new Map(goal.forms.map(f=>[f.formCode,f.role]));
  const models=goal.models.filter(m=>m.outputMetricCodes.includes(metricCode));
  const inputs=new Set(models.flatMap(m=>m.inputFormCodes));
  return {
    metricCode,
    branches:models.map(model=>({
      model,
      inputs:[...new Set(model.inputFormCodes)].map(formCode=>({
        formCode,goalRole:goalRoles.get(formCode)??null,
        linkKind:'MODEL_INPUT_CANDIDATE' as const
      }))
    })),
    // Direct use-case recommendations are not automatically inputs of a
    // particular metric. Keep them explicitly separate from model edges.
    recommendations:goal.forms.filter(f=>!inputs.has(f.formCode))
      .sort((a,b)=>a.priority-b.priority)
  };
}
export function formContext(
  goal:IndicatorGoal,metricCode:string,formCode:string,
  modelCode:string|null
):DecisionContext|null{
  const tree=buildDecisionTree(goal,metricCode);
  const link=modelCode?
    tree.branches.find(b=>b.model.code===modelCode)?.inputs.find(x=>x.formCode===formCode):
    tree.recommendations.find(x=>x.formCode===formCode);
  if(!link)return null;
  const role=tree.branches.find(b=>b.model.code===modelCode)?
    (tree.branches.find(b=>b.model.code===modelCode)!.inputs.find(x=>x.formCode===formCode)?.goalRole??null):
    (tree.recommendations.find(x=>x.formCode===formCode)?.role??null);
  return {
    goalCode:goal.code,goalTitle:goal.title,metricCode,
    modelCode,formCode,
    linkKind:modelCode?'MODEL_INPUT_CANDIDATE':'GOAL_RECOMMENDATION_CANDIDATE',
    role
  };
}
