package kz.fin.formlib.calc.ingest;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only INGEST-1 entrypoint: exact revision -> unqualified candidate projection.
 * NO fin_fact write, NO metric mapping, NO qualifier, NO controller exposure.
 */
@Service
public final class FormFactCandidateService {
  private final FormFactSourceReader sourceReader;
  private final FormFactCandidateExtractor extractor;

  public FormFactCandidateService(
      FormFactSourceReader sourceReader, FormFactCandidateExtractor extractor) {
    this.sourceReader = sourceReader;
    this.extractor = extractor;
  }

  @Transactional(readOnly = true)
  public FormFactCandidateExtractor.Projection extractExact(
      UUID revisionId, UUID organizationId, UUID projectId) {
    return extractor.extract(sourceReader.readExact(
        revisionId, organizationId, projectId));
  }
}
