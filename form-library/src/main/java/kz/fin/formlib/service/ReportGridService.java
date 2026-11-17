package kz.fin.formlib.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class ReportGridService {
  public record CreateReport(UUID organizationId,String reportCode,String title,
      String currencyCode,String periodType,List<String> periodCodes,
      List<String> metricCodes,String timeAxis) {}
  public record CreateReceipt(UUID reportId,String reportCode,int metricCount,
                              int periodCount,String timeAxis) {}
  public record CellInput(String metricCode,String periodCode,String state,String value) {}
  public record SaveReport(UUID organizationId,int expectedRevision,
      List<CellInput> cells,String createdBy,String changeReason,String sourceKind) {}
  public record SaveReceipt(UUID reportId,UUID revisionId,int revisionNo,
                            String contentSha256,String status) {}
  public record LayoutRequest(UUID organizationId,int expectedLayoutVersion,String timeAxis) {}
  public record LayoutReceipt(UUID reportId,int layoutVersion,String timeAxis,int latestRevisionNo) {}
  public record AxisItem(String code,String label,String translationStatus,int displayOrder) {}
  public record MatrixCell(String rowKey,String columnKey,String metricCode,String periodCode,
                           String state,String value,String formattedValue,boolean derived) {
    public MatrixCell(String rowKey,String columnKey,String metricCode,String periodCode,
                      String state,String value,String formattedValue){
      this(rowKey,columnKey,metricCode,periodCode,state,value,formattedValue,false);
    }
  }
  public record GridView(UUID reportId,String reportCode,String title,UUID organizationId,
                         int revisionNo,String contentSha256,String qualificationState,
                         String currencyCode,String periodType,String timeAxis,
                         int layoutVersion,List<AxisItem> rowHeaders,List<AxisItem> columnHeaders,
                         List<MatrixCell> cells,int formatVersion,String viewMode,int derivedVersion) {}

  private record Pair(String metric,String period) {}
  private record Value(String state,String value) {}
  private record MetricBinding(LibMetricDefinition metric,String fieldType) {}

  private static final int MAX_METRICS=40, MAX_PERIODS=60;
  private static final Set<String> LOCALES=Set.of("ru","kk","en","zh-Hans");
  private static final Set<String> SOURCES=Set.of("MANUAL","CLIPBOARD","API");

  private final LibReportDefinitionRepository reports;
  private final LibReportMetricRepository reportMetrics;
  private final LibReportPeriodRepository reportPeriods;
  private final LibReportRevisionRepository revisions;
  private final LibReportRevisionCellRepository revisionCells;
  private final LibReportCommandReceiptRepository receipts;
  private final LibMetricDefinitionRepository metrics;
  private final LibMetricTranslationRepository metricTranslations;
  private final FormDefinitionRepository forms;
  private final FormVersionRepository versions;
  private final FormFieldRepository fields;
  private final LibCurrencyRepository currencies;
  private final ObjectMapper json;
  private final ReportFormatService formatService;
  private final ReportInputValidationService inputValidator;
  private final ReportDerivedTotalService derivedService;

  public ReportGridService(LibReportDefinitionRepository reports,
       LibReportMetricRepository reportMetrics,LibReportPeriodRepository reportPeriods,
       LibReportRevisionRepository revisions,LibReportRevisionCellRepository revisionCells,
       LibReportCommandReceiptRepository receipts,LibMetricDefinitionRepository metrics,
       LibMetricTranslationRepository metricTranslations,FormDefinitionRepository forms,
       FormVersionRepository versions,FormFieldRepository fields,
       LibCurrencyRepository currencies,ObjectMapper json,
       ReportFormatService formatService,ReportInputValidationService inputValidator,
       ReportDerivedTotalService derivedService) {
    this.reports=reports;this.reportMetrics=reportMetrics;this.reportPeriods=reportPeriods;
    this.revisions=revisions;this.revisionCells=revisionCells;this.receipts=receipts;
    this.metrics=metrics;this.metricTranslations=metricTranslations;this.forms=forms;
    this.versions=versions;this.fields=fields;this.currencies=currencies;this.json=json;
    this.formatService=formatService;this.inputValidator=inputValidator;
    this.derivedService=derivedService;
  }
  private static ResponseStatusException invalid(String error) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,error);
  }
  private static ResponseStatusException conflict(String error) {
    return new ResponseStatusException(HttpStatus.CONFLICT,error);
  }
  private static String required(String s,String name) {
    if(s==null||s.isBlank())throw invalid(name+"_REQUIRED");
    return s.trim();
  }
  private static UUID required(UUID id,String name){
    if(id==null)throw invalid(name+"_REQUIRED");
    return id;
  }
  private static String axis(String s) {
    if(!"ROWS".equals(s)&&!"COLUMNS".equals(s))throw invalid("INVALID_TIME_AXIS");
    return s;
  }
  private static String locale(String s){
    if(!LOCALES.contains(s))throw invalid("UNSUPPORTED_LOCALE");
    return s;
  }
  private static String digest(String message){
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(message.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception e){throw new IllegalStateException(e);}
  }
  private String toJson(Object o){
    try{return json.writeValueAsString(o);}
    catch(JsonProcessingException e){throw new IllegalStateException(e);}
  }
  private LibReportDefinition owned(UUID id,UUID org){
    if(id==null||org==null)throw invalid("REPORT_CONTEXT_REQUIRED");
    return reports.findByIdAndOrganizationId(id,org)
      .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND"));
  }
  private LibReportDefinition locked(UUID id,UUID org){
    if(id==null||org==null)throw invalid("REPORT_CONTEXT_REQUIRED");
    return reports.lockByIdAndOrganizationId(id,org).orElseThrow(
      ()->new ResponseStatusException(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND"));
  }
  private LibCurrency currency(String code){
    return currencies.findById(required(code,"CURRENCY"))
      .filter(c->"ACTIVE".equals(c.getStatus()))
      .orElseThrow(()->invalid("UNSUPPORTED_CURRENCY"));
  }
  private MetricBinding metricBinding(String code){
    var metric=metrics.findByMetricCode(required(code,"METRIC_CODE"))
      .orElseThrow(()->invalid("UNKNOWN_METRIC"));
    if(!"SOURCE_FIELD".equals(metric.getMetricKind()))
      throw invalid("ANALYTICAL_METRIC_READ_ONLY");
    var f=forms.findByFormCode(metric.getFormCode())
      .orElseThrow(()->invalid("SOURCE_FORM_UNKNOWN"));
    var v=versions.findById(f.getCurrentVersionId())
      .orElseThrow(()->invalid("SOURCE_FORM_VERSION_UNKNOWN"));
    var field=fields.findByFormVersionIdOrderByDisplayOrderAsc(v.getId())
      .stream().filter(x->x.getFieldCode().equals(metric.getSourceFieldCode()))
      .findFirst().orElseThrow(()->invalid("SOURCE_FIELD_NOT_IN_VERSION"));
    if(!Set.of("MONEY","DECIMAL","INTEGER").contains(field.getFieldType()))
      throw invalid("NON_NUMERIC_METRIC");
    if(!"MANUAL".equals(field.getInputMode()))throw invalid("READ_ONLY_METRIC");
    return new MetricBinding(metric,field.getFieldType());
  }
  private static String canonicalDecimal(String raw,String type,LibCurrency currency){
    String value=required(raw,"CELL_VALUE");
    if(!value.matches("-?[0-9]{1,26}(\\.[0-9]{1,12})?"))
      throw invalid("INVALID_DECIMAL");
    BigDecimal d=new BigDecimal(value);
    if(d.precision()>38||d.scale()>12)throw invalid("DECIMAL_OUT_OF_RANGE");
    int scale=d.stripTrailingZeros().scale();
    if("INTEGER".equals(type)&&scale>0)throw invalid("INTEGER_REQUIRED");
    if("MONEY".equals(type)&&scale>currency.getMinorUnits())
      throw invalid("CURRENCY_MINOR_UNITS_EXCEEDED");
    return d.signum()==0?"0":d.stripTrailingZeros().toPlainString();
  }
  private Map<String,MetricBinding> bindings(LibReportDefinition report){
    LinkedHashMap<String,MetricBinding> out=new LinkedHashMap<>();
    for(var rm:reportMetrics.findByReportIdOrderByDisplayOrderAsc(report.getId())){
      var m=metrics.findById(rm.getMetricId()).orElseThrow();
      // Catalog version has been pinned implicitly at creation in v1; for future upgrades
      // a server-owned versioned metric binding will be required.
      out.put(m.getMetricCode(),metricBinding(m.getMetricCode()));
    }
    return out;
  }
  private Map<String,LibReportPeriod> periods(LibReportDefinition report){
    LinkedHashMap<String,LibReportPeriod> result=new LinkedHashMap<>();
    for(var p:reportPeriods.findByReportIdOrderByDisplayOrderAsc(report.getId()))
      result.put(p.getPeriodCode(),p);
    return result;
  }

  @Transactional
  public CreateReceipt create(CreateReport request){
    if(request==null)throw invalid("REPORT_REQUEST_REQUIRED");
    UUID org=required(request.organizationId(),"ORGANIZATION");
    String code=required(request.reportCode(),"REPORT_CODE");
    String title=required(request.title(),"REPORT_TITLE");
    if(!code.matches("[A-Za-z0-9][A-Za-z0-9_-]{1,95}"))throw invalid("INVALID_REPORT_CODE");
    if(title.length()>255)throw invalid("TITLE_TOO_LONG");
    String timeAxis=axis(request.timeAxis());
    var currency=currency(request.currencyCode());
    if(request.periodCodes()==null||request.periodCodes().isEmpty()||
       request.periodCodes().size()>MAX_PERIODS)throw invalid("PERIOD_COUNT_INVALID");
    if(request.metricCodes()==null||request.metricCodes().isEmpty()||
       request.metricCodes().size()>MAX_METRICS)throw invalid("METRIC_COUNT_INVALID");
    if(reports.findByOrganizationIdAndReportCode(org,code).isPresent())
      throw conflict("REPORT_CODE_EXISTS");
    LinkedHashMap<String,PeriodResolver.Range> selectedPeriods=new LinkedHashMap<>();
    for(String text:request.periodCodes()){
      PeriodResolver.Range range;
      try{range=PeriodResolver.resolve(request.periodType(),text);}
      catch(IllegalArgumentException e){throw invalid("INVALID_REPORT_PERIOD");}
      if(!range.periodType().equals(
          selectedPeriods.isEmpty()?range.periodType():selectedPeriods.values().iterator().next().periodType()))
        throw invalid("MIXED_PERIOD_TYPE");
      if(selectedPeriods.putIfAbsent(range.periodCode(),range)!=null)
        throw invalid("DUPLICATE_REPORT_PERIOD");
    }
    String periodType=selectedPeriods.values().iterator().next().periodType();
    LinkedHashMap<String,MetricBinding> selectedMetrics=new LinkedHashMap<>();
    for(String metricCode:request.metricCodes()){
      if(selectedMetrics.containsKey(metricCode))throw invalid("DUPLICATE_REPORT_METRIC");
      selectedMetrics.put(metricCode,metricBinding(metricCode));
    }
    LibReportDefinition report=new LibReportDefinition();
    report.setId(UUID.randomUUID());report.setOrganizationId(org);
    report.setReportCode(code);report.setTitle(title);report.setCurrencyCode(currency.getCode());
    report.setPeriodType(periodType);report.setTimeAxis(timeAxis);
    report.setLayoutVersion(1);report.setFormatVersion(1);
    report.setValidationVersion(1);report.setDerivedVersion(1);
    report.setLatestRevisionNo(0);
    report.setCreatedAt(Instant.now());
    reports.saveAndFlush(report);
    int rank=0;
    for(var binding:selectedMetrics.values()){
      LibReportMetric relation=new LibReportMetric();
      relation.setId(UUID.randomUUID());relation.setReportId(report.getId());
      relation.setMetricId(binding.metric().getId());relation.setDisplayOrder((++rank)*10);
      reportMetrics.save(relation);
    }
    rank=0;
    for(var range:selectedPeriods.values()){
      LibReportPeriod dimension=new LibReportPeriod();
      dimension.setId(UUID.randomUUID());dimension.setReportId(report.getId());
      dimension.setPeriodCode(range.periodCode());
      dimension.setStartInclusive(range.startInclusive());
      dimension.setEndExclusive(range.endExclusive());
      dimension.setDisplayOrder((++rank)*10);
      reportPeriods.save(dimension);
    }
    reportMetrics.flush();reportPeriods.flush();
    formatService.initialize(report);
    inputValidator.initialize(report);
    return new CreateReceipt(report.getId(),code,selectedMetrics.size(),
       selectedPeriods.size(),timeAxis);
  }

  @Transactional
  public SaveReceipt save(UUID reportId,SaveReport req,String idempotencyKey){
    if(req==null)throw invalid("REVISION_REQUEST_REQUIRED");
    var report=locked(reportId,req.organizationId());
    String key=required(idempotencyKey,"IDEMPOTENCY_KEY");
    if(key.length()>200)throw invalid("IDEMPOTENCY_KEY_TOO_LONG");
    String actor=required(req.createdBy(),"AUTHOR");
    String reason=required(req.changeReason(),"CHANGE_REASON");
    if(actor.length()>255||reason.length()>255)throw invalid("REVISION_METADATA_TOO_LONG");
    if(!SOURCES.contains(req.sourceKind()))throw invalid("INVALID_SOURCE_KIND");
    if(req.expectedRevision()<0)throw invalid("INVALID_EXPECTED_REVISION");
    var selectedMetrics=bindings(report);
    var selectedPeriods=periods(report);
    var currency=currency(report.getCurrencyCode());
    List<CellInput> raw=req.cells()==null?List.of():req.cells();
    if(raw.size()>MAX_METRICS*MAX_PERIODS)throw invalid("CELL_COUNT_EXCEEDED");
    TreeMap<String,Value> normalized=new TreeMap<>();
    Map<String,Pair> pairs=new HashMap<>();
    for(var cell:raw){
      if(cell==null)throw invalid("NULL_CELL");
      var metric=selectedMetrics.get(cell.metricCode());
      if(metric==null)throw invalid("UNSELECTED_METRIC");
      PeriodResolver.Range period;
      try{period=PeriodResolver.resolve(report.getPeriodType(),cell.periodCode());}
      catch(IllegalArgumentException e){throw invalid("INVALID_CELL_PERIOD");}
      if(!selectedPeriods.containsKey(period.periodCode()))throw invalid("UNSELECTED_PERIOD");
      String state=required(cell.state(),"CELL_STATE");
      String canonical;
      if("PRESENT".equals(state))canonical=canonicalDecimal(cell.value(),metric.fieldType(),currency);
      else if("NOT_APPLICABLE".equals(state)){
        if(cell.value()!=null)throw invalid("NOT_APPLICABLE_VALUE_FORBIDDEN");
        canonical=null;
      }else throw invalid("INVALID_VALUE_STATE");
      var pair=new Pair(cell.metricCode(),period.periodCode());
      String identity=pair.metric()+"|"+pair.period();
      if(normalized.containsKey(identity))throw invalid("DUPLICATE_METRIC_PERIOD_CELL");
      normalized.put(identity,new Value(state,canonical));pairs.put(identity,pair);
    }
    List<CellInput> normalizedInputs=new ArrayList<>();
    normalized.forEach((cellKey,value)->{
      var p=pairs.get(cellKey);
      normalizedInputs.add(new CellInput(p.metric(),p.period(),value.state(),value.value()));
    });
    var validation=inputValidator.validateSnapshot(reportId,req.organizationId(),normalizedInputs);
    if(!validation.valid()){
      var error=validation.issues().getFirst();
      throw invalid("VALIDATION_FAILED:"+error.code()+":"+error.metricCode()+":"+error.periodCode());
    }
    List<List<String>> canon=new ArrayList<>();
    normalized.forEach((k,v)->{var p=pairs.get(k);
      canon.add(Arrays.asList(p.metric(),p.period(),v.state(),v.value()));});
    String canonicalContent=toJson(Arrays.asList(report.getId().toString(),
      report.getOrganizationId().toString(),report.getCurrencyCode(),
      report.getPeriodType(),canon));
    String dataSha=digest("FIN-LIB-REPORT-V1|"+canonicalContent);
    String reqSha=digest("FIN-LIB-REPORT-CMD-V1|"+toJson(Arrays.asList(
       canonicalContent,req.expectedRevision(),actor,reason,req.sourceKind())));
    var previous=receipts.findByReportIdAndIdempotencyKey(reportId,key);
    if(previous.isPresent()){
      var p=previous.get();
      if(!p.getRequestSha256().equals(reqSha))throw conflict("IDEMPOTENCY_KEY_REUSED");
      var old=revisions.findById(p.getRevisionId()).orElseThrow();
      return new SaveReceipt(reportId,old.getId(),old.getRevisionNo(),
          old.getCanonicalContentSha256(),"IDEMPOTENT_REPLAY");
    }
    if(report.getLatestRevisionNo()!=req.expectedRevision())
      throw conflict("STALE_REPORT_REVISION");
    var rev=new LibReportRevision();
    rev.setId(UUID.randomUUID());rev.setReportId(reportId);
    rev.setRevisionNo(req.expectedRevision()+1);rev.setCanonicalContentSha256(dataSha);
    rev.setCreatedAt(Instant.now());rev.setCreatedBy(actor);rev.setChangeReason(reason);
    rev.setSourceKind(req.sourceKind());rev.setQualificationState("UNQUALIFIED_DRAFT");
    rev.setValidationVersion(validation.validationVersion());
    rev.setValidationSha256(validation.rulesSha256());
    revisions.saveAndFlush(rev);
    var storedCells=new ArrayList<LibReportRevisionCell>(normalized.size());
    for(var kv:normalized.entrySet()){
      var pair=pairs.get(kv.getKey());var val=kv.getValue();
      var row=new LibReportRevisionCell();
      row.setId(UUID.randomUUID());row.setRevisionId(rev.getId());
      row.setMetricId(selectedMetrics.get(pair.metric()).metric().getId());
      row.setPeriodCode(pair.period());row.setValueState(val.state());
      row.setDecimalValue(val.value());
      storedCells.add(row);
    }
    revisionCells.saveAll(storedCells);
    revisionCells.flush();
    report.setCurrentRevisionId(rev.getId());
    report.setLatestRevisionNo(rev.getRevisionNo());
    reports.saveAndFlush(report);
    var receipt=new LibReportCommandReceipt();
    receipt.setId(UUID.randomUUID());receipt.setReportId(reportId);
    receipt.setIdempotencyKey(key);receipt.setRequestSha256(reqSha);
    receipt.setRevisionId(rev.getId());receipts.saveAndFlush(receipt);
    return new SaveReceipt(reportId,rev.getId(),rev.getRevisionNo(),dataSha,
       "UNQUALIFIED_DRAFT");
  }

  @Transactional
  public LayoutReceipt changeLayout(UUID reportId,LayoutRequest request){
    if(request==null)throw invalid("LAYOUT_REQUEST_REQUIRED");
    var report=owned(reportId,request.organizationId());
    String orientation=axis(request.timeAxis());
    if(report.getLayoutVersion()!=request.expectedLayoutVersion())
      throw conflict("STALE_LAYOUT_VERSION");
    if(!report.getTimeAxis().equals(orientation)){
      report.setTimeAxis(orientation);
      report.setLayoutVersion(report.getLayoutVersion()+1);
      reports.saveAndFlush(report);
    }
    return new LayoutReceipt(reportId,report.getLayoutVersion(),
       report.getTimeAxis(),report.getLatestRevisionNo());
  }

  @Transactional(readOnly=true)
  public GridView grid(UUID reportId,UUID org,String requestedAxis,
                       String requestedLocale,Integer revisionNo){
    return grid(reportId,org,requestedAxis,requestedLocale,revisionNo,null,"CODE");
  }

  @Transactional(readOnly=true)
  public GridView grid(UUID reportId,UUID org,String requestedAxis,
                       String requestedLocale,Integer revisionNo,
                       Integer requestedFormatVersion,String requestedPeriodLabelStyle){
    return grid(reportId,org,requestedAxis,requestedLocale,revisionNo,
      requestedFormatVersion,requestedPeriodLabelStyle,"VIEW",null);
  }

  @Transactional(readOnly=true)
  public GridView grid(UUID reportId,UUID org,String requestedAxis,
                       String requestedLocale,Integer revisionNo,
                       Integer requestedFormatVersion,String requestedPeriodLabelStyle,
                       String requestedViewMode,Integer requestedDerivedVersion){
    var report=owned(reportId,org);
    String mode=requestedViewMode==null?"VIEW":requestedViewMode;
    if(!Set.of("INPUT","VIEW","PREVIEW","EXPORT").contains(mode))
      throw invalid("INVALID_VIEW_MODE");
    int effectiveDerivedVersion=requestedDerivedVersion==null
      ?report.getDerivedVersion():requestedDerivedVersion;
    String l=locale(requestedLocale);
    java.util.Locale renderLocale=ReportFormatService.checkLocale(l);
    String labelStyle=ReportFormatService.checkPeriodStyle(
      requestedPeriodLabelStyle==null?"CODE":requestedPeriodLabelStyle);
    var formatViews=formatService.getFormats(reportId,org,requestedFormatVersion);
    var selectedFormats=formatService.index(formatViews);
    int effectiveFormatVersion=formatViews.getFirst().formatVersion();
    String orientation=requestedAxis==null?report.getTimeAxis():axis(requestedAxis);
    var selectedMetrics=bindings(report);
    var selectedPeriods=periods(report);
    Map<UUID,LibMetricTranslation> titles=new HashMap<>();
    metricTranslations.findByLocale(l).forEach(t->titles.put(t.getMetricId(),t));
    List<AxisItem> metricHeaders=new ArrayList<>();
    int index=0;
    for(var metric:selectedMetrics.values()){
      var text=titles.get(metric.metric().getId());
      metricHeaders.add(new AxisItem(metric.metric().getMetricCode(),
        text==null?null:text.getTitle(),text==null?"PENDING_TRANSLATION":
        text.getReviewStatus(),(++index)*10));
    }
    List<AxisItem> periodHeaders=new ArrayList<>();
    index=0;
    for(var p:selectedPeriods.values())
      periodHeaders.add(new AxisItem(p.getPeriodCode(),
        formatService.periodLabel(p,report.getPeriodType(),labelStyle,renderLocale),
        "PERIOD_CODE",(++index)*10));

    LibReportRevision selected=null;
    if(revisionNo!=null){
      if(revisionNo<1)throw invalid("INVALID_REVISION_NUMBER");
      selected=revisions.findByReportIdAndRevisionNo(reportId,revisionNo)
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"REVISION_NOT_FOUND"));
    }else if(report.getCurrentRevisionId()!=null)
      selected=revisions.findById(report.getCurrentRevisionId()).orElseThrow();

    Map<String,Value> values=new HashMap<>();
    if(selected!=null){
      Map<UUID,String> metricCodes=new HashMap<>();
      selectedMetrics.values().forEach(m->metricCodes.put(m.metric().getId(),m.metric().getMetricCode()));
      for(var cell:revisionCells.findByRevisionId(selected.getId())){
        String code=metricCodes.get(cell.getMetricId());
        if(code==null||!selectedPeriods.containsKey(cell.getPeriodCode()))
          throw new IllegalStateException("REPORT_CELL_BINDING_INTEGRITY");
        values.put(code+"|"+cell.getPeriodCode(),
          new Value(cell.getValueState(),cell.getDecimalValue()));
      }
    }
    List<MatrixCell> matrix=new ArrayList<>();
    // Row-major order is explicit. All pairs must be represented, including missing cells.
    if("COLUMNS".equals(orientation)){
      for(var m:metricHeaders)for(var p:periodHeaders){
        Value v=values.getOrDefault(m.code()+"|"+p.code(),new Value("MISSING",null));
        matrix.add(new MatrixCell(m.code(),p.code(),m.code(),p.code(),v.state(),v.value(),
          formatService.formatValue(v.state(),v.value(),selectedFormats.get(m.code()),
            renderLocale,report.getCurrencyCode())));
      }
    }else{
      for(var p:periodHeaders)for(var m:metricHeaders){
        Value v=values.getOrDefault(m.code()+"|"+p.code(),new Value("MISSING",null));
        matrix.add(new MatrixCell(p.code(),m.code(),m.code(),p.code(),v.state(),v.value(),
          formatService.formatValue(v.state(),v.value(),selectedFormats.get(m.code()),
            renderLocale,report.getCurrencyCode())));
      }
    }
    if(!"INPUT".equals(mode)){
      var derived=derivedService.calculate(reportId,org,effectiveDerivedVersion,matrix);
      if(!derived.isEmpty()){
        Map<String,ReportDerivedTotalService.DerivedResult> byMetric=new HashMap<>();
        derived.forEach(total->byMetric.put(total.metricCode(),total));
        int originalPeriods=periodHeaders.size();
        periodHeaders.add(new AxisItem("__TOTAL__",
          ReportDerivedTotalService.label(l),"UNQUALIFIED_DERIVED", (originalPeriods+1)*10));
        var withTotals=new ArrayList<MatrixCell>();
        if("COLUMNS".equals(orientation)){
          for(int i=0;i<metricHeaders.size();i++){
            for(int j=0;j<originalPeriods;j++)
              withTotals.add(matrix.get(i*originalPeriods+j));
            withTotals.add(derivedCell(metricHeaders.get(i).code(),orientation,
              byMetric.get(metricHeaders.get(i).code()),
              selectedFormats.get(metricHeaders.get(i).code()),renderLocale,l,
              report.getCurrencyCode()));
          }
        }else{
          withTotals.addAll(matrix);
          for(var metric:metricHeaders)
            withTotals.add(derivedCell(metric.code(),orientation,byMetric.get(metric.code()),
              selectedFormats.get(metric.code()),renderLocale,l,report.getCurrencyCode()));
        }
        matrix=withTotals;
      }
    }
    return new GridView(reportId,report.getReportCode(),report.getTitle(),
      report.getOrganizationId(),selected==null?0:selected.getRevisionNo(),
      selected==null?null:selected.getCanonicalContentSha256(),
      selected==null?"UNQUALIFIED_DRAFT":selected.getQualificationState(),
      report.getCurrencyCode(),report.getPeriodType(),orientation,
      report.getLayoutVersion(),
      "COLUMNS".equals(orientation)?metricHeaders:periodHeaders,
      "COLUMNS".equals(orientation)?periodHeaders:metricHeaders,
      List.copyOf(matrix),effectiveFormatVersion,mode,effectiveDerivedVersion);
  }

  private MatrixCell derivedCell(String metricCode,String axis,
      ReportDerivedTotalService.DerivedResult result,
      ReportFormatService.FormatView format,java.util.Locale locale,String language,String currency){
    String state=result==null?"NOT_CONFIGURED":result.state();
    String raw=result==null?null:result.rawValue();
    String output;
    if(result==null)output="";
    else if("INCOMPLETE".equals(state))
      output=ReportDerivedTotalService.incompleteLabel(language);
    else output=formatService.formatValue("PRESENT",raw,format,locale,currency);
    String row="COLUMNS".equals(axis)?metricCode:"__TOTAL__";
    String col="COLUMNS".equals(axis)?"__TOTAL__":metricCode;
    return new MatrixCell(row,col,metricCode,"__TOTAL__",state,raw,output,true);
  }
}
