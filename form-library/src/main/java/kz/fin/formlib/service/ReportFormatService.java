package kz.fin.formlib.service;

import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Read-time presentation only. Never changes the stored decimal or financial SHA. */
@Service
public class ReportFormatService {
  public record FormatInput(String metricCode,String formatKind,int decimalPlaces,
      int scaleDivisor,String roundingMode,boolean groupingEnabled,
      String negativeStyle,String currencyDisplay) {}
  public record FormatView(String metricCode,int formatVersion,String formatKind,
      int decimalPlaces,int scaleDivisor,String roundingMode,boolean groupingEnabled,
      String negativeStyle,String currencyDisplay) {}
  public record UpdateFormatRequest(UUID organizationId,int expectedFormatVersion,
                                    List<FormatInput> formats) {}
  public record UpdateFormatReceipt(UUID reportId,int formatVersion,int dataRevisionNo) {}

  private static final Set<String> LOCALES=Set.of("ru","kk","en","zh-Hans");
  private static final Set<String> FORMATS=Set.of(
      "MONEY","DECIMAL","INTEGER","PERCENT_POINTS","RATIO_PERCENT");
  private static final Set<Integer> SCALES=Set.of(1,1000,1000000);
  private static final Set<String> ROUNDING=Set.of("HALF_EVEN","HALF_UP","DOWN");
  private static final Set<String> NEGATIVE=Set.of("MINUS","PARENTHESES");
  private static final Set<String> CURRENCY=Set.of("NONE","CODE");
  private static final Set<String> PERIOD_LABELS=Set.of("CODE","SHORT","LONG");

  private final LibReportDefinitionRepository reports;
  private final LibReportMetricRepository reportMetrics;
  private final LibReportMetricFormatRepository formats;
  private final LibMetricDefinitionRepository metrics;
  private final LibCurrencyRepository currencies;
  private final FormDefinitionRepository forms;
  private final FormVersionRepository versions;
  private final FormFieldRepository fields;

