package kz.fin.formlib.calc.graph;

import kz.fin.formlib.calc.ingest.FinIntakeUatSourceAdapter;
import kz.fin.formlib.calc.ingest.FormFactCandidateExtractor;
import kz.fin.formlib.calc.loan.LoanScheduleService;
import kz.fin.formlib.calc.loan.LoanAmendmentService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * MG-R0.3: read-only, exact revision source availability for the existing
 * graph and A05 calculation projection. No default "latest" resolution,
 * no inferred metric mappings, no automatically approved values.
 */
@Service
public class MetricSourceAvailabilityService {
  public record Request(UUID bindingRevisionId,UUID organizationId,
      UUID projectId,Map<String,UUID> exactRevisionsByForm,
      String expectedPeriodType,String expectedPeriodCode,String expectedCurrency,
      String expectedReportingBasis,String expectedDataSpace) {
    public Request{
      exactRevisionsByForm=exactRevisionsByForm==null
        ?Map.of():Map.copyOf(exactRevisionsByForm);
    }
  }
  public record Requirement(String metricCode,String formCode,String fieldCode,
      UUID exactRevisionId,String sourceSha256,String sourceSchemaSha256,
      String periodType,String periodCode,String currency,
      String state,String reason,String typedValue) {}
  public record Availability(UUID bindingRevisionId,String metricCode,
      String bindingState,String graphState,List<Requirement> inputs,
      String overallState,boolean executable,boolean financiallyQualified) {
    public Availability{inputs=List.copyOf(inputs);}
  }
  public record A05Node(String nodeKind,String metricCode,
      UUID scheduleRevisionId,UUID sourceRevisionId,UUID contractRevisionId,
      UUID methodContractRevisionId,String sourceSha256,String termsSha256,
      String resultSha256,String state,List<String> dependencies,
      boolean executable,boolean financiallyQualified) {
    public A05Node{dependencies=List.copyOf(dependencies);}
  }

