package kz.fin.formlib.calc.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FormFactSourceReaderTest {
  private final UUID revisionId=UUID.randomUUID();
  private final UUID instanceId=UUID.randomUUID();
  private final UUID organizationId=UUID.randomUUID();
  private final UUID projectId=UUID.randomUUID();
  private final UUID formId=UUID.randomUUID();
  private final UUID versionId=UUID.randomUUID();

  private FormRevisionRepository revisions;
  private FormInstanceRepository instances;
  private FormVersionRepository versions;
  private FormDefinitionRepository forms;
  private FormFieldRepository fields;
  private FormRevisionRowRepository rows;
  private FormRevisionEvidenceRepository evidence;
  private FormFactSourceReader reader;
  private FormRevision revision;

  @BeforeEach void setUp() {
    revisions=mock(FormRevisionRepository.class);
    instances=mock(FormInstanceRepository.class);
    versions=mock(FormVersionRepository.class);
    forms=mock(FormDefinitionRepository.class);
    fields=mock(FormFieldRepository.class);
    rows=mock(FormRevisionRowRepository.class);
    evidence=mock(FormRevisionEvidenceRepository.class);
    reader=new FormFactSourceReader(revisions,instances,versions,forms,fields,
        rows,evidence,new ObjectMapper());

    revision=new FormRevision();
    revision.setId(revisionId); revision.setInstanceId(instanceId);
    revision.setRevisionNo(2); revision.setFormVersionId(versionId);
    revision.setContentMode("SINGLE_RECORD");
    revision.setValuesJson("{\"netRevenue\":\"100\",\"cogs\":\"40\",\"operatingExpenses\":\"10\"}");
    revision.setCanonicalContentSha256("a".repeat(64));
    when(revisions.findById(revisionId)).thenReturn(Optional.of(revision));

    var instance=new FormInstance();
    instance.setId(instanceId); instance.setOrganizationId(organizationId);
    instance.setEconomicEntityId(UUID.randomUUID());
    instance.setBusinessScopeId(UUID.randomUUID());
    instance.setProjectId(projectId); instance.setFormId(formId);
    instance.setCurrentRevisionId(UUID.randomUUID()); // intentionally NOT pinned revision
    instance.setPeriodType("M"); instance.setPeriodCode("2026-10");
    instance.setPeriodStart(LocalDate.of(2026,10,1));
    instance.setPeriodEndExclusive(LocalDate.of(2026,11,1));
    instance.setCurrency("KZT"); instance.setCoverage("ACTUAL");
    instance.setReportingBasis("IFRS"); instance.setDataSpace("PROJECT");
    when(instances.findById(instanceId)).thenReturn(Optional.of(instance));

    var version=new FormVersion();
    version.setId(versionId); version.setFormId(formId);
    version.setContentMode("SINGLE_RECORD");
    version.setSchemaSha256("b".repeat(64));
    version.setMappingSha256("c".repeat(64));
    when(versions.findById(versionId)).thenReturn(Optional.of(version));

    var form=new FormDefinition();form.setId(formId);form.setFormCode("F02");
    when(forms.findById(formId)).thenReturn(Optional.of(form));
    var amount=new FormField();amount.setFieldCode("netRevenue");
    amount.setFieldType("MONEY");amount.setInputMode("MANUAL");amount.setRequired(true);
    var cost=new FormField();cost.setFieldCode("cogs");
    cost.setFieldType("MONEY");cost.setInputMode("MANUAL");cost.setRequired(true);
    var expense=new FormField();expense.setFieldCode("operatingExpenses");
    expense.setFieldType("MONEY");expense.setInputMode("MANUAL");expense.setRequired(true);
    when(fields.findByFormVersionIdOrderByDisplayOrderAsc(versionId))
        .thenReturn(List.of(amount,cost,expense));
    when(rows.findByRevisionIdOrderByRowPositionAscRowIdAsc(revisionId))
        .thenReturn(List.of());
    when(evidence.findByRevisionId(revisionId)).thenReturn(List.of());
  }

  @Test void loadsExactHistoricalRevisionNotInstanceLatest() {
    var src=reader.readExact(revisionId,organizationId,projectId);
    assertEquals(revisionId,src.revisionId());
    assertEquals(versionId,src.formVersionId());
    assertEquals("F02",src.formCode());
    assertEquals("100",src.values().get("netRevenue"));
    assertEquals("a".repeat(64),src.claimedCanonicalContentSha256());
    assertEquals(organizationId,src.scope().organizationId());
    assertEquals(projectId,src.scope().projectId());
    verify(revisions).findById(revisionId);
    verify(revisions,times(1)).findById(revisionId);
  }

  @Test void refusesForeignOrganizationAndProjectBeforeReadingForm() {
    assertThrows(IllegalArgumentException.class,
        ()->reader.readExact(revisionId,UUID.randomUUID(),projectId));
    assertThrows(IllegalArgumentException.class,
        ()->reader.readExact(revisionId,organizationId,UUID.randomUUID()));
    verifyNoInteractions(versions);
  }

  @Test void rejectsNonStringJsonCellsRatherThanCoercingThem() {
    revision.setValuesJson("{\"netRevenue\":123}");
    assertThrows(IllegalArgumentException.class,
        ()->reader.readExact(revisionId,organizationId,projectId));
  }

  @Test void rejectsInvalidJsonAndNonObjectPayload() {
    revision.setValuesJson("[\"100\"]");
    assertThrows(IllegalArgumentException.class,
        ()->reader.readExact(revisionId,organizationId,projectId));
    revision.setValuesJson("{");
    assertThrows(IllegalArgumentException.class,
        ()->reader.readExact(revisionId,organizationId,projectId));
  }

  @Test void candidateFacadeRemainsUnqualifiedAndReadOnly() {
    var service=new FormFactCandidateService(reader,new FormFactCandidateExtractor());
    var out=service.extractExact(revisionId,organizationId,projectId);
    assertEquals("UNQUALIFIED_SOURCE_PROJECTION",out.qualificationState());
    assertEquals("SOURCE_SHA_NOT_REVERIFIED",out.sourceIntegrityState());
    assertEquals(3,out.numeric().size());
  }
}
