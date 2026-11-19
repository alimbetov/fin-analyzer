package kz.fin.formlib.calc.ingest;

import kz.fin.formlib.repository.FormRevisionEvidenceRepository;
import kz.fin.formlib.repository.FormRevisionRepository;
import kz.fin.formlib.repository.FormVersionRepository;
import kz.fin.formlib.service.FormAttachmentStore;
import kz.fin.formlib.service.FormContentFingerprint;
import kz.fin.formlib.service.PeriodResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * INGEST-2: independently reconstructs both historical hashes from the
 * PostgreSQL-pinned snapshot. No trust in a copied SHA string or latest draft.
 * For EVIDENCE the verified hash binds fileName/mediaType/rawSha and actual
 * local bytes are checked by FormAttachmentStore.load().
 *
 * Caller MUST run readExact + verify + extract in one REPEATABLE_READ TX.
 * This is source integrity, NOT S2 financial mapping/qualification.
 */
@Component
public final class FormFactSourceIntegrityVerifier {
  private final FormRevisionRepository revisions;
  private final FormVersionRepository versions;
  private final FormRevisionEvidenceRepository evidence;
  private final FormAttachmentStore attachments;
  private final FormContentFingerprint fingerprints;

  public FormFactSourceIntegrityVerifier(
      FormRevisionRepository revisions, FormVersionRepository versions,
      FormRevisionEvidenceRepository evidence, FormAttachmentStore attachments,
      ObjectMapper mapper) {
    this.revisions=revisions;this.versions=versions;this.evidence=evidence;
    this.attachments=attachments;
    this.fingerprints=new FormContentFingerprint(mapper);
  }

  public void verify(FormFactCandidateExtractor.SourceSnapshot source) {
    if(source == null || source.scope() == null)
      throw new IllegalArgumentException("SOURCE_SNAPSHOT_REQUIRED");
    var rev=revisions.findById(source.revisionId())
        .orElseThrow(()->new IllegalStateException("PINNED_SOURCE_REVISION_MISSING"));
    var version=versions.findById(source.formVersionId())
        .orElseThrow(()->new IllegalStateException("PINNED_FORM_VERSION_MISSING"));
    if(!Objects.equals(rev.getId(),source.revisionId())
        || !Objects.equals(rev.getInstanceId(),source.instanceId())
        || !Objects.equals(rev.getFormVersionId(),source.formVersionId())
        || !Objects.equals(rev.getContentMode(),source.contentMode())
        || !Objects.equals(version.getId(),source.formVersionId())
        || !Objects.equals(version.getContentMode(),source.contentMode())
        || !Objects.equals(version.getSchemaSha256(),source.sourceSchemaSha256())
        || !Objects.equals(version.getMappingSha256(),source.sourceMappingSha256())
        || !Objects.equals(rev.getCanonicalContentSha256(),source.claimedCanonicalContentSha256())) {
      throw new IllegalStateException("PINNED_SOURCE_CONTRACT_MISMATCH");
    }

    var scope=source.scope();
    var range=PeriodResolver.resolve(scope.periodType(),scope.periodCode());
    if(!Objects.equals(range.periodType(),scope.periodType())
        || !Objects.equals(range.periodCode(),scope.periodCode())
        || !Objects.equals(range.startInclusive(),scope.periodStart())
        || !Objects.equals(range.endExclusive(),scope.periodEndExclusive()))
      throw new IllegalStateException("SOURCE_PERIOD_INTEGRITY_MISMATCH");
    if(source.rows()==null || source.values()==null || source.evidence()==null)
      throw new IllegalStateException("SOURCE_CONTENT_MISSING");

    var links=evidence.findByRevisionId(rev.getId());
    if(links.size()>1 || source.evidence().size()!=links.size()
        || !links.isEmpty() && !"EVIDENCE".equals(source.contentMode()))
      throw new IllegalStateException("SOURCE_EVIDENCE_SHAPE_INVALID");

    FormContentFingerprint.Attachment attachment=null;
    if(!links.isEmpty()) {
      var link=links.get(0);
      var sourceEvidence=source.evidence().get(0);
      if(!Objects.equals(link.getRevisionId(),rev.getId())
          || !Objects.equals(link.getEvidenceId(),sourceEvidence.evidenceId())
          || !Objects.equals(link.getDocumentRef(),sourceEvidence.documentRef())
          || !Objects.equals(link.getRawSha256(),sourceEvidence.sha256())
          || !Objects.equals(link.getMediaType(),sourceEvidence.mediaType())
          || !Objects.equals(link.getSizeBytes(),sourceEvidence.sizeBytes()))
        throw new IllegalStateException("SOURCE_EVIDENCE_LINK_MISMATCH");
      var stored=attachments.find(rev.getId())
          .orElseThrow(()->new IllegalStateException("SOURCE_EVIDENCE_METADATA_INVALID"));
      if(!Objects.equals(stored.id(),link.getEvidenceId())
          || !Objects.equals(stored.mediaType(),link.getMediaType())
          || !Objects.equals(stored.sizeBytes(),link.getSizeBytes())
          || !Objects.equals(stored.sha256(),link.getRawSha256()))
        throw new IllegalStateException("SOURCE_EVIDENCE_METADATA_MISMATCH");
      // Verify the saved bytes and their SHA, not just the DB metadata.
      attachments.load(stored);
      attachment=new FormContentFingerprint.Attachment(
          stored.fileName(),stored.mediaType(),stored.sha256());
    }

    var context=new FormContentFingerprint.Context(
        scope.organizationId(),scope.economicEntityId(),
        scope.businessScopeId(),scope.projectId(),source.formCode(),
        version.getVersionLabel(),scope.periodType(),scope.periodCode(),
        scope.coverage(),scope.currencyCode(),scope.reportingBasis(),scope.dataSpace());
    List<FormContentFingerprint.Row> contentRows=source.rows().stream()
        .map(r->new FormContentFingerprint.Row(r.rowId(),r.rowPosition(),r.values()))
        .toList();
    var hashes=fingerprints.compute(context,source.values(),contentRows,attachment);
    if(!Objects.equals(hashes.contentSha256(),rev.getCanonicalContentSha256()))
      throw new IllegalStateException("SOURCE_CONTENT_SHA_MISMATCH");
    if(!Objects.equals(hashes.rowOrderSha256(),rev.getRowOrderSha256()))
      throw new IllegalStateException("SOURCE_ROW_ORDER_SHA_MISMATCH");
  }
}
