# FIN Project Marketplace — каталог исходных данных для экономического анализа и бизнес-решений

**FIN-DATA-FORMS v1.0** · 2026-11-15 · Ветка `docs/fin-marketplace-requirements-baseline` · **SPECIFIED / NOT IMPLEMENTED**.

## 1. Цель и принципы

Этот реестр содержит **59 форм первичных бизнес-данных** для **ИП, ТОО и АО**, а также новых организаций без финансовой истории (S0). Он дополняет, а **не заменяет**, существующие [44 формы F/C/P/T/D](FORM-CATALOG.md). Исходные данные поступают в единую каноническую модель и используются для [FIN Business Health](FIN-BUSINESS-HEALTH.md), [Indicator Dictionary RU/KK/EN](FIN-INDICATOR-DICTIONARY-RU-KK-EN.md), «Моего бизнеса», стратегических проектов, отчетов и адресного обмена материалами.

**59 форм — это логические типы входа, не 59 обязательных Excel-файлов или экранов.** Пользователь выбирает цель, а wizard запрашивает минимум подходящих данных. Первое значение метрики не обязано означать готовую диагностическую модель: если данных недостаточно, указывается `NOT_COMPUTABLE` или `NOT_APPLICABLE`.

В колонке KPI код **без пометы** существует как описание в трёхъязычном словаре из 27 KPI, но ещё не обязательно реализован в коде. **(PROPOSED)** — новый кандидат, требующий формулы, RU/KK/EN переводов и golden-тестов. `Zxx` — будущие зоны доступа по [ADR-009](ADR-009-REPORT-ACCESS-ZONES.md).

**Отраслевые расширения:** [SF-формы для 21 секции / 63 таблицы](FIN-SECTOR-FORM-CATALOG.md) подключаются после выбора страны, отрасли и цели. [CadencePolicy](FIN-SECTOR-METHODS-PERIODICITY.md) определяет частоту сбора и анализа (месяц, квартал, полугодие, год, сезон). `DF-*` обеспечивает общие данные, `SF-*` — специальные процессы, `F/C/P/T/D` — отчёты и документы; один канонический факт используется во всех слоях.

## 2. Каталог форм — 59 записей

### Профиль и контекст (5)

| FormCode | Данные | Минимальные поля и типы | KPI / статус | Решение, которое поддерживает | Связь с F/C/P/B/D/T | Поставка | Зона |
|---|---|---|---|---|---|---|---|
| `DF-CTX-01` | Профиль бизнеса, страна, отрасль | country:CODE;legalForm:ENUM;industryCode:CODE;stage:ENUM;currency:CODE | — | Какие методы применимы? | B01 | G1 | Z10 |
| `DF-CTX-02` | Филиалы, направления и сегменты | branch:REF;segment:REF;location:CODE;effectiveFrom:DATE | SEGMENT_MARGIN (PROPOSED) | Где развивается бизнес? | B01;F07 | G2 | Z40 |
| `DF-CTX-03` | Периоды, валюта, модель учёта | periodStart:DATE;periodEnd:DATE;currency:CODE;basis:ENUM;fxSource:REF | — | Сопоставимы ли данные? | B01;F01-F03 | G1 | Z20 |
| `DF-CTX-04` | Цели и ограничения собственника | goal:CODE;horizon:INTEGER;minimumCash:MONEY;riskTolerance:ENUM | CASH_COVER_30D | Что изменить прежде всего? | B01;P08 | G1 | Z60 |
| `DF-CTX-05` | Группа компаний и связанные стороны | owner:REF;relatedEntity:REF;ownership:DECIMAL;relation:ENUM | GROUP_EXPOSURE (PROPOSED) | Нужны ли корректировки консолидации? | F08;C14 | G4 | Z70 |

### Продажи и цены (6)

