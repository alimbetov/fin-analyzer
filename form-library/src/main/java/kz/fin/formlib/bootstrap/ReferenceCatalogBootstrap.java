package kz.fin.formlib.bootstrap;

import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component @Order(2)
public class ReferenceCatalogBootstrap implements ApplicationRunner {
  public static final List<String> LOCALES=List.of("ru","kk","en","zh-Hans");
  private final LibCurrencyRepository currencies;
  private final LibCurrencyTranslationRepository currencyTranslations;
  private final LibPeriodTypeRepository periodTypes;
  private final LibPeriodTypeTranslationRepository periodTranslations;
  private final LibFormTranslationRepository formTranslations;
  private final FormDefinitionRepository forms;
  private final LibMetricDefinitionRepository metrics;
  private final LibMetricTranslationRepository metricTranslations;
  private final FormFieldTranslationRepository fieldTranslations;

  public ReferenceCatalogBootstrap(
      LibCurrencyRepository currencies,LibCurrencyTranslationRepository currencyTranslations,
      LibPeriodTypeRepository periodTypes,LibPeriodTypeTranslationRepository periodTranslations,
      LibFormTranslationRepository formTranslations,FormDefinitionRepository forms,
      LibMetricDefinitionRepository metrics,LibMetricTranslationRepository metricTranslations,
      FormFieldTranslationRepository fieldTranslations) {
    this.currencies=currencies;this.currencyTranslations=currencyTranslations;
    this.periodTypes=periodTypes;this.periodTranslations=periodTranslations;
    this.formTranslations=formTranslations;this.forms=forms;
    this.metrics=metrics;this.metricTranslations=metricTranslations;this.fieldTranslations=fieldTranslations;
  }
  private static List<String[]> rows(String resource,int columns) throws Exception {
    try(var in=new BufferedReader(new InputStreamReader(
           new ClassPathResource(resource).getInputStream(),StandardCharsets.UTF_8))) {
      if(in.readLine()==null) throw new IllegalStateException("EMPTY_REFERENCE: "+resource);
      var all=new ArrayList<String[]>();
      String line;
      while((line=in.readLine())!=null){
        if(line.isBlank())continue;
        String[] s=line.split("\t",-1);
        if(s.length!=columns)throw new IllegalStateException("BAD_REFERENCE_ROW:"+resource);
        all.add(s);
      }
      return all;
    }
  }
  public record Labels(String[] titles,String[] descriptions) {}
  private static final Map<String,Labels> CURATED=curatedTranslations();
  // Editorial draft purposes. Not verified S2. All other form purposes remain
  // explicitly PENDING_TRANSLATION until a catalog moderator supplies them.
  private static final Map<String,String[]> CURATED_PURPOSES=Map.ofEntries(
    Map.entry("F01",new String[]{"Оценить активы, обязательства и капитал","Активтерді, міндеттемелерді және капиталды бағалау","Assess assets, liabilities and equity","评估资产、负债和权益"}),
    Map.entry("F02",new String[]{"Оценить доходность и финансовый результат","Табыстылық пен қаржылық нәтижені бағалау","Assess profitability and financial results","评估盈利能力与财务业绩"}),
    Map.entry("F03",new String[]{"Проанализировать движение денежных средств","Ақша қаражатының қозғалысын талдау","Analyze cash movements","分析现金流动"}),
    Map.entry("C01",new String[]{"Изучить дебиторскую задолженность","Дебиторлық берешекті талдау","Analyze receivables","分析应收账款"}),
    Map.entry("C02",new String[]{"Изучить кредиторскую задолженность","Кредиторлық берешекті талдау","Analyze payables","分析应付账款"}),
    Map.entry("C03",new String[]{"Оценить запасы и их стоимость","Қорлар мен олардың құнын бағалау","Assess inventory and its value","评估库存及其价值"}),
    Map.entry("DF-AST-01",new String[]{"Учитывать основные средства и их состояние","Негізгі құралдар мен олардың жай-күйін есепке алу","Track fixed assets and their condition","跟踪固定资产及其状况"}),
    Map.entry("DF-CSH-01",new String[]{"Учитывать денежные операции","Ақша операцияларын есепке алу","Record cash transactions","记录现金交易"}),
    Map.entry("DF-CSH-03",new String[]{"Контролировать остатки денежных средств","Ақша қаражатының қалдығын бақылау","Monitor cash balances","监控现金余额"}),
    Map.entry("DF-CST-01",new String[]{"Детализировать затраты по категориям","Шығындарды санаттар бойынша нақтылау","Detail costs by category","按类别细分成本"}),
    Map.entry("SF-C01",new String[]{"Оценить производственные операции","Өндірістік операцияларды бағалау","Assess manufacturing operations","评估制造运营"}),
    Map.entry("SF-F01",new String[]{"Контролировать выполнение строительных работ","Құрылыс жұмыстарының орындалуын бақылау","Monitor construction progress","监控施工进度"})
  );

