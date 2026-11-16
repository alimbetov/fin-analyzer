import ExcelJS from "exceljs";
import {enforceZipBudget} from "../adapters/zip-budget";
import {
  CAPTURE_CATALOG,CATALOG_CAPTURE_VERSION,MAX_CATALOG_ROWS,CAPTURE_LOCALES,
  getCaptureForm,validateCatalogCapture,type CaptureLocale,type CatalogCaptureContext,
  type CatalogPreview,type CatalogRow,type CatalogField
} from "./catalog-schema";
import {resolvePeriod} from "../intake-v5/reporting-period";
import {fieldPresentation} from "./field-presentation-r6";
import {SPECIALIZED_CANDIDATES,validateSpecializedCandidate} from "./specialized-contracts";

/** A machine-coded draft XLSX for the 122 typed-hint entries; NOT an approved source. */
const MAX_XLSX=2_000_000;
const MIME="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const SHEET="FIN_Catalog",INFO="Instructions",META="_FIN_Metadata";
const EXCEL_LAYOUT_VERSION="FIN-CATALOG-XLSX-2";
const META_KEYS=[
  "CAPTURE_VERSION","FORM_CODE","FORM_KIND","AGGREGATE_ROOT","GRAIN",
  "PROJECT_CODE","SOURCE_LOCALE","PERIOD_TYPE","PERIOD_CODE","COVERAGE",
  "CURRENCY","AMOUNT_SCALE","FIELD_SIGNATURE"
] as const;
const UI={
  ru:{rowId:"Код строки",types:"Тип поля",intro:"Заполните строки с 4-й. Коды полей пока не имеют утверждённого перевода.",
    warn:"Черновик по каталожным подсказкам. Не утверждает финансовые показатели и не передаёт данные на сервер.",
    edit:"Изменяйте только значения ниже заголовков. Не редактируйте идентификаторы, коды, контекст или метаданные."},
  kk:{rowId:"Жол коды",types:"Өріс түрі",intro:"4-жолдан бастап толтырыңыз. Өріс кодтарының бекітілген аудармасы әлі жоқ.",
    warn:"Каталог нұсқаулары бойынша нобай. Қаржылық көрсеткіштерді бекітпейді және серверге жібермейді.",
    edit:"Тек тақырып астындағы мәндерді өңдеңіз. Идентификаторларды, кодтарды және метадеректерді өзгертпеңіз."},
  en:{rowId:"Row ID",types:"Field type",intro:"Enter rows starting at row 4. Approved translations of field codes are not yet available.",
    warn:"Catalog-hint draft only. It does not approve financial indicators or upload data to a server.",
    edit:"Edit only the values below the headings. Do not change IDs, codes, context or metadata."}
} as const;

function ensureContext(context:CatalogCaptureContext){
  const canonical=getCaptureForm(context.formCode);
  const candidate=SPECIALIZED_CANDIDATES.find(f=>f.code===context.formCode);
  if(canonical.captureMode!=="TYPED_DRAFT"&&!candidate)
    throw new Error("CATALOG_SCHEMA_NOT_QUALIFIED:"+context.formCode);
  const form=candidate?{...canonical,fields:candidate.fields}:canonical;
  if(!CAPTURE_LOCALES.includes(context.locale))throw new Error("TRANSLATION_NOT_READY");
  if(!/^[\p{L}\p{N}_-]{1,50}$/u.test(context.projectCode)||
     context.currency!=="KZT"||![1,1000,1000000].includes(context.amountScale))
    throw new Error("CATALOG_CONTEXT_INVALID");
  try{resolvePeriod(context.periodType,context.periodCode,context.coverage);}
  catch{throw new Error("CATALOG_PERIOD_INVALID");}
  return form;
}
function signature(fields:readonly CatalogField[]):string{
  return fields.map(f=>f.code+":"+f.type).join(";");
}
function manifest(context:CatalogCaptureContext,version:string=EXCEL_LAYOUT_VERSION):readonly string[]{
  const form=ensureContext(context);
  return [
    version,form.code,form.kind,form.aggregateRoot,form.grain,
    context.projectCode,context.locale,context.periodType,context.periodCode,
    context.coverage,context.currency,String(context.amountScale),signature(form.fields)
  ];
}
function passport(context:CatalogCaptureContext,version:string=EXCEL_LAYOUT_VERSION):string[]{
  return [version,context.formCode,context.projectCode,
    context.periodCode,context.currency,String(context.amountScale),
    context.locale,context.periodType+"/"+context.coverage];
}
const REPORT_LABELS={
  ru:["Паспорт отчёта","Организация","Форма","Проект","Период","Валюта","Масштаб","Версия шаблона","Язык","Статус"],
  kk:["Есеп паспорты","Ұйым","Нысан","Жоба","Кезең","Валюта","Масштаб","Үлгі нұсқасы","Тіл","Мәртебе"],
  en:["Report passport","Organization","Form","Project","Period","Currency","Scale","Template version","Language","Status"]
} as const;
const UNBOUND={
  ru:"Не привязано к организации: локальный черновик",
  kk:"Ұйымға байланыстырылмаған: жергілікті нобай",
  en:"Not bound to an organization: local draft"
} as const;
function reportPassportRows(
  context:CatalogCaptureContext,
  form:ReturnType<typeof getCaptureForm>,
  locale:CaptureLocale,version:string
):readonly (readonly [string,string])[]{
  const values=["UNQUALIFIED_DRAFT",UNBOUND[locale],form.code+" · "+form.aggregateRoot,
    context.projectCode,context.periodCode+" ("+context.periodType+"/"+context.coverage+")",
    context.currency,String(context.amountScale),version,locale,"UNQUALIFIED_DRAFT"];
  return REPORT_LABELS[locale].map((label,i)=>[label,values[i]!] as const);
}