| FormCode | Данные | Минимальные поля и типы | KPI / статус | Решение, которое поддерживает | Связь с F/C/P/B/D/T | Поставка | Зона |
|---|---|---|---|---|---|---|---|
| `DF-SAL-01` | Реализованные товары и услуги | saleRef:REF;date:DATE;sku:REF;quantity:DECIMAL;price:MONEY;discount:MONEY;clientAlias:REF | REV_NET;AVG_ORDER | На чём зарабатываем выручку? | B02;C07;F02 | G1 | Z40 |
| `DF-SAL-02` | Заказы, выполнение и отказы | orderRef:REF;orderedAt:DATE;dueAt:DATE;orderedQty:DECIMAL;deliveredQty:DECIMAL | BACKLOG_RATE (PROPOSED) | Почему заказы не исполняются? | B02;P02 | G2 | Z40 |
| `DF-SAL-03` | Возвраты и коррекции продаж | saleRef:REF;returnedAt:DATE;quantity:DECIMAL;refund:MONEY;reason:CODE | RETURNS_RATE;REV_NET | Что уменьшает выручку? | B02;C07 | G1 | Z40 |
| `DF-SAL-04` | Цены и правила скидок | sku:REF;effectiveFrom:DATE;listPrice:MONEY;finalPrice:MONEY;discountRule:CODE | CONTRIBUTION_MARGIN | Можно ли менять цену? | B03;C08 | G2 | Z40 |
| `DF-SAL-05` | Каналы и регионы продаж | channel:CODE;region:CODE;netSales:MONEY;fee:MONEY;returns:MONEY | CHANNEL_CONTRIBUTION (PROPOSED) | Какой канал окупается? | B02;B11 | G2 | Z40 |
| `DF-SAL-06` | Смены и эффективность торговли | shift:REF;branch:REF;netSales:MONEY;workHours:DECIMAL | SALES_PER_HOUR (PROPOSED) | Как распределять смены? | B02;B09 | G2 | Z40 |

### Деньги и обязательства (6)

| FormCode | Данные | Минимальные поля и типы | KPI / статус | Решение, которое поддерживает | Связь с F/C/P/B/D/T | Поставка | Зона |
|---|---|---|---|---|---|---|---|
| `DF-CSH-01` | Денежные поступления | paymentRef:REF;postedAt:DATE;amount:MONEY;purpose:CODE;invoiceRef:REF | CASH_IN_OP | Почему продажи не равны оплатам? | B05;F03;C06 | G1 | Z30 |
| `DF-CSH-02` | Денежные выплаты | paymentRef:REF;postedAt:DATE;amount:MONEY;category:CODE;obligationRef:REF | CASH_OUT_OP;NET_CASH_FLOW | Какие платежи создают разрыв? | B05;F03;C06 | G1 | Z30 |
| `DF-CSH-03` | Счета, остатки и резерв | accountAlias:REF;asOf:DATE;balance:MONEY;restricted:MONEY;reserve:MONEY | CASH_END;CASH_COVER_30D;CASH_RUNWAY | Хватит ли доступных денег? | B05;F01 | G1 | Z80 |
| `DF-CSH-04` | Дебиторка и сроки оплаты | invoiceRef:REF;clientAlias:REF;dueAt:DATE;recognized:MONEY;received:MONEY | AR_END;AR_OVERDUE_SHARE | Кому напомнить об оплате? | B06;C01 | G1 | Z30 |
| `DF-CSH-05` | Кредиторка и платежный календарь | billRef:REF;supplierAlias:REF;dueAt:DATE;amount:MONEY;paid:MONEY | AP_END;CASH_COVER_30D | Какие платежи критичны? | B07;C02 | G1 | Z30 |
| `DF-CSH-06` | Кредиты и лизинг: график | contractRef:REF;dueAt:DATE;principal:MONEY;interest:MONEY;balance:MONEY | DEBT_SERVICE_COVER | Выдержим ли выплаты по долгу? | B10;C05;P04 | G2 | Z30 |

### Себестоимость и налоги (5)