  public ReportFormatService(LibReportDefinitionRepository reports,
      LibReportMetricRepository reportMetrics,LibReportMetricFormatRepository formats,
      LibMetricDefinitionRepository metrics,LibCurrencyRepository currencies,
      FormDefinitionRepository forms,FormVersionRepository versions,
      FormFieldRepository fields) {
    this.reports=reports;this.reportMetrics=reportMetrics;this.formats=formats;
    this.metrics=metrics;this.currencies=currencies;
    this.forms=forms;this.versions=versions;this.fields=fields;
  }
  private static ResponseStatusException invalid(String code) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }
  private static ResponseStatusException conflict(String code) {
    return new ResponseStatusException(HttpStatus.CONFLICT,code);
  }
  public static Locale checkLocale(String s) {
    if(!LOCALES.contains(s))throw invalid("UNSUPPORTED_LOCALE");
    return Locale.forLanguageTag(s);
  }
  public static String checkPeriodStyle(String s) {
    if(!PERIOD_LABELS.contains(s))throw invalid("INVALID_PERIOD_LABEL_STYLE");
    return s;
  }
  private LibReportDefinition owned(UUID reportId,UUID org){
    if(reportId==null||org==null)throw invalid("REPORT_CONTEXT_REQUIRED");
    return reports.findByIdAndOrganizationId(reportId,org)
      .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND"));
  }
  private String sourceType(LibMetricDefinition metric){
    if(!"SOURCE_FIELD".equals(metric.getMetricKind()))throw invalid("NOT_A_SOURCE_METRIC");
    var form=forms.findByFormCode(metric.getFormCode()).orElseThrow();
    var version=versions.findById(form.getCurrentVersionId()).orElseThrow();
    return fields.findByFormVersionIdOrderByDisplayOrderAsc(version.getId()).stream()
      .filter(f->f.getFieldCode().equals(metric.getSourceFieldCode()))
      .findFirst().orElseThrow(()->invalid("METRIC_FIELD_UNAVAILABLE"))
      .getFieldType();
  }
  private void validate(FormatInput f,String sourceType) {
    if(f==null||!FORMATS.contains(f.formatKind()))throw invalid("INVALID_FORMAT_KIND");
    if(f.decimalPlaces()<0||f.decimalPlaces()>12)throw invalid("FORMAT_DECIMALS_OUT_OF_RANGE");
    if(!SCALES.contains(f.scaleDivisor()))throw invalid("INVALID_FORMAT_SCALE");
    if(!ROUNDING.contains(f.roundingMode()))throw invalid("INVALID_ROUNDING_MODE");
    if(!NEGATIVE.contains(f.negativeStyle()))throw invalid("INVALID_NEGATIVE_STYLE");
    if(!CURRENCY.contains(f.currencyDisplay()))throw invalid("INVALID_CURRENCY_DISPLAY");
    if(!switch(sourceType) {
      case "MONEY" -> f.formatKind().equals("MONEY");
      case "DECIMAL" -> Set.of("DECIMAL","PERCENT_POINTS","RATIO_PERCENT").contains(f.formatKind());
      case "INTEGER" -> f.formatKind().equals("INTEGER");
      default -> false;
    })throw invalid("FORMAT_INCOMPATIBLE_WITH_METRIC_TYPE");
    if(!f.formatKind().equals("MONEY")&&!f.currencyDisplay().equals("NONE"))
      throw invalid("CURRENCY_DISPLAY_ON_NON_MONEY");
    if(f.formatKind().equals("INTEGER")&&f.decimalPlaces()!=0)
      throw invalid("INTEGER_DECIMALS_MUST_BE_ZERO");
    if(f.formatKind().endsWith("PERCENT")||f.formatKind().equals("PERCENT_POINTS")){
      if(f.scaleDivisor()!=1)throw invalid("PERCENT_SCALE_FORBIDDEN");
    }
  }
  private static FormatInput defaultFormat(String type,int currencyMinorUnits,String code){
    return switch(type){
      case "MONEY" -> new FormatInput(code,"MONEY",currencyMinorUnits,1,
          "HALF_EVEN",true,"MINUS","CODE");
      case "DECIMAL" -> new FormatInput(code,"DECIMAL",2,1,
          "HALF_EVEN",true,"MINUS","NONE");
      case "INTEGER" -> new FormatInput(code,"INTEGER",0,1,
          "HALF_EVEN",true,"MINUS","NONE");
      default -> throw invalid("UNSUPPORTED_FORMAT_METRIC_TYPE");
    };
  }
  private void writeFormats(LibReportDefinition report,int version,List<FormatInput> rules){
    var selected=reportMetrics.findByReportIdOrderByDisplayOrderAsc(report.getId());
    Map<String,UUID> ids=new LinkedHashMap<>();
    Map<String,String> types=new HashMap<>();
    for(var relation:selected){
      var metric=metrics.findById(relation.getMetricId()).orElseThrow();
      ids.put(metric.getMetricCode(),metric.getId());
      types.put(metric.getMetricCode(),sourceType(metric));
    }
    if(rules==null||rules.size()!=ids.size())throw invalid("FORMAT_COVERAGE_MISMATCH");
    Set<String> included=new HashSet<>();
    for(var f:rules){
      if(f==null||f.metricCode()==null||!ids.containsKey(f.metricCode()))
        throw invalid("UNSELECTED_FORMAT_METRIC");
      if(!included.add(f.metricCode()))throw invalid("DUPLICATE_FORMAT_METRIC");
      validate(f,types.get(f.metricCode()));
      var stored=new LibReportMetricFormat();
      stored.setId(UUID.randomUUID());stored.setReportId(report.getId());
      stored.setMetricId(ids.get(f.metricCode()));stored.setFormatVersion(version);
      stored.setFormatKind(f.formatKind());stored.setDecimalPlaces(f.decimalPlaces());
      stored.setScaleDivisor(f.scaleDivisor());stored.setRoundingMode(f.roundingMode());
      stored.setGroupingEnabled(f.groupingEnabled());stored.setNegativeStyle(f.negativeStyle());
      stored.setCurrencyDisplay(f.currencyDisplay());formats.save(stored);
    }
    formats.flush();
  }
  @Transactional
  public void initialize(LibReportDefinition report) {
    var currency=currencies.findById(report.getCurrencyCode()).orElseThrow();
    var selected=reportMetrics.findByReportIdOrderByDisplayOrderAsc(report.getId());
    List<FormatInput> values=new ArrayList<>();
    for(var rm:selected){
      var m=metrics.findById(rm.getMetricId()).orElseThrow();
      values.add(defaultFormat(sourceType(m),currency.getMinorUnits(),m.getMetricCode()));
    }
    writeFormats(report,1,values);
  }
  @Transactional(readOnly=true)
  public List<FormatView> getFormats(UUID reportId,UUID organizationId,Integer requestedVersion) {
    var report=owned(reportId,organizationId);
    int version=requestedVersion==null?report.getFormatVersion():requestedVersion;
    if(version<1||version>report.getFormatVersion())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"FORMAT_VERSION_NOT_FOUND");
    var list=formats.findByReportIdAndFormatVersion(reportId,version);
    if(list.size()!=reportMetrics.findByReportIdOrderByDisplayOrderAsc(reportId).size())
      throw new IllegalStateException("FORMAT_VERSION_INCOMPLETE");
    Map<UUID,String> codes=new HashMap<>();
    for(var row:reportMetrics.findByReportIdOrderByDisplayOrderAsc(reportId))
      codes.put(row.getMetricId(),metrics.findById(row.getMetricId()).orElseThrow().getMetricCode());
    var byId=new HashMap<UUID,LibReportMetricFormat>();
    list.forEach(f->byId.put(f.getMetricId(),f));
    return reportMetrics.findByReportIdOrderByDisplayOrderAsc(reportId).stream()
      .map(r->{
        var v=byId.get(r.getMetricId());
        return new FormatView(codes.get(r.getMetricId()),version,v.getFormatKind(),
          v.getDecimalPlaces(),v.getScaleDivisor(),v.getRoundingMode(),
          v.getGroupingEnabled(),v.getNegativeStyle(),v.getCurrencyDisplay());
      }).toList();
  }
  @Transactional
  public UpdateFormatReceipt update(UUID reportId,UpdateFormatRequest request) {
    if(request==null)throw invalid("FORMAT_REQUEST_REQUIRED");
    var report=owned(reportId,request.organizationId());
    if(report.getFormatVersion()!=request.expectedFormatVersion())
      throw conflict("STALE_FORMAT_VERSION");
    int next=report.getFormatVersion()+1;
    writeFormats(report,next,request.formats());
    report.setFormatVersion(next);
    reports.saveAndFlush(report);
    return new UpdateFormatReceipt(reportId,next,report.getLatestRevisionNo());
  }
  public Map<String,FormatView> index(List<FormatView> list){
    Map<String,FormatView> map=new HashMap<>();
    list.forEach(f->map.put(f.metricCode(),f));
    return map;
  }
  public String formatValue(String state,String raw,FormatView spec,Locale locale,String isoCurrency) {
    if("MISSING".equals(state))return "—";
    if("NOT_APPLICABLE".equals(state)){
      return switch(locale.getLanguage()){
        case "ru" -> "н/п";
        case "kk" -> "қ/ж";
        case "zh" -> "不适用";
        default -> "n/a";
      };
    }
    if(!"PRESENT".equals(state)||raw==null||spec==null)
      throw new IllegalStateException("UNRENDERABLE_CELL");
    BigDecimal number=new BigDecimal(raw);
    if("RATIO_PERCENT".equals(spec.formatKind()))number=number.multiply(BigDecimal.valueOf(100));
    number=number.divide(BigDecimal.valueOf(spec.scaleDivisor()),spec.decimalPlaces(),
      RoundingMode.valueOf(spec.roundingMode()));
    boolean isNegative=number.signum()<0;
    BigDecimal magnitude=isNegative?number.negate():number;
    DecimalFormatSymbols symbols=DecimalFormatSymbols.getInstance(locale);
    String prefix=spec.groupingEnabled()?"#,##0":"0";
    String pattern=prefix+(spec.decimalPlaces()>0?"."+"0".repeat(spec.decimalPlaces()):"");
    DecimalFormat formatter=new DecimalFormat(pattern,symbols);
    formatter.setGroupingUsed(spec.groupingEnabled());
    formatter.setParseBigDecimal(true);
    formatter.setRoundingMode(RoundingMode.valueOf(spec.roundingMode()));
    String text=formatter.format(magnitude);
    if(isNegative)text="PARENTHESES".equals(spec.negativeStyle())?"("+text+")":"-"+text;
    if(spec.formatKind().endsWith("PERCENT")||spec.formatKind().equals("PERCENT_POINTS"))
      text+=" %";
    if(spec.scaleDivisor()>1){
      String scaleName=switch(locale.getLanguage()){
        case "ru" -> spec.scaleDivisor()==1000?"тыс.":"млн.";
        case "kk" -> spec.scaleDivisor()==1000?"мың":"млн.";
        case "zh" -> spec.scaleDivisor()==1000?"千":"百万";
        default -> spec.scaleDivisor()==1000?"thousand":"million";
      };
      text+=" "+scaleName;
    }
    if("MONEY".equals(spec.formatKind())&&"CODE".equals(spec.currencyDisplay()))
      text+=" "+isoCurrency;
    return text;
  }
  public String periodLabel(LibReportPeriod period,String periodType,String style,Locale locale){
    checkPeriodStyle(style);
    if("CODE".equals(style))return period.getPeriodCode();
    LocalDate first=period.getStartInclusive();
    String code=period.getPeriodCode();
    DateTimeFormatter day=DateTimeFormatter.ofPattern("d MMM uuuu",locale);
    DateTimeFormatter month=DateTimeFormatter.ofPattern("MMM uuuu",locale);
    String shortLabel=switch(periodType){
      case "D" -> first.format(day);
      case "W" -> code.substring(5)+" "+code.substring(0,4);
      case "DEC" -> first.format(month)+" · "+code.substring(code.length()-1);
      case "M" -> first.format(month);
      case "Q","H" -> code.substring(5)+" "+code.substring(0,4);
      case "Y" -> code;
      default -> code;
    };
    if("SHORT".equals(style))return shortLabel;
    String range=first.format(day)+" – "+period.getEndExclusive().minusDays(1).format(day);
    return shortLabel+" ("+range+")";
  }
}
