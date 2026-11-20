package kz.fin.formlib.calc.ingest;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exact source revision -> SHA-reverified, but FINANCIALLY UNQUALIFIED values.
 * No fin_fact write, no S2 approval, no public endpoint.
 */
@Service
public class FormFactCandidateService {
  private final FormFactSourceReader sourceReader;
  private final FormFactCandidateExtractor extractor;
  private final FormFactSourceIntegrityVerifier verifier;

  public FormFactCandidateService(
      FormFactSourceReader sourceReader, FormFactCandidateExtractor extractor,
      FormFactSourceIntegrityVerifier verifier) {
    this.sourceReader = sourceReader;
    this.extractor = extractor;
    this.verifier = verifier;
  }

  /** Full row census belongs to the exact FIN-LIB-CONTENT-V1 revision. */
  public record ExactRead(FormFactCandidateExtractor.Projection projection,
      java.util.List<java.util.UUID> sourceRowIds) {
    public ExactRead {
      java.util.Objects.requireNonNull(projection,"EXACT_PROJECTION_REQUIRED");
      sourceRowIds=java.util.List.copyOf(sourceRowIds);
      if(new java.util.HashSet<>(sourceRowIds).size()!=sourceRowIds.size())
        throw new IllegalStateException("SOURCE_ROW_CENSUS_DUPLICATE");
    }
  }

  /** Existing projection API retained for all INGEST-2 / FACT-1 callers. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ,
      propagation = Propagation.REQUIRES_NEW)
  public FormFactCandidateExtractor.Projection extractExact(
      UUID revisionId, UUID organizationId, UUID projectId) {
    return verifiedRead(revisionId,organizationId,projectId).projection();
  }

  /** DC-R2.4: read candidate *and every row ID*, including empty source rows. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ,
      propagation = Propagation.REQUIRES_NEW)
  public ExactRead extractExactWithRowCensus(
      UUID revisionId, UUID organizationId, UUID projectId) {
    return verifiedRead(revisionId,organizationId,projectId);
  }

  /** Called only within a public transactional entrypoint on the Spring proxy. */
  private ExactRead verifiedRead(UUID revisionId,UUID organizationId,UUID projectId) {
    var source=sourceReader.readExact(revisionId,organizationId,projectId);
    String verifiedRowOrderSha256 = verifier.verify(source);
    var projected=extractor.extract(source);
    var verified=new FormFactCandidateExtractor.Projection(
        projected.revisionId(), projected.instanceId(), projected.formVersionId(),
        projected.formCode(), projected.contentMode(), verifiedRowOrderSha256,
        projected.scope(), projected.sourceSha256Claim(),
        projected.sourceSchemaSha256(), projected.sourceMappingSha256(),
        projected.ingestionRegistrySha256(),
        "SOURCE_SHA_REVERIFIED", "UNQUALIFIED_SOURCE_PROJECTION",
        projected.numeric(), projected.attributes(), projected.evidence());
    var rows=source.rows().stream()
        .map(FormFactCandidateExtractor.Row::rowId).toList();
    return new ExactRead(verified,rows);
  }
}
