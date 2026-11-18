package kz.fin.formlib.api;

import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import kz.fin.formlib.service.FormDraftService;
import kz.fin.formlib.service.FormAttachmentStore;
import kz.fin.formlib.service.CatalogPaging;
import kz.fin.formlib.service.CatalogReadCache;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
  private final CatalogReadCache cache;

  public FormLibraryController(FormDefinitionRepository forms,FormVersionRepository versions,
        FormSectionRepository sections,FormFieldRepository fields,FormDraftService drafts,
        CatalogReadCache cache){
    this.forms=forms;this.versions=versions;this.sections=sections;this.fields=fields;this.drafts=drafts;
    this.cache=cache;
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
    return cache.get("legacy-form-summaries","ALL",()->forms.findAllByOrderByFormCodeAsc().stream().map(f->{
      var v=version(f);
      return new FormSummary(f.getFormCode(),f.getCatalog(),f.getLegacyCaptureMode(),
          v.getVersionLabel(),v.getContentMode(),v.getQualificationState());
    }).toList());
  }
  @GetMapping("/forms/{code}")
  public FormDetail get(@PathVariable String code){
    return cache.get("legacy-form-detail",code,()->{
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
    });
  }

  @GetMapping("/forms/page")
  public CatalogPaging.Page<FormSummary> formsPage(
      @RequestParam(defaultValue="0") int page,
      @RequestParam(defaultValue="24") int size,
      @RequestParam(required=false) String q,
      @RequestParam(defaultValue="CARDS") String viewMode){
    String search=CatalogPaging.normalizedSearch(q);
    var filtered=list().stream().filter(f->CatalogPaging.matches(search,
      f.formCode(),f.catalog(),f.contentMode())).toList();
    return CatalogPaging.page(filtered,page,size,viewMode,"CODE");
  }
  @PostMapping("/drafts")
  public FormDraftService.DraftReceipt save(@RequestHeader("Idempotency-Key") String key,
          @RequestBody FormDraftService.DraftRequest payload){
    return drafts.save(payload,key);
  }
  @PostMapping(value="/drafts/with-attachment",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public FormDraftService.DraftReceipt saveWithAttachment(@RequestHeader("Idempotency-Key") String key,
      @RequestPart("draft") FormDraftService.DraftRequest payload,@RequestPart("file") MultipartFile file) {
    if(file.getSize()>FormAttachmentStore.MAX_BYTES)
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"ATTACHMENT_TOO_LARGE");
    byte[] bytes;
    try(var input=file.getInputStream()){
      bytes=input.readNBytes(FormAttachmentStore.MAX_BYTES+1);
    }catch(IOException e){
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"ATTACHMENT_READ_FAILED");
    }
    return drafts.saveWithAttachment(payload,key,FormAttachmentStore.validate(file.getOriginalFilename(),bytes));
  }

  @GetMapping("/drafts/{id}/revisions/{number}/attachment")
  public ResponseEntity<byte[]> downloadAttachment(@PathVariable UUID id,@PathVariable int number,
      @RequestParam UUID organizationId,@RequestParam UUID projectId) {
    var file=drafts.readAttachment(id,organizationId,projectId,number);
    var disposition=ContentDisposition.attachment().filename(file.attachment().fileName(),StandardCharsets.UTF_8).build();
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(file.attachment().sizeBytes())
        .cacheControl(CacheControl.noStore())
        .header("Content-Disposition",disposition.toString())
        .header("X-Content-Type-Options","nosniff")
        .body(file.bytes());
  }

  @GetMapping("/drafts/{id}")
  public FormDraftService.DraftView read(@PathVariable UUID id,
           @RequestParam UUID organizationId){
    return drafts.read(id,organizationId);
  }
  @GetMapping("/drafts/{id}/revisions/{number}")
  public FormDraftService.DraftView readRevision(@PathVariable UUID id,
           @PathVariable int number,@RequestParam UUID organizationId){
    if(number<1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"REVISION_NOT_FOUND");
    return drafts.readRevision(id,organizationId,number);
  }
}
