package kz.fin.formlib.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class FormDraftService {
  private final FormDefinitionRepository forms;
  private final FormVersionRepository versions;
  private final FormFieldRepository fields;
  private final FormInstanceRepository instances;
  private final FormRevisionRepository revisions;
  private final FormRevisionRowRepository rows;
  private final FormCommandReceiptRepository receipts;
  private final LibCurrencyRepository currencies;
  private final ObjectMapper mapper;
  private final FieldMetricContractGuard contractGuard;
  private final FormAttachmentStore attachments;
  private final FormContentFingerprint contentFingerprint;

  public record RowInput(UUID rowId, int rowPosition, Map<String,String> values) {}
  public record DraftRequest(UUID instanceId, UUID organizationId, UUID economicEntityId,
          UUID businessScopeId, UUID projectId, String formCode,
          String periodType, String periodCode, String currency, String coverage,
          String reportingBasis, String dataSpace, int expectedRevision,
          Map<String,String> values, List<RowInput> rows, String createdBy, String changeReason) {}
  public record DraftReceipt(UUID instanceId, UUID revisionId, int revisionNo,
                             String contentSha256, String result) {}
  public record RowView(UUID rowId, int rowPosition, Map<String,String> values) {}
  public record DraftView(UUID instanceId, UUID revisionId, UUID organizationId, int revisionNo,
                          String formCode, String formVersion, String contentMode,
                          Map<String,String> values, List<RowView> rows, String sha256,
                          String periodType, String periodCode, java.time.LocalDate periodStart,
                          java.time.LocalDate periodEndExclusive, String currency,
                          String workflowStatus,long statusVersion,
                          UUID economicEntityId,UUID businessScopeId,UUID projectId,
                          String coverage,String reportingBasis,String dataSpace,
                          FormAttachmentStore.Attachment attachment) {}

  public FormDraftService(FormDefinitionRepository forms,FormVersionRepository versions,
         FormFieldRepository fields,FormInstanceRepository instances,
         FormRevisionRepository revisions,FormRevisionRowRepository rows,
         FormCommandReceiptRepository receipts,LibCurrencyRepository currencies,
         ObjectMapper mapper,FieldMetricContractGuard contractGuard,FormAttachmentStore attachments) {
    this.forms=forms;this.versions=versions;this.fields=fields;this.instances=instances;
    this.revisions=revisions;this.rows=rows;this.receipts=receipts;
    this.currencies=currencies;this.mapper=mapper;this.contractGuard=contractGuard;this.attachments=attachments;
    this.contentFingerprint=new FormContentFingerprint(mapper);
  }
  private static ResponseStatusException bad(String code) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }
  private static String required(String value,String code) {
    if(value==null||value.isBlank()) throw bad(code+"_REQUIRED");
    return value;
  }
  private static UUID required(UUID value,String code) {
    if(value==null) throw bad(code+"_REQUIRED");
    return value;
  }
  private static String sha(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch(Exception ex) {throw new IllegalStateException(ex);}
  }
  private String json(Object data) {
    try{return mapper.writeValueAsString(data);}
    catch(JsonProcessingException ex){throw new IllegalStateException(ex);}
  }
  private Map<String,String> parseValues(String json) {
    if(json==null||json.isBlank()) return Map.of();
    try{return mapper.readValue(json,new TypeReference<TreeMap<String,String>>(){});
    } catch(JsonProcessingException ex){throw new IllegalStateException(ex);}
  }
  private static String normalize(String type,String value,int moneyMinorUnits) {
    if(value==null) throw bad("NULL_CELL_NOT_ALLOWED");
    String raw=value.trim();
    if(raw.isEmpty()) return ""; // distinct from omitted key; never implicit zero
    try {
      return switch(type) {
        case "MONEY","DECIMAL" -> {
          if(!raw.matches("-?[0-9]{1,26}(\\.[0-9]{1,12})?"))
            throw bad("INVALID_DECIMAL");
          var v=new BigDecimal(raw);
          if(v.precision()>38||v.scale()>12) throw bad("INVALID_DECIMAL");
          if(type.equals("MONEY") && v.stripTrailingZeros().scale()>moneyMinorUnits)
            throw bad("CURRENCY_MINOR_UNITS_EXCEEDED");
          yield v.signum()==0?"0":v.stripTrailingZeros().toPlainString();
        }
        case "INTEGER" -> {
          if(!raw.matches("-?[0-9]{1,26}")) throw bad("INVALID_INTEGER");
          yield new java.math.BigInteger(raw).toString();
        }
        case "DATE" -> LocalDate.parse(raw).toString();
        case "BOOLEAN" -> {
          if(!Set.of("true","false").contains(raw)) throw bad("INVALID_BOOLEAN");
          yield raw;
        }
        default -> {
          if(raw.length()>1000) throw bad("VALUE_TOO_LONG");
          yield raw;
        }
      };
    } catch(java.time.format.DateTimeParseException ex) {throw bad("INVALID_DATE");}
  }
  private Map<String,String> canonical(UUID versionId,Map<String,String> input,
                                       boolean checkRequired,int moneyMinorUnits) {
    if(input==null) input=Map.of();
    var defs=fields.findByFormVersionIdOrderByDisplayOrderAsc(versionId);
    Map<String,FormField> byCode=new HashMap<>();
    defs.forEach(f->byCode.put(f.getFieldCode(),f));
    Map<String,String> result=new TreeMap<>();
    for(var e:input.entrySet()) {
      FormField f=byCode.get(e.getKey());
      if(f==null) throw bad("UNKNOWN_FIELD:"+e.getKey());
      if(!"MANUAL".equals(f.getInputMode())) throw bad("READ_ONLY_FIELD:"+e.getKey());
      result.put(e.getKey(),normalize(f.getFieldType(),e.getValue(),moneyMinorUnits));
    }
    if(checkRequired) for(FormField f:defs) {
      if(Boolean.TRUE.equals(f.getRequired())&&
          (result.get(f.getFieldCode())==null||result.get(f.getFieldCode()).isBlank()))
        throw bad("REQUIRED_FIELD_MISSING:"+f.getFieldCode());
    }
    return result;
  }

  /**
   * No-write preview validator for the R19 XLSX workflow. Uses the same
   * canonical field/value normalizer as save(). The commit revalidates everything.
   * Caller must already have pinned instance scope and form version.
   */
  public record ImportCanonical(Map<String,String> values,
      Map<UUID,Map<String,String>> rowValues) {}

  @Transactional(readOnly=true)
  public void validateImportCandidate(DraftRequest req,UUID pinnedFormVersionId) {
    normalizeImportCandidate(req,pinnedFormVersionId);
  }

  @Transactional(readOnly=true)
  public ImportCanonical normalizeImportCandidate(DraftRequest req,UUID pinnedFormVersionId) {
    if(req==null||pinnedFormVersionId==null)throw bad("IMPORT_CONTEXT_REQUIRED");
    var currency=currencies.findById(required(req.currency(),"CURRENCY"))
      .filter(x->"ACTIVE".equals(x.getStatus()))
      .orElseThrow(()->bad("UNSUPPORTED_CURRENCY"));
    try{PeriodResolver.resolve(req.periodType(),req.periodCode());}
    catch(IllegalArgumentException e){throw bad("INVALID_PERIOD");}
    var form=forms.findByFormCode(required(req.formCode(),"FORM_CODE"))
      .orElseThrow(()->bad("FORM_NOT_FOUND"));
    var version=versions.findById(pinnedFormVersionId)
      .orElseThrow(()->bad("FORM_VERSION_NOT_FOUND"));
    if(!version.getFormId().equals(form.getId()))
      throw bad("FORM_VERSION_CONTEXT_MISMATCH");
    if(!contractGuard.matches(req.formCode(),pinnedFormVersionId))
      throw bad("FIELD_METRIC_CONTRACT_STALE");
    boolean single="SINGLE_RECORD".equals(version.getContentMode());
    if(!single&&!"ROWSET".equals(version.getContentMode()))
      throw bad("UNSUPPORTED_CONTENT_MODE");
    if(!single&&req.values()!=null&&!req.values().isEmpty())
      throw bad("ROWSET_TOP_LEVEL_VALUES_FORBIDDEN");
    Map<String,String> normalizedValues=canonical(pinnedFormVersionId,req.values(),single,currency.getMinorUnits());
    var sourceRows=req.rows()==null?List.<RowInput>of():req.rows();
    if((single&&!sourceRows.isEmpty())||(!single&&
        (sourceRows.isEmpty()||sourceRows.size()>200)))
      throw bad("INVALID_ROW_COUNT");
    Map<UUID,Map<String,String>> normalizedRows=new HashMap<>();
    if(!single){
      Set<UUID> ids=new HashSet<>();
      Set<Integer> positions=new HashSet<>();
      for(var row:sourceRows){
        if(row==null||row.rowId()==null||!ids.add(row.rowId())
            ||row.rowPosition()<1||!positions.add(row.rowPosition()))
          throw bad("INVALID_IMPORT_ROW");
        normalizedRows.put(row.rowId(),canonical(pinnedFormVersionId,row.values(),true,
          currency.getMinorUnits()));
      }
    }
    return new ImportCanonical(Map.copyOf(normalizedValues),Map.copyOf(normalizedRows));
  }

  @Transactional
  public DraftReceipt save(DraftRequest req,String idempotencyKey) {
    return saveCommand(req,idempotencyKey,null);
  }

  @Transactional
  public DraftReceipt saveWithAttachment(DraftRequest req,String idempotencyKey,FormAttachmentStore.Upload upload) {
    return saveCommand(req,idempotencyKey,Objects.requireNonNull(upload));
  }

  private DraftReceipt saveCommand(DraftRequest req,String idempotencyKey,FormAttachmentStore.Upload upload) {
    if(req==null) throw bad("REQUEST_REQUIRED");
    required(req.organizationId(),"ORGANIZATION");required(req.economicEntityId(),"ENTITY");
    required(req.businessScopeId(),"SCOPE");required(req.projectId(),"PROJECT");
    required(req.formCode(),"FORM_CODE"); required(req.periodType(),"PERIOD_TYPE");
    required(req.periodCode(),"PERIOD_CODE");required(req.currency(),"CURRENCY");
    required(req.reportingBasis(),"REPORTING_BASIS");required(req.dataSpace(),"DATA_SPACE");
    required(req.coverage(),"COVERAGE");required(req.createdBy(),"ACTOR");
    required(req.changeReason(),"REASON");
    if(req.currency().length()!=3||!req.currency().matches("[A-Z]{3}"))
      throw bad("CURRENCY_INVALID");
    var currency=currencies.findById(req.currency())
      .filter(x->"ACTIVE".equals(x.getStatus()))
      .orElseThrow(()->bad("UNSUPPORTED_CURRENCY"));
    PeriodResolver.Range period;
    try{period=PeriodResolver.resolve(req.periodType(),req.periodCode());}
    catch(IllegalArgumentException e){throw bad("INVALID_PERIOD");}
    if(req.expectedRevision()<0) throw bad("EXPECTED_REVISION_INVALID");
    if(idempotencyKey==null||idempotencyKey.isBlank()||idempotencyKey.length()>200)
      throw bad("IDEMPOTENCY_KEY_REQUIRED");
    var form=forms.findByFormCode(req.formCode()).orElseThrow(()->bad("FORM_NOT_FOUND"));
    var version=versions.findById(form.getCurrentVersionId()).orElseThrow();

    // Reject malformed rows before generating any database writes or hashes.
    if(req.rows()!=null&&req.rows().stream().anyMatch(Objects::isNull))
      throw bad("INVALID_ROW");
    // Hash the caller's exact request (before generation of row UUIDs), with map keys ordered.
    String canonicalRequest=json(List.of(String.valueOf(req.instanceId()),
      req.organizationId().toString(),req.economicEntityId().toString(),
      req.businessScopeId().toString(),req.projectId().toString(),
      req.formCode(),req.periodType(),req.periodCode(),req.currency(),req.coverage(),
      req.reportingBasis(),req.dataSpace(),req.expectedRevision(),
      req.createdBy(),req.changeReason(),
      json(req.values()==null?Map.of():new TreeMap<>(req.values())),
      json(req.rows()==null?List.of():req.rows().stream()
        .map(r->List.of(String.valueOf(r.rowId()),r.rowPosition(),
            json(r.values()==null?Map.of():new TreeMap<>(r.values())))).toList())));
    if(upload!=null)canonicalRequest=json(List.of(canonicalRequest,upload.fileName(),upload.mediaType(),upload.sha256()));
    String requestSha=sha("FIN-LIB-COMMAND-V1|"+canonicalRequest);
    var previous=receipts.findByOrganizationIdAndIdempotencyKey(req.organizationId(),idempotencyKey);
    if(previous.isPresent()) {
      var p=previous.get();
      if(!p.getRequestSha256().equals(requestSha))
        throw new ResponseStatusException(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_REUSED");
      var old=revisions.findById(p.getRevisionId()).orElseThrow();
      return new DraftReceipt(old.getInstanceId(),old.getId(),old.getRevisionNo(),
          old.getCanonicalContentSha256(),"IDEMPOTENT_REPLAY");
    }

    FormInstance inst;
    if(req.instanceId()==null) {
      if(req.expectedRevision()!=0)
        throw new ResponseStatusException(HttpStatus.CONFLICT,"EXPECTED_INITIAL_REVISION_0");
      // An existing slot is not a new document. Never silently append a
      // financial revision or hide this condition as a database unique error.
      if(instances.findExactSlot(req.organizationId(),req.economicEntityId(),
          req.businessScopeId(),req.projectId(),form.getId(),period.periodType(),
          period.periodCode(),req.coverage(),req.currency(),req.reportingBasis(),
          req.dataSpace()).isPresent())
        throw new ResponseStatusException(HttpStatus.CONFLICT,"REPORT_SLOT_ALREADY_EXISTS");
      inst=new FormInstance();inst.setId(UUID.randomUUID());
      inst.setOrganizationId(req.organizationId());inst.setEconomicEntityId(req.economicEntityId());
      inst.setBusinessScopeId(req.businessScopeId());inst.setProjectId(req.projectId());
      inst.setFormId(form.getId());inst.setPeriodType(period.periodType());
      inst.setPeriodCode(period.periodCode());
      inst.setPeriodStart(period.startInclusive());
      inst.setPeriodEndExclusive(period.endExclusive());
      inst.setCoverage(req.coverage());
      inst.setCurrency(req.currency());inst.setReportingBasis(req.reportingBasis());
      inst.setDataSpace(req.dataSpace());inst.setLatestRevisionNo(0);
      instances.saveAndFlush(inst);
    } else {
      inst=instances.lockByIdAndOrganizationId(req.instanceId(),req.organizationId())
          .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"INSTANCE_NOT_FOUND"));
      if(!"DRAFT".equals(inst.getWorkflowStatus()))
        throw new ResponseStatusException(HttpStatus.CONFLICT,"REPORT_NOT_EDITABLE_IN_CURRENT_STATUS");
      if(!Objects.equals(inst.getFormId(),form.getId())
        ||!Objects.equals(inst.getEconomicEntityId(),req.economicEntityId())
        ||!Objects.equals(inst.getBusinessScopeId(),req.businessScopeId())
        ||!Objects.equals(inst.getProjectId(),req.projectId())
        ||!Objects.equals(inst.getPeriodType(),period.periodType())
        ||!Objects.equals(inst.getPeriodCode(),period.periodCode())
        ||!Objects.equals(inst.getCurrency(),req.currency())
        ||!Objects.equals(inst.getCoverage(),req.coverage())
        ||!Objects.equals(inst.getReportingBasis(),req.reportingBasis())
        ||!Objects.equals(inst.getDataSpace(),req.dataSpace()))
        throw new ResponseStatusException(HttpStatus.CONFLICT,"SLOT_CONTEXT_CHANGED");
    }
    if(!inst.getLatestRevisionNo().equals(req.expectedRevision()))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"STALE_EXPECTED_REVISION");

    // Never rebind an existing report to a newly published catalog version.
    // New instances use the current version; each subsequent immutable
    // revision stays on its originally selected financial/schema contract.
    if(inst.getCurrentRevisionId()!=null){
      var previousRevision=revisions.findById(inst.getCurrentRevisionId()).orElseThrow();
      if(!previousRevision.getInstanceId().equals(inst.getId()))
        throw new IllegalStateException("REVISION_INSTANCE_INTEGRITY");
      version=versions.findById(previousRevision.getFormVersionId()).orElseThrow();
      if(!version.getFormId().equals(form.getId()))
        throw new IllegalStateException("REVISION_FORM_VERSION_INTEGRITY");
    }

    if(!contractGuard.matches(req.formCode(),version.getId()))
      throw bad("FIELD_METRIC_CONTRACT_STALE");
    boolean evidence="EVIDENCE".equals(version.getContentMode());
    if(upload!=null&&!evidence)throw bad("ATTACHMENT_REQUIRES_DOCUMENT_FORM");
    FormAttachmentStore.Attachment inherited=evidence&&inst.getCurrentRevisionId()!=null
        ? attachments.find(inst.getCurrentRevisionId()).orElse(null) : null;
    // EVIDENCE uses the versioned single-record metadata contract.
    boolean single=evidence||"SINGLE_RECORD".equals(version.getContentMode());
    if(!single&&!"ROWSET".equals(version.getContentMode()))
      throw bad("UNSUPPORTED_CONTENT_MODE");
    if(!single&&req.values()!=null&&!req.values().isEmpty())
      throw bad("ROWSET_TOP_LEVEL_VALUES_FORBIDDEN");
    Map<String,String> normalized=canonical(version.getId(),req.values(),single,
      currency.getMinorUnits());
    List<RowInput> sourceRows=req.rows()==null?List.of():req.rows();
    if((single&&!sourceRows.isEmpty())||(!single&&(sourceRows.isEmpty()||sourceRows.size()>200)))
      throw bad("INVALID_ROW_COUNT");
    List<RowInput> prepared=new ArrayList<>();
    if(!single){
      Set<UUID> ids=new HashSet<>();Set<Integer> positions=new HashSet<>();
      for(RowInput row:sourceRows) {
        if(row==null||row.rowPosition()<=0||!positions.add(row.rowPosition()))
          throw bad("INVALID_ROW_POSITION");
        UUID id=row.rowId()==null?UUID.randomUUID():row.rowId();
        if(!ids.add(id)) throw bad("DUPLICATE_ROW_ID");
        prepared.add(new RowInput(id,row.rowPosition(),canonical(version.getId(),row.values(),true,
          currency.getMinorUnits())));
      }
      prepared.sort(Comparator.comparingInt(RowInput::rowPosition).thenComparing(RowInput::rowId));
    }

    // INGEST-2 shares the exact historical canonical encoder with readback.
    var context=new FormContentFingerprint.Context(req.organizationId(),
      req.economicEntityId(),req.businessScopeId(),req.projectId(),req.formCode(),
      version.getVersionLabel(),period.periodType(),period.periodCode(),req.coverage(),
      req.currency(),req.reportingBasis(),req.dataSpace());
    var contentRows=prepared.stream()
      .map(r->new FormContentFingerprint.Row(r.rowId(),r.rowPosition(),r.values()))
      .toList();
    var contentAttachment=upload!=null
      ?new FormContentFingerprint.Attachment(upload.fileName(),upload.mediaType(),upload.sha256())
      :inherited!=null
        ?new FormContentFingerprint.Attachment(inherited.fileName(),inherited.mediaType(),inherited.sha256())
        :null;
    var hashes=contentFingerprint.compute(context,normalized,contentRows,contentAttachment);
    String contentHash=hashes.contentSha256();
    String orderHash=hashes.rowOrderSha256();
    // A fresh idempotency key must not create a phantom revision when
    // neither the financial content nor the explicit row order changed.
    // A replay of the SAME key was already returned above.
    if(inst.getCurrentRevisionId()!=null){
      var current=revisions.findById(inst.getCurrentRevisionId()).orElseThrow();
      if(contentHash.equals(current.getCanonicalContentSha256())&&
         orderHash.equals(current.getRowOrderSha256()))
        throw bad("NO_FINANCIAL_CHANGES");
    }
    FormRevision revision=new FormRevision();revision.setId(UUID.randomUUID());
    revision.setInstanceId(inst.getId());revision.setRevisionNo(inst.getLatestRevisionNo()+1);
    revision.setFormVersionId(version.getId());revision.setContentMode(version.getContentMode());
    revision.setValuesJson(json(normalized));revision.setCanonicalContentSha256(contentHash);
    revision.setRowOrderSha256(orderHash);revision.setSourceKind("CLIPBOARD_OR_MANUAL_DRAFT");
    revision.setCreatedAt(Instant.now());revision.setCreatedBy(req.createdBy());
    revision.setChangeReason(req.changeReason());revisions.saveAndFlush(revision);
    var storedRows=new ArrayList<FormRevisionRow>(prepared.size());
    for(RowInput row:prepared) {
      FormRevisionRow entry=new FormRevisionRow();entry.setId(UUID.randomUUID());
      entry.setRevisionId(revision.getId());entry.setRowId(row.rowId());
      entry.setRowPosition(row.rowPosition());entry.setValuesJson(json(row.values()));
      storedRows.add(entry);
    }
    if(!storedRows.isEmpty())rows.saveAll(storedRows);
    var attachment=upload==null?inherited:attachments.store(upload);
    if(attachment!=null)attachments.link(revision.getId(),attachment,normalized.get("documentRef"));
    inst.setCurrentRevisionId(revision.getId());
    inst.setLatestRevisionNo(revision.getRevisionNo());
    instances.saveAndFlush(inst); // Hibernate @Version protects concurrent same-slot commits.

    FormCommandReceipt receipt=new FormCommandReceipt();
    receipt.setId(UUID.randomUUID());receipt.setOrganizationId(req.organizationId());
    receipt.setIdempotencyKey(idempotencyKey);receipt.setRequestSha256(requestSha);
    receipt.setRevisionId(revision.getId());receipt.setResultCode("UNQUALIFIED_DRAFT");
    receipts.saveAndFlush(receipt);
    return new DraftReceipt(inst.getId(),revision.getId(),revision.getRevisionNo(),
        contentHash,"UNQUALIFIED_DRAFT");
  }

  public record SavedReport(UUID instanceId, String periodType, String periodCode,
                           java.time.LocalDate periodStart, String currency, String coverage,
                           String reportingBasis, String dataSpace, int revisionNo, String workflowStatus) {}

  @Transactional(readOnly=true)
  public List<SavedReport> listSavedReports(UUID organizationId, UUID economicEntityId,
      UUID businessScopeId, UUID projectId, String formCode) {
    var form=forms.findByFormCode(formCode)
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"FORM_NOT_FOUND"));
    return instances.findSavedReports(organizationId,economicEntityId,businessScopeId,projectId,form.getId())
        .stream().map(i->new SavedReport(i.getId(),i.getPeriodType(),i.getPeriodCode(),i.getPeriodStart(),
            i.getCurrency(),i.getCoverage(),i.getReportingBasis(),i.getDataSpace(),
            i.getLatestRevisionNo(),i.getWorkflowStatus())).toList();
  }

  /** Safe re-open of the exact financial slot. No automatic write or rebind. */
  @Transactional(readOnly=true)
  public DraftView readExactSlot(UUID organizationId, UUID economicEntityId,
      UUID businessScopeId, UUID projectId, String formCode,
      String periodType, String periodCode, String coverage,
      String currency, String reportingBasis, String dataSpace) {
    required(organizationId,"ORGANIZATION"); required(economicEntityId,"ENTITY");
    required(businessScopeId,"SCOPE"); required(projectId,"PROJECT");
    required(formCode,"FORM_CODE"); required(periodType,"PERIOD_TYPE");
    required(periodCode,"PERIOD_CODE"); required(currency,"CURRENCY");
    required(coverage,"COVERAGE"); required(reportingBasis,"REPORTING_BASIS");
    required(dataSpace,"DATA_SPACE");
    var form=forms.findByFormCode(formCode)
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"FORM_NOT_FOUND"));
    var instance=instances.findExactSlot(organizationId,economicEntityId,
        businessScopeId,projectId,form.getId(),periodType,periodCode,
        coverage,currency,reportingBasis,dataSpace)
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"REPORT_SLOT_NOT_FOUND"));
    return read(instance.getId(),organizationId);
  }

  @Transactional(readOnly=true)
  public DraftView read(UUID id,UUID organizationId) {
    return readRevision(id,organizationId,null);
  }

  @Transactional(readOnly=true)
  public DraftView readRevision(UUID id,UUID organizationId,Integer revisionNo) {
    FormInstance inst=instances.findByIdAndOrganizationId(id,organizationId)
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"INSTANCE_NOT_FOUND"));
    FormRevision rev=lookupRevision(inst,revisionNo);
    FormDefinition form=forms.findById(inst.getFormId()).orElseThrow();
    FormVersion version=versions.findById(rev.getFormVersionId()).orElseThrow();
    if(!inst.getFormId().equals(version.getFormId())||
       !Objects.equals(rev.getContentMode(),version.getContentMode()))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"REVISION_FORM_CONTRACT_INTEGRITY");
    List<RowView> lineItems=rows.findByRevisionIdOrderByRowPositionAscRowIdAsc(rev.getId())
       .stream().map(r->new RowView(r.getRowId(),r.getRowPosition(),parseValues(r.getValuesJson()))).toList();
    return new DraftView(inst.getId(),rev.getId(),inst.getOrganizationId(),rev.getRevisionNo(),
        form.getFormCode(),version.getVersionLabel(),version.getContentMode(),
        parseValues(rev.getValuesJson()),lineItems,rev.getCanonicalContentSha256(),
        inst.getPeriodType(),inst.getPeriodCode(),inst.getPeriodStart(),
        inst.getPeriodEndExclusive(),inst.getCurrency(),
        inst.getWorkflowStatus(),inst.getStatusVersion(),
        inst.getEconomicEntityId(),inst.getBusinessScopeId(),inst.getProjectId(),
        inst.getCoverage(),inst.getReportingBasis(),inst.getDataSpace(),
        "EVIDENCE".equals(rev.getContentMode())?attachments.find(rev.getId()).orElse(null):null);
  }

  @Transactional(readOnly=true)
  public FormAttachmentStore.Download readAttachment(UUID id,UUID organizationId,UUID projectId,int revisionNo) {
    var instance=instances.findByIdAndOrganizationId(id,organizationId)
        .filter(i->Objects.equals(i.getProjectId(),projectId))
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"INSTANCE_NOT_FOUND"));
    var revision=lookupRevision(instance,revisionNo);
    if(!"EVIDENCE".equals(revision.getContentMode()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ATTACHMENT_NOT_FOUND");
    var attachment=attachments.find(revision.getId())
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"ATTACHMENT_NOT_FOUND"));
    return attachments.load(attachment);
  }

  private FormRevision lookupRevision(FormInstance instance,Integer revisionNo) {
    if(instance.getCurrentRevisionId()==null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"REVISION_NOT_FOUND");
    FormRevision revision=(revisionNo==null
        ? revisions.findById(instance.getCurrentRevisionId())
        : revisions.findByInstanceIdAndRevisionNo(instance.getId(),revisionNo))
        .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"REVISION_NOT_FOUND"));
    if(!instance.getId().equals(revision.getInstanceId())||
       (revisionNo==null&&!Objects.equals(instance.getLatestRevisionNo(),revision.getRevisionNo()))||
       (revisionNo!=null&&revision.getRevisionNo()!=revisionNo))
      throw new ResponseStatusException(HttpStatus.CONFLICT,"REVISION_INSTANCE_INTEGRITY");
    return revision;
  }
}