  private static Map<String,Labels> curatedTranslations() {
    Map<String,Labels> m=new HashMap<>();
    m.put("F01",new Labels(new String[]{"Бухгалтерский баланс","Бухгалтерлік баланс","Balance sheet","资产负债表"},new String[]{"Активы, обязательства и капитал на отчётную дату","Есепті күндегі активтер, міндеттемелер және капитал","Assets, liabilities and equity at the reporting date","报告日的资产、负债和权益"}));
    m.put("F02",new Labels(new String[]{"Отчёт о прибылях и убытках","Пайда мен залал туралы есеп","Statement of profit or loss","利润表"},new String[]{"Выручка, себестоимость и операционные расходы за период","Кезең ішіндегі түсім, өзіндік құн және операциялық шығыстар","Revenue, cost of goods sold and operating expenses for the period","期间营业收入、营业成本和营业费用"}));
    m.put("F03",new Labels(new String[]{"Отчёт о движении денежных средств","Ақша қаражатының қозғалысы туралы есеп","Statement of cash flows","现金流量表"},new String[]{"Денежные потоки от операционной, инвестиционной и финансовой деятельности","Операциялық, инвестициялық және қаржылық қызметтен ақша ағындары","Cash flows from operating, investing and financing activities","经营、投资和筹资活动现金流量"}));
    m.put("C01",new Labels(new String[]{"Реестр дебиторской задолженности","Дебиторлық берешек тізілімі","Accounts receivable register","应收账款台账"},new String[]{"Задолженность контрагентов, сроки и просрочка","Контрагенттер берешегі, мерзімдер және кешіктірулер","Counterparty receivables, due dates and overdue balances","往来单位应收款、到期日及逾期余额"}));
    m.put("C02",new Labels(new String[]{"Реестр кредиторской задолженности","Кредиторлық берешек тізілімі","Accounts payable register","应付账款台账"},new String[]{"Обязательства перед контрагентами и сроки оплаты","Контрагенттер алдындағы міндеттемелер және төлем мерзімдері","Payables to counterparties and payment due dates","对往来单位的应付款及付款期限"}));
    m.put("C03",new Labels(new String[]{"Реестр запасов","Қорлар тізілімі","Inventory register","存货台账"},new String[]{"Количество, стоимость и обесценение запасов","Қорлардың саны, құны және құнсыздануы","Inventory quantities, values and impairment","存货数量、价值及减值"}));
    m.put("DF-AST-01",new Labels(new String[]{"Реестр основных средств","Негізгі құралдар тізілімі","Fixed assets register","固定资产台账"},new String[]{"Объекты основных средств, стоимость и балансовая стоимость","Негізгі құралдар, бастапқы және баланстық құн","Fixed assets, acquisition cost and carrying amount","固定资产、取得成本和账面价值"}));
    m.put("DF-CSH-01",new Labels(new String[]{"Журнал денежных операций","Ақша операциялары журналы","Cash transactions ledger","现金交易台账"},new String[]{"Платежи, даты проведения, суммы и назначения","Төлемдер, жүргізілген күндер, сомалар және мақсаттар","Payments, posting dates, amounts and purposes","付款、入账日期、金额和用途"}));
    m.put("DF-CSH-03",new Labels(new String[]{"Остатки денежных средств","Ақша қаражатының қалдықтары","Cash balances","现金余额"},new String[]{"Доступные, ограниченные и резервные денежные средства на дату","Күнгі қолжетімді, шектеулі және резервтік ақша","Available, restricted and reserved cash at a date","某日可用、受限及储备现金"}));
    m.put("DF-CST-01",new Labels(new String[]{"Детализация затрат","Шығындарды нақтылау","Cost details","成本明细"},new String[]{"Затраты по периоду, центру затрат и категории","Кезең, шығындар орталығы және санат бойынша шығындар","Costs by period, cost centre and category","按期间、成本中心及类别列示成本"}));
    m.put("SF-C01",new Labels(new String[]{"Производственные операции","Өндірістік операциялар","Manufacturing operations","生产作业"},new String[]{"Выпуск продукции, переработка и потери по сменам","Ауысымдар бойынша өнім, қайта өңдеу және шығындар","Production output, rework and waste by shift","按班次统计产量、返工和损耗"}));
    m.put("SF-F01",new Labels(new String[]{"Выполнение строительных работ","Құрылыс жұмыстарының орындалуы","Construction work progress","建筑工程进度"},new String[]{"Плановые, фактические и подтверждённые объёмы работ","Жоспарланған, нақты және расталған жұмыс көлемдері","Planned, actual and certified work quantities","计划、实际及已确认的工程量"}));
    return Map.copyOf(m);
  }

