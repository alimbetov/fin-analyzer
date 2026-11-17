package kz.fin.formlib.api;

import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import kz.fin.formlib.service.FormDraftService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class FormLibraryController {
  private final FormDefinitionRepository forms;
  private final FormVersionRepository versions;
  private final FormSectionRepository sections;
  private final FormFieldRepository fields;
  private final FormDraftService drafts;

  public FormLibraryController(FormDefinitionRepository forms,FormVersionRepository versions,
        FormSectionRepository sections,FormFieldRepository fields,FormDraftService drafts){
    this.forms=forms;this.versions=versions;this.sections=sections;this.fields=fields;this.drafts=drafts;
  }
  public record FormSummary(String formCode,String catalog,String captureMode,
                            String version,String contentMode,String qualificationState){}
  public record SectionView(String sectionCode,int sectionOrder){}
  public record FieldView(String fieldCode,String fieldType,String inputMode,int displayOrder,
                          boolean required,String sectionCode){}
  public record FormDetail(FormSummary summary,List<SectionView> sections,List<FieldView> fields){}
  private FormVersion version(FormDefinition f){
    return versions.findById(f.getCurrentVersionId()).orElseThrow();
  }
  @GetMapping("/forms")
  public List<FormSummary> list(){
    return forms.findAllByOrderByFormCodeAsc().stream().map(f->{
      var v=version(f);
      return new FormSummary(f.getFormCode(),f.getCatalog(),f.getLegacyCaptureMode(),
          v.getVersionLabel(),v.getContentMode(),v.getQualificationState());
    }).toList();
  }
  @GetMapping("/forms/{code}")
  public FormDetail get(@PathVariable String code){
    FormDefinition f=forms.findByFormCode(code)
      .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"FORM_NOT_FOUND"));
    var v=version(f);
    var orderedSections=sections.findByFormVersionIdOrderBySectionOrderAsc(v.getId());
    var byId=new HashMap<UUID,String>();
    orderedSections.forEach(s->byId.put(s.getId(),s.getSectionCode()));
    List<SectionView> sec=orderedSections.stream()
        .map(s->new SectionView(s.getSectionCode(),s.getSectionOrder())).toList();
    List<FieldView> vals=fields.findByFormVersionIdOrderByDisplayOrderAsc(v.getId())
        .stream().sorted(Comparator.comparingInt((FormField field)->
            orderedSections.stream().filter(s->s.getId().equals(field.getSectionId()))
              .findFirst().orElseThrow().getSectionOrder())
            .thenComparingInt(FormField::getDisplayOrder).thenComparing(FormField::getFieldCode))
        .map(field->new FieldView(field.getFieldCode(),field.getFieldType(),field.getInputMode(),
            field.getDisplayOrder(),Boolean.TRUE.equals(field.getRequired()),
            byId.get(field.getSectionId()))).toList();
    return new FormDetail(new FormSummary(f.getFormCode(),f.getCatalog(),f.getLegacyCaptureMode(),
        v.getVersionLabel(),v.getContentMode(),v.getQualificationState()),sec,vals);
  }
  @PostMapping("/drafts")
  public FormDraftService.DraftReceipt save(@RequestHeader("Idempotency-Key") String key,
          @RequestBody FormDraftService.DraftRequest payload){
    return drafts.save(payload,key);
  }
  @GetMapping("/drafts/{id}")
  public FormDraftService.DraftView read(@PathVariable UUID id,
           @RequestParam UUID organizationId){
    return drafts.read(id,organizationId);
  }
}
