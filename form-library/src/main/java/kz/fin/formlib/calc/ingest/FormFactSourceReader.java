package kz.fin.formlib.calc.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.repository.FormDefinitionRepository;
import kz.fin.formlib.repository.FormFieldRepository;
import kz.fin.formlib.repository.FormInstanceRepository;
import kz.fin.formlib.repository.FormRevisionEvidenceRepository;
import kz.fin.formlib.repository.FormRevisionRepository;
import kz.fin.formlib.repository.FormRevisionRowRepository;
import kz.fin.formlib.repository.FormVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Exact revision loader. Refuses the "latest draft" fallback and cross-scope reads.
 * Deliberately does NOT claim to reverify FIN-LIB-CONTENT-V1: INGEST-2 adds
 * a shared canonical source rehash, including attachment filename identity.
 */
@Service
public class FormFactSourceReader {
  private final FormRevisionRepository revisions;
  private final FormInstanceRepository instances;
  private final FormVersionRepository versions;
  private final FormDefinitionRepository forms;
  private final FormFieldRepository fields;
  private final FormRevisionRowRepository rows;
  private final FormRevisionEvidenceRepository evidence;
  private final ObjectMapper mapper;

  public FormFactSourceReader(FormRevisionRepository revisions,
      FormInstanceRepository instances, FormVersionRepository versions,
      FormDefinitionRepository forms, FormFieldRepository fields,
      FormRevisionRowRepository rows, FormRevisionEvidenceRepository evidence,
      ObjectMapper mapper) {
    this.revisions = revisions;
    this.instances = instances;
    this.versions = versions;
    this.forms = forms;
    this.fields = fields;
    this.rows = rows;
    this.evidence = evidence;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public FormFactCandidateExtractor.SourceSnapshot readExact(
      UUID revisionId, UUID expectedOrganizationId, UUID expectedProjectId) {
    if (revisionId == null || expectedOrganizationId == null || expectedProjectId == null) {
      throw new IllegalArgumentException("PINNED_SOURCE_SCOPE_REQUIRED");
    }
    var revision = revisions.findById(revisionId)
        .orElseThrow(() -> new IllegalArgumentException("SOURCE_REVISION_NOT_FOUND"));
    var instance = instances.findById(revision.getInstanceId())
        .orElseThrow(() -> new IllegalStateException("SOURCE_INSTANCE_MISSING"));
    if (!expectedOrganizationId.equals(instance.getOrganizationId())
        || !expectedProjectId.equals(instance.getProjectId())
        || !instance.getId().equals(revision.getInstanceId())) {
      throw new IllegalArgumentException("SOURCE_SCOPE_ACCESS_DENIED");
    }
    var version = versions.findById(revision.getFormVersionId())
        .orElseThrow(() -> new IllegalStateException("SOURCE_VERSION_MISSING"));
    var definition = forms.findById(instance.getFormId())
        .orElseThrow(() -> new IllegalStateException("SOURCE_FORM_MISSING"));
    if (!instance.getFormId().equals(version.getFormId())
        || !revision.getContentMode().equals(version.getContentMode())) {
      throw new IllegalStateException("SOURCE_FORM_VERSION_MISMATCH");
    }

    var formFields = fields.findByFormVersionIdOrderByDisplayOrderAsc(version.getId())
        .stream().map(f -> new FormFactCandidateExtractor.Field(
            f.getFieldCode(), f.getFieldType(), f.getInputMode(),
            Boolean.TRUE.equals(f.getRequired()))).toList();
    var sourceRows = rows.findByRevisionIdOrderByRowPositionAscRowIdAsc(revisionId)
        .stream().map(r -> new FormFactCandidateExtractor.Row(
            r.getRowId(), r.getRowPosition(), parseStringMap(r.getValuesJson()))).toList();

    var sourceEvidence = new ArrayList<FormFactCandidateExtractor.Evidence>();
    for (var item : evidence.findByRevisionId(revisionId)) {
      sourceEvidence.add(new FormFactCandidateExtractor.Evidence(
          item.getEvidenceId(), item.getDocumentRef(), item.getRawSha256(),
          item.getMediaType(), Objects.requireNonNull(item.getSizeBytes(), "EVIDENCE_SIZE_REQUIRED")));
    }

    var scope = new FormFactCandidateExtractor.Scope(
        instance.getOrganizationId(), instance.getEconomicEntityId(),
        instance.getBusinessScopeId(), instance.getProjectId(),
        instance.getPeriodType(), instance.getPeriodCode(),
        instance.getPeriodStart(), instance.getPeriodEndExclusive(),
        instance.getCurrency(), instance.getCoverage(),
        instance.getReportingBasis(), instance.getDataSpace());

    return new FormFactCandidateExtractor.SourceSnapshot(
        revision.getId(), instance.getId(), version.getId(),
        definition.getFormCode(), revision.getContentMode(),
        revision.getCanonicalContentSha256(), version.getSchemaSha256(),
        version.getMappingSha256(), scope, formFields,
        parseStringMap(revision.getValuesJson()), sourceRows,
        java.util.List.copyOf(sourceEvidence));
  }

  private Map<String,String> parseStringMap(String json) {
    if (json == null || json.isBlank()) return Map.of();
    try {
      JsonNode node = mapper.readTree(json);
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("SOURCE_JSON_OBJECT_REQUIRED");
      }
      Map<String,String> values = new TreeMap<>();
      var it = node.fields();
      while (it.hasNext()) {
        var field = it.next();
        if (!field.getValue().isTextual()) {
          throw new IllegalArgumentException("SOURCE_JSON_STRING_VALUE_REQUIRED");
        }
        values.put(field.getKey(), field.getValue().textValue());
      }
      return java.util.Collections.unmodifiableMap(values);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalArgumentException("SOURCE_JSON_INVALID", ex);
    }
  }
}