  @Transactional
  @Override
  public void run(ApplicationArguments args) throws Exception {
    // H2 memory bootstraps once. Reject incomplete data instead of silently returning.
    if(currencies.count()>0) {
      if(currencies.count()!=11 || periodTypes.count()!=7 ||
        forms.count()!=166 || formTranslations.count()!=664)
        throw new IllegalStateException("INCOMPLETE_REFERENCE_SEED");
      return;
    }
    for(String[] d:CURRENCY_DATA) {
      LibCurrency c=new LibCurrency();c.setCode(d[0]);c.setIsoNumeric(d[1]);
      c.setMinorUnits(Integer.parseInt(d[2]));c.setStatus("ACTIVE");currencies.save(c);
      for(int i=0;i<4;i++){
        LibCurrencyTranslation t=new LibCurrencyTranslation();t.setId(UUID.randomUUID());
        t.setCurrencyCode(d[0]);t.setLocale(LOCALES.get(i));t.setTitle(d[3+i]);
        t.setReviewStatus("DRAFT_UNREVIEWED");currencyTranslations.save(t);
      }
    }
    for(String[] d:PERIOD_DATA) {
      LibPeriodType p=new LibPeriodType();p.setCode(d[0]);p.setSortOrder(Integer.parseInt(d[1]));
      p.setIsoCalendar("ISO_8601_GREGORIAN");p.setStatus("ACTIVE");periodTypes.save(p);
      for(int i=0;i<4;i++){
        LibPeriodTypeTranslation t=new LibPeriodTypeTranslation();t.setId(UUID.randomUUID());
        t.setPeriodType(d[0]);t.setLocale(LOCALES.get(i));t.setTitle(d[2+i]);
        t.setDescription(d[6+i]);periodTranslations.save(t);
      }
    }
    var source=rows("catalog/form-compatibility.tsv",8);
    if(source.size()!=166||forms.count()!=166)throw new IllegalStateException("FORMS_COUNT_DRIFT");
    var originalByCode=new HashMap<String,String[]>();
    for(var d:source)originalByCode.put(d[0],d);

    List<LibFormTranslation> ft=new ArrayList<>(664);
    for(FormDefinition f:forms.findAll()) {
      Labels c=CURATED.get(f.getFormCode());
      String[] d=originalByCode.get(f.getFormCode());
      if(d==null)throw new IllegalStateException("UNRECOGNIZED_FORM");
      for(int i=0;i<4;i++){
        LibFormTranslation t=new LibFormTranslation();t.setId(UUID.randomUUID());
        t.setFormVersionId(f.getCurrentVersionId());t.setLocale(LOCALES.get(i));
        if(c!=null){
          t.setTitle(c.titles()[i]);t.setDescription(c.descriptions()[i]);
          t.setReviewStatus("DRAFT_UNREVIEWED");
          String[] purpose=CURATED_PURPOSES.get(f.getFormCode());
          if(purpose!=null){t.setPurpose(purpose[i]);
            t.setPurposeReviewStatus("DRAFT_UNREVIEWED");}
        }else if(i==0 && "CATALOG_SUMMARY".equals(d[6])){
          t.setDescription(d[5]);t.setReviewStatus("PROVISIONAL_SOURCE_TEXT");
        }else{
          t.setReviewStatus("PENDING_TRANSLATION");
        }
        ft.add(t);
      }
    }
    formTranslations.saveAll(ft);

    var existing=new HashMap<String,FormFieldTranslation>();
    for(FormFieldTranslation t:fieldTranslations.findAll()) {
      existing.put(t.getFormVersionId()+"|"+t.getSubjectCode()+"|"+t.getLocale(),t);
    }
    var translatedFields=new ArrayList<FormFieldTranslation>();
    var metricList=new ArrayList<LibMetricDefinition>();
    var mtList=new ArrayList<LibMetricTranslation>();
    var presentation=rows("catalog/form-field-presentation-review-r6.tsv",10);
    if(presentation.size()!=794)throw new IllegalStateException("FIELD_PRESENTATION_DRIFT");
    Set<String> indicatorIds=new HashSet<>();
    for(String[] d:presentation) {
      String code=d[0],field=d[1],key=code+"."+field;
      if(!indicatorIds.add(key))throw new IllegalStateException("DUPLICATE_INDICATOR:"+key);
      FormDefinition form=forms.findByFormCode(code).orElseThrow();
      String unitPolicy=d[9];
      var metric=new LibMetricDefinition();metric.setId(UUID.randomUUID());
      metric.setMetricCode(key);metric.setMetricKind("SOURCE_FIELD");
      metric.setFormCode(code);metric.setSourceFieldCode(field);
      metric.setUnitPolicy(unitPolicy);metric.setQualificationState("CATALOG_UNQUALIFIED");
      metricList.add(metric);
      for(int i=0;i<4;i++){
        String title=d[3+i];
        LibMetricTranslation t=new LibMetricTranslation();t.setId(UUID.randomUUID());
        t.setMetricId(metric.getId());t.setLocale(LOCALES.get(i));
        t.setTitle(title.isBlank()?null:title);
        t.setReviewStatus(title.isBlank()?"PENDING_TRANSLATION":"PROVISIONAL_NOT_S2");
        mtList.add(t);
        var k=form.getCurrentVersionId()+"|"+field+"|"+LOCALES.get(i);
        FormFieldTranslation f=existing.get(k);
        if(f==null){
          f=new FormFieldTranslation();f.setId(UUID.randomUUID());
          f.setFormVersionId(form.getCurrentVersionId());
          f.setSubjectType("FIELD");f.setSubjectCode(field);f.setLocale(LOCALES.get(i));
        }
        // Existing table requires a non-null title; use the machine code as a visible
        // fallback while preserving the independent translation coverage status.
        f.setTitle(title.isBlank()?field:title);
        f.setReviewStatus(title.isBlank()?"PENDING_TRANSLATION":"PROVISIONAL_NOT_S2");
        translatedFields.add(f);
      }
    }
    fieldTranslations.saveAll(translatedFields);
    Set<String> outputCodes=new TreeSet<>();
    for(String[] row:rows("catalog/analytics-model-catalog.tsv",6)) {
      for(String token:row[4].split(";"))if(!token.isBlank())outputCodes.add(token);
    }
    if(outputCodes.size()!=46)throw new IllegalStateException("OUTPUT_METRIC_COUNT_DRIFT");
    for(String code:outputCodes) {
      if(!indicatorIds.add(code))throw new IllegalStateException("METRIC_COLLISION");
      var metric=new LibMetricDefinition();metric.setId(UUID.randomUUID());
      metric.setMetricCode(code);metric.setMetricKind("ANALYTICAL_OUTPUT");
      metric.setUnitPolicy("PENDING_METRIC_REVIEW");
      metric.setQualificationState("CATALOG_UNQUALIFIED");
      metricList.add(metric);
      for(String locale:LOCALES){
        var mt=new LibMetricTranslation();mt.setId(UUID.randomUUID());
        mt.setMetricId(metric.getId());mt.setLocale(locale);
        mt.setReviewStatus("PENDING_TRANSLATION");mtList.add(mt);
      }
    }
    metrics.saveAll(metricList);
    metricTranslations.saveAll(mtList);
    if(metrics.count()!=840||metricTranslations.count()!=840*4
      ||formTranslations.count()!=166*4||periodTypes.count()!=7
      ||currencies.count()!=11)
      throw new IllegalStateException("REFERENCE_CATALOG_DRIFT");
  }
  private static final String[][] CURRENCY_DATA={
    {"KZT","398","2","Казахстанский тенге","Қазақстан теңгесі","Kazakhstani tenge","哈萨克斯坦坚戈"},
    {"USD","840","2","Доллар США","АҚШ доллары","US dollar","美元"},
    {"EUR","978","2","Евро","Еуро","Euro","欧元"},
    {"RUB","643","2","Российский рубль","Ресей рублі","Russian ruble","俄罗斯卢布"},
    {"CNY","156","2","Китайский юань","Қытай юані","Chinese yuan","人民币"},
    {"GBP","826","2","Фунт стерлингов","Фунт стерлинг","Pound sterling","英镑"},
    {"TRY","949","2","Турецкая лира","Түрік лирасы","Turkish lira","土耳其里拉"},
    {"JPY","392","0","Японская иена","Жапон иенасы","Japanese yen","日元"},
    {"AED","784","2","Дирхам ОАЭ","БАӘ дирхамы","UAE dirham","阿联酋迪拉姆"},
    {"CHF","756","2","Швейцарский франк","Швейцария франкі","Swiss franc","瑞士法郎"},
    {"KRW","410","0","Южнокорейская вона","Оңтүстік Корея воны","South Korean won","韩元"}
  };
  private static final String[][] PERIOD_DATA={
    {"D","10","День","Күн","Day","日","Один календарный день","Бір күнтізбелік күн","One calendar day","一个日历日"},
    {"W","20","Неделя","Апта","Week","周","Неделя ISO: понедельник — воскресенье","ISO аптасы: дүйсенбі — жексенбі","ISO week: Monday through Sunday","ISO 周：周一至周日"},
    {"DEC","30","Декада (10 дней)","Онкүндік","Ten-day period","旬","1–10, 11–20 или 21–последний день месяца","Айдың 1–10, 11–20 не 21–соңғы күні","Days 1–10, 11–20 or 21 to month end","每月1–10日、11–20日或21日至月末"},
    {"M","40","Месяц","Ай","Month","月","Календарный месяц","Күнтізбелік ай","Calendar month","日历月"},
    {"Q","50","Квартал","Тоқсан","Quarter","季度","Три календарных месяца","Үш күнтізбелік ай","Three calendar months","三个日历月"},
    {"H","60","Полугодие","Жарты жыл","Half-year","半年","Январь–июнь или июль–декабрь","Қаңтар–маусым не шілде–желтоқсан","January–June or July–December","一月至六月或七月至十二月"},
    {"Y","70","Год","Жыл","Year","年","Календарный год","Күнтізбелік жыл","Calendar year","日历年"}
  };
}
