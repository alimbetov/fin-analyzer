import type {Locale} from './api';
import type {LucideIcon} from 'lucide-react';
import {BookOpen,FileText,Wallet,Calculator,Building2,Users,ShieldCheck,BriefcaseBusiness,Megaphone,Factory,FolderKanban,ShoppingCart,Package,ChartNoAxesCombined,CalendarRange} from 'lucide-react';

/** UI presentation keys only. No financial classifications or contract changes. */
export type FormVisualKey='ledgers'|'documents'|'assets'|'cash'|'costs'|'business'|'customers'|'governance'|'people'|'marketing'|'operations'|'projects'|'sales'|'inventory'|'finance'|'planning'|'sector';
export const formVisuals:Record<FormVisualKey,{icon:LucideIcon;tone:string}>={
  ledgers:{icon:BookOpen,tone:'blue'},
  documents:{icon:FileText,tone:'violet'},
  assets:{icon:Building2,tone:'teal'},
  cash:{icon:Wallet,tone:'teal'},
  costs:{icon:Calculator,tone:'amber'},
  business:{icon:BriefcaseBusiness,tone:'blue'},
  customers:{icon:Users,tone:'teal'},
  governance:{icon:ShieldCheck,tone:'violet'},
  people:{icon:Users,tone:'blue'},
  marketing:{icon:Megaphone,tone:'amber'},
  operations:{icon:Factory,tone:'teal'},
  projects:{icon:FolderKanban,tone:'blue'},
  sales:{icon:ShoppingCart,tone:'teal'},
  inventory:{icon:Package,tone:'amber'},
  finance:{icon:ChartNoAxesCombined,tone:'blue'},
  planning:{icon:CalendarRange,tone:'violet'},
  sector:{icon:Factory,tone:'blue'}
};
const domainVisuals:Record<string,FormVisualKey>={
  ACCOUNTING_REGISTERS:'ledgers',EVIDENCE_AND_DOCUMENTS:'documents',
  ASSETS:'assets',CASH_AND_DEBT:'cash',COSTS:'costs',
  BUSINESS_CONTEXT:'business',CUSTOMERS:'customers',GOVERNANCE:'governance',
  HUMAN_RESOURCES:'people',MARKETING:'marketing',OPERATIONS:'operations',
  PROJECTS:'projects',SALES:'sales',INVENTORY:'inventory',
  FINANCIAL_REPORTING:'finance',BUDGET_AND_FORECAST:'planning'
};
export function formVisual(domain:string):{icon:LucideIcon;tone:string}{
  const key=domainVisuals[domain]??(domain.startsWith('SECTOR_')?'sector':'finance');
  return formVisuals[key];
}

/** Editorial UI caption. Section contracts currently contain only codes, not translations. */
const mainLabel:Record<Locale,string>={
  ru:'Основные показатели',kk:'Негізгі көрсеткіштер',en:'Main indicators','zh-Hans':'主要指标'
};
const generalLabel:Record<Locale,string>={
  ru:'Общие сведения',kk:'Жалпы мәліметтер',en:'General information','zh-Hans':'基本信息'
};
export function sectionTitle(code:string,locale:Locale):string{
  if(code==='MAIN')return mainLabel[locale];
  if(code==='GENERAL')return generalLabel[locale];
  return code; // Unknown codes remain explicitly visible; do not invent translations.
}
