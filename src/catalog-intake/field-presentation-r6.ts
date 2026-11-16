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
const pilot:Record<string,Record<FieldDisplayLocale,string>>={
  assetRef:{ru:"Идентификатор актива",kk:"Актив идентификаторы",en:"Asset identifier","zh-Hans":"资产标识"},
  type:{ru:"Вид актива",kk:"Актив түрі",en:"Asset type","zh-Hans":"资产类型"},
  acquiredAt:{ru:"Дата приобретения",kk:"Сатып алынған күні",en:"Acquisition date","zh-Hans":"购置日期"},
  cost:{ru:"Первоначальная стоимость",kk:"Бастапқы құны",en:"Original cost","zh-Hans":"原始成本"},
  netBookValue:{ru:"Балансовая стоимость",kk:"Баланстық құны",en:"Net book value","zh-Hans":"账面净值"},
  capacity:{ru:"Мощность (единица требует уточнения)",kk:"Қуаты (өлшем бірлігі нақтыланады)",
    en:"Capacity (unit to be defined)","zh-Hans":"产能（单位待确认）"}
};
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
  const label=formCode==="DF-AST-01"?pilot[field.code]?.[locale]:undefined;
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
