package kz.fin.formlib.calc.ingest;

import kz.fin.formlib.calc.fact.FactDigest;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DC-R2.5 canonical user-facing UAT result: shared by F02/C01/C02.
 * All values are canonical decimal strings, never JS floating point.
 * Methodological approval and production execution remain explicitly false.
 */
public record UatCalculationPreview(
    String formCode, UUID instanceId, UUID sourceRevisionId,UUID formVersionId,
    String sourceContentSha256,String sourceRowOrderSha256,
    String pinnedSourceSha256,String sourceSchemaSha256,
    String calculationMethodSha256,String methodCode,
    String periodType,String periodCode,LocalDate asOfDate,
    String currency,String reportingBasis,String dataSpace,
    int sourceRowCount, Map<String,Node> outputs,List<String> diagnostics,
    String resultValuesSha256,String previewSha256,String executionState,
    boolean financiallyApproved,boolean productionAdmitted) {
  public static final String STATE="FIN_INTAKE_UAT_PREVIEW_UNQUALIFIED";

  public record Node(String state,String unit,String value,String errorCode) {
    public Node {
      if(!List.of("PRESENT","MISSING","UNDEFINED","INVALID").contains(state)
          || unit==null || unit.isBlank()
          || "PRESENT".equals(state) != (value!=null)
          || ("PRESENT".equals(state) && errorCode!=null)
          || (!"PRESENT".equals(state) && (errorCode==null || errorCode.isBlank())))
        throw new IllegalArgumentException("INTAKE_UAT_NODE_STATE_INVALID");
    }
  }

  public UatCalculationPreview {
    if(!List.of("F02","C01","C02").contains(formCode)
        || instanceId==null||sourceRevisionId==null||formVersionId==null
        ||sourceRowCount<1 ||outputs==null||outputs.isEmpty()
        ||diagnostics==null || !"FIN_INTAKE_UAT_PREVIEW_UNQUALIFIED".equals(executionState)
        ||financiallyApproved||productionAdmitted)
      throw new IllegalArgumentException("INTAKE_UAT_UNQUALIFIED_PREVIEW_REQUIRED");
    for(String s:List.of(sourceContentSha256,sourceRowOrderSha256,
        pinnedSourceSha256,sourceSchemaSha256,calculationMethodSha256,
        resultValuesSha256,previewSha256))
      FactDigest.requireSha(s,"INTAKE_UAT_PREVIEW_SHA_REQUIRED");
    outputs=java.util.Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
    diagnostics=List.copyOf(diagnostics);
  }
}
