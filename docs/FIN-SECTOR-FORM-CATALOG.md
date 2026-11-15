# FIN Project Marketplace — отраслевые формы экономических и операционных данных

**FIN-SECTOR-FORMS v1.0** · 2026-11-15 · **SPECIFIED / NOT IMPLEMENTED**  
**Ветка:** `docs/fin-marketplace-requirements-baseline` · **Охват:** 21 секция деятельности A–U, 63 отраслевые формы SF-* (по 3 на секцию), ИП/ТОО/АО и другие допустимые организации в пределах отраслевых правил. Отдельные виды деятельности пятизначного ОКЭД дорабатываются специализированными `SectorPack` по мере квалификации; таблицы ниже **не означают, что все виды ОКЭД уже поддерживаются математическим движком**.

**Основные источники:** [Общий классификатор ОКЭД РК — НК РК 03-2019, актуализация 01.07.2026](https://stat.gov.kz/ru/classifiers/statistical/21/); [переходные ключи ОКЭД](https://stat.gov.kz/ru/classifiers/sdapter/); [UN ISIC Rev.4 — структура 21 секции](https://unstats.un.org/unsd/demographic/sources/census/wphc/docs/ISIC_rev4.pdf). Эти источники задают классификационную основу, а **частоты/формулы ниже — предлагаемые продуктовые методики FIN, не установленные государством нормы отчётности**.

## 1. Как формируется анкета для конкретной компании

`BusinessProfile(country, classificationVersion, activityCode, stage=S0..S4, entityType, selectedGoals)` → [базовые 59 форм DF-*](FIN-BUSINESS-DATA-FORMS.md) → `SectorPack` с подходящими `SF-*` формами → [27 KPI RU/KK/EN](FIN-INDICATOR-DICTIONARY-RU-KK-EN.md) и новые версии проверенных отраслевых KPI → [FIN Business Health](FIN-BUSINESS-HEALTH.md) → объяснимые наблюдения/гипотезы → сценарии/DecisionPacket.

**На входе**: компания может иметь несколько видов деятельности и объектов. Применимость определяется `classificationId+code+version+effectiveDate`, а не строковым префиксом кода и не только юридической формой. Для ИП/ТОО/АО действует одно и то же экономическое ядро, но различаются source access, полномочия, юрисдикция, стандарты учёта, согласование отчётов. Сектора O/T/U имеют специальный scope: они не представляют типичный частный коммерческий бизнес, и их методики не должны механически навязываться ИП/ТОО/АО.

**Соглашения по периодам:** `E` событие, `D` день, `W` неделя, `M` месяц, `Q` квартал, `H` полугодие, `Y` год, `S` агросезон/учебный или другой натуральный цикл. В последней колонке `E/M` означает «ввод по событию, основной анализ за месяц». Подробная периодическая модель и исключения: [FIN Sector Cadence & Methodology](FIN-SECTOR-METHODS-PERIODICITY.md).

**Все перечисленные SF-показатели здесь CANDIDATE/PROPOSED**, а не готовые показатели. Выражения — предварительные семантические формулы, должны пройти checking units, denominator!=0, source/period consistency, versioned MethodRegistry, golden fixtures, 3 перевода RU/KK/EN и независимое методическое review. Клинические, технические, налоговые и пруденциальные заключения система не выдаёт автоматически.

## 2. Каталог 63 отраслевых таблиц ввода

### A — Сельское, лесное и рыбное хозяйство

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-A01 | Растениеводство: поля, посевы, урожай | farmId:REF;crop:CODE;season:CODE;cultivatedHa:DECIMAL;harvestT:DECIMAL;grade:CODE | farm×crop×season | CROP_YIELD_T_HA = harvestT / cultivatedHa | Какие культуры/поля окупают сезон? | DF-CTX-02;DF-OPS-01;DF-CST-02 | S/M |
| SF-A02 | Животноводство: поголовье, выпуск, корм | farmId:REF;animalGroup:CODE;headDays:DECIMAL;milkL:DECIMAL;feedKg:DECIMAL;feedCost:MONEY | farm×animalGroup×month | MILK_L_HEAD_DAY = milkL / headDays | Как изменяется продуктивность и кормовая себестоимость? | DF-OPS-02;DF-STK-01;DF-CST-02 | D/M |
| SF-A03 | Сельхозсезон: затраты, оборотные средства | crop:CODE;season:CODE;month:PERIOD;cashOut:MONEY;cashIn:MONEY;stockValue:MONEY | crop×month | AGRI_NET_CASH = cashIn - cashOut | Выдержит ли ферма межсезонье и сбор урожая? | DF-CSH-01;DF-CSH-02;DF-PRJ-03 | M/Q |

### B — Добыча полезных ископаемых

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-B01 | Добыча: объём и содержание сырья | site:REF;mineral:CODE;extractedT:DECIMAL;processedT:DECIMAL;recoveredT:DECIMAL | site×month | ORE_RECOVERY_PCT = 100 * recoveredT / processedT | Как меняется извлечение и объём пригодной продукции? | DF-OPS-01;DF-CST-02 | D/M |
| SF-B02 | Добыча: энергозатраты и себестоимость | site:REF;period:PERIOD;energyKWh:DECIMAL;fuelL:DECIMAL;totalDirectCost:MONEY;saleableT:DECIMAL | site×month | MINING_COST_T = totalDirectCost / saleableT | Где растёт себестоимость тонны? | DF-AST-04;DF-CST-02 | D/M |
| SF-B03 | Добыча: простои и обязательства по площадке | asset:REF;downtimeHours:DECIMAL;scheduledHours:DECIMAL;rehabilitationEstimate:MONEY;provisionBasis:CODE | site×quarter | MINING_DOWNTIME_PCT = 100 * downtimeHours / scheduledHours | Какая остановка или обязательство влияет на проект? | DF-OPS-03;DF-PRJ-05 | D/Q |

### C — Обрабатывающая промышленность

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-C01 | Производство: выпуск, брак, выход годного | plant:REF;product:REF;unitsStarted:DECIMAL;goodUnits:DECIMAL;reworkedUnits:DECIMAL;wasteUnits:DECIMAL | plant×product×shift | GOOD_YIELD_PCT = 100 * goodUnits / unitsStarted | Где потери качества и производительности? | DF-OPS-01;DF-OPS-03 | D/W |
| SF-C02 | Производство: спецификация и фактическая себестоимость | product:REF;materialUsed:MONEY;directLabor:MONEY;overhead:MONEY;goodUnits:DECIMAL;allocationBasis:CODE | product×month | UNIT_MFG_COST = (materialUsed + directLabor + overhead) / goodUnits | Какие изделия производить выгоднее? | DF-OPS-02;DF-CST-03 | E/M |
| SF-C03 | Производство: станки и загрузка | asset:REF;plannedHours:DECIMAL;runHours:DECIMAL;downtimeHours:DECIMAL;unitsMade:DECIMAL | asset×shift | EQUIPMENT_UTILIZATION_PCT = 100 * runHours / plannedHours | Нужно ли оборудование или устранить простои? | DF-OPS-01;DF-AST-02 | D/W |

### D — Электроэнергия, газ и тепло

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-D01 | Энергетика: генерация и реализация | plant:REF;generatedMWh:DECIMAL;meteredDeliveredMWh:DECIMAL;unbilledMWh:DECIMAL;period:PERIOD | plant×day | DELIVERY_YIELD_PCT = 100 * meteredDeliveredMWh / generatedMWh | Где разница между генерацией и поставкой? | DF-AST-04;DF-SAL-01 | D/M |
| SF-D02 | Энергетика: топливо и расходные ресурсы | plant:REF;fuelGJ:DECIMAL;generatedMWh:DECIMAL;fuelCost:MONEY | plant×month | FUEL_GJ_MWH = fuelGJ / generatedMWh | Какие станции имеют больший удельный расход? | DF-AST-04;DF-CST-02 | D/M |
| SF-D03 | Энергетика: простои и ремонты | asset:REF;availableHours:DECIMAL;scheduledHours:DECIMAL;repairCost:MONEY;month:PERIOD | asset×month | AVAILABILITY_PCT = 100 * availableHours / scheduledHours | Что ремонтировать до следующего пика спроса? | DF-AST-02;DF-PRJ-02 | D/Q |

### E — Вода, канализация, отходы

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-E01 | Водоснабжение: баланс воды | network:REF;waterInputM3:DECIMAL;authorizedDeliveredM3:DECIMAL;unbilledAuthorizedM3:DECIMAL | network×month | NON_REVENUE_WATER_PCT = 100 * (waterInputM3 - billedVolumeM3) / waterInputM3 (requires billedVolumeM3) | Где теряется выручка от поставленной воды? | DF-AST-04;DF-SAL-01 | D/M |
| SF-E02 | Канализация и очистка: объём и затраты | plant:REF;treatedM3:DECIMAL;processCost:MONEY;energyKWh:DECIMAL;complianceEvidence:REF | plant×month | TREATMENT_COST_M3 = processCost / treatedM3 | Как сократить стоимость обработки? | DF-OPS-01;DF-AST-04 | D/M |
| SF-E03 | Переработка отходов: сортировка и выход | facility:REF;incomingT:DECIMAL;recoveredT:DECIMAL;disposedT:DECIMAL;period:PERIOD | facility×month | RECOVERY_SHARE_PCT = 100 * recoveredT / incomingT | Как увеличить полезный выход материала? | DF-OPS-03;DF-STK-03 | D/M |

### F — Строительство

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-F01 | Строительство: выполненный объём и акты | project:REF;wbsCode:CODE;plannedQty:DECIMAL;actualQty:DECIMAL;certifiedQty:DECIMAL;unit:CODE | project×WBS×month | CERTIFIED_SHARE_PCT = 100 * certifiedQty / actualQty | Почему работа выполнена, но не оплачена? | DF-PRJ-06;DF-CSH-04 | W/M |
| SF-F02 | Строительство: бюджет, смета, затраты | project:REF;wbsCode:CODE;budgetCost:MONEY;actualCost:MONEY;committedCost:MONEY | project×WBS×month | COST_OVERRUN_PCT = 100 * (actualCost - budgetCost) / budgetCost (comparable WBS scope) | Какой этап превышает бюджет? | DF-PRJ-02;DF-CST-01 | W/M |
| SF-F03 | Строительство: удержания и cash gap | project:REF;invoiceGross:MONEY;retention:MONEY;receipts:MONEY;dueAt:DATE;period:PERIOD | project×month | CONSTRUCTION_NET_COLLECTION = receipts - retention (not revenue) | Когда не хватает оборотных средств? | DF-CSH-01;DF-CSH-04 | E/M |

### G — Торговля и ремонт транспорта

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-G01 | Розница и опт: продажи и маржа SKU | store:REF;sku:REF;netRevenue:MONEY;cogs:MONEY;soldQty:DECIMAL;period:PERIOD | store×sku×month | SKU_GROSS_MARGIN_PCT = 100 * (netRevenue - cogs) / netRevenue | Что оставить в ассортименте? | DF-SAL-01;DF-SAL-03;DF-CST-02 | E/M |
| SF-G02 | Торговля: остатки, оборачиваемость, сроки годности | store:REF;sku:REF;stockQty:DECIMAL;avgStockValue:MONEY;periodCogs:MONEY;expiryAt:DATE | store×sku×asOf/month | SKU_STOCK_TURN = periodCogs / avgStockValue | Что заказать или распродать до списания? | DF-STK-01;DF-STK-03 | D/W |
| SF-G03 | Торговля: поставщик, логистика, скидка | supplier:REF;sku:REF;purchasePrice:MONEY;freight:MONEY;receivedQty:DECIMAL;leadDays:DECIMAL | supplier×sku×month | LANDED_UNIT_COST = (purchasePrice * receivedQty + freight) / receivedQty | У кого выгоднее закупать? | DF-STK-02;DF-STK-04 | E/M |

### H — Транспорт и складирование

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-H01 | Транспорт: рейсы, загрузка, расстояния | trip:REF;distanceKm:DECIMAL;loadedKm:DECIMAL;shipmentQty:DECIMAL;fuelL:DECIMAL;tripCost:MONEY | trip | LOADED_KM_SHARE_PCT = 100 * loadedKm / distanceKm | Какие рейсы убыточны или пустые? | DF-OPS-05;DF-CST-02 | E/W |
| SF-H02 | Логистика: соблюдение сроков | shipment:REF;promisedAt:DATE;deliveredAt:DATE;deliveredComplete:BOOLEAN;damageQty:DECIMAL | shipment | ON_TIME_DELIVERY_PCT = 100 * onTimeComplete / completedShipments | Где нарушаются сроки доставки? | DF-SAL-02;DF-OPS-05 | E/M |
| SF-H03 | Складской оператор: площадь и обработка | warehouse:REF;processedOrders:DECIMAL;laborHours:DECIMAL;storageM2:DECIMAL;occupancyM2:DECIMAL | warehouse×week | ORDER_LINES_PER_HOUR = processedOrderLines / laborHours (requires lines) | Когда нужен персонал или площадь? | DF-HR-02;DF-STK-01 | D/W |

### I — Гостиницы и общественное питание

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-I01 | Ресторан и кафе: блюда, продажи, food cost | restaurant:REF;menuItem:REF;portions:DECIMAL;netSales:MONEY;ingredientCogs:MONEY;period:PERIOD | menuItem×day | FOOD_COST_PCT = 100 * ingredientCogs / netSales | Какие блюда портят маржу? | DF-SAL-01;DF-CST-02 | D/W |
| SF-I02 | Ресторан и кафе: списания, кухня и очередь | restaurant:REF;wasteCost:MONEY;foodPurchased:MONEY;ordersMade:DECIMAL;kitchenHours:DECIMAL | restaurant×day | WASTE_TO_PURCHASE_PCT = 100 * wasteCost / foodPurchased (context warning) | Какие закупки и процессы дают потери? | DF-STK-03;DF-OPS-04 | D/W |
| SF-I03 | Гостиницы: номера, загрузка, доход | hotel:REF;availableRoomNights:DECIMAL;soldRoomNights:DECIMAL;roomRevenue:MONEY;period:PERIOD | hotel×day | OCCUPANCY_PCT = 100 * soldRoomNights / availableRoomNights | Когда менять тариф и маркетинг? | DF-SAL-01;DF-AST-03 | D/M |

### J — Информация и связь

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-J01 | SaaS/IT: подписки, изменения MRR | plan:REF;newMrr:MONEY;expansionMrr:MONEY;churnMrr:MONEY;contractionMrr:MONEY;priorMrr:MONEY | product×month | MRR_END = priorMrr + newMrr + expansionMrr - churnMrr - contractionMrr | Что вызывает падение повторяемой выручки? | DF-SAL-01;DF-CUS-02 | E/M |
| SF-J02 | IT-проекты: часы, бюджет, работа | deliveryProject:REF;budgetHours:DECIMAL;actualHours:DECIMAL;costRate:MONEY;approvedScope:REF | project×month | DELIVERY_LABOR_COST = actualHours * costRate | Какие проекты и контракты убыточны? | DF-OPS-04;DF-HR-02 | W/M |
| SF-J03 | IT-инфраструктура: инциденты и доступность | service:REF;scheduledMinutes:DECIMAL;unavailableMinutes:DECIMAL;incidentCount:INTEGER;period:PERIOD | service×month | SERVICE_AVAILABILITY_PCT = 100 * (scheduledMinutes - unavailableMinutes) / scheduledMinutes | Что ухудшает выполнение SLA? | DF-OPS-03;DF-CUS-03 | D/M |

### K — Финансы и страхование

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-K01 | Банки: риск портфеля (только управленческий) | portfolio:REF;exposure:MONEY;impairedExposure:MONEY;overdueExposure:MONEY;asOf:DATE | portfolio×asOf | OVERDUE_EXPOSURE_SHARE_PCT = 100 * overdueExposure / exposure | Где концентрация кредитного риска? | DF-CSH-04;DF-PRJ-05 | D/M |
| SF-K02 | Страхование: премии и выплаты | product:REF;earnedPremium:MONEY;incurredClaims:MONEY;period:PERIOD;basis:CODE | product×quarter | CLAIMS_RATIO_PCT = 100 * incurredClaims / earnedPremium | Какие страховые продукты убыточны? | DF-SAL-01;DF-CST-01 | M/Q |
| SF-K03 | Финансовые услуги: стоимость операций | businessLine:REF;processedCount:INTEGER;directCost:MONEY;overheadAllocated:MONEY;period:PERIOD | businessLine×month | COST_PER_TRANSACTION = (directCost + overheadAllocated) / processedCount | Как оптимизировать операционную стоимость? | DF-OPS-04;DF-CST-03 | D/M |

### L — Недвижимость

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-L01 | Недвижимость: аренда, площади и вакантность | property:REF;leasableM2:DECIMAL;occupiedM2:DECIMAL;leasedRevenue:MONEY;month:PERIOD | property×month | VACANCY_PCT = 100 * (leasableM2 - occupiedM2) / leasableM2 | Где простаивает площадь? | DF-AST-03;DF-SAL-01 | M/Q |
| SF-L02 | Недвижимость: эксплуатационные затраты | property:REF;rentRevenue:MONEY;operatingPropertyCost:MONEY;period:PERIOD | property×month | PROPERTY_NOI = rentRevenue - operatingPropertyCost (scope/version required) | Как повысить операционный доход объекта? | DF-AST-03;DF-CST-01 | M/Q |
| SF-L03 | Девелопмент: продажи и CAPEX строительства | project:REF;unitsSold:INTEGER;totalUnits:INTEGER;capexSpent:MONEY;capexBudget:MONEY | project×quarter | PRE_SALES_SHARE_PCT = 100 * unitsSold / totalUnits | Достаточен ли спрос для строительства? | DF-PRJ-02;DF-PRJ-03 | M/Q |

### M — Профессиональные, научные и технические услуги

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-M01 | Консалтинг и профуслуги: оплачиваемые часы | team:REF;billableHours:DECIMAL;availableHours:DECIMAL;period:PERIOD | team×month | BILLABLE_UTILIZATION_PCT = 100 * billableHours / availableHours | Нужно ли менять загрузку команды? | DF-HR-02;DF-OPS-04 | W/M |
| SF-M02 | Профессиональные услуги: маржа проекта | engagement:REF;recognizedFee:MONEY;directHours:DECIMAL;hourCost:MONEY;otherCost:MONEY | engagement×month | ENGAGEMENT_CONTRIBUTION = recognizedFee - directHours * hourCost - otherCost | Какие контракты прибыльны? | DF-OPS-04;DF-CST-03 | E/M |
| SF-M03 | Научные и инженерные проекты: этапы | project:REF;milestone:REF;budget:MONEY;actualCost:MONEY;acceptanceStatus:CODE | project×quarter | MILESTONE_COST_VARIANCE = actualCost - budget | На каких этапах перерасход? | DF-PRJ-06;DF-PRJ-02 | M/Q |

### N — Административные и вспомогательные услуги

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-N01 | Подрядный персонал: смены, расходы, оплата | contract:REF;providedHours:DECIMAL;paidHours:DECIMAL;customerBilledHours:DECIMAL;wageCost:MONEY | contract×week | BILLED_HOUR_SHARE_PCT = 100 * customerBilledHours / providedHours | Где недооплаченные часы? | DF-HR-02;DF-SAL-01 | D/W |
| SF-N02 | Аренда оборудования: загрузка и стоимость | asset:REF;rentedDays:DECIMAL;availableDays:DECIMAL;revenue:MONEY;maintenanceCost:MONEY | asset×month | RENTAL_UTILIZATION_PCT = 100 * rentedDays / availableDays | Какой парк техники окупается? | DF-AST-01;DF-AST-02 | E/M |
| SF-N03 | Уборка/охрана: заявки и качество | contract:REF;scheduledJobs:INTEGER;completedJobs:INTEGER;reworkJobs:INTEGER;serviceHours:DECIMAL | contract×month | SERVICE_COMPLETION_PCT = 100 * completedJobs / scheduledJobs | Нужны ли дополнительные исполнители? | DF-OPS-04;DF-CUS-03 | D/M |

### O — Государственное управление

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-O01 | Государственные услуги: исполнение бюджета | program:REF;approvedBudget:MONEY;actualSpend:MONEY;period:PERIOD | program×quarter | BUDGET_EXECUTION_PCT = 100 * actualSpend / approvedBudget | Где бюджет не исполняется? | DF-PRJ-06;DF-CST-01 | M/Q |
| SF-O02 | Государственные услуги: сроки обращений | service:REF;submittedCases:INTEGER;resolvedCases:INTEGER;onTimeCases:INTEGER;period:PERIOD | service×month | ON_TIME_PUBLIC_SERVICE_PCT = 100 * onTimeCases / resolvedCases | Где задерживаются обращения? | DF-CUS-03;DF-OPS-04 | D/M |
| SF-O03 | Госпрограммы: целевые результаты | program:REF;targetCount:DECIMAL;achievedCount:DECIMAL;coverageGroup:CODE;period:PERIOD | program×half-year | PROGRAM_TARGET_SHARE_PCT = 100 * achievedCount / targetCount | Какова фактическая результативность? | DF-CTX-04;DF-PRJ-06 | Q/H |

### P — Образование

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-P01 | Образование: учащиеся и посещаемость | program:REF;enrolledLearners:INTEGER;attendedLearnerSessions:INTEGER;offeredLearnerSessions:INTEGER | program×month | ATTENDANCE_PCT = 100 * attendedLearnerSessions / offeredLearnerSessions | Какие группы теряют вовлечённость? | DF-CUS-02;DF-OPS-04 | D/M |
| SF-P02 | Образование: группы и преподавательская нагрузка | course:REF;teachingHours:DECIMAL;availableHours:DECIMAL;groupCount:INTEGER;teacherCost:MONEY | course×term | TEACHING_UTILIZATION_PCT = 100 * teachingHours / availableHours | Как оптимизировать расписание и группы? | DF-HR-02;DF-CST-02 | W/Q |
| SF-P03 | Образование: результаты и себестоимость | cohort:REF;completedLearners:INTEGER;eligibleLearners:INTEGER;programCost:MONEY | cohort×term | COMPLETION_PCT = 100 * completedLearners / eligibleLearners | Какие программы эффективнее при сопоставимости? | DF-CST-01;DF-CUS-02 | Q/H |

### Q — Здравоохранение и социальная помощь

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-Q01 | Медицина: записи, посещения и неявки | clinic:REF;appointments:INTEGER;attended:INTEGER;cancelled:INTEGER;noShows:INTEGER;period:PERIOD | clinic×week | NO_SHOW_PCT = 100 * noShows / appointments | Где простаивают кабинеты? | DF-OPS-04;DF-CUS-03 | D/W |
| SF-Q02 | Медицина: процедура и её затраты | procedure:REF;completedCount:INTEGER;consumablesCost:MONEY;laborCost:MONEY;overhead:MONEY | procedure×month | COST_PER_PROCEDURE = (consumablesCost + laborCost + overhead) / completedCount | Какие процедуры требуют пересмотра стоимости? | DF-CST-02;DF-HR-02 | D/M |
| SF-Q03 | Медицина: кабинеты, смены и оборудование | room:REF;availableHours:DECIMAL;bookedHours:DECIMAL;downtimeHours:DECIMAL;period:PERIOD | room×week | ROOM_UTILIZATION_PCT = 100 * bookedHours / availableHours | Нужны ли дополнительные кабинеты? | DF-AST-01;DF-OPS-03 | D/W |

### R — Искусство, развлечения, спорт

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-R01 | Мероприятия: билеты и вместимость | event:REF;ticketsSold:INTEGER;availableTickets:INTEGER;netTicketRevenue:MONEY;period:PERIOD | event | TICKET_SELLTHROUGH_PCT = 100 * ticketsSold / availableTickets | Какой размер площадки выбирать? | DF-SAL-01;DF-PRJ-03 | E/W |
| SF-R02 | Мероприятия: затраты и окупаемость | event:REF;eventRevenue:MONEY;venueCost:MONEY;productionCost:MONEY;marketingCost:MONEY | event | EVENT_CONTRIBUTION = eventRevenue - venueCost - productionCost - marketingCost | Какие мероприятия окупаются? | DF-CST-01;DF-MKT-01 | E/M |
| SF-R03 | Досуг и спорт: абонементы и удержание | facility:REF;startingMembers:INTEGER;renewedMembers:INTEGER;newMembers:INTEGER;period:PERIOD | facility×month | MEMBER_RENEWAL_PCT = 100 * renewedMembers / startingMembers | Почему падает продление подписок? | DF-CUS-02;DF-SAL-01 | M/Q |

### S — Прочие услуги

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-S01 | Бытовые услуги: заказы и стоимость | service:REF;completedJobs:INTEGER;netRevenue:MONEY;directLaborCost:MONEY;materialsCost:MONEY | service×month | SERVICE_UNIT_MARGIN = (netRevenue - directLaborCost - materialsCost) / completedJobs | Какие услуги выгоднее? | DF-OPS-04;DF-CST-02 | E/M |
| SF-S02 | Салоны/ремонт: время и повторные обращения | branch:REF;bookingCount:INTEGER;completedCount:INTEGER;repeatedClients:INTEGER;workHours:DECIMAL | branch×week | BOOKING_COMPLETION_PCT = 100 * completedCount / bookingCount | Почему не заполняется расписание? | DF-CUS-02;DF-OPS-04 | D/W |
| SF-S03 | Профессиональные ассоциации: взносы и участники | association:REF;dueMembers:INTEGER;paidMembers:INTEGER;duesCollected:MONEY;period:PERIOD | association×quarter | DUES_COLLECTION_PCT = 100 * paidMembers / dueMembers | Хватает ли регулярных поступлений? | DF-CSH-01;DF-CUS-02 | M/Q |

### T — Домашние хозяйства

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-T01 | Домашние хозяйства: бюджет и расходы | household:REF;period:PERIOD;income:MONEY;expenses:MONEY;savings:MONEY | household×month | HOUSEHOLD_SAVINGS_RATE = 100 * savings / income | Какова финансовая устойчивость домохозяйства? | DF-CSH-01;DF-CSH-02 | M/Q |
| SF-T02 | Домашняя занятость: рабочие часы | household:REF;workHours:DECIMAL;wagePaid:MONEY;period:PERIOD | household×month | DOMESTIC_LABOR_COST_H = wagePaid / workHours | Как планировать расходы домашнего работодателя? | DF-HR-02;DF-CST-01 | M/Q |
| SF-T03 | Домашнее производство для собственного использования | household:REF;product:CODE;qtyProduced:DECIMAL;inputCost:MONEY;period:PERIOD | household×season | HOME_UNIT_COST = inputCost / qtyProduced | Как оценить ресурсные затраты без ложной выручки? | DF-OPS-01;DF-CST-02 | S/Y |

### U — Экстерриториальные организации

| Код | Таблица / бизнес-событие | Поля (типизированные) | Зернистость | Предлагаемый KPI и формула | Решаемый вопрос | Базовые DF-формы | Сбор / анализ |
|---|---|---|---|---|---|---|---|
| SF-U01 | Международные организации: бюджет и программы | program:REF;annualBudget:MONEY;spendToDate:MONEY;period:PERIOD;fundingSource:CODE | program×quarter | PROGRAM_SPEND_SHARE = 100 * spendToDate / annualBudget | Как выполняется программа? | DF-PRJ-06;DF-CST-01 | M/Q |
| SF-U02 | Международные организации: целевые показатели | program:REF;targetOutputs:DECIMAL;achievedOutputs:DECIMAL;period:PERIOD;methodVersion:CODE | program×half-year | OUTPUT_TARGET_PCT = 100 * achievedOutputs / targetOutputs | Как достигаются результаты программы? | DF-CTX-04;DF-PRJ-06 | Q/H |
| SF-U03 | Международные организации: договоры и гранты | program:REF;committedFunds:MONEY;receivedFunds:MONEY;unspentFunds:MONEY;period:PERIOD | program×quarter | COMMITTED_FUNDING_GAP = committedFunds - receivedFunds | Какие программы зависят от ожидаемых средств? | DF-CSH-01;DF-PRJ-04 | E/Q |

## 3. Правила вычисления и агрегации

- **Операции** собираются \`E/D\` (чеки, рейсы, записи, производство, платежи). Их **аналитика** обычно W/M, а не ручной перенос в новые месячные таблицы.
- **Потоки** (\`netSales, volume, costs, hours\`) суммируются за непрерывные интервалы при согласованных категориях и валютах. **Остатки** (\`inventory, account balance, outstanding debt\`) фиксируются \`AS_OF\`, не складываются за месяцы. Средние для знаменателей должны быть weighted/time-weighted при необходимости.
- **Ratio** за квартал/полугодие/год рассчитывать из **суммарного числителя и суммарного знаменателя**, а не арифметическим средним 3/6/12 месячных процентов. Пример: доля брака = \`sum(defectiveQty)/sum(startedQty)\`. Не усреднять KPI по объектам без весов.
- **Cohort/vintage** привязаны к месяцу или событию привлечения; сезонные культуры — к агросезону, школьные группы — к семестру, проекты — к стадии; при календарном сравнении указать несопоставимость.
- **Проектные расходы** не превращать автоматически в P&L: CAPEX vs OPEX, actual vs plan, cash vs accrual, earned vs invoiced в строительстве, подписочный MRR vs признанная выручка.
- **Мультивалютность:** денежные KPI не складываются без курса, даты и rate source; units проходят проверку (кВт⋅ч ≠ МВт⋅ч; т ≠ кг; час ≠ смена), бухгалтерская и управленческая базы различаются.
- **SENSITIVE/REGULATED:** K (банки/страхование), Q (здоровье), O (государство) и U (международные организации) требуют специальной методической, правовой и data-security квалификации; расчёт управленческого коэффициента **не** означает выполнение пруденциального, клинического, статистического или законодательного требования.
- **Компании со смешанной деятельностью:** \`SectorPack\` per businessUnit/project, \`segmentId\`, elimination when consolidating, не создавать дважды общий расход или выручку.
- **Необязательная AI/ML помощь:** версия Tribuo/ONNX могут выявлять тренды и аномалии только после baseline evaluation; финансовые формулы остаются детерминированными, модели не назначают себе факты или регуляторный статус. Данные компаний не используют для внешнего обучения по умолчанию.

## 4. Допуск отраслевого пакета к релизу

\`SectorPackDefinition\` хранит \`packId, classificationName, classificationVersion, activityCodePredicate, effectiveFrom, effectiveTo, country, supportedStages, businessModel, requiredDF, optionalDF, sectorForms[], methodIds[], decisionIds[], sourceReferences[], cadencePolicyVersion, languageKeys[ru,kk,en], approvalStatus\`.

**CoverageState**: \`CATALOG_ONLY\` (только отраслевой поиск) → \`GENERIC_ANALYSIS\` (базовые финансовые 27 KPI) → \`SECTOR_FORMS_DRAFT\` (таблицы SF, расчёты только кандидаты) → \`METHOD_QUALIFIED\` (золотые фикстуры и проверенные методики) → \`UAT_ACCEPTED\`. Само наличие SF-форм в этом ТЗ означает лишь \`SECTOR_FORMS_DRAFT\`, **не** \`METHOD_QUALIFIED\`.

Приоритет пилотов: 1) торговля G; 2) услуги M/S; 3) производство C; 4) рестораны I; 5) сельское хозяйство A; 6) строительство F; далее H/J/P/Q/L и остальные по потребностям пользователей. Это про **качество исполнения**, а не навязывание отдельных отраслевых релизов каждому ИП.

**Документная связь:** [отраслевые периоды и примеры](FIN-SECTOR-METHODS-PERIODICITY.md), [59 базовых DF-форм](FIN-BUSINESS-DATA-FORMS.md), [44 F/C/P/T/D форм](FORM-CATALOG.md), [27 KPI RU/KK/EN](FIN-INDICATOR-DICTIONARY-RU-KK-EN.md). Ни одна таблица не публикуется внешним адресатам автоматически: зона доступа, version/field scope и expiry определяются через [ADR-009](ADR-009-REPORT-ACCESS-ZONES.md).