| FormCode | Данные | Минимальные поля и типы | KPI / статус | Решение, которое поддерживает | Связь с F/C/P/B/D/T | Поставка | Зона |
|---|---|---|---|---|---|---|---|
| `DF-CST-01` | Постоянные и переменные расходы | period:PERIOD;costCenter:REF;category:CODE;amount:MONEY;behavior:ENUM | OPERATING_PROFIT;BREAK_EVEN_REV | Где можно снизить затраты? | B04;C09 | G1 | Z30 |
| `DF-CST-02` | Себестоимость реализации | sku:REF;period:PERIOD;costOfDelivered:MONEY;units:DECIMAL;costPolicy:CODE | COGS;GROSS_PROFIT;GROSS_MARGIN | Какие товары действительно прибыльны? | B03;C08 | G1 | Z50 |
| `DF-CST-03` | Распределение накладных | costPool:REF;amount:MONEY;driver:CODE;driverUnits:DECIMAL | CONTRIBUTION_PROFIT | Правильно ли распределена себестоимость? | B04;C08 | G2 | Z50 |
| `DF-CST-04` | Вложения и изъятия владельца | ownerAlias:REF;eventAt:DATE;amount:MONEY;kind:ENUM | OWNER_DRAWS (IP only) | Не истощают ли изъятия оборотный капитал? | B14;F04 | G1 | Z30 |
| `DF-CST-05` | Налоговые начисления и платежи | jurisdiction:CODE;period:PERIOD;taxType:CODE;assessed:MONEY;paid:MONEY;dueAt:DATE | TAX_CASH_GAP (PROPOSED) | Какие платежи запланировать? | B13;C10;T01-T04 | G2 | Z30 |

### Склад и закупки (5)

| FormCode | Данные | Минимальные поля и типы | KPI / статус | Решение, которое поддерживает | Связь с F/C/P/B/D/T | Поставка | Зона |
|---|---|---|---|---|---|---|---|
| `DF-STK-01` | Остатки по SKU и складам | sku:REF;warehouse:REF;asOf:DATE;qty:DECIMAL;costValue:MONEY | INV_TURNOVER;INVENTORY_DAYS | Где заморожены оборотные средства? | B08;C03 | G2 | Z50 |
| `DF-STK-02` | Закупки и поставщики | purchaseRef:REF;sku:REF;orderedAt:DATE;receivedAt:DATE;qty:DECIMAL;price:MONEY | DELIVERY_VARIANCE (PROPOSED) | Кто надежнее поставляет? | B08;C12 | G2 | Z50 |
| `DF-STK-03` | Списания и потери | sku:REF;eventAt:DATE;qty:DECIMAL;reason:CODE;unitCost:MONEY | SHRINKAGE_RATE (PROPOSED) | Почему списания растут? | B08;C03 | G2 | Z50 |
| `DF-STK-04` | Поставки, доставка, сроки | supplierAlias:REF;leadDays:INTEGER;minOrder:DECIMAL;freight:MONEY;paymentTerm:CODE | LANDED_COST (PROPOSED) | Какой поставщик выгоднее? | B08;C12 | G2 | Z70 |
| `DF-STK-05` | Потребность и риск дефицита | sku:REF;week:PERIOD;demandQty:DECIMAL;onHand:DECIMAL;onOrder:DECIMAL;safetyStock:DECIMAL | STOCKOUT_RISK (PROPOSED) | Что закупить заранее? | B08;P06 | G2 | Z50 |

### Клиенты и контракты (4)

| FormCode | Данные | Минимальные поля и типы | KPI / статус | Решение, которое поддерживает | Связь с F/C/P/B/D/T | Поставка | Зона |
|---|---|---|---|---|---|---|---|
| `DF-CUS-01` | Клиентские сегменты | segment:CODE;period:PERIOD;revenue:MONEY;orders:INTEGER;activeClients:INTEGER | CLIENT_TOP1_SHARE (with client-level data) | Какие клиенты важнее? | B12;C12 | G2 | Z40 |
| `DF-CUS-02` | Повторные продажи и удержание | cohort:CODE;period:PERIOD;startingClients:INTEGER;repeatClients:INTEGER;lostClients:INTEGER | RETENTION_RATE (PROPOSED) | Почему клиенты уходят? | B12 | G2 | Z40 |
| `DF-CUS-03` | Жалобы и качество обслуживания | ticketRef:REF;openedAt:DATE;category:CODE;closedAt:DATE;resolution:CODE | COMPLAINT_RATE (PROPOSED) | Где ухудшается сервис? | B12 | G2 | Z40 |
| `DF-CUS-04` | Ключевые договоры с клиентами | contractRef:REF;clientAlias:REF;validFrom:DATE;validTo:DATE;recognizedRevenue:MONEY | CLIENT_TOP1_SHARE | Как снизить зависимость от клиента? | C12;D05 | G2 | Z70 |