  private final MetricIndicatorBindingService links;
  private final FinIntakeUatSourceAdapter intake;
  private final LoanScheduleService schedules;
  private final LoanAmendmentService amendments;
  public MetricSourceAvailabilityService(MetricIndicatorBindingService links,
      FinIntakeUatSourceAdapter intake,LoanScheduleService schedules,
      LoanAmendmentService amendments){
    this.links=Objects.requireNonNull(links);
    this.intake=Objects.requireNonNull(intake);
    this.schedules=Objects.requireNonNull(schedules);
    this.amendments=Objects.requireNonNull(amendments);
  }
  private static void check(boolean ok,String message){
    if(!ok)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Availability resolve(Request request) {
    check(request!=null&&request.bindingRevisionId()!=null
        &&request.organizationId()!=null&&request.projectId()!=null,
        "MG_R03_EXACT_SCOPE_AND_BINDING_REQUIRED");
    check(request.expectedCurrency()!=null&&!request.expectedCurrency().isBlank()
        &&request.expectedPeriodType()!=null&&!request.expectedPeriodType().isBlank()
        &&request.expectedPeriodCode()!=null&&!request.expectedPeriodCode().isBlank()
        &&request.expectedReportingBasis()!=null&&!request.expectedReportingBasis().isBlank()
        &&request.expectedDataSpace()!=null&&!request.expectedDataSpace().isBlank(),
        "MG_R03_EXPLICIT_TEMPORAL_AND_ACCOUNTING_SCOPE_REQUIRED");
    var binding=links.requireExact(request.bindingRevisionId());
    var graph=binding.metricGraph();
    var resolved=new ArrayList<Requirement>();
    var requiredForms=new HashSet<String>();
    for(var input:graph.requiredPhysicalInputs()) {
      requiredForms.add(input.formCode());
      UUID pinned=request.exactRevisionsByForm().get(input.formCode());
      if(pinned==null) {
        resolved.add(new Requirement(input.metricCode(),input.formCode(),
          input.fieldCode(),null,null,null,null,null,null,
          "MISSING_SOURCE","MG_EXACT_SOURCE_REVISION_REQUIRED",null));
        continue;
      }
      // Adapter refuses scope mismatch and does not fall back to latest revision.
      var source=intake.readExact(request.organizationId(),request.projectId(),
        pinned,input.formCode()).projection();
      var scope=source.scope();
      String state="SOURCE_PRESENT_UNQUALIFIED",reason="CANDIDATE_NOT_S2";
      String value=null;
      if(!input.formVersionId().equals(source.formVersionId())
          ||!input.schemaSha256().equals(source.sourceSchemaSha256())) {
        state="STALE_SOURCE";reason="MG_SOURCE_SCHEMA_DRIFT";
      }else if(!request.expectedPeriodType().equals(scope.periodType())
          ||!request.expectedPeriodCode().equals(scope.periodCode())
          ||!request.expectedCurrency().equals(scope.currencyCode())
          ||!request.expectedReportingBasis().equals(scope.reportingBasis())
          ||!request.expectedDataSpace().equals(scope.dataSpace())){
        state="INCOMPATIBLE_SCOPE";reason="MG_PERIOD_CURRENCY_OR_BASIS_MISMATCH";
      }else{
        List<FormFactCandidateExtractor.NumericCandidate> numbers=
          source.numeric().stream()
            .filter(v->v.sourceFieldCode().equals(input.fieldCode())).toList();
        if(numbers.isEmpty()){
          state="MISSING_VALUE";reason="MG_REQUIRED_FIELD_NOT_ENTERED";
        }else if(numbers.size()!=1) {
          state="ROW_SELECTION_REQUIRED";reason="MG_ROW_GRAIN_NOT_DECLARED";
        }else if(!"PRESENT".equals(numbers.getFirst().valueState())
          ||numbers.getFirst().numericValue()==null){
          state="MISSING_VALUE";reason="MG_REQUIRED_FIELD_BLANK_OR_INVALID";
        }else{
          value=numbers.getFirst().numericValue().toPlainString();
        }
      }
      resolved.add(new Requirement(input.metricCode(),input.formCode(),
        input.fieldCode(),pinned,source.sourceSha256Claim(),
        source.sourceSchemaSha256(),scope.periodType(),scope.periodCode(),
        scope.currencyCode(),state,reason,value));
    }
    // Supplied unrelated sources are not accepted as implicit graph inputs.
    check(requiredForms.containsAll(request.exactRevisionsByForm().keySet()),
      "MG_R03_UNEXPECTED_SOURCE_FORM");
    String overall;
    if(!"DIRECT_SOURCE_CANDIDATE_NOT_CALCULATED".equals(binding.state())
        &&!"EXACT_DAG_CANDIDATE_NOT_EXECUTABLE".equals(binding.state())){
      overall="METHOD_OR_METRIC_NOT_AUTHORED";
    }else if(resolved.stream().anyMatch(r->!"SOURCE_PRESENT_UNQUALIFIED".equals(r.state()))) {
      overall="REQUIRED_INPUTS_UNRESOLVED";
    }else if(resolved.isEmpty()){
      overall="REQUIRED_INPUTS_UNRESOLVED";
    }else{
      overall="INPUTS_PRESENT_NOT_FINANCIALLY_QUALIFIED";
    }
    return new Availability(binding.binding().id(),binding.binding().targetMetricCode(),
      binding.state(),graph.state(),resolved,overall,false,false);
  }

  /** Exact amendment remains a child graph result, never a cash fact. */
  public record A05AmendmentNode(String nodeKind,String metricCode,
      UUID amendmentRevisionId,UUID parentScheduleRevisionId,
      UUID sourceRevisionId,String sourceSha256,
      String parentResultSha256,String resultSha256,String state,
      List<String> dependencies,boolean executable,
      boolean financiallyQualified){
    public A05AmendmentNode{dependencies=List.copyOf(dependencies);}
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public A05AmendmentNode a05AmendmentExact(UUID organizationId,UUID projectId,
      UUID amendmentRevisionId){
    check(organizationId!=null&&projectId!=null&&amendmentRevisionId!=null,
      "MG_R03_A05_AMENDMENT_REVISION_REQUIRED");
    var result=amendments.readExact(organizationId,projectId,amendmentRevisionId);
    return new A05AmendmentNode("SPECIALIZED_CALC_RESULT",
      "CALC-A05.AMENDED_CONTRACT_CASH",result.amendmentRevisionId(),
      result.parentScheduleRevisionId(),result.exactSourceRevisionId(),
      result.sourceSha256(),result.parentResultSha256(),result.resultSha256(),
      "UAT_REPLAY_VERIFIED_GRAPH_NODE_NOT_QUALIFIED",
      List.of("C05:"+result.exactSourceRevisionId(),
        "CALC-A05:SCHEDULE:"+result.parentScheduleRevisionId(),
        "CALC-A05:AMENDMENT-EVENT:"+result.eventSha256()),
      false,false);
  }

  /** A05 cash result is a specialized graph node; not a new metric registry. */
  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public A05Node a05Exact(UUID organizationId,UUID projectId,UUID scheduleId){
    check(organizationId!=null&&projectId!=null&&scheduleId!=null,
      "MG_R03_A05_EXACT_RESULT_REQUIRED");
    var result=schedules.readExact(organizationId,projectId,scheduleId);
    return new A05Node("SPECIALIZED_CALC_RESULT","CALC-A05.CONTRACT_CASH",
      result.scheduleRevisionId(),result.exactSourceRevisionId(),
      result.contractRevisionId(),result.appContractRevisionId(),
      result.sourceContentSha256(),result.termsSha256(),result.resultSha256(),
      "UAT_REPLAY_VERIFIED_GRAPH_NODE_NOT_QUALIFIED",
      List.of("C05:"+result.exactSourceRevisionId(),
        "CALC-A05:"+result.appContractRevisionId()),
      false,false);
  }
}
