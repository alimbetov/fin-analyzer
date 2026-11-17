package kz.fin.formlib.service;

import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Versioned input constraints. No implicit zero, and no financial source approval. */
@Service
public class ReportInputValidationService {
  public record RuleInput(String metricCode,boolean allowMissing,boolean allowNotApplicable,
      String signRule,String minValue,String maxValue,int maxPrecision,int maxScale) {}
  public record RuleView(String metricCode,int validationVersion,
      boolean allowMissing,boolean allowNotApplicable,String signRule,
      String minValue,String maxValue,int maxPrecision,int maxScale) {}
  public record UpdateRequest(UUID organizationId,int expectedValidationVersion,List<RuleInput> rules) {}
  public record UpdateReceipt(UUID reportId,int validationVersion,int dataRevisionNo,String rulesSha256) {}
  public record ValidationIssue(String metricCode,String periodCode,String code) {}
  public record ValidationResult(UUID reportId,int validationVersion,String rulesSha256,
                                 boolean valid,List<ValidationIssue> issues) {}
  private static final Set<String> SIGNS=Set.of(
      "ANY","NON_NEGATIVE","POSITIVE","NON_POSITIVE","NEGATIVE","ZERO_ONLY","NON_ZERO");
  private final LibReportDefinitionRepository reports;
  private final LibReportMetricRepository reportMetrics;
  private final LibReportPeriodRepository reportPeriods;
  private final LibReportMetricValidationRepository repository;
  private final LibMetricDefinitionRepository metrics;
  private final FormDefinitionRepository forms;
  private final FormVersionRepository versions;
  private final FormFieldRepository fields;
  private final LibCurrencyRepository currencies;

