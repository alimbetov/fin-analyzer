package kz.fin.formlib.calc.ingest;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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

  /** One stable database snapshot for read + hash verification + conversion. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public FormFactCandidateExtractor.Projection extractExact(
      UUID revisionId, UUID organizationId, UUID projectId) {
    var source=sourceReader.readExact(revisionId,organizationId,projectId);
    verifier.verify(source);
    var projected=extractor.extract(source);
    return new FormFactCandidateExtractor.Projection(
        projected.revisionId(), projected.formVersionId(), projected.formCode(),
        projected.scope(), projected.sourceSha256Claim(),
        projected.sourceSchemaSha256(), projected.sourceMappingSha256(),
        projected.ingestionRegistrySha256(),
        "SOURCE_SHA_REVERIFIED", "UNQUALIFIED_SOURCE_PROJECTION",
        projected.numeric(), projected.attributes(), projected.evidence());
  }
}
