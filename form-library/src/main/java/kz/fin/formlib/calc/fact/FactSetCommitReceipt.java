package kz.fin.formlib.calc.fact;

import java.util.UUID;

/** Identifies one immutable TEST_ONLY FactSet commit or its exact replay. */
public record FactSetCommitReceipt(UUID factSetId,UUID sourceRevisionId,
    UUID projectId,UUID userId,String factsetSha256,String bindingSha256,
    String stableScopeKey,long qualificationEpoch,String result) {
  public FactSetCommitReceipt {
    if(factSetId==null||sourceRevisionId==null||projectId==null||userId==null
        ||qualificationEpoch<1||!"TEST_QUALIFIED".equals(result)
           &&!"IDEMPOTENT_REPLAY".equals(result))
      throw new IllegalArgumentException("FACT1B_RECEIPT_INVALID");
    FactDigest.requireSha(factsetSha256,"FACT1B_FACTSET_SHA_INVALID");
    FactDigest.requireSha(bindingSha256,"FACT1B_BINDING_SHA_INVALID");
    FactDigest.requireSha(stableScopeKey,"FACT1B_SCOPE_SHA_INVALID");
  }
}
