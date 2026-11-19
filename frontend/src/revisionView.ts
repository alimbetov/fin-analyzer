import type {Field} from './api';

export type HistoryValue={fieldCode:string;label:string;value:string};

/**
 * Immutable revision snapshots are authoritative. Current form metadata can
 * supply labels only when its version matches the pinned revision version.
 * Never use the current list of fields to filter historical saved values.
 */
export function historyValues(
  persisted:Record<string,string>,
  currentFields:Pick<Field,'fieldCode'|'title'>[],
  sameFormVersion:boolean
):HistoryValue[]{
  const labels=sameFormVersion
    ?new Map(currentFields.map(field=>[field.fieldCode,field.title||field.fieldCode]))
    :new Map<string,string>();
  return Object.entries(persisted).map(([fieldCode,value])=>({
    fieldCode,label:labels.get(fieldCode)??fieldCode,value
  }));
}
