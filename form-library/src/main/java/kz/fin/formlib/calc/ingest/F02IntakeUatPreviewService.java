package kz.fin.formlib.calc.ingest;

import kz.fin.formlib.calc.fact.CandidateFactSet;
import kz.fin.formlib.calc.fact.F02MappingDescriptor;
import kz.fin.formlib.calc.fact.F02SourceFactAssembler;
import kz.fin.formlib.calc.fact.FactDigest;
import kz.fin.formlib.calc.kernel.F02CalculationKernel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * DC-R2.2/2.3 first VERTICAL calculation:
 * exact saved F02 revision -> INGEST-2 source SHA rehash -> existing
 * typed F02 source assembler -> existing BigDecimal kernel -> read-only preview.
 *
 * Local/test UAT only (OFF by default); no fin_fact, no calc_plan and no
 * production S2/admission. This is not a persisted calculation receipt.
 */
@Service
@ConditionalOnProperty(name="fin.calc.intake-uat.enabled",havingValue="true")
public class F02IntakeUatPreviewService {
  public static final String STATE="FIN_INTAKE_UAT_PREVIEW_UNQUALIFIED";
  private final FinIntakeUatSourceAdapter intake;
  private final F02SourceFactAssembler assembler;
  private final F02CalculationKernel kernel=new F02CalculationKernel();

  public record Preview(
      UUID instanceId,UUID sourceRevisionId,UUID formVersionId,
      String sourceContentSha256,String sourceRowOrderSha256,
      String pinnedSourceSha256,String sourceSchemaSha256,
      String candidateFactsetSha256,String mappingDescriptorSha256,
      String periodType,String periodCode,String currency,
      String reportingBasis,String dataSpace,
      Map<String,F02CalculationKernel.NodeValue> outputs,
      String resultValuesSha256,String previewSha256,
      String executionState,boolean financiallyApproved,
      boolean productionAdmitted) {
    public Preview {
      outputs=java.util.Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
      FactDigest.requireSha(previewSha256,"F02_INTAKE_PREVIEW_SHA_INVALID");
      if(!STATE.equals(executionState)||financiallyApproved||productionAdmitted)
        throw new IllegalArgumentException("F02_INTAKE_PREVIEW_QUALIFICATION_FORBIDDEN");
    }
  }

  public F02IntakeUatPreviewService(
      FinIntakeUatSourceAdapter intake,F02SourceFactAssembler assembler) {
    this.intake=Objects.requireNonNull(intake);
    this.assembler=Objects.requireNonNull(assembler);
  }

  public Preview preview(UUID organizationId,UUID projectId,UUID exactRevisionId) {
    var pinned=intake.readExact(organizationId,projectId,exactRevisionId,"F02");
    var p=pinned.projection();
    // No caller-configurable mapping: the existing fixed TEST_ONLY pilot
    // descriptor is bound to the verified form version/schema/mapping.
    var descriptor=F02MappingDescriptor.testOnlyPilot(
        p.formVersionId(),p.sourceSchemaSha256(),p.sourceMappingSha256());
    CandidateFactSet source=assembler.assemble(p,descriptor);
    var values=new LinkedHashMap<String,BigDecimal>();
    for(var observation:source.observations()) {
      if(values.putIfAbsent(observation.metricCode(),observation.value())!=null)
        throw new IllegalStateException("F02_INTAKE_DUPLICATE_METRIC");
    }
    var result=kernel.evaluate(Map.copyOf(values));
    String digest=FactDigest.sha("FIN-FACT-DC-R2-F02-PREVIEW-V1",
        pinned.pinnedSourceSha256(),source.candidateFactsetSha256(),
        descriptor.descriptorSha256(),F02CalculationKernel.CONTRACT,
        result.outputValuesSha256(),STATE);
    var s=p.scope();
    return new Preview(p.instanceId(),p.revisionId(),p.formVersionId(),
        p.sourceSha256Claim(),p.rowOrderSha256(),
        pinned.pinnedSourceSha256(),p.sourceSchemaSha256(),
        source.candidateFactsetSha256(),descriptor.descriptorSha256(),
        s.periodType(),s.periodCode(),s.currencyCode(),
        s.reportingBasis(),s.dataSpace(),result.nodes(),
        result.outputValuesSha256(),digest,STATE,false,false);
  }
}