### Маркетинг и спрос (4)

| FormCode | Данные | Минимальные поля и типы | KPI / статус | Решение, которое поддерживает | Связь с F/C/P/B/D/T | Поставка | Зона |
|---|---|---|---|---|---|---|---|
| `DF-MKT-01` | Рекламные расходы | campaign:REF;channel:CODE;period:PERIOD;spend:MONEY | CAC (PROPOSED) | Как расходовать маркетинговый бюджет? | B11 | G2 | Z40 |
| `DF-MKT-02` | Лиды и воронка | leadRef:REF;stage:CODE;enteredAt:DATE;exitedAt:DATE;source:CODE | LEAD_CONVERSION (PROPOSED) | Где теряются лиды? | B11 | G2 | Z40 |
| `DF-MKT-03` | Атрибуция кампаний | method:CODE;window:INTEGER;attributedRevenue:MONEY;adSpend:MONEY;conversions:INTEGER | ATTRIBUTED_ROAS (PROPOSED) | Какие кампании выглядят успешнее? | B11 | G2 | Z40 |
| `DF-MKT-04` | Рыночные гипотезы и исследования | hypothesis:TEXT;market:CODE;sampleSize:INTEGER;observation:TEXT;source:REF | MARKET_COVERAGE (PROPOSED) | Есть ли основание входить на рынок? | P07;B11 | G2 | Z60 |

### Производство и операции (5)

| FormCode | Данные | Минимальные поля и типы | KPI / статус | Решение, которое поддерживает | Связь с F/C/P/B/D/T | Поставка | Зона |
|---|---|---|---|---|---|---|---|
| `DF-OPS-01` | Загрузка оборудования и выпуск | facility:REF;shift:REF;plannedCapacity:DECIMAL;actualOutput:DECIMAL;machineHours:DECIMAL | CAPACITY_UTILIZATION (PROPOSED) | Нужно ли расширять производство? | B16 | G2 | Z50 |
| `DF-OPS-02` | Спецификации и нормы материалов | product:REF;component:REF;quantityPerUnit:DECIMAL;unit:CODE;lossNorm:DECIMAL | STANDARD_UNIT_COST (PROPOSED) | Где растёт себестоимость? | B16;C08 | G2 | Z50 |
| `DF-OPS-03` | Брак, переделки, простои | facility:REF;defects:DECIMAL;reworkHours:DECIMAL;downtimeMin:DECIMAL | SCRAP_RATE (PROPOSED) | Что тормозит производство? | B16 | G2 | Z50 |
| `DF-OPS-04` | Сервисные задания | jobRef:REF;service:CODE;actualHours:DECIMAL;directCost:MONEY;completedAt:DATE | SERVICE_UNIT_COST (PROPOSED) | Какие услуги выгодны? | B16;B09 | G2 | Z50 |
| `DF-OPS-05` | Логистика и рейсы | tripRef:REF;distanceKm:DECIMAL;fuelLitres:DECIMAL;driverHours:DECIMAL;cargoQty:DECIMAL;totalCost:MONEY | COST_PER_KM (PROPOSED) | Какие рейсы убыточны? | B16 | G2 | Z50 |

### Персонал и занятость (4)

| FormCode | Данные | Минимальные поля и типы | KPI / статус | Решение, которое поддерживает | Связь с F/C/P/B/D/T | Поставка | Зона |
|---|---|---|---|---|---|---|---|
| `DF-HR-01` | Структура персонала и ФОТ | department:CODE;period:PERIOD;fte:DECIMAL;payroll:MONEY;benefits:MONEY | PAYROLL_SHARE (PROPOSED) | Как соотносятся штат и расходы? | B09 | G2 | Z80 |
| `DF-HR-02` | Рабочее время и выработка | team:CODE;shift:REF;paidHours:DECIMAL;productiveHours:DECIMAL;outputQty:DECIMAL | LABOR_PRODUCTIVITY (PROPOSED) | Где простой персонала? | B09 | G2 | Z80 |
| `DF-HR-03` | План найма | team:CODE;month:PERIOD;plannedFte:DECIMAL;expectedWage:MONEY | LABOR_COST_FORECAST (PROPOSED) | Нужен ли новый сотрудник? | B09;P02 | G2 | Z80 |
| `DF-HR-04` | Текучесть и вакансии в агрегатах | team:CODE;quarter:PERIOD;startingFte:DECIMAL;leavers:INTEGER;vacancies:INTEGER | TURNOVER_RATE (PROPOSED) | Почему не хватает сотрудников? | B09 | G2 | Z80 |