  public ReportInputValidationService(LibReportDefinitionRepository reports,
      LibReportMetricRepository reportMetrics,LibReportPeriodRepository reportPeriods,
      LibReportMetricValidationRepository repository,LibMetricDefinitionRepository metrics,
      FormDefinitionRepository forms,FormVersionRepository versions,
      FormFieldRepository fields,LibCurrencyRepository currencies){
    this.reports=reports;this.reportMetrics=reportMetrics;this.reportPeriods=reportPeriods;
    this.repository=repository;this.metrics=metrics;this.forms=forms;
    this.versions=versions;this.fields=fields;this.currencies=currencies;
  }
  private static ResponseStatusException invalid(String code){
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }
  private static ResponseStatusException conflict(String code){
    return new ResponseStatusException(HttpStatus.CONFLICT,code);
  }
  public LibReportDefinition owned(UUID reportId,UUID org){
    if(reportId==null||org==null)throw invalid("REPORT_CONTEXT_REQUIRED");
    return reports.findByIdAndOrganizationId(reportId,org)
      .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND"));
  }
  private String sourceType(LibMetricDefinition metric){
    if(!"SOURCE_FIELD".equals(metric.getMetricKind()))throw invalid("SOURCE_METRIC_REQUIRED");
    var f=forms.findByFormCode(metric.getFormCode()).orElseThrow();
    var v=versions.findById(f.getCurrentVersionId()).orElseThrow();
    return fields.findByFormVersionIdOrderByDisplayOrderAsc(v.getId()).stream()
      .filter(x->x.getFieldCode().equals(metric.getSourceFieldCode()))
      .findFirst().orElseThrow(()->invalid("SOURCE_FIELD_NOT_FOUND"))
      .getFieldType();
  }
  private Map<String,UUID> selected(LibReportDefinition report){
    Map<String,UUID> out=new LinkedHashMap<>();
    for(var selection:reportMetrics.findByReportIdOrderByDisplayOrderAsc(report.getId())){
      var metric=metrics.findById(selection.getMetricId()).orElseThrow();
      out.put(metric.getMetricCode(),metric.getId());
    }
    return out;
  }
  private static String numeric(String value,String name){
    if(value==null||value.isBlank())return null;
    if(!value.matches("-?[0-9]{1,26}(\\.[0-9]{1,12})?"))
      throw invalid("INVALID_RULE_"+name);
    BigDecimal n=new BigDecimal(value);
    if(n.precision()>38||n.scale()>12)throw invalid("INVALID_RULE_"+name);
    return n.signum()==0?"0":n.stripTrailingZeros().toPlainString();
  }
  private void checkRule(RuleInput input,String fieldType,int currencyMinorUnits){
    if(input==null||!SIGNS.contains(input.signRule()))throw invalid("INVALID_SIGN_RULE");
    if(input.maxPrecision()<1||input.maxPrecision()>38)throw invalid("INVALID_RULE_PRECISION");
    if(input.maxScale()<0||input.maxScale()>12)throw invalid("INVALID_RULE_SCALE");
    if("INTEGER".equals(fieldType)&&input.maxScale()!=0)
      throw invalid("INTEGER_SCALE_MUST_BE_ZERO");
    if("MONEY".equals(fieldType)&&input.maxScale()>currencyMinorUnits)
      throw invalid("RULE_EXCEEDS_CURRENCY_MINOR_UNITS");
    String min=numeric(input.minValue(),"MIN"),max=numeric(input.maxValue(),"MAX");
    if(min!=null&&max!=null&&new BigDecimal(min).compareTo(new BigDecimal(max))>0)
      throw invalid("RULE_MIN_GREATER_THAN_MAX");
  }
  private void write(LibReportDefinition report,int version,List<RuleInput> list) {
    Map<String,UUID> selected=selected(report);
    if(list==null||list.size()!=selected.size())throw invalid("RULE_COVERAGE_MISMATCH");
    int minorUnits=currencies.findById(report.getCurrencyCode()).orElseThrow().getMinorUnits();
    Set<String> seen=new HashSet<>();
    for(var input:list){
      if(input==null||input.metricCode()==null||!selected.containsKey(input.metricCode()))
        throw invalid("UNSELECTED_VALIDATION_METRIC");
      if(!seen.add(input.metricCode()))throw invalid("DUPLICATE_VALIDATION_METRIC");
      var metric=metrics.findById(selected.get(input.metricCode())).orElseThrow();
      checkRule(input,sourceType(metric),minorUnits);
      var rule=new LibReportMetricValidation();
      rule.setId(UUID.randomUUID());rule.setReportId(report.getId());
      rule.setMetricId(metric.getId());rule.setValidationVersion(version);
      rule.setAllowMissing(input.allowMissing());rule.setAllowNotApplicable(input.allowNotApplicable());
      rule.setSignRule(input.signRule());rule.setMinValue(numeric(input.minValue(),"MIN"));
      rule.setMaxValue(numeric(input.maxValue(),"MAX"));
      rule.setMaxPrecision(input.maxPrecision());rule.setMaxScale(input.maxScale());
      repository.save(rule);
    }
    repository.flush();
  }
  @Transactional
  public void initialize(LibReportDefinition report){
    int moneyScale=currencies.findById(report.getCurrencyCode()).orElseThrow().getMinorUnits();
    var defaults=new ArrayList<RuleInput>();
    for(var selection:reportMetrics.findByReportIdOrderByDisplayOrderAsc(report.getId())){
      var metric=metrics.findById(selection.getMetricId()).orElseThrow();
      String type=sourceType(metric);
      defaults.add(new RuleInput(metric.getMetricCode(),true,true,
         "ANY",null,null,38,
         "INTEGER".equals(type)?0:"MONEY".equals(type)?moneyScale:12));
    }
    write(report,1,defaults);
  }
  @Transactional(readOnly=true)
  public List<RuleView> getRules(UUID id,UUID organizationId,Integer requestedVersion){
    var report=owned(id,organizationId);
    int version=requestedVersion==null?report.getValidationVersion():requestedVersion;
    if(version<1||version>report.getValidationVersion())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"VALIDATION_VERSION_NOT_FOUND");
    var selected=selected(report);
    var stored=repository.findByReportIdAndValidationVersion(id,version);
    if(stored.size()!=selected.size())throw new IllegalStateException("VALIDATION_VERSION_INCOMPLETE");
    var byMetricId=new HashMap<UUID,LibReportMetricValidation>();
    stored.forEach(x->byMetricId.put(x.getMetricId(),x));
    var out=new ArrayList<RuleView>();
    for(var entry:selected.entrySet()){
      var r=byMetricId.get(entry.getValue());
      if(r==null)throw new IllegalStateException("VALIDATION_RULE_MISSING");
      out.add(new RuleView(entry.getKey(),version,r.getAllowMissing(),
         r.getAllowNotApplicable(),r.getSignRule(),r.getMinValue(),r.getMaxValue(),
         r.getMaxPrecision(),r.getMaxScale()));
    }
    return List.copyOf(out);
  }
  private static String sha(String content){
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(content.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception ex){throw new IllegalStateException(ex);}
  }
  public String rulesSha(List<RuleView> list){
    var sorted=new ArrayList<>(list);
    sorted.sort(Comparator.comparing(RuleView::metricCode));
    StringBuilder sb=new StringBuilder("FIN-LIB-VALIDATION-V1");
    for(var r:sorted){
      sb.append('|').append(r.metricCode()).append('|').append(r.validationVersion())
        .append('|').append(r.allowMissing()).append('|').append(r.allowNotApplicable())
        .append('|').append(r.signRule()).append('|')
        .append(r.minValue()).append('|').append(r.maxValue())
        .append('|').append(r.maxPrecision()).append('|').append(r.maxScale());
    }
    return sha(sb.toString());
  }
  @Transactional
  public UpdateReceipt update(UUID reportId,UpdateRequest request){
    if(request==null)throw invalid("VALIDATION_REQUEST_REQUIRED");
    var report=owned(reportId,request.organizationId());
    if(!report.getValidationVersion().equals(request.expectedValidationVersion()))
      throw conflict("STALE_VALIDATION_VERSION");
    int next=report.getValidationVersion()+1;
    write(report,next,request.rules());
    report.setValidationVersion(next);
    reports.saveAndFlush(report);
    return new UpdateReceipt(reportId,next,report.getLatestRevisionNo(),
      rulesSha(getRules(reportId,request.organizationId(),next)));
  }
  private static boolean signValid(BigDecimal value,String sign){
    int c=value.signum();
    return switch(sign){
      case "ANY" -> true;
      case "NON_NEGATIVE" -> c>=0;
      case "POSITIVE" -> c>0;
      case "NON_POSITIVE" -> c<=0;
      case "NEGATIVE" -> c<0;
      case "ZERO_ONLY" -> c==0;
      case "NON_ZERO" -> c!=0;
      default -> false;
    };
  }
  @Transactional(readOnly=true)
  public ValidationResult validateSnapshot(UUID reportId,UUID org,
         List<ReportGridService.CellInput> cells){
    var report=owned(reportId,org);
    var rules=getRules(reportId,org,null);
    Map<String,RuleView> rulesByMetric=new HashMap<>();
    rules.forEach(r->rulesByMetric.put(r.metricCode(),r));
    Set<String> permittedPeriods=new LinkedHashSet<>();
    reportPeriods.findByReportIdOrderByDisplayOrderAsc(reportId)
      .forEach(x->permittedPeriods.add(x.getPeriodCode()));
    Map<String,ReportGridService.CellInput> given=new HashMap<>();
    List<ValidationIssue> issues=new ArrayList<>();
    var input=cells==null?List.<ReportGridService.CellInput>of():cells;
    for(var c:input){
      if(c==null){issues.add(new ValidationIssue(null,null,"NULL_CELL"));continue;}
      RuleView rule=rulesByMetric.get(c.metricCode());
      String period=null;
      try{period=PeriodResolver.resolve(report.getPeriodType(),c.periodCode()).periodCode();}
      catch(IllegalArgumentException ex){issues.add(new ValidationIssue(c.metricCode(),c.periodCode(),"INVALID_PERIOD"));continue;}
      if(rule==null||!permittedPeriods.contains(period)){
        issues.add(new ValidationIssue(c.metricCode(),period,"UNSELECTED_METRIC_OR_PERIOD"));continue;
      }
      String key=c.metricCode()+"|"+period;
      if(given.putIfAbsent(key,c)!=null){
        issues.add(new ValidationIssue(c.metricCode(),period,"DUPLICATE_METRIC_PERIOD_CELL"));continue;
      }
      if("NOT_APPLICABLE".equals(c.state())){
        if(c.value()!=null||!rule.allowNotApplicable())
          issues.add(new ValidationIssue(c.metricCode(),period,"NOT_APPLICABLE_FORBIDDEN"));
        continue;
      }
      if(!"PRESENT".equals(c.state())||c.value()==null||c.value().isBlank()){
        issues.add(new ValidationIssue(c.metricCode(),period,"INVALID_PRESENT_VALUE"));continue;
      }
      try {
        BigDecimal value=new BigDecimal(c.value());
        if(value.precision()>rule.maxPrecision()||
           Math.max(0,value.stripTrailingZeros().scale())>rule.maxScale())
          issues.add(new ValidationIssue(c.metricCode(),period,"PRECISION_OR_SCALE_EXCEEDED"));
        if(!signValid(value,rule.signRule()))
          issues.add(new ValidationIssue(c.metricCode(),period,"SIGN_RULE_VIOLATION"));
        if(rule.minValue()!=null&&value.compareTo(new BigDecimal(rule.minValue()))<0)
          issues.add(new ValidationIssue(c.metricCode(),period,"BELOW_MINIMUM"));
        if(rule.maxValue()!=null&&value.compareTo(new BigDecimal(rule.maxValue()))>0)
          issues.add(new ValidationIssue(c.metricCode(),period,"ABOVE_MAXIMUM"));
      }catch(NumberFormatException ex){
        issues.add(new ValidationIssue(c.metricCode(),period,"INVALID_DECIMAL"));
      }
    }
    for(var rule:rules)if(!rule.allowMissing())
      for(String period:permittedPeriods)
        if(!given.containsKey(rule.metricCode()+"|"+period))
          issues.add(new ValidationIssue(rule.metricCode(),period,"MISSING_REQUIRED_CELL"));
    return new ValidationResult(reportId,report.getValidationVersion(),
       rulesSha(rules),issues.isEmpty(),List.copyOf(issues));
  }
}
