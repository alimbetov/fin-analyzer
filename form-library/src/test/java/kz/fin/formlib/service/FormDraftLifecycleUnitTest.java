package kz.fin.formlib.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fast isolated write-path tests. These do not claim DB transaction or concurrency proof.
 * Actual PostgreSQL/HTTP round-trips belong in integration acceptance.
 */
class FormDraftLifecycleUnitTest {
  private final UUID org=UUID.randomUUID(), entity=UUID.randomUUID(), scope=UUID.randomUUID();
  private final UUID project=UUID.randomUUID(), formId=UUID.randomUUID(), versionId=UUID.randomUUID();
  private FormDefinitionRepository forms;
  private FormVersionRepository versions;
  private FormFieldRepository fields;
  private FormInstanceRepository instances;
  private FormRevisionRepository revisions;
  private FormRevisionRowRepository rows;
  private FormCommandReceiptRepository receipts;
  private LibCurrencyRepository currencies;
  private FieldMetricContractGuard guard;
  private FormDraftService editor;

  @BeforeEach
  void setUp() {
    forms=mock(FormDefinitionRepository.class);
    versions=mock(FormVersionRepository.class);
    fields=mock(FormFieldRepository.class);
    instances=mock(FormInstanceRepository.class);
    revisions=mock(FormRevisionRepository.class);
    rows=mock(FormRevisionRowRepository.class);
    receipts=mock(FormCommandReceiptRepository.class);
    currencies=mock(LibCurrencyRepository.class);
    guard=mock(FieldMetricContractGuard.class);
    editor=new FormDraftService(forms,versions,fields,instances,revisions,rows,
        receipts,currencies,new ObjectMapper(),guard);

    var form=new FormDefinition();
    form.setId(formId);form.setFormCode("F02");
    form.setCurrentVersionId(versionId);form.setLegacyCaptureMode("TYPED_DRAFT");
    when(forms.findByFormCode("F02")).thenReturn(Optional.of(form));

    var version=new FormVersion();
    version.setId(versionId);version.setFormId(formId);
    version.setVersionLabel("F02-V1");version.setContentMode("SINGLE_RECORD");
    when(versions.findById(versionId)).thenReturn(Optional.of(version));

    var currency=new LibCurrency();
    currency.setCode("KZT");currency.setMinorUnits(2);currency.setStatus("ACTIVE");
    when(currencies.findById("KZT")).thenReturn(Optional.of(currency));
    when(guard.matches("F02",versionId)).thenReturn(true);

    var amount=new FormField();
    amount.setFieldCode("netRevenue");amount.setFieldType("MONEY");
    amount.setInputMode("MANUAL");amount.setRequired(true);
    var note=new FormField();
    note.setFieldCode("comment");note.setFieldType("STRING");
    note.setInputMode("MANUAL");note.setRequired(false);
    when(fields.findByFormVersionIdOrderByDisplayOrderAsc(versionId)).thenReturn(List.of(amount,note));

    when(instances.saveAndFlush(any(FormInstance.class))).thenAnswer(a->a.getArgument(0));
    when(revisions.saveAndFlush(any(FormRevision.class))).thenAnswer(a->a.getArgument(0));
    when(receipts.saveAndFlush(any(FormCommandReceipt.class))).thenAnswer(a->a.getArgument(0));
  }

  private FormDraftService.DraftRequest request(UUID instance, int expected,
      Map<String,String> values) {
    return new FormDraftService.DraftRequest(instance,org,entity,scope,project,
        "F02","M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
        expected,values,List.of(),"unit-test","edit");
  }

  private FormInstance existing(UUID id, int revisionNo, UUID revisionId) {
    var instance=new FormInstance();
    instance.setId(id);instance.setOrganizationId(org);instance.setEconomicEntityId(entity);
    instance.setBusinessScopeId(scope);instance.setProjectId(project);
    instance.setFormId(formId);instance.setPeriodType("M");instance.setPeriodCode("2026-11");
    instance.setCoverage("PERIOD_ONLY");instance.setCurrency("KZT");
    instance.setReportingBasis("MANAGEMENT");instance.setDataSpace("SYNTHETIC");
    instance.setLatestRevisionNo(revisionNo);instance.setCurrentRevisionId(revisionId);
    return instance;
  }

