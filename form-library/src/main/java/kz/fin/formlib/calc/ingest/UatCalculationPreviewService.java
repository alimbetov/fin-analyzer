package kz.fin.formlib.calc.ingest;

import kz.fin.formlib.calc.fact.FactDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * DC-R2.5: one Calculate / Explain API contract across F02/C01/C02.
 * Delegates to the SAME existing calculation kernels. This does not
 * implement a second financial engine or promote an UAT candidate.
 */
@Service
@ConditionalOnProperty(name="fin.calc.intake-uat.enabled",havingValue="true")
public class UatCalculationPreviewService {
  private final F02IntakeUatPreviewService f02;
  private final C01C02IntakeUatPreviewService asOf;

  public UatCalculationPreviewService(F02IntakeUatPreviewService f02,
      C01C02IntakeUatPreviewService asOf){
    this.f02=Objects.requireNonNull(f02);
    this.asOf=Objects.requireNonNull(asOf);
  }

  public UatCalculationPreview preview(UUID organizationId,UUID projectId,
      UUID exactRevisionId,String formCode) {
    if(organizationId==null||projectId==null||exactRevisionId==null)
      throw new IllegalArgumentException("CALC_UAT_EXACT_SOURCE_REQUIRED");
    return switch(formCode==null?"":formCode){
      case "F02"->f02(organizationId,projectId,exactRevisionId);
      case "C01","C02"->asOf.preview(
          organizationId,projectId,exactRevisionId,formCode);
      default->throw new IllegalArgumentException("CALC_UAT_FORM_UNSUPPORTED");
    };
  }

  private UatCalculationPreview f02(
      UUID organizationId,UUID projectId,UUID exactRevisionId) {
    var raw=f02.preview(organizationId,projectId,exactRevisionId);
    var outputs=new LinkedHashMap<String,UatCalculationPreview.Node>();
    for(var pair:raw.outputs().entrySet()){
      var node=pair.getValue();
      outputs.put(pair.getKey(),new UatCalculationPreview.Node(
          node.state(),node.unit(),node.value(),node.errorCode()));
    }
    String methodSha=FactDigest.sha("FIN-FACT-DC-R2-F02-METHOD-V1",
        raw.mappingDescriptorSha256(),"FIN-CALC-R02-F02-KERNEL-V1");
    String normalizedSha=FactDigest.sha("FIN-FACT-DC-R2-F02-UNIFIED-V1",
        raw.previewSha256(),methodSha,raw.sourceRevisionId().toString());
    return new UatCalculationPreview("F02",raw.instanceId(),
        raw.sourceRevisionId(),raw.formVersionId(),
        raw.sourceContentSha256(),raw.sourceRowOrderSha256(),
        raw.pinnedSourceSha256(),raw.sourceSchemaSha256(),
        methodSha,"F02_TEST_ONLY_V1",raw.periodType(),raw.periodCode(),
        null,raw.currency(),raw.reportingBasis(),raw.dataSpace(),
        1,outputs,List.of("F02_MANAGEMENT_MONTHLY_KZT_TEST_ONLY"),
        raw.resultValuesSha256(),normalizedSha,UatCalculationPreview.STATE,
        false,false);
  }
}
