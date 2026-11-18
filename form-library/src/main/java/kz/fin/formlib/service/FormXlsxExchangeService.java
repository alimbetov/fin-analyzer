package kz.fin.formlib.service;

import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Round-trip XLSX adapter for existing fin_intake.lib_form_instance revisions.
 * Only supported MANUAL field values are imported. No independent fact writer.
 *
 * The workbook is untrusted: version, SHA, context and editable bindings are
 * resolved against fin_intake and the pinned fin_lib schema on each import.
 */
@Service
public class FormXlsxExchangeService {
  private final FormDraftService drafts;
  private final FormInstanceRepository instances;
  private final FormRevisionRepository revisions;
  private final FormFieldRepository fields;
  private final FormStylePreferenceService preferences;

  public FormXlsxExchangeService(FormDraftService drafts,FormInstanceRepository instances,
      FormRevisionRepository revisions,FormFieldRepository fields,
      FormStylePreferenceService preferences) {
    this.drafts=drafts;this.instances=instances;this.revisions=revisions;
    this.fields=fields;this.preferences=preferences;
  }

  private static ResponseStatusException conflict(String code) {
    return new ResponseStatusException(HttpStatus.CONFLICT,code);
  }
  private static ResponseStatusException invalid(String code) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }

  private record Current(FormInstance instance,FormRevision revision,
                         FormDraftService.DraftView view,List<FormField> fields) {}

  private Current current(UUID instanceId,UUID organizationId) {
    if(instanceId==null||organizationId==null)throw invalid("REPORT_CONTEXT_REQUIRED");
    FormInstance instance=instances.findByIdAndOrganizationId(instanceId,organizationId)
      .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"INSTANCE_NOT_FOUND"));
    if(instance.getCurrentRevisionId()==null)throw conflict("REPORT_NOT_SAVED");
    FormRevision revision=revisions.findById(instance.getCurrentRevisionId())
      .orElseThrow(()->conflict("REPORT_REVISION_MISSING"));
    if(!instanceId.equals(revision.getInstanceId())||
        !instance.getLatestRevisionNo().equals(revision.getRevisionNo()))
      throw conflict("REPORT_REVISION_INTEGRITY");
    var view=drafts.read(instanceId,organizationId);
    if(view.revisionNo()!=revision.getRevisionNo()||
        !view.sha256().equals(revision.getCanonicalContentSha256()))
      throw conflict("REPORT_SNAPSHOT_MISMATCH");
    var definitions=fields.findByFormVersionIdOrderByDisplayOrderAsc(revision.getFormVersionId())
      .stream().filter(f->"MANUAL".equals(f.getInputMode())).toList();
    if(definitions.isEmpty())throw invalid("XLSX_NO_EDITABLE_FIELDS");
    int count=definitions.size()*(view.contentMode().equals("ROWSET")?view.rows().size():1);
    if(count<=0||count>FormXlsxCodec.MAX_CELLS)throw invalid("XLSX_FIELD_COUNT_INVALID");
    if(!Set.of("SINGLE_RECORD","ROWSET").contains(view.contentMode()))
      throw invalid("XLSX_CONTENT_MODE_UNSUPPORTED");
    return new Current(instance,revision,view,definitions);
  }

  private static List<FormXlsxCodec.Entry> entries(Current current) {
    var output=new ArrayList<FormXlsxCodec.Entry>();
    var view=current.view();
    if("SINGLE_RECORD".equals(view.contentMode())){
      for(FormField field:current.fields())
        output.add(new FormXlsxCodec.Entry(field.getFieldCode(),
          view.values().getOrDefault(field.getFieldCode(),""),
          field.getFieldType(),field.getUnitCode()==null?"":field.getUnitCode(),
          Boolean.TRUE.equals(field.getRequired())));
    }else{
      for(var row:view.rows())
        for(FormField field:current.fields())
          output.add(new FormXlsxCodec.Entry(row.rowId()+"|"+field.getFieldCode(),
            row.values().getOrDefault(field.getFieldCode(),""),
            field.getFieldType(),field.getUnitCode()==null?"":field.getUnitCode(),
            Boolean.TRUE.equals(field.getRequired())));
    }
    return output;
  }

  @Transactional(readOnly=true)
  public byte[] export(UUID instanceId,UUID organizationId,String styleCode,UUID userId){
    Current current=current(instanceId,organizationId);
    String selected=styleCode;
    if(selected==null||selected.isBlank())
      selected=userId==null?"CLASSIC":
        preferences.getForForm(current.view().formCode(),userId).styleCode();
    var envelope=new FormXlsxCodec.Envelope(instanceId,organizationId,
      current.revision().getFormVersionId(),current.view().formCode(),
      current.revision().getRevisionNo(),current.revision().getCanonicalContentSha256(),
      FormXlsxCodec.style(selected));
    return FormXlsxCodec.export(envelope,entries(current));
  }

  public record ImportPreview(UUID instanceId,UUID organizationId,UUID formVersionId,
                              String formCode,int expectedRevision,
                              String originalContentSha256,int inputCellCount,
                              Map<String,String> values) {}

  private record Prepared(FormDraftService.DraftRequest request,
                          ImportPreview preview) {}

  private Prepared prepare(UUID instanceId,UUID organizationId,byte[] upload,
      String actor,String reason) {
    if(actor==null||actor.isBlank()||reason==null||reason.isBlank())
      throw invalid("IMPORT_AUDIT_METADATA_REQUIRED");
    Current current=current(instanceId,organizationId);
    if(!"DRAFT".equals(current.instance().getWorkflowStatus()))
      throw conflict("REPORT_NOT_EDITABLE_IN_CURRENT_STATUS");
    var parsed=FormXlsxCodec.parse(upload,entries(current));
    var metadata=parsed.envelope();
    if(!instanceId.equals(metadata.instanceId())||
       !organizationId.equals(metadata.organizationId())||
       !current.view().formCode().equals(metadata.formCode())||
       !current.revision().getFormVersionId().equals(metadata.formVersionId()))
      throw conflict("XLSX_CONTEXT_MISMATCH");
    if(current.revision().getRevisionNo()!=metadata.revision()||
       !current.revision().getCanonicalContentSha256().equals(metadata.contentSha256()))
      throw conflict("XLSX_STALE_REVISION");
    Map<String,String> values=new LinkedHashMap<>();
    List<FormDraftService.RowInput> rows=new ArrayList<>();
    if("SINGLE_RECORD".equals(current.view().contentMode())){
      for(FormField field:current.fields()){
        String code=field.getFieldCode();
        String value=parsed.values().get(code);
        if(!value.isEmpty()||current.view().values().containsKey(code))
          values.put(code,value);
      }
    }else{
      for(var row:current.view().rows()){
        Map<String,String> rowValues=new LinkedHashMap<>();
        for(FormField field:current.fields()){
          String code=field.getFieldCode();
          String value=parsed.values().get(row.rowId()+"|"+code);
          if(!value.isEmpty()||row.values().containsKey(code))rowValues.put(code,value);
        }
        rows.add(new FormDraftService.RowInput(row.rowId(),row.rowPosition(),rowValues));
      }
    }
    var i=current.instance();
    var request=new FormDraftService.DraftRequest(instanceId,organizationId,
      i.getEconomicEntityId(),i.getBusinessScopeId(),i.getProjectId(),
      current.view().formCode(),i.getPeriodType(),i.getPeriodCode(),
      i.getCurrency(),i.getCoverage(),i.getReportingBasis(),i.getDataSpace(),
      metadata.revision(),values,rows,actor,reason);
    drafts.validateImportCandidate(request,metadata.formVersionId());
    return new Prepared(request,new ImportPreview(instanceId,organizationId,
      metadata.formVersionId(),metadata.formCode(),metadata.revision(),
      metadata.contentSha256(),parsed.values().size(),Map.copyOf(parsed.values())));
  }

  @Transactional(readOnly=true)
  public ImportPreview preview(UUID instanceId,UUID organizationId,
      byte[] upload,String actor,String reason) {
    return prepare(instanceId,organizationId,upload,actor,reason).preview();
  }

  @Transactional
  public FormDraftService.DraftReceipt importFile(UUID instanceId,UUID organizationId,
      byte[] upload,String idempotencyKey,String actor,String reason) {
    // Re-parse and revalidate at commit: preview never authorizes a write.
    Prepared prepared=prepare(instanceId,organizationId,upload,actor,reason);
    return drafts.save(prepared.request(),idempotencyKey);
  }
}