  private static void rejects(Runnable operation,HttpStatus status,String reason) {
    var ex=assertThrows(ResponseStatusException.class,operation::run);
    assertEquals(status,ex.getStatusCode());
    assertTrue(ex.getReason().contains(reason),ex.getReason());
  }

  @Test
  void newDraftCreatesFirstRevisionWithExactCanonicalValues() {
    var saved=editor.save(request(null,0,Map.of("netRevenue","00120.50","comment","ok")),"create-1");
    assertEquals(1,saved.revisionNo());
    assertEquals("UNQUALIFIED_DRAFT",saved.result());
    assertNotNull(saved.instanceId());
    var capture=org.mockito.ArgumentCaptor.forClass(FormRevision.class);
    verify(revisions).saveAndFlush(capture.capture());
    assertEquals(saved.instanceId(),capture.getValue().getInstanceId());
    assertEquals(versionId,capture.getValue().getFormVersionId());
    assertEquals(1,capture.getValue().getRevisionNo());
    assertEquals("120.5",new ObjectMapper().convertValue(
        readJson(capture.getValue().getValuesJson()),Map.class).get("netRevenue"));
    verify(instances,atLeastOnce()).saveAndFlush(any(FormInstance.class));
  }

  private static Object readJson(String value) {
    try {return new ObjectMapper().readTree(value);}
    catch(Exception e){throw new AssertionError(e);}
  }

  @Test
  void existingDraftWritesSecondRevisionWithoutCreatingNewInstance() {
    var id=UUID.randomUUID();
    var previousRevisionId=UUID.randomUUID();
    var previous=new FormRevision();
    previous.setId(previousRevisionId);previous.setInstanceId(id);
    previous.setFormVersionId(versionId);
    when(instances.lockByIdAndOrganizationId(id,org))
        .thenReturn(Optional.of(existing(id,1,previousRevisionId)));
    when(revisions.findById(previousRevisionId)).thenReturn(Optional.of(previous));

    var receipt=editor.save(request(id,1,Map.of("netRevenue","200","comment","kept")),"edit-2");
    assertEquals(id,receipt.instanceId());
    assertEquals(2,receipt.revisionNo());
    var capture=org.mockito.ArgumentCaptor.forClass(FormRevision.class);
    verify(revisions).saveAndFlush(capture.capture());
    assertEquals(2,capture.getValue().getRevisionNo());
    assertEquals(versionId,capture.getValue().getFormVersionId());
    verify(instances,never()).save(any(FormInstance.class));
  }

  @Test
  void staleRevisionDoesNotWriteAnything() {
    var id=UUID.randomUUID();
    when(instances.lockByIdAndOrganizationId(id,org))
        .thenReturn(Optional.of(existing(id,2,null)));
    rejects(()->editor.save(request(id,1,Map.of("netRevenue","300")),"stale"),
        HttpStatus.CONFLICT,"STALE_EXPECTED_REVISION");
    verify(revisions,never()).saveAndFlush(any(FormRevision.class));
    verify(receipts,never()).saveAndFlush(any(FormCommandReceipt.class));
  }

  @Test
  void changedSlotContextIsRejectedBeforeRevisionCreation() {
    var id=UUID.randomUUID();
    when(instances.lockByIdAndOrganizationId(id,org))
        .thenReturn(Optional.of(existing(id,1,null)));
    var wrong=new FormDraftService.DraftRequest(id,org,entity,scope,UUID.randomUUID(),
        "F02","M","2026-11","KZT","PERIOD_ONLY","MANAGEMENT","SYNTHETIC",
        1,Map.of("netRevenue","100"),List.of(),"unit-test","wrong project");
    rejects(()->editor.save(wrong,"wrong-context"),HttpStatus.CONFLICT,"SLOT_CONTEXT_CHANGED");
    verify(revisions,never()).saveAndFlush(any(FormRevision.class));
  }

  @Test
  void invalidRequestRejectsBeforeAnyRepositoryWrite() {
    rejects(()->editor.save(request(null,0,Map.of("comment","missing revenue")),"invalid"),
        HttpStatus.UNPROCESSABLE_ENTITY,"REQUIRED_FIELD_MISSING");
    verify(revisions,never()).saveAndFlush(any(FormRevision.class));
    verify(receipts,never()).saveAndFlush(any(FormCommandReceipt.class));
  }
}
