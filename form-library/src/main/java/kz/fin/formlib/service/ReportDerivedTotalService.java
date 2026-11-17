package kz.fin.formlib.service;

import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.*;

/** An explicitly configured draft view calculation, not a qualified financial fact. */
@Service
public class ReportDerivedTotalService {
  public record RuleInput(String metricCode,String operator) {}
  public record RuleView(String metricCode,int derivedVersion,String operator,
                         String visibility,String completenessPolicy,String reviewState) {}
  public record UpdateRequest(UUID organizationId,int expectedDerivedVersion,List<RuleInput> rules) {}
  public record UpdateReceipt(UUID reportId,int derivedVersion,int dataRevisionNo) {}
  public record DerivedResult(String metricCode,String operator,String state,String rawValue,
                              String reviewState) {}

  private final LibReportDefinitionRepository reports;
  private final LibReportMetricRepository reportMetrics;
  private final LibReportPeriodRepository reportPeriods;
  private final LibReportDerivedRuleRepository ruleRepository;
  private final LibMetricDefinitionRepository metrics;

  public ReportDerivedTotalService(LibReportDefinitionRepository reports,
      LibReportMetricRepository reportMetrics,LibReportPeriodRepository reportPeriods,
      LibReportDerivedRuleRepository ruleRepository,LibMetricDefinitionRepository metrics){
    this.reports=reports;this.reportMetrics=reportMetrics;this.reportPeriods=reportPeriods;
    this.ruleRepository=ruleRepository;this.metrics=metrics;
  }
  private static ResponseStatusException invalid(String code){
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }
  private static ResponseStatusException conflict(String code){
    return new ResponseStatusException(HttpStatus.CONFLICT,code);
  }
  private LibReportDefinition owned(UUID reportId,UUID org){
    if(reportId==null||org==null)throw invalid("REPORT_CONTEXT_REQUIRED");
    return reports.findByIdAndOrganizationId(reportId,org)
      .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND"));
  }
  private Map<String,UUID> selectedMetrics(UUID reportId){
    var result=new LinkedHashMap<String,UUID>();
    for(var rm:reportMetrics.findByReportIdOrderByDisplayOrderAsc(reportId)){
      var metric=metrics.findById(rm.getMetricId()).orElseThrow();
      result.put(metric.getMetricCode(),metric.getId());
    }
    return result;
  }
  @Transactional(readOnly=true)
  public List<RuleView> getRules(UUID reportId,UUID org,Integer requestedVersion){
    var report=owned(reportId,org);
    int version=requestedVersion==null?report.getDerivedVersion():requestedVersion;
    if(version<1||version>report.getDerivedVersion())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"DERIVED_VERSION_NOT_FOUND");
    var codes=new HashMap<UUID,String>();
    selectedMetrics(reportId).forEach((code,id)->codes.put(id,code));
    var result=new ArrayList<RuleView>();
    for(var rule:ruleRepository.findByReportIdAndDerivedVersion(reportId,version)){
      var code=codes.get(rule.getMetricId());
      if(code==null)throw new IllegalStateException("DERIVED_RULE_METRIC_MISSING");
      result.add(new RuleView(code,version,rule.getOperator(),rule.getVisibility(),
        rule.getCompletenessPolicy(),rule.getReviewState()));
    }
    result.sort(Comparator.comparing(RuleView::metricCode));
    return List.copyOf(result);
  }
  @Transactional
  public UpdateReceipt update(UUID reportId,UpdateRequest request){
    if(request==null)throw invalid("DERIVED_REQUEST_REQUIRED");
    var report=owned(reportId,request.organizationId());
    if(report.getDerivedVersion()!=request.expectedDerivedVersion())
      throw conflict("STALE_DERIVED_VERSION");
    List<RuleInput> input=request.rules()==null?List.of():request.rules();
    var selected=selectedMetrics(reportId);
    Set<String> seen=new HashSet<>();
    int version=report.getDerivedVersion()+1;
    for(var item:input){
      if(item==null||item.metricCode()==null||!selected.containsKey(item.metricCode()))
        throw invalid("UNSELECTED_DERIVED_METRIC");
      if(!seen.add(item.metricCode()))throw invalid("DUPLICATE_DERIVED_METRIC");
      if(!Set.of("SUM","LAST_VALUE").contains(item.operator()))
        throw invalid("UNSUPPORTED_DERIVED_OPERATOR");
      var rule=new LibReportDerivedRule();
      rule.setId(UUID.randomUUID());rule.setReportId(reportId);
      rule.setMetricId(selected.get(item.metricCode()));rule.setDerivedVersion(version);
      rule.setOperator(item.operator());rule.setVisibility("OUTPUT_ONLY");
      rule.setCompletenessPolicy("REQUIRE_ALL_PRESENT");
      rule.setReviewState("UNQUALIFIED_DRAFT");
      ruleRepository.save(rule);
    }
    ruleRepository.flush();
    report.setDerivedVersion(version);
    reports.saveAndFlush(report);
    return new UpdateReceipt(reportId,version,report.getLatestRevisionNo());
  }
  private static String raw(BigDecimal value){
    return value.signum()==0?"0":value.stripTrailingZeros().toPlainString();
  }
  @Transactional(readOnly=true)
  public List<DerivedResult> calculate(UUID reportId,UUID org,Integer requestedVersion,
      List<ReportGridService.MatrixCell> baseCells){
    var rules=getRules(reportId,org,requestedVersion);
    if(rules.isEmpty())return List.of();
    // Input grid must be complete; unconfigured values remain MISSING, not zero.
    var periodOrder=new ArrayList<>(
      reportPeriods.findByReportIdOrderByDisplayOrderAsc(reportId));
    if(periodOrder.isEmpty())throw new IllegalStateException("DERIVED_NO_PERIODS");
    periodOrder.sort(Comparator.comparing(LibReportPeriod::getStartInclusive)
      .thenComparing(LibReportPeriod::getPeriodCode));
    Map<String,ReportGridService.MatrixCell> byPair=new HashMap<>();
    for(var cell:baseCells){
      if(!Set.of("PRESENT","NOT_APPLICABLE","MISSING").contains(cell.state()))
        throw new IllegalStateException("DERIVED_INPUT_NOT_RAW");
      byPair.put(cell.metricCode()+"|"+cell.periodCode(),cell);
    }
    List<DerivedResult> result=new ArrayList<>();
    for(var rule:rules){
      boolean complete=true;
      BigDecimal sum=BigDecimal.ZERO;
      BigDecimal last=null;
      for(var period:periodOrder){
        var cell=byPair.get(rule.metricCode()+"|"+period.getPeriodCode());
        if(cell==null||!"PRESENT".equals(cell.state())||cell.value()==null){
          complete=false;break;
        }
        BigDecimal value=new BigDecimal(cell.value());
        sum=sum.add(value);
        last=value;
      }
      result.add(new DerivedResult(rule.metricCode(),rule.operator(),
        complete?"PRESENT":"INCOMPLETE",
        complete?raw("SUM".equals(rule.operator())?sum:last):null,
        rule.reviewState()));
    }
    return List.copyOf(result);
  }
  public static String label(String locale){
    return switch(locale){
      case "ru" -> "Итого";
      case "kk" -> "Жиыны";
      case "zh-Hans" -> "合计";
      default -> "Total";
    };
  }
  public static String incompleteLabel(String locale){
    return switch(locale){
      case "ru" -> "неполные данные";
      case "kk" -> "деректер толық емес";
      case "zh-Hans" -> "数据不完整";
      default -> "incomplete";
    };
  }
}
