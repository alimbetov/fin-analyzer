package kz.fin.formlib.calc.ingest;

import kz.fin.formlib.calc.fact.FactDigest;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * DC-R2.1: READ-ONLY adapter from an exact, SHA-reverified fin_intake revision
 * to a typed, financially UNQUALIFIED calculation candidate.
 *
 * Do not write this projection into fin_fact or label it TEST_QUALIFIED.
 * The underlying INGEST-2 reader/rehash/extractor runs in a single
 * REQUIRES_NEW / REPEATABLE_READ transaction.
 */
@Service
public class FinIntakeUatSourceAdapter {
  private final FormFactCandidateService candidates;

  public record PinnedSource(
      FormFactCandidateExtractor.Projection projection,
      java.util.List<UUID> sourceRowIds,
      String pinnedSourceSha256,
      String integrityState,
      String qualificationState) {
    public PinnedSource {
      Objects.requireNonNull(projection, "INTAKE_UAT_PROJECTION_REQUIRED");
      sourceRowIds=java.util.List.copyOf(sourceRowIds);
      if(new java.util.HashSet<>(sourceRowIds).size()!=sourceRowIds.size())
        throw new IllegalArgumentException("INTAKE_UAT_ROW_CENSUS_DUPLICATE");
      FactDigest.requireSha(pinnedSourceSha256,"INTAKE_UAT_SOURCE_SHA_INVALID");
      if(!"SOURCE_SHA_REVERIFIED".equals(integrityState)
          ||!"UNQUALIFIED_SOURCE_PROJECTION".equals(qualificationState))
        throw new IllegalArgumentException("INTAKE_UAT_STATE_NOT_UNQUALIFIED");
    }
  }

  public FinIntakeUatSourceAdapter(FormFactCandidateService candidates) {
    this.candidates=Objects.requireNonNull(candidates);
  }

  public PinnedSource readExact(
      UUID organizationId,UUID projectId,UUID revisionId,String expectedFormCode) {
    if(organizationId==null || projectId==null || revisionId==null
        || expectedFormCode==null || !expectedFormCode.matches("[A-Z0-9-]{2,40}"))
      throw new IllegalArgumentException("INTAKE_UAT_EXACT_SCOPE_REQUIRED");

    // This is the ONLY source loader: readExact -> INGEST-2 SHA rehash
    // -> typed extractor. No latest-revision fallback and no third writer.
    var exact=candidates.extractExactWithRowCensus(
        revisionId,organizationId,projectId);
    var p=exact.projection();
    if(!expectedFormCode.equals(p.formCode())
        || !revisionId.equals(p.revisionId())
        || p.scope()==null
        || !organizationId.equals(p.scope().organizationId())
        || !projectId.equals(p.scope().projectId())
        || !("ROWSET".equals(p.contentMode())
             ||"SINGLE_RECORD".equals(p.contentMode()))
        || !"SOURCE_SHA_REVERIFIED".equals(p.sourceIntegrityState())
        || !"UNQUALIFIED_SOURCE_PROJECTION".equals(p.qualificationState()))
      throw new IllegalStateException("INTAKE_UAT_PINNED_SOURCE_MISMATCH");
    FactDigest.requireSha(p.sourceSha256Claim(),"INTAKE_UAT_CONTENT_SHA_INVALID");
    FactDigest.requireSha(p.rowOrderSha256(),"INTAKE_UAT_ROWS_SHA_INVALID");
    FactDigest.requireSha(p.sourceSchemaSha256(),"INTAKE_UAT_SCHEMA_SHA_INVALID");
    FactDigest.requireSha(p.sourceMappingSha256(),"INTAKE_UAT_MAPPING_SHA_INVALID");
    FactDigest.requireSha(p.ingestionRegistrySha256(),"INTAKE_UAT_REGISTRY_SHA_INVALID");

    String fingerprint=FactDigest.sha("FIN-FACT-INTAKE-UAT-SOURCE-V1",
        organizationId.toString(),projectId.toString(),revisionId.toString(),
        p.instanceId().toString(),p.formVersionId().toString(),
        p.formCode(),p.sourceSha256Claim(),p.rowOrderSha256(),
        p.sourceSchemaSha256(),p.sourceMappingSha256(),p.ingestionRegistrySha256(),
        p.scope().currencyCode(),p.scope().periodType(),p.scope().periodCode(),
        p.scope().reportingBasis(),p.scope().dataSpace());
    return new PinnedSource(p,exact.sourceRowIds(),fingerprint,
        p.sourceIntegrityState(),p.qualificationState());
  }
}