### Активы и ресурсы (4)

| FormCode | Данные | Минимальные поля и типы | KPI / статус | Решение, которое поддерживает | Связь с F/C/P/B/D/T | Поставка | Зона |
|---|---|---|---|---|---|---|---|
| `DF-AST-01` | Основные средства и оборудование | assetRef:REF;type:CODE;acquiredAt:DATE;cost:MONEY;netBookValue:MONEY;capacity:DECIMAL | ASSET_PRODUCTIVITY (PROPOSED) | Что ремонтировать или заменить? | F01;C04 | G2 | Z50 |
| `DF-AST-02` | Ремонты и обслуживание | assetRef:REF;eventAt:DATE;repairCost:MONEY;downtimeHours:DECIMAL | MAINTENANCE_COST (PROPOSED) | Не выгоднее ли замена оборудования? | B16 | G2 | Z50 |
| `DF-AST-03` | Помещения и аренда | propertyRef:REF;areaSqM:DECIMAL;leaseMonthly:MONEY;termEnd:DATE | COST_PER_SQM (PROPOSED) | Какие площади неэффективны? | C04;C09 | G2 | Z70 |
| `DF-AST-04` | Энергия, вода и ресурсы | resource:CODE;period:PERIOD;quantity:DECIMAL;unit:CODE;cost:MONEY;outputQty:DECIMAL | RESOURCE_INTENSITY (PROPOSED) | Как сократить удельные затраты? | B16 | G4 | Z50 |

### Капитал и корпоративное управление (4)

| FormCode | Данные | Минимальные поля и типы | KPI / статус | Решение, которое поддерживает | Связь с F/C/P/B/D/T | Поставка | Зона |
|---|---|---|---|---|---|---|---|
| `DF-GOV-01` | Собственники и структура капитала | ownerAlias:REF;ownerType:ENUM;ownershipShare:DECIMAL;effectiveAt:DATE | OWNERSHIP_CONCENTRATION (PROPOSED) | Кто владеет предприятием? | F04;D01 | G3 | Z70 |
| `DF-GOV-02` | Корпоративные решения и согласования | decisionRef:REF;approvedAt:DATE;decisionType:CODE;authorizer:REF;documentRef:REF | — | Кто может утвердить публикацию? | D01;D08 | G3 | Z70 |
| `DF-GOV-03` | Дивиденды и распределения | approvedAt:DATE;paidAt:DATE;amount:MONEY;recipientAlias:REF;distributionType:ENUM | — | Как распределение влияет на ликвидность? | F04;C14 | G3 | Z30 |
| `DF-GOV-04` | Обязательства участников и акционеров | obligationRef:REF;dueAt:DATE;amount:MONEY;status:ENUM;approvedBy:REF | EQUITY_COMMITMENT_GAP (PROPOSED) | Есть ли невыполненные обязательства? | D07;P10 | G3 | Z70 |

### Проекты и стратегия (7)