function literal(cell:ExcelJS.Cell):string{
  if(cell.value===null||cell.value===undefined)return "";
  if(typeof cell.value==="string")return cell.value;
  if(typeof cell.value==="number"&&Number.isFinite(cell.value)){
    const n=String(cell.value);
    if(/[eE]/.test(n)||n.replace(/[^0-9]/g,"").length>15)
      throw new Error("CATALOG_EXCEL_NUMERIC_PRECISION_UNSAFE");
    return n;
  }
  throw new Error("CATALOG_XLSX_NON_LITERAL");
}
export async function generateTypedCatalogExcel(context:CatalogCaptureContext):Promise<Blob>{
  const form=ensureContext(context);
  const book=new ExcelJS.Workbook();
  book.creator="FIN Analyzer catalog typed draft";
  const sheet=book.addWorksheet(SHEET);
  const instructions=book.addWorksheet(INFO);
  const locale=UI[context.locale];
  [locale.intro,locale.warn,locale.edit].forEach(value=>instructions.addRow([value]));
  instructions.getColumn(1).width=45;
  instructions.getColumn(2).width=85;
  const info=reportPassportRows(context,form,context.locale,EXCEL_LAYOUT_VERSION);
  for(const [i,[label,value]] of info.entries()){
    const row=i+5;
    const name=instructions.getCell(row,1),detail=instructions.getCell(row,2);
    name.value=label;detail.value=value;
    name.font={bold:true,color:{argb:"FF173550"}};
    name.fill={type:"pattern",pattern:"solid",fgColor:{argb:"FFE8F1F8"}};
    detail.fill={type:"pattern",pattern:"solid",fgColor:{argb:"FFF2F8FE"}};
    name.alignment={vertical:"middle",wrapText:true,horizontal:"left"};
    detail.alignment={vertical:"middle",wrapText:true,horizontal:"left"};
    instructions.getRow(row).height=27;
  }
  const passportCells=passport(context);
  for(let i=0;i<passportCells.length;i++){
    const c=sheet.getCell(1,i+1);
    c.value=passportCells[i]!;
    c.font={bold:true,color:{argb:"FF163455"}};
    c.fill={type:"pattern",pattern:"solid",fgColor:{argb:"FFE2ECF8"}};
    c.alignment={horizontal:"center",vertical:"middle",wrapText:true};
  }
  sheet.getRow(1).height=37;
  const names=[locale.rowId,...form.fields.map(f=>fieldPresentation(form.code,f,context.locale).label)];
  const types=[locale.types,...form.fields.map(f=>f.type)];
  for(let i=0;i<names.length;i++){
    const c=sheet.getCell(2,i+1);
    c.value=names[i]!;
    if(i>0){
      const p=fieldPresentation(form.code,form.fields[i-1]!,context.locale);
      c.note=`${p.fieldCode} · ${p.unit}\n${p.help}\nRequiredness: pending review · ${p.translationReview}`;
    }
    c.font={bold:true,color:{argb:"FFFFFFFF"}};
    c.fill={type:"pattern",pattern:"solid",fgColor:{argb:"FF25476F"}};
    c.alignment={horizontal:"center",vertical:"middle",wrapText:true};
    const type=sheet.getCell(3,i+1);
    type.value=types[i]!;
    type.font={italic:true,color:{argb:"FF244260"}};
    type.fill={type:"pattern",pattern:"solid",fgColor:{argb:"FFE8F1F8"}};
    type.alignment={horizontal:"center",vertical:"middle"};
    sheet.getColumn(i+1).width=i===0?20:Math.max(19,Math.min(37,names[i]!.length+9));
  }
  sheet.getRow(2).height=34;
  sheet.getRow(3).height=28;
  const starter=sheet.getRow(4);
  starter.getCell(1).value="ROW-001";
  starter.height=36;
  for(let i=0;i<form.fields.length;i++){
    const type=form.fields[i]!.type;
    const c=starter.getCell(i+2);
    c.numFmt=["MONEY","DECIMAL","INTEGER"].includes(type)?"#,##0.############":"@";
    c.fill={type:"pattern",pattern:"solid",fgColor:{argb:"FFDCEFFD"}};
    c.alignment={horizontal:["MONEY","DECIMAL","INTEGER"].includes(type)?"right":"center",
      vertical:"middle",wrapText:true};
  }
  sheet.views=[{state:"frozen",xSplit:1,ySplit:3}];
  const meta=book.addWorksheet(META);
  for(const [i,value] of manifest(context).entries()){
    meta.getCell(i+1,1).value=META_KEYS[i]!;
    meta.getCell(i+1,2).value=value;
  }
  meta.state="veryHidden";
  const bytes=await book.xlsx.writeBuffer();
  if(bytes.byteLength>MAX_XLSX)throw new Error("CATALOG_XLSX_SIZE_LIMIT");
  return new Blob([bytes as BlobPart],{type:MIME});
}
export async function parseTypedCatalogExcel(
  source:ArrayBuffer, expected:CatalogCaptureContext
):Promise<CatalogPreview>{
  const form=ensureContext(expected);
  if(source.byteLength>MAX_XLSX)throw new Error("CATALOG_XLSX_SIZE_LIMIT");
  enforceZipBudget(source);
  const book=new ExcelJS.Workbook();
  try{await book.xlsx.load(new Uint8Array(source) as unknown as Parameters<typeof book.xlsx.load>[0]);}
  catch{throw new Error("CATALOG_XLSX_DAMAGED");}
  if(book.worksheets.length!==3||book.worksheets.map(s=>s.name).join("|")!==SHEET+"|"+INFO+"|"+META||
     book.getWorksheet(META)?.state!=="veryHidden")throw new Error("CATALOG_XLSX_SHEETS_CHANGED");
  book.eachSheet(sheet=>sheet.eachRow(row=>row.eachCell({includeEmpty:false},cell=>{
    if(cell.type===ExcelJS.ValueType.Formula||cell.type===ExcelJS.ValueType.Error)
      throw new Error("CATALOG_XLSX_FORMULAS_FORBIDDEN");
  })));
  const metadata=book.getWorksheet(META)!;
  metadata.eachRow(row=>{
    if(row.number>META_KEYS.length)throw new Error("CATALOG_XLSX_META_CHANGED");
    row.eachCell({includeEmpty:false},(_cell,col)=>{
      if(col>2)throw new Error("CATALOG_XLSX_META_CHANGED");
    });
  });
  const sourceVersion=literal(metadata.getCell(1,2));
  const legacy=sourceVersion===CATALOG_CAPTURE_VERSION;
  if(legacy&&getCaptureForm(expected.formCode).captureMode!=="TYPED_DRAFT")
    throw new Error("CATALOG_XLSX_LEGACY_CANDIDATE_UNSUPPORTED");
  if(!legacy&&sourceVersion!==EXCEL_LAYOUT_VERSION)
    throw new Error("CATALOG_XLSX_VERSION_UNSUPPORTED");
  const suppliedLocale=literal(metadata.getCell(7,2)) as CaptureLocale;
  if(!CAPTURE_LOCALES.includes(suppliedLocale))throw new Error("TRANSLATION_NOT_READY");
  const issued={...expected,locale:suppliedLocale};
  const correct=manifest(issued,sourceVersion);
  if(metadata.actualRowCount!==META_KEYS.length)throw new Error("CATALOG_XLSX_META_CHANGED");
  for(let i=0;i<META_KEYS.length;i++)
    if(literal(metadata.getCell(i+1,1))!==META_KEYS[i]||
       literal(metadata.getCell(i+1,2))!==correct[i])
      throw new Error("CATALOG_XLSX_META_CHANGED:"+META_KEYS[i]);
  const info=book.getWorksheet(INFO)!;
  info.eachRow(row=>{
    if(row.number>(legacy?3:14))throw new Error("CATALOG_XLSX_INSTRUCTIONS_CHANGED");
    row.eachCell({includeEmpty:false},(_cell,col)=>{
      if(col>(legacy?1:2))throw new Error("CATALOG_XLSX_INSTRUCTIONS_CHANGED");
    });
  });
  if(info.actualRowCount!==(legacy?3:13))throw new Error("CATALOG_XLSX_INSTRUCTIONS_CHANGED");
  if(!legacy){
    for(const [i,[label,value]] of reportPassportRows(issued,form,suppliedLocale,sourceVersion).entries()){
      if(literal(info.getCell(i+5,1))!==label||
         literal(info.getCell(i+5,2))!==value)
         throw new Error("CATALOG_XLSX_REPORT_PASSPORT_CHANGED");
    }
  }
  for(const [i,line] of [UI[suppliedLocale].intro,UI[suppliedLocale].warn,UI[suppliedLocale].edit].entries())
    if(literal(info.getCell(i+1,1))!==line)throw new Error("CATALOG_XLSX_INSTRUCTIONS_CHANGED");
  const data=book.getWorksheet(SHEET)!;
  const width=form.fields.length+1;
  data.eachRow(row=>{
    if(row.number>MAX_CATALOG_ROWS+3)throw new Error("CATALOG_XLSX_EXTRA_ROW");
    row.eachCell({includeEmpty:false},(_cell,col)=>{
      if(col>(row.number===1?8:width))throw new Error("CATALOG_XLSX_EXTRA_COLUMN");
    });
  });
  const expectedPassport=passport(issued,sourceVersion);
  for(let i=0;i<expectedPassport.length;i++)
    if(literal(data.getCell(1,i+1))!==expectedPassport[i])
      throw new Error("CATALOG_XLSX_PASSPORT_CHANGED");
  const headings=[UI[suppliedLocale].rowId,...form.fields.map(f=>
     legacy?f.code:fieldPresentation(form.code,f,suppliedLocale).label)];
  const types=[UI[suppliedLocale].types,...form.fields.map(f=>f.type)];
  for(let i=0;i<headings.length;i++){
    if(literal(data.getCell(2,i+1))!==headings[i]||
       literal(data.getCell(3,i+1))!==types[i])
      throw new Error("CATALOG_XLSX_FIELD_SCHEMA_CHANGED");
  }
  const rows:CatalogRow[]=[];
  for(let n=4;n<=data.rowCount;n++){
    const row=data.getRow(n);
    const id=literal(row.getCell(1));
    const cells:Record<string,string>={};
    let populated=false;
    for(const [i,field] of form.fields.entries()){
      const value=literal(row.getCell(i+2));
      if(value.trim())populated=true;
      cells[field.code]=value;
    }
    if(!id && !populated)continue;
    rows.push({rowId:id,cells});
  }
  // UI language is a display preference. Decode the source values using the
  // workbook's own locale, so comma decimals in Russian books remain exact.
  const candidate=SPECIALIZED_CANDIDATES.find(f=>f.code===form.code);
  const sourcePreview=candidate?validateSpecializedCandidate(form.code,rows,issued.locale):null;
  const preview=sourcePreview?{
    status:"UNQUALIFIED_DRAFT" as const,publishable:false as const,
    context:issued,rows:sourcePreview.rows,
    findings:sourcePreview.findings,
    valid:sourcePreview.acceptedAsUnqualifiedDraft
  }:validateCatalogCapture(issued,rows);
  const findings=preview.findings.map(f=>{
    const rowIndex=rows.findIndex(r=>r.rowId===f.rowId);
    const colIndex=form.fields.findIndex(field=>field.code===f.fieldCode);
    if(rowIndex<0||colIndex<0)return f;
    return {...f,sourceCell:SHEET+"!"+String.fromCharCode(66+colIndex)+(rowIndex+4)};
  });
  return {...preview,findings,valid:findings.length===0};
}

/** Registry supports 166 classifications, but only typed-hint forms are XLSX-editable. */
export const TYPED_CATALOG_TEMPLATE_COUNT=CAPTURE_CATALOG.filter(f=>f.captureMode==="TYPED_DRAFT").length;
