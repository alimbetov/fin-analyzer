// FIN Project Marketplace sector pilot — exact fixed-scale calculations, no external I/O.
// Inputs: approved MONTH snapshots. Money and quantities: decimal strings, never JS Number.
const SCALE = 1000000n;
export const FORM_FIELDS = Object.freeze({
  'SF-G01': ['netRevenue','cogs','soldQty'],
  'SF-G02': ['periodCogs','avgStockValue'],
  'SF-G03': ['purchasePrice','receivedQty','freight'],
  'SF-M01': ['billableHours','availableHours'],
  'SF-M02': ['recognizedFee','directHours','hourCost','otherCost'],
  'SF-S01': ['netRevenue','directLaborCost','materialsCost','completedJobs'],
  'SF-C01': ['unitsStarted','goodUnits','reworkedUnits','wasteUnits'],
  'SF-C02': ['materialUsed','directLabor','overhead','goodUnits'],
  'SF-C03': ['runHours','plannedHours','downtimeHours']
});
export const PILOT_FORMS = Object.freeze(Object.keys(FORM_FIELDS));
const METRICS = Object.freeze({
  'SF-G01': ['SKU_GROSS_PROFIT','SKU_GROSS_MARGIN_PCT'],
  'SF-G02': ['SKU_STOCK_TURN_PERIOD'],
  'SF-G03': ['LANDED_UNIT_COST'],
  'SF-M01': ['BILLABLE_UTILIZATION_PCT'],
  'SF-M02': ['ENGAGEMENT_CONTRIBUTION'],
  'SF-S01': ['SERVICE_UNIT_MARGIN'],
  'SF-C01': ['GOOD_YIELD_PCT'],
  'SF-C02': ['UNIT_MFG_COST'],
  'SF-C03': ['EQUIPMENT_UTILIZATION_PCT']
});
const HEADERS = ['formCode','businessId','dimensionId','currency','period','sourceId','revisionId','approved','values'];
function check(condition, msg) { if (!condition) throw new Error(msg); }
function parseDecimal(v, name) {
  if (v === null) return null;
  check(typeof v === 'string' && /^(?:0|[1-9]\d{0,13})(?:\.\d{1,6})?$/.test(v),
    name + ': expected nonnegative decimal string with up to 6 decimals');
  const [a,b=''] = v.split('.');
  return BigInt(a)*SCALE + BigInt(b.padEnd(6,'0'));
}
function roundDivide(n,d) {
  check(d>0n,'invalid denominator');
  const sign=n<0n?-1n:1n, a=n<0n?-n:n;
  return sign*((a+d/2n)/d);
}
function fixed(n,d=SCALE) {
  if (d<=0n) return null;
  const r=roundDivide(n*100n,d), a=r<0n?-r:r;
  return (r<0n?'-':'')+(a/100n).toString()+'.'+(a%100n).toString().padStart(2,'0');
}
function multiply(a,b) {return roundDivide(a*b,SCALE);}
function days(period) {const [y,m]=period.split('-').map(Number);return BigInt(new Date(Date.UTC(y,m,0)).getUTCDate());}
function normalize(row) {
  check(row && typeof row==='object' && !Array.isArray(row),'row must be object');
  check(Object.keys(row).every(k=>HEADERS.includes(k)),'unexpected row property');
  const fields=FORM_FIELDS[row.formCode];
  check(fields!==undefined,'unsupported formCode');
  for(const k of ['businessId','dimensionId','sourceId','revisionId'])
    check(typeof row[k]==='string' && /^[A-Za-z0-9_.:-]{1,80}$/.test(row[k]),'invalid '+k);
  check(typeof row.currency==='string' && /^[A-Z]{3}$/.test(row.currency),'invalid currency');
  check(typeof row.period==='string' && /^20\d\d-(0[1-9]|1[0-2])$/.test(row.period),'invalid period');
  check(row.approved===true,'only approved data revisions allowed');
  check(row.values && typeof row.values==='object' && !Array.isArray(row.values),'invalid values');
  check(Object.keys(row.values).every(k=>fields.includes(k)),'unexpected field');
  const v={},missing=[];
  for(const field of fields) {
    check(Object.hasOwn(row.values,field),'missing must be explicit null: '+field);
    v[field]=parseDecimal(row.values[field],field);
    if(v[field]===null)missing.push(field);
  }
  if(missing.length) return {missing,parts:null,revisionId:row.revisionId};
  if(row.formCode==='SF-M01')check(v.billableHours<=v.availableHours,'billable > available');
  if(row.formCode==='SF-C01'){
    check(v.goodUnits<=v.unitsStarted,'good > started');
    check(v.wasteUnits<=v.unitsStarted,'waste > started');
  }
  if(row.formCode==='SF-C03'){
    check(v.runHours<=v.plannedHours,'run > planned');
    check(v.downtimeHours<=v.plannedHours,'downtime > planned');
  }
  let parts;
  switch(row.formCode) {
    case 'SF-G01':parts={rev:v.netRevenue,cogs:v.cogs};break;
    case 'SF-G02':parts={cogs:v.periodCogs,stockDays:v.avgStockValue*days(row.period),days:days(row.period)};break;
    case 'SF-G03':parts={cost:multiply(v.purchasePrice,v.receivedQty)+v.freight,qty:v.receivedQty};break;
    case 'SF-M01':parts={billable:v.billableHours,available:v.availableHours};break;
    case 'SF-M02':parts={fee:v.recognizedFee,labor:multiply(v.directHours,v.hourCost),other:v.otherCost};break;
    case 'SF-S01':parts={rev:v.netRevenue,cost:v.directLaborCost+v.materialsCost,jobs:v.completedJobs};break;
    case 'SF-C01':parts={good:v.goodUnits,started:v.unitsStarted};break;
    case 'SF-C02':parts={cost:v.materialUsed+v.directLabor+v.overhead,good:v.goodUnits};break;
    case 'SF-C03':parts={run:v.runHours,planned:v.plannedHours};break;
  }
  return {missing:[],parts,revisionId:row.revisionId};
}
function merge(entries) {
  const p={};
  for(const entry of entries)
    for(const [k,v] of Object.entries(entry.parts))p[k]=(p[k]??0n)+v;
  return p;
}
function metric(v,unit,reason='ZERO_DENOMINATOR') {
  return v===null?{status:'NOT_COMPUTABLE',value:null,reason}:{status:'CALCULATED',value:v,unit};
}
function calc(form,p,currency) {
  const money=n=>metric(fixed(n),currency);
  const percent=(n,d)=>metric(fixed(n*100n,d),'PERCENT');
  const unit=(n,d)=>metric(fixed(n,d),currency+'/UNIT');
  switch(form) {
    case 'SF-G01':return {SKU_GROSS_PROFIT:money(p.rev-p.cogs),SKU_GROSS_MARGIN_PCT:percent(p.rev-p.cogs,p.rev)};
    case 'SF-G02':return {SKU_STOCK_TURN_PERIOD:metric(fixed(p.cogs*p.days,p.stockDays),'PER_WINDOW')};
    case 'SF-G03':return {LANDED_UNIT_COST:unit(p.cost,p.qty)};
    case 'SF-M01':return {BILLABLE_UTILIZATION_PCT:percent(p.billable,p.available)};
    case 'SF-M02':return {ENGAGEMENT_CONTRIBUTION:money(p.fee-p.labor-p.other)};
    case 'SF-S01':return {SERVICE_UNIT_MARGIN:unit(p.rev-p.cost,p.jobs)};
    case 'SF-C01':return {GOOD_YIELD_PCT:percent(p.good,p.started)};
    case 'SF-C02':return {UNIT_MFG_COST:unit(p.cost,p.good)};
    case 'SF-C03':return {EQUIPMENT_UTILIZATION_PCT:percent(p.run,p.planned)};
    default:throw new Error('unsupported form');
  }
}
export function evaluateSectorForms(input) {
  check(Array.isArray(input)&&input.length<=50000,'invalid row count');
  const groups=new Map(),sources=new Set();
  for(const row of input) {
    const v=normalize(row);
    check(!sources.has(row.sourceId),'duplicate sourceId');
    sources.add(row.sourceId);
    const key=JSON.stringify([row.businessId,row.dimensionId,row.formCode,row.currency]);
    if(!groups.has(key))groups.set(key,new Map());
    const months=groups.get(key);
    check(!months.has(row.period),'duplicate monthly row; reconcile before approval');
    months.set(row.period,v);
  }
  const results=[];
  function emit(id,type,code,periods,months) {
    const absent=periods.filter(p=>!months.has(p));
    const entries=periods.filter(p=>months.has(p)).map(p=>months.get(p));
    const fields=[...new Set(entries.flatMap(v=>v.missing))];
    const incomplete=absent.length>0||fields.length>0;
    const metrics=incomplete
      ?Object.fromEntries(METRICS[id[2]].map(k=>[k,{status:'NOT_COMPUTABLE',value:null,reason:absent.length?'MISSING_PERIODS':'MISSING_FIELDS'}]))
      :calc(id[2],merge(entries),id[3]);
    results.push({businessId:id[0],dimensionId:id[1],formCode:id[2],currency:id[3],
      windowType:type,windowCode:code,status:incomplete?'INCOMPLETE':'CALCULATED',
      missingPeriods:absent,missingFields:fields,
      sourceRevisions:entries.map(x=>x.revisionId).sort(),metrics});
  }
  for(const [key,months] of groups) {
    const id=JSON.parse(key),windows=new Map();
    for(const period of [...months.keys()].sort()) {
      emit(id,'MONTH',period,[period],months);
      const year=Number(period.slice(0,4)),m=Number(period.slice(5,7));
      for(const [type,code,start,size] of [
        ['QUARTER',year+'-Q'+Math.ceil(m/3),3*Math.floor((m-1)/3)+1,3],
        ['HALF_YEAR',year+'-H'+Math.ceil(m/6),6*Math.floor((m-1)/6)+1,6],
        ['YEAR',String(year),1,12]
      ]) {
        const k=type+':'+code;
        if(!windows.has(k))windows.set(k,{type,code,periods:Array.from({length:size},(_,i)=>year+'-'+String(start+i).padStart(2,'0'))});
      }
    }
    for(const w of windows.values())emit(id,w.type,w.code,w.periods,months);
  }
  const order={MONTH:0,QUARTER:1,HALF_YEAR:2,YEAR:3};
  return results.sort((a,b)=>a.businessId.localeCompare(b.businessId)||
    a.formCode.localeCompare(b.formCode)||a.dimensionId.localeCompare(b.dimensionId)||
    order[a.windowType]-order[b.windowType]||a.windowCode.localeCompare(b.windowCode));
}