| FormCode | Данные | Минимальные поля и типы | KPI / статус | Решение, которое поддерживает | Связь с F/C/P/B/D/T | Поставка | Зона |
|---|---|---|---|---|---|---|---|
| `DF-PRJ-01` | Стратегические цели и альтернативы | project:REF;objective:TEXT;option:CODE;target:DECIMAL;deadline:DATE | — | Расти, оптимизировать или ждать? | P07;P08 | G1 | Z60 |
| `DF-PRJ-02` | CAPEX и инвестиционные платежи | project:REF;capexType:CODE;amount:MONEY;paymentMonth:PERIOD;commissionAt:DATE | INVESTMENT_GAP (PROPOSED) | Сколько денег нужно на проект? | P05;P10 | G1 | Z60 |
| `DF-PRJ-03` | План продаж и затрат проекта | project:REF;month:PERIOD;scenario:CODE;units:DECIMAL;price:MONEY;variableCost:MONEY | BREAK_EVEN_REV (if qualified) | Когда проект выйдет на безубыточность? | P02;P07 | G1 | Z60 |
| `DF-PRJ-04` | Источники и условия финансирования | source:ENUM;amount:MONEY;drawdown:PERIOD;rate:DECIMAL;repayment:TEXT | DEBT_SERVICE_COVER (if qualified) | Как меняется нагрузка от финансирования? | P04;P10 | G2 | Z60 |
| `DF-PRJ-05` | Риски и чувствительность | driver:CODE;base:DECIMAL;downside:DECIMAL;upside:DECIMAL;sourceRef:REF | NPV_SENSITIVITY (PROPOSED) | Какой риск наиболее опасен? | P08;P07 | G2 | Z60 |
| `DF-PRJ-06` | Вехи проекта и план-факт | milestone:REF;plannedAt:DATE;owner:REF;budget:MONEY;actualCost:MONEY;actualAt:DATE | MILESTONE_VARIANCE (PROPOSED) | Почему проект отстаёт? | B15;P05 | G2 | Z60 |
| `DF-PRJ-07` | Партнёрство и совместные ресурсы | partner:REF;resource:CODE;quantity:DECIMAL;costSharing:CODE;dueAt:DATE | — | Самостоятельно или с партнёром? | P07;P10 | G2 | Z60 |

## 3. Применимость: ИП, ТОО, АО и стадии S0–S4

| Профиль | Express (необходимое начало) | Full / Corporate | Правило |
|---|---|---|---|
| **ИП** | CTX, SAL-01/03, CSH-01…05, CST-01/02/04 по применимости | Склад, долги, клиенты, персонал, проекты и услуги по фактической деятельности | личное изъятие ≠ расход бизнеса в P&L |
| **ТОО** | тот же Express при небольшом объёме операций | F01–F04, C-формы, сегменты, активы, GOV при наличии соответствующих данных | распределение прибыли ТОО ≠ личное изъятие ИП |
| **АО** | Express для отдельно рассматриваемого бизнеса/направления | F01–F08, сегменты, группы компаний, GOV-01…04, утверждение публикаций, контроль отчетных версий | данные о капитале и дивидендах не равны финансовым операциям FIN |
| **S0** | CTX, PRJ и допущения | бюджет, сценарии, бизнес-план | отсутствующие исторические продажи не заменяются прогнозами |

**Юридическая форма не определяет автоматически полноту требуемой финансовой отчетности**. При международном использовании действуют versioned CountryPack / AccountingStandard / EffectiveDate. Реестр не является налоговым или законодательным заключением по Казахстану.

## 4. Decision Packets: вопрос владельца → минимальные формы → показатель → действие

