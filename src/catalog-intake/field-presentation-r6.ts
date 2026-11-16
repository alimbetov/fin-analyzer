import presentationText from "../../backend/src/main/resources/fin/form-field-presentation-review-r6.tsv?raw";
import type {CatalogField} from "./catalog-schema";

/**
 * Human-readable financial terms need independent professional review.
 * Never translate field codes algorithmically or label proposed text APPROVED.
 */
export type FieldDisplayLocale="ru"|"kk"|"en"|"zh-Hans";
export type TranslationReview="PROVISIONAL_TRANSLATION"|"TRANSLATION_NOT_READY";
export interface FieldPresentation {
  readonly fieldCode:string;readonly label:string;readonly unit:string;
  readonly help:string;readonly requiredness:"UNSPECIFIED_PENDING_METHOD_REVIEW";
  readonly translationReview:TranslationReview;
}
export const PRESENTATION_HEADER=
  "formCode\tfieldCode\tfieldType\tlabelRu\tlabelKk\tlabelEn\tlabelZhHans\treviewStatus\trequiredness\tunitPolicy";
export interface PresentationReviewRow {
  formCode:string;fieldCode:string;fieldType:string;
  labels:Readonly<Record<FieldDisplayLocale,string>>;
  reviewStatus:"PROVISIONAL_NOT_S2"|"PENDING_TRANSLATION";
  requiredness:"PENDING_METHOD_REVIEW";unitPolicy:string;
}
export function parsePresentationInventory(text:string):readonly PresentationReviewRow[]{
  const lines=text.trimEnd().split(/\r?\n/);
  if(lines.shift()!==PRESENTATION_HEADER)
    throw new Error("R6_FIELD_TRANSLATION_HEADER_DRIFT");
  const keys=new Set<string>();
  const records=lines.map(line=>{
    const row=line.split("\t");
    if(row.length!==10)throw new Error("R6_FIELD_TRANSLATION_BAD_ROW");
    const [formCode,fieldCode,fieldType,ru,kk,en,zh,review,required,unitPolicy]=row as string[];
    const key=formCode+":"+fieldCode;
    if(keys.has(key)||!/^[A-Z][A-Z0-9-]*$/.test(formCode!)||
       !/^[A-Za-z][A-Za-z0-9_]*$/.test(fieldCode!)||
       !["REF","CODE","DATE","MONEY","DECIMAL","PERIOD","ENUM","INTEGER","TEXT","BOOLEAN"].includes(fieldType!)||
       !["PROVISIONAL_NOT_S2","PENDING_TRANSLATION"].includes(review!)||
       required!=="PENDING_METHOD_REVIEW"||!unitPolicy)
      throw new Error("R6_FIELD_TRANSLATION_DUPLICATE_OR_UNAPPROVED");
    keys.add(key);
    if(review==="PENDING_TRANSLATION"&&[ru,kk,en,zh].some(Boolean))
      throw new Error("R6_FIELD_TRANSLATION_UNREVIEWED_VALUE");
    if(review==="PROVISIONAL_NOT_S2"&&![ru,kk,en].some(Boolean))
      throw new Error("R6_FIELD_TRANSLATION_DRAFT_WITHOUT_LABEL");
    return {formCode:formCode!,fieldCode:fieldCode!,fieldType:fieldType!,
      labels:{ru:ru!,kk:kk!,en:en!,"zh-Hans":zh!},
      reviewStatus:review as PresentationReviewRow["reviewStatus"],
      requiredness:"PENDING_METHOD_REVIEW" as const,unitPolicy:unitPolicy!};
  });
  if(records.length!==794)throw new Error("R6_FIELD_TRANSLATION_COVERAGE_DRIFT");
  return records;
}
export const PRESENTATION_REVIEW_INVENTORY=parsePresentationInventory(presentationText);
const translated=new Map(PRESENTATION_REVIEW_INVENTORY.map(row=>[
  row.formCode+":"+row.fieldCode,row
]));

const unit:Record<string,Record<FieldDisplayLocale,string>>={
  MONEY:{ru:"KZT в масштабе формы",kk:"Нысан масштабындағы KZT",
    en:"KZT in report scale","zh-Hans":"按报表单位计的 KZT"},
  DATE:{ru:"Дата ISO 8601",kk:"ISO 8601 күні",en:"ISO 8601 date","zh-Hans":"ISO 8601 日期"},
  INTEGER:{ru:"Целое число",kk:"Бүтін сан",en:"Integer","zh-Hans":"整数"},
  PERIOD:{ru:"Отчётный период",kk:"Есепті кезең",en:"Reporting period","zh-Hans":"报告期间"},
  REF:{ru:"Код ссылки",kk:"Сілтеме коды",en:"Reference ID","zh-Hans":"引用标识"}
};
const notSet:Record<FieldDisplayLocale,string>={
  ru:"Единица измерения не квалифицирована",kk:"Өлшем бірлігі бекітілмеген",
  en:"Unit not yet qualified","zh-Hans":"计量单位尚未核定"
};
const help:Record<FieldDisplayLocale,string>={
  ru:"Уточняемый черновой контракт. Обязательность, единица и экономический смысл требуют методического подтверждения.",
  kk:"Алдын ала келісімшарт. Міндеттілік, өлшем бірлігі және экономикалық мәні бекітілуі тиіс.",
  en:"Provisional contract. Requiredness, units and accounting meaning need methodological review.",
  "zh-Hans":"临时字段约定。必填性、计量单位和会计含义尚待审核。"
};
export function fieldPresentation(formCode:string,field:CatalogField,
                                  locale:FieldDisplayLocale):FieldPresentation {
  const candidate=translated.get(formCode+":"+field.code);
  // This dictionary is a review backlog, not a translation approval.
  const label=candidate?.labels[locale]||undefined;
  return {
    fieldCode:field.code,label:label||field.code,
    unit:unit[field.type]?.[locale]||notSet[locale],
    help:help[locale],requiredness:"UNSPECIFIED_PENDING_METHOD_REVIEW",
    translationReview:label?"PROVISIONAL_TRANSLATION":"TRANSLATION_NOT_READY"
  };
}
export function formHasAllHumanLabels(
  formCode:string,fields:readonly CatalogField[],locale:FieldDisplayLocale
):boolean{
  return fields.every(f=>fieldPresentation(formCode,f,locale).translationReview!=="TRANSLATION_NOT_READY");
}
