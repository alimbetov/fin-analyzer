/**
 * IND-R1 draft methodology metadata. Formula execution remains in the tested
 * sector-pilot JS kernel. Translations are candidates, not expert-approved.
 */
interface Translation { name: string; what: string; how: string; shows: string }
export interface MethodInfo {
  code: string;
  version: "pilot-1.0";
  formula: string;
  qualification: "PILOT_UNQUALIFIED";
  translations: { ru: Translation; kk: Translation; en: Translation };
  caveatRu: string;
}
function method(code:string, formula:string,
  ru:Translation,kk:Translation,en:Translation,caveatRu:string):MethodInfo {
  return {code,version:"pilot-1.0",formula,qualification:"PILOT_UNQUALIFIED",translations:{ru,kk,en},caveatRu};
}
export const PILOT_METHODS: Readonly<Record<string,MethodInfo>> = {
  SKU_GROSS_PROFIT: method("SKU_GROSS_PROFIT","netRevenue - cogs",
    {name:"Валовая прибыль SKU",what:"Разницу между чистой выручкой и себестоимостью",how:"Выручка минус себестоимость",shows:"Вклад товара до остальных расходов"},
    {name:"Тауардың жалпы пайдасы",what:"Таза түсім мен өзіндік құн айырмасын",how:"Түсімнен өзіндік құн шегеріледі",shows:"Өзге шығындарға дейінгі тауар нәтижесін"},
    {name:"SKU gross profit",what:"Net sales less cost of sales",how:"Net revenue minus COGS",shows:"Product contribution before other expenses"},
    "Возвраты, скидки и НДС должны быть согласованы с netRevenue и cogs."),
  SKU_GROSS_MARGIN_PCT:method("SKU_GROSS_MARGIN_PCT","100 × (netRevenue - cogs) / netRevenue",
    {name:"Валовая маржа SKU, %",what:"Долю валовой прибыли в продажах",how:"Валовая прибыль / чистая выручка × 100",shows:"Доходность проданного товара"},
    {name:"Тауардың жалпы маржасы, %",what:"Жалпы пайданың сатылымдағы үлесін",how:"Жалпы пайда / таза түсім × 100",shows:"Тауар сатылымының маржасын"},
    {name:"SKU gross margin, %",what:"Gross profit as a share of sales",how:"Gross profit / net revenue × 100",shows:"Gross margin of product sales"},
    "При нулевой выручке доля не вычисляется; не усреднять проценты месяцев."),
  SKU_STOCK_TURN_PERIOD:method("SKU_STOCK_TURN_PERIOD","Σ(periodCogs) / dayWeightedMean(avgStockValue)",
    {name:"Оборачиваемость запасов",what:"Число оборотов стоимости запасов за период",how:"Себестоимость продаж / средний запас",shows:"Скорость использования капитала в запасах"},
    {name:"Қор айналымдылығы",what:"Кезеңдегі қор айналымының санын",how:"Сату өзіндік құны / орташа қор",shows:"Қордағы қаражат айналымын"},
    {name:"Inventory turnover",what:"Turns of inventory in the window",how:"Period COGS / average inventory",shows:"Use of capital tied up in inventory"},
    "Средний запас за месяц должен быть подтверждён; результат за квартал не годовой."),
  LANDED_UNIT_COST:method("LANDED_UNIT_COST","Σ(purchasePrice × receivedQty + freight) / Σ(receivedQty)",
    {name:"Полная закупочная стоимость единицы",what:"Цену единицы с доставкой",how:"(Стоимость закупок + доставка) / количество",shows:"Сопоставимую стоимость поставщиков"},
    {name:"Бірліктің жеткізілген құны",what:"Жеткізумен бірге бірлік құнын",how:"(Сатып алу + жеткізу) / сан",shows:"Жеткізушілер құнын салыстыруды"},
    {name:"Landed unit cost",what:"Unit cost including freight",how:"(Purchase cost + freight) / received quantity",shows:"Supplier acquisition economics"},
    "Прочие пошлины, страховка и landed cost включаются только по единой политике."),
  BILLABLE_UTILIZATION_PCT:method("BILLABLE_UTILIZATION_PCT","100 × Σ(billableHours) / Σ(availableHours)",
    {name:"Загрузка оплачиваемыми часами, %",what:"Долю оплачиваемой работы",how:"Оплачиваемые часы / доступные × 100",shows:"Монетизацию времени команды"},
    {name:"Ақылы сағаттар үлесі, %",what:"Ақылы жұмыс сағаттарының үлесін",how:"Ақылы сағаттар / қолжетімді сағаттар × 100",shows:"Команда уақытының тиімділігін"},
    {name:"Billable utilization, %",what:"Share of billable working time",how:"Billable / available hours × 100",shows:"Capacity monetization"},
    "Время одного специалиста не должно учитываться дважды в разных договорах."),
  ENGAGEMENT_CONTRIBUTION:method("ENGAGEMENT_CONTRIBUTION","Σ(recognizedFee - directHours × hourCost - otherCost)",
    {name:"Вклад контракта",what:"Остаток дохода после прямых затрат",how:"Признанный доход минус труд и прочие прямые затраты",shows:"Экономику отдельного договора"},
    {name:"Шарттың үлесі",what:"Тікелей шығындардан кейінгі табысты",how:"Танылған табыс – еңбек – өзге тікелей шығындар",shows:"Шарттың экономикалық нәтижесін"},
    {name:"Engagement contribution",what:"Revenue remaining after direct costs",how:"Recognized fee less labor and other direct costs",shows:"Contribution of a contract"},
    "Не чистая прибыль; признанный доход не равен оплате, ставка часа помесячная."),
  SERVICE_UNIT_MARGIN:method("SERVICE_UNIT_MARGIN","Σ(netRevenue - directLaborCost - materialsCost) / Σ(completedJobs)",
    {name:"Вклад на завершённую услугу",what:"Результат на один выполненный заказ",how:"(Выручка – труд – материалы) / выполненные заказы",shows:"Экономику единицы услуги"},
    {name:"Орындалған қызметтің үлесі",what:"Бір аяқталған тапсырыстың нәтижесін",how:"(Түсім – еңбек – материал) / орындалған тапсырыс",shows:"Қызмет бірлігінің экономикасын"},
    {name:"Contribution per completed job",what:"Contribution per completed service",how:"(Net revenue - labor - materials) / completed jobs",shows:"Service unit economics"},
    "Административные расходы не включены; отсутствие завершённых заказов исключает деление."),
  GOOD_YIELD_PCT:method("GOOD_YIELD_PCT","100 × Σ(goodUnits) / Σ(unitsStarted)",
    {name:"Выход годной продукции, %",what:"Долю годных изделий в запуске",how:"Годные изделия / запущенные × 100",shows:"Качество выхода в производстве"},
    {name:"Жарамды өнім шығымы, %",what:"Жарамды өнімнің үлесін",how:"Жарамды өнім / басталған өнім × 100",shows:"Өндірістің жарамды шығымын"},
    {name:"Good production yield, %",what:"Share of started units that are good",how:"Good units / started units × 100",shows:"Manufacturing good output"},
    "Повторная обработка не прибавляется к годному выпуску второй раз."),
  UNIT_MFG_COST:method("UNIT_MFG_COST","Σ(materialUsed + directLabor + overhead) / Σ(goodUnits)",
    {name:"Себестоимость единицы продукции",what:"Затраты на единицу годного выпуска",how:"(Материалы + труд + накладные) / годный выпуск",shows:"Экономику производства продукта"},
    {name:"Өнім бірлігінің өзіндік құны",what:"Жарамды өнім бірлігінің шығынын",how:"(Материал + еңбек + үстеме) / жарамды өнім",shows:"Өндіріс бірлігінің шығынын"},
    {name:"Manufacturing cost per good unit",what:"Cost per good unit of production",how:"(Material + labor + overhead) / good output",shows:"Manufacturing unit cost"},
    "Требуется политика распределения накладных и согласованность НЗП."),
  EQUIPMENT_UTILIZATION_PCT:method("EQUIPMENT_UTILIZATION_PCT","100 × Σ(runHours) / Σ(plannedHours)",
    {name:"Загрузка оборудования, %",what:"Использование запланированного времени",how:"Часы работы / плановые часы × 100",shows:"Фактическую загрузку машины"},
    {name:"Жабдық жүктемесі, %",what:"Жоспарланған уақытты пайдалануды",how:"Жұмыс сағаты / жоспар сағаты × 100",shows:"Жабдықтың жүктелуін"},
    {name:"Equipment utilization, %",what:"Use of scheduled equipment hours",how:"Run hours / planned hours × 100",shows:"Equipment capacity usage"},
    "Не является OEE и не доказывает узкие места или неисправности оборудования.")
};