| ID | Управленческий вопрос | Формы и зависимости | KPI из действующего словаря | Возможный результат |
|---|---|---|---|---|
| DEC-01 | Почему есть продажи, но нет денег? | SAL-01, CSH-01/02/03/04/05 | REV_NET, CASH_IN_OP, CASH_OUT_OP, NET_CASH_FLOW, CASH_END | кассовый разрыв, сроки оплат, запрос недостающих данных |
| DEC-02 | Какие товары прибыльнее? | SAL-01/03, CST-02 | REV_NET, COGS, GROSS_PROFIT, GROSS_MARGIN | сравнение маржи SKU, без задвоения возвратов |
| DEC-03 | Поднимать цену или сокращать расходы? | SAL-01/04, CST-01/02 | CONTRIBUTION_MARGIN, BREAK_EVEN_REV | чувствительность и условная стратегия |
| DEC-04 | Какие покупатели создают риск? | CUS-01/04, CSH-04 | CLIENT_TOP1_SHARE, AR_END, AR_OVERDUE_SHARE | концентрация клиента и просрочка |
| DEC-05 | Что не продаётся на складе? | STK-01, CST-02, SAL-01 | INV_TURNOVER, INVENTORY_DAYS | оборачиваемость при корректном учёте |
| DEC-06 | Можно ли обслужить новый кредит? | CSH-03/05/06 и подтверждённый CFADS | CASH_COVER_30D, DEBT_SERVICE_COVER | сценарий долговой нагрузки, **не одобрение** |
| DEC-07 | Стоит ли расширить бизнес? | CTX-02/04, PRJ-01/02/03/05 | BREAK_EVEN_REV | A/B/do-nothing, инвестиционный прогноз по отдельной методике |
| DEC-08 | Где теряется производительность? | OPS-01/02/03, CST-03 | пока нет подходящего определённого KPI | предложить отраслевую методику и дополнительные вопросы |
| DEC-09 | Нужен ли дополнительный сотрудник? | HR-01/02/03, SAL-06 или OPS-04 | только частично NET_PROFIT | сценарий найма, без выдуманной productivity |
| DEC-10 | Куда направлять маркетинговый бюджет? | MKT-01/02/03, SAL-05 | REV_NET только как общий результат | proposed CAC/ROAS; атрибуция ≠ причинность |
| DEC-11 | Безопасно ли выводить прибыль? | CST-04 для ИП; GOV-03 для ТОО/АО; CSH-03/05 | OWNER_DRAWS только при корректной применимости, CASH_END | сценарная ликвидность после распределений |
| DEC-12 | Какие документы направить инвестору/банку? | PRJ-01…05, F/C/P по применимости | approved snapshot metrics | package с preview и разрешением, без передачи денег |

**Финальный результат каждого Decision Packet:** \`observations, hypotheses, dataGaps, metrics, assumptions, alternatives, suggestedActions, limitations, sourceRevisions, methodVersion\`. Неподтверждённая возможная причина никогда не показывается как установленный факт.

## 5. Схема Form Registry

\`DataFormDefinition\`: \`formCode\`, \`version\`, \`ownerModule\`, \`applicableStages\`, \`industryRules\`, \`countryPackVersion\`, \`localeKeys.{ru,kk,en}\`, \`rowGrain\`, \`fieldSchema[]\`, \`validationRules\`, \`factMappings\`, \`indicatorDependencies\`, \`defaultZone\`, \`sourcePolicy\`, \`status\`.

\`DataFormInstance\`: \`tenantId?\`, \`businessId\`, \`projectId?\`, \`reportingPeriod\`, \`formVersion\`, \`inputRevision\`, \`sourceEvidenceRefs\`, \`rowValues\`, \`dataQuality\`, \`approvalState\`, \`approvedBy\`, \`approvedAt\`. Все денежные величины — Decimal, валюта и масштаб обязательны; длительность и дата остатка не смешиваются. Множество рядов поддерживает CSV/XLSX import, React Grid и простой Wizard. Отдельно хранится trace к \`FinancialFact\` и \`MetricResult\`.

**Статусы входа:** \`PRESENT\`, \`EXPLICIT_ZERO\`, \`MISSING\`, \`NOT_APPLICABLE\`, \`ESTIMATED\`. Признанная выручка ≠ платёж; purchase ≠ COGS; займ/взнос ≠ sales; owner draws/дивиденды ≠ OPEX; прогноз ≠ actual. Возвраты не учитываются дважды. Не допускается сложение несопоставимых единиц, периодов и валют без проверяемой конверсии. Import staged, dedup, line-level validation, human approval. Только APPROVED fact revision используется в ответственном отчёте.

**Права:** формы — PRIVATE по умолчанию, уровень чувствительности определяется колонкой зоны и отдельной классификацией поля. \`Z80\` и \`Z70\` требуют особого контроля в hosted-контуре. Межорганизационный доступ только через Spring Boot/Keycloak session + PostgreSQL AccessGrant по полям, периодам, версиям и времени. Пользовательская форма не публикуется автоматически.

## 6. Финансовый движок, KPI и 3 языка

[FIN Indicator Dictionary](FIN-INDICATOR-DICTIONARY-RU-KK-EN.md) уже документирует **27 KPI с неизменяемыми кодами** и переводами названия, «что считает», «как считает», «что показывает» на русском, казахском и английском. \`DF-*\` формы **не дублируют формулу и не создают новые KPI автоматически**. Помета PROPOSED требует отдельного \`MetricDefinition\`, формулы/AST, применимости, missing/zero policy, golden fixtures, независимого методического review и RU/KK/EN локализации.

Приоритет новых KPI после пилотов: \`BACKLOG_RATE, CHANNEL_CONTRIBUTION, RETENTION_RATE, CAPACITY_UTILIZATION, LABOR_PRODUCTIVITY, RESOURCE_INTENSITY, ATTRIBUTED_ROAS, NPV_SENSITIVITY\`. Пока они только кандидаты; исполнительная методика не утверждена. **Никакого универсального «здоровье 82/100»** без прозрачной калиброванной формулы и проверки.

## 7. FIN Business Health + ML-опция

**FIN-BH-WIZ**: ИП/ТОО/АО → цель («почему нет денег», «где падает маржа», «можно ли расширяться») → адаптивный поднабор DF → staged review → approve facts → Calculation Engine → Rule Interpreter → OBSERVATION/HYPOTHESIS/MISSING_EVIDENCE → NextBestQuestion → CorrectiveActionPlan → повторный замер.

Начальный анализ работает **без ML**. Tribuo (Java, backend) допустим позже для аномалий, прогнозирования и сегментации, ONNX Runtime — как возможный backend runtime для моделей из иных экосистем. Модель лишь дополняет, не заменяет точные финансовые формулы. Требования к ML: добровольное использование бизнес-данных для обучения только при явном допустимом основании и изоляции; temporal/group split без leakage, сравнение с сезонным baseline, прозрачность confidence и modelVersion, rollback. При отсутствии доказуемого улучшения baseline ML-функция выключена. Результат ML — \`MODEL_ESTIMATE\`, не доказанная причина или аудиторский вывод.

## 8. Приемка DF-T01…DF-T20 (PLANNED)

| ID | Сценарий |
|---|---|
| DF-T01 | ИП получает Express-диагноз без заполнения всех форм |
| DF-T02 | ТОО услуг не вынуждается вводить склад и акционерную структуру |
| DF-T03 | АО с филиалами получает корректную сегментную модель |
| DF-T04 | 5 млн выручки и 3 млн поступлений не объединяются |
| DF-T05 | Закупка на 1 млн и COGS 0.6 млн не смешиваются |
| DF-T06 | missing/zero/not applicable отличаются в импорте и UI |
| DF-T07 | CSV + ручной ввод одного платежа не удваивают cash |
| DF-T08 | Один Canonical Fact используется в Business Health, F/C/P, Business Plan |
| DF-T09 | Существующие 27 KPI используют те же metricCode и версии в RU/KK/EN |
| DF-T10 | PROPOSED KPI без формулы и переводов не вычисляется |
| DF-T11 | Смена отрасли не удаляет уже сохранённые факты |
| DF-T12 | Диагноз OBSERVATION не подменяется HYPOTHESIS/ML output |
| DF-T13 | Доступ чужой организации к формам/файлам отвергается |
| DF-T14 | Финансовые данные не становятся PUBLIC без disclosure preview и approval |
| DF-T15 | Корпоративные дивиденды не считаются owner draws ИП |
| DF-T16 | Эффект рекламы не выдаётся за доказанную причинную выручку |
| DF-T17 | S0-проект не подделывает фактическую P&L |
| DF-T18 | Unsupported отраслевой KPI даёт NOT_COMPUTABLE/NOT_APPLICABLE |
| DF-T19 | Ручной ввод, CSV и XLSX после canonical validation дают одинаковый результат |
| DF-T20 | При выключенном ML детерминированная диагностика работает полностью |

**Приоритет:** G1 — CTX/SAL/CSH/CST, экспресс FIN Business Health и связка с 27 KPI; G2 — цены/склад/маркетинг/операции/HR/проект; G3 — корпоративные документы GOV, hosted доступ и публикация; G4 — международные отраслевые country/sector методики. Модули и тесты только SPECIFIED; готовой реализации и прошедших проверок нет.
