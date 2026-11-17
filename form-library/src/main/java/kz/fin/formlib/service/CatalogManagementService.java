package kz.fin.formlib.service;

import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * R7.A: presentation-only catalog workflow. No security or financial S2 approval.
 * Edit a CLONED version; never mutate a version referenced by historical forms.
 */
@Service
public class CatalogManagementService {
  public record BeginRequest(UUID expectedActiveVersionId,String actor) {}
  public record EditReceipt(UUID editSessionId,String formCode,UUID baseVersionId,
                            UUID draftVersionId,int editRevision,String status) {}
  public record TranslationUpdate(int expectedRevision,String subjectType,String subjectCode,
                                  String title,String description,String actor) {}
  public record FieldPosition(String fieldCode,int displayOrder) {}
  public record FieldOrderUpdate(int expectedRevision,String sectionCode,
                                 List<FieldPosition> positions,String actor) {}
  public record SectionPosition(String sectionCode,int sectionOrder) {}
  public record SectionOrderUpdate(int expectedRevision,List<SectionPosition> positions,String actor) {}
  public record FinishRequest(int expectedRevision,UUID expectedActiveVersionId,String actor) {}
  public record TranslationView(String subjectType,String subjectCode,String locale,
                                String title,String description,String reviewStatus) {}
  public record SectionView(String sectionCode,int sectionOrder,String parentSectionCode) {}
  public record FieldView(String sectionCode,String fieldCode,String fieldType,
                          String inputMode,int displayOrder,boolean required) {}
  public record DraftDetail(EditReceipt session,List<SectionView> sections,
                            List<FieldView> fields,List<TranslationView> translations,
                            String schemaSha256,String presentationSha256) {}
  public record HistoryView(int revision,String type,String actor,Instant occurredAt,
                            String contentSha256) {}

  private static final Set<String> LOCALES=Set.of("ru","kk","en","zh-Hans");
  private final FormDefinitionRepository forms;
  private final FormVersionRepository versions;
  private final FormSectionRepository sections;
  private final FormFieldRepository fields;
  private final FormFieldTranslationRepository fieldTranslations;
  private final LibFormTranslationRepository formTranslations;
  private final FormValidationRuleRepository validationRules;
  private final FormMetricMappingCandidateRepository metricMappings;
  private final LibFormEditSessionRepository sessions;
  private final LibFormEditEventRepository events;

  public CatalogManagementService(FormDefinitionRepository forms,
      FormVersionRepository versions,FormSectionRepository sections,
      FormFieldRepository fields,FormFieldTranslationRepository fieldTranslations,
      LibFormTranslationRepository formTranslations,
      FormValidationRuleRepository validationRules,
      FormMetricMappingCandidateRepository metricMappings,
      LibFormEditSessionRepository sessions,LibFormEditEventRepository events){
    this.forms=forms;this.versions=versions;this.sections=sections;this.fields=fields;
    this.fieldTranslations=fieldTranslations;this.formTranslations=formTranslations;
    this.validationRules=validationRules;this.metricMappings=metricMappings;
    this.sessions=sessions;this.events=events;
  }
  private static ResponseStatusException bad(String message){
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,message);
  }
  private static ResponseStatusException conflict(String message){
    return new ResponseStatusException(HttpStatus.CONFLICT,message);
  }
  private static String actor(String text){
    if(text==null||text.isBlank()||text.length()>160)throw bad("ACTOR_REQUIRED");
    return text.trim();
  }
  private static String text(String input,int max,String code){
    if(input==null||input.isBlank()||input.length()>max)throw bad(code);
    return input.trim();
  }
  private static String description(String input,int max){
    if(input!=null&&input.length()>max)throw bad("DESCRIPTION_TOO_LONG");
    return input;
  }
  private static String sha(String text){
    try{
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(text.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception e){throw new IllegalStateException(e);}
  }
  private FormDefinition lockForm(UUID id){
    return forms.lockById(id).orElseThrow(
      ()->new ResponseStatusException(HttpStatus.NOT_FOUND,"FORM_NOT_FOUND"));
  }
  private FormDefinition lockForm(String code){
    var f=forms.findByFormCode(code).orElseThrow(
      ()->new ResponseStatusException(HttpStatus.NOT_FOUND,"FORM_NOT_FOUND"));
    return lockForm(f.getId());
  }
  private record Locked(FormDefinition form,LibFormEditSession session) {}
  private Locked lockDraft(UUID id,int expectedRev){
    var probe=sessions.findById(id).orElseThrow(
      ()->new ResponseStatusException(HttpStatus.NOT_FOUND,"EDIT_SESSION_NOT_FOUND"));
    var f=lockForm(probe.getFormId());
    var session=sessions.lockById(id).orElseThrow();
    if(!session.getFormId().equals(f.getId()))throw new IllegalStateException("SESSION_FORM_DRIFT");
    if(!"DRAFT".equals(session.getStatus()))throw conflict("EDIT_SESSION_NOT_DRAFT");
    if(session.getEditRevision()!=expectedRev)throw conflict("STALE_EDIT_REVISION");
    return new Locked(f,session);
  }
  private static EditReceipt receipt(FormDefinition f,LibFormEditSession s){
    return new EditReceipt(s.getId(),f.getFormCode(),s.getBaseVersionId(),
      s.getDraftVersionId(),s.getEditRevision(),s.getStatus());
  }
  private void event(LibFormEditSession session,String type,String who,String contentHash){
    LibFormEditEvent ev=new LibFormEditEvent();ev.setId(UUID.randomUUID());
    ev.setEditSessionId(session.getId());ev.setEditRevision(session.getEditRevision());
    ev.setEventType(type);ev.setActor(who);ev.setOccurredAt(Instant.now());
    ev.setContentSha256(contentHash);events.save(ev);
  }
  private void changed(LibFormEditSession session,String who,String type,String contentHash){
    session.setEditRevision(session.getEditRevision()+1);
    session.setUpdatedAt(Instant.now());session.setUpdatedBy(who);
    sessions.saveAndFlush(session);
    event(session,type,who,contentHash);
  }
  private static <T> List<T> sorted(Collection<T> collection,Comparator<T> comparator){
    return collection.stream().sorted(comparator).toList();
  }

  @Transactional
  public EditReceipt begin(String formCode,BeginRequest request){
    if(request==null||request.expectedActiveVersionId()==null)throw bad("EXPECTED_VERSION_REQUIRED");
    String who=actor(request.actor());
    var f=lockForm(formCode);
    if(!request.expectedActiveVersionId().equals(f.getCurrentVersionId()))
      throw conflict("STALE_ACTIVE_VERSION");
    if(!sessions.findByFormIdAndStatus(f.getId(),"DRAFT").isEmpty())
      throw conflict("OPEN_DRAFT_ALREADY_EXISTS");
    var active=versions.findById(f.getCurrentVersionId()).orElseThrow();
    UUID newId=UUID.randomUUID();
    var candidate=new FormVersion();candidate.setId(newId);candidate.setFormId(f.getId());
    candidate.setVersionLabel("LIB-EDIT-"+newId);
    candidate.setContentMode(active.getContentMode());candidate.setGrain(active.getGrain());
    candidate.setQualificationState("CANDIDATE_UNQUALIFIED");
    candidate.setSchemaSha256(active.getSchemaSha256());
    candidate.setPresentationSha256(active.getPresentationSha256());
    candidate.setMappingSha256(active.getMappingSha256());
    candidate.setCreatedAt(Instant.now());versions.saveAndFlush(candidate);

    var originals=sections.findByFormVersionIdOrderBySectionOrderAsc(active.getId());
    Map<UUID,UUID> sectionIds=new HashMap<>();
    for(var original:originals)sectionIds.put(original.getId(),UUID.randomUUID());
    for(var old:originals){
      var copy=new FormSection();copy.setId(sectionIds.get(old.getId()));
      copy.setFormVersionId(newId);copy.setSectionCode(old.getSectionCode());
      copy.setSectionOrder(old.getSectionOrder());copy.setLayoutKind(old.getLayoutKind());
      if(old.getParentSectionId()!=null){
        UUID parent=sectionIds.get(old.getParentSectionId());
        if(parent==null)throw new IllegalStateException("FOREIGN_SECTION_PARENT");
        copy.setParentSectionId(parent);
      }
      sections.save(copy);
    }
    sections.flush();

    for(var original:fields.findByFormVersionIdOrderByDisplayOrderAsc(active.getId())){
      var copy=new FormField();copy.setId(UUID.randomUUID());copy.setFormVersionId(newId);
      UUID section=sectionIds.get(original.getSectionId());
      if(section==null)throw new IllegalStateException("FOREIGN_FIELD_SECTION");
      copy.setSectionId(section);copy.setFieldCode(original.getFieldCode());
      copy.setDisplayOrder(original.getDisplayOrder());copy.setFieldType(original.getFieldType());
      copy.setInputMode(original.getInputMode());copy.setRequired(original.getRequired());
      copy.setFormulaCode(original.getFormulaCode());copy.setUnitCode(original.getUnitCode());
      fields.save(copy);
    }
    fields.flush();
    for(var source:formTranslations.findByFormVersionId(active.getId())){
      var copy=new LibFormTranslation();copy.setId(UUID.randomUUID());copy.setFormVersionId(newId);
      copy.setLocale(source.getLocale());copy.setTitle(source.getTitle());
      copy.setDescription(source.getDescription());copy.setReviewStatus(source.getReviewStatus());
      formTranslations.save(copy);
    }
    for(var source:fieldTranslations.findByFormVersionId(active.getId())){
      var copy=new FormFieldTranslation();copy.setId(UUID.randomUUID());
      copy.setFormVersionId(newId);copy.setSubjectType(source.getSubjectType());
      copy.setSubjectCode(source.getSubjectCode());copy.setLocale(source.getLocale());
      copy.setTitle(source.getTitle());copy.setDescription(source.getDescription());
      copy.setReviewStatus(source.getReviewStatus());fieldTranslations.save(copy);
    }
    for(var source:validationRules.findByFormVersionId(active.getId())){
      var copy=new FormValidationRule();copy.setId(UUID.randomUUID());copy.setFormVersionId(newId);
      copy.setRuleCode(source.getRuleCode());copy.setRuleVersion(source.getRuleVersion());
      copy.setRuleKind(source.getRuleKind());copy.setTypedParams(source.getTypedParams());
      copy.setSeverity(source.getSeverity());validationRules.save(copy);
    }
    for(var source:metricMappings.findByFormVersionId(active.getId())){
      var copy=new FormMetricMappingCandidate();copy.setId(UUID.randomUUID());
      copy.setFormVersionId(newId);copy.setFieldCode(source.getFieldCode());
      copy.setMetricCode(source.getMetricCode());copy.setMetricVersion(source.getMetricVersion());
      copy.setMappingSha256(source.getMappingSha256());copy.setReviewState(source.getReviewState());
      metricMappings.save(copy);
    }
    var session=new LibFormEditSession();session.setId(UUID.randomUUID());
    session.setFormId(f.getId());session.setBaseVersionId(active.getId());
    session.setDraftVersionId(newId);session.setStatus("DRAFT");session.setEditRevision(0);
    session.setCreatedBy(who);session.setUpdatedBy(who);
    session.setCreatedAt(Instant.now());session.setUpdatedAt(Instant.now());
    sessions.saveAndFlush(session);
    event(session,"CREATED",who,sha(active.getSchemaSha256()+"|"+active.getPresentationSha256()));
    return receipt(f,session);
  }

  @Transactional(readOnly=true)
  public DraftDetail detail(UUID sessionId){
    var session=sessions.findById(sessionId).orElseThrow(
      ()->new ResponseStatusException(HttpStatus.NOT_FOUND,"EDIT_SESSION_NOT_FOUND"));
    var f=forms.findById(session.getFormId()).orElseThrow();
    var v=versions.findById(session.getDraftVersionId()).orElseThrow();
    var sectionList=sections.findByFormVersionIdOrderBySectionOrderAsc(v.getId());
    Map<UUID,String> codes=new HashMap<>();
    sectionList.forEach(s->codes.put(s.getId(),s.getSectionCode()));
    var sec=sectionList.stream().map(s->new SectionView(s.getSectionCode(),
      s.getSectionOrder(),s.getParentSectionId()==null?null:codes.get(s.getParentSectionId()))).toList();
    var fieldList=fields.findByFormVersionIdOrderByDisplayOrderAsc(v.getId()).stream()
      .sorted(Comparator.comparingInt((FormField x)->
          sectionList.stream().filter(s->s.getId().equals(x.getSectionId()))
            .findFirst().orElseThrow().getSectionOrder())
        .thenComparingInt(FormField::getDisplayOrder).thenComparing(FormField::getFieldCode))
      .map(x->new FieldView(codes.get(x.getSectionId()),x.getFieldCode(),
        x.getFieldType(),x.getInputMode(),x.getDisplayOrder(),x.getRequired())).toList();
    var tr=new ArrayList<TranslationView>();
    formTranslations.findByFormVersionId(v.getId()).forEach(t->
      tr.add(new TranslationView("FORM",f.getFormCode(),t.getLocale(),
        t.getTitle(),t.getDescription(),t.getReviewStatus())));
    fieldTranslations.findByFormVersionId(v.getId()).forEach(t->
      tr.add(new TranslationView(t.getSubjectType(),t.getSubjectCode(),t.getLocale(),
        t.getTitle(),t.getDescription(),t.getReviewStatus())));
    tr.sort(Comparator.comparing(TranslationView::subjectType)
      .thenComparing(TranslationView::subjectCode).thenComparing(TranslationView::locale));
    return new DraftDetail(receipt(f,session),sec,fieldList,tr,
      v.getSchemaSha256(),v.getPresentationSha256());
  }

  @Transactional
  public EditReceipt updateTranslation(UUID editId,String locale,TranslationUpdate req){
    if(req==null)throw bad("TRANSLATION_REQUEST_REQUIRED");
    var locked=lockDraft(editId,req.expectedRevision());
    if(!LOCALES.contains(locale))throw bad("UNSUPPORTED_LOCALE");
    String who=actor(req.actor()),title=text(req.title(),255,"TITLE_REQUIRED");
    var session=locked.session();
    UUID versionId=session.getDraftVersionId();
    if("FORM".equals(req.subjectType())){
      var t=formTranslations.findByFormVersionId(versionId).stream()
        .filter(x->x.getLocale().equals(locale)).findFirst().orElseThrow();
      t.setTitle(title);t.setDescription(description(req.description(),1500));
      t.setReviewStatus("DRAFT_UNREVIEWED");formTranslations.save(t);
    }else{
      String subject=text(req.subjectCode(),128,"SUBJECT_CODE_REQUIRED");
      boolean exists=switch(req.subjectType()){
        case "SECTION" -> sections.findByFormVersionIdOrderBySectionOrderAsc(versionId)
          .stream().anyMatch(s->s.getSectionCode().equals(subject));
        case "FIELD" -> fields.findByFormVersionIdOrderByDisplayOrderAsc(versionId)
          .stream().anyMatch(f->f.getFieldCode().equals(subject));
        default -> false;
      };
      if(!exists)throw bad("TRANSLATION_SUBJECT_NOT_FOUND");
      var match=fieldTranslations.findByFormVersionId(versionId).stream().filter(t->
        t.getLocale().equals(locale)&&t.getSubjectType().equals(req.subjectType())&&
          t.getSubjectCode().equals(subject)).findFirst();
      var t=match.orElseGet(()->{
        var created=new FormFieldTranslation();created.setId(UUID.randomUUID());
        created.setFormVersionId(versionId);created.setLocale(locale);
        created.setSubjectType(req.subjectType());created.setSubjectCode(subject);
        return created;
      });
      t.setTitle(title);t.setDescription(description(req.description(),255));
      t.setReviewStatus("DRAFT_UNREVIEWED");fieldTranslations.save(t);
    }
    fieldTranslations.flush();formTranslations.flush();
    updatePresentationHash(versionId);
    changed(session,who,"TRANSLATION_EDIT",presentationHash(versionId));
    return receipt(locked.form(),session);
  }

  @Transactional
  public EditReceipt reorderFields(UUID editId,FieldOrderUpdate req){
    if(req==null||req.positions()==null)throw bad("FIELD_ORDER_REQUIRED");
    var locked=lockDraft(editId,req.expectedRevision());
    String who=actor(req.actor());
    var sec=sections.findByFormVersionIdOrderBySectionOrderAsc(locked.session().getDraftVersionId())
      .stream().filter(s->s.getSectionCode().equals(req.sectionCode()))
      .findFirst().orElseThrow(()->bad("SECTION_NOT_FOUND"));
    var selection=fields.findByFormVersionIdOrderByDisplayOrderAsc(locked.session().getDraftVersionId())
      .stream().filter(f->f.getSectionId().equals(sec.getId())).toList();
    Map<String,Integer> order=new HashMap<>();
    Set<Integer> positions=new HashSet<>();
    for(var p:req.positions()){
      if(p==null||p.fieldCode()==null||p.displayOrder()<=0||
        !positions.add(p.displayOrder())||order.putIfAbsent(p.fieldCode(),p.displayOrder())!=null)
        throw bad("INVALID_FIELD_ORDER");
    }
    if(selection.size()!=order.size()||selection.stream().anyMatch(f->!order.containsKey(f.getFieldCode())))
      throw bad("INCOMPLETE_FIELD_ORDER");
    // Positive temporary positions prevent UNIQUE(section_id,display_order) violations on swaps.
    int max=selection.stream().mapToInt(FormField::getDisplayOrder).max().orElse(0);
    int requestedMax=positions.stream().mapToInt(Integer::intValue).max().orElse(0);
    int temporaryStart=Math.max(max,requestedMax);
    if(temporaryStart>Integer.MAX_VALUE-selection.size()-1000)throw bad("ORDER_OUT_OF_RANGE");
    for(int i=0;i<selection.size();i++)
      selection.get(i).setDisplayOrder(temporaryStart+100+i);
    fields.flush();
    for(var f:selection)f.setDisplayOrder(order.get(f.getFieldCode()));
    fields.flush();
    updatePresentationHash(locked.session().getDraftVersionId());
    changed(locked.session(),who,"FIELD_REORDER",presentationHash(locked.session().getDraftVersionId()));
    return receipt(locked.form(),locked.session());
  }

  @Transactional
  public EditReceipt reorderSections(UUID editId,SectionOrderUpdate req){
    if(req==null||req.positions()==null)throw bad("SECTION_ORDER_REQUIRED");
    var locked=lockDraft(editId,req.expectedRevision());
    String who=actor(req.actor());
    var existing=sections.findByFormVersionIdOrderBySectionOrderAsc(locked.session().getDraftVersionId());
    Map<String,Integer> positions=new HashMap<>();
    Set<Integer> unique=new HashSet<>();
    for(var item:req.positions()){
      if(item==null||item.sectionCode()==null||item.sectionOrder()<=0||
        !unique.add(item.sectionOrder())||positions.putIfAbsent(item.sectionCode(),item.sectionOrder())!=null)
        throw bad("INVALID_SECTION_ORDER");
    }
    if(existing.size()!=positions.size()||existing.stream().anyMatch(s->!positions.containsKey(s.getSectionCode())))
      throw bad("INCOMPLETE_SECTION_ORDER");
    for(var section:existing)section.setSectionOrder(positions.get(section.getSectionCode()));
    sections.flush();
    updatePresentationHash(locked.session().getDraftVersionId());
    changed(locked.session(),who,"SECTION_REORDER",presentationHash(locked.session().getDraftVersionId()));
    return receipt(locked.form(),locked.session());
  }

  private String semanticFingerprint(UUID versionId){
    var ver=versions.findById(versionId).orElseThrow();
    var sec=sections.findByFormVersionIdOrderBySectionOrderAsc(versionId);
    Map<UUID,String> sectionCodes=sec.stream().collect(Collectors.toMap(
      FormSection::getId,FormSection::getSectionCode));
    var s=new StringBuilder("FIN-CATALOG-SEMANTIC-V1|")
      .append(ver.getContentMode()).append('|').append(ver.getGrain());
    sec.stream().sorted(Comparator.comparing(FormSection::getSectionCode)).forEach(x->
      s.append("|S|").append(x.getSectionCode()).append('|').append(x.getLayoutKind())
       .append('|').append(sectionCodes.get(x.getParentSectionId())));
    fields.findByFormVersionIdOrderByDisplayOrderAsc(versionId).stream()
      .sorted(Comparator.comparing(FormField::getFieldCode)).forEach(x->
      s.append("|F|").append(x.getFieldCode()).append('|')
       .append(sectionCodes.get(x.getSectionId())).append('|').append(x.getFieldType())
       .append('|').append(x.getInputMode()).append('|').append(x.getRequired())
       .append('|').append(x.getFormulaCode()).append('|').append(x.getUnitCode()));
    validationRules.findByFormVersionId(versionId).stream()
      .sorted(Comparator.comparing(FormValidationRule::getRuleCode)
        .thenComparing(FormValidationRule::getRuleVersion)).forEach(x->
      s.append("|V|").append(x.getRuleCode()).append('|').append(x.getRuleVersion())
       .append('|').append(x.getRuleKind()).append('|').append(x.getTypedParams())
       .append('|').append(x.getSeverity()));
    metricMappings.findByFormVersionId(versionId).stream()
      .sorted(Comparator.comparing(FormMetricMappingCandidate::getFieldCode)
        .thenComparing(FormMetricMappingCandidate::getMetricCode)
        .thenComparing(FormMetricMappingCandidate::getMetricVersion)).forEach(x->
      s.append("|M|").append(x.getFieldCode()).append('|').append(x.getMetricCode())
       .append('|').append(x.getMetricVersion()).append('|')
       .append(x.getMappingSha256()).append('|').append(x.getReviewState()));
    return sha(s.toString());
  }
  private String presentationHash(UUID versionId){
    var sb=new StringBuilder("FIN-CATALOG-PRESENTATION-V1");
    sections.findByFormVersionIdOrderBySectionOrderAsc(versionId).stream()
      .sorted(Comparator.comparing(FormSection::getSectionCode)).forEach(x->
      sb.append("|S|").append(x.getSectionCode()).append('|').append(x.getSectionOrder()));
    fields.findByFormVersionIdOrderByDisplayOrderAsc(versionId).stream()
      .sorted(Comparator.comparing(FormField::getFieldCode)).forEach(x->
      sb.append("|F|").append(x.getFieldCode()).append('|').append(x.getDisplayOrder()));
    formTranslations.findByFormVersionId(versionId).stream()
      .sorted(Comparator.comparing(LibFormTranslation::getLocale)).forEach(x->
      sb.append("|T|FORM|").append(x.getLocale()).append('|').append(x.getTitle())
        .append('|').append(x.getDescription()).append('|').append(x.getReviewStatus()));
    fieldTranslations.findByFormVersionId(versionId).stream()
      .sorted(Comparator.comparing(FormFieldTranslation::getSubjectType)
        .thenComparing(FormFieldTranslation::getSubjectCode)
        .thenComparing(FormFieldTranslation::getLocale)).forEach(x->
      sb.append("|T|").append(x.getSubjectType()).append('|').append(x.getSubjectCode())
        .append('|').append(x.getLocale()).append('|').append(x.getTitle())
        .append('|').append(x.getDescription()).append('|').append(x.getReviewStatus()));
    return sha(sb.toString());
  }
  private void updatePresentationHash(UUID versionId){
    var v=versions.findById(versionId).orElseThrow();
    v.setPresentationSha256(presentationHash(versionId));versions.save(v);
  }

  @Transactional
  public EditReceipt publish(UUID id,FinishRequest req){
    if(req==null||req.expectedActiveVersionId()==null)throw bad("EXPECTED_ACTIVE_VERSION_REQUIRED");
    var locked=lockDraft(id,req.expectedRevision());
    String who=actor(req.actor());
    var s=locked.session();var f=locked.form();
    if(!f.getCurrentVersionId().equals(req.expectedActiveVersionId())||
       !f.getCurrentVersionId().equals(s.getBaseVersionId()))
      throw conflict("STALE_ACTIVE_VERSION");
    String originalSemantic=semanticFingerprint(s.getBaseVersionId());
    String draftSemantic=semanticFingerprint(s.getDraftVersionId());
    if(!originalSemantic.equals(draftSemantic))throw conflict("SEMANTIC_CATALOG_CHANGE_PROHIBITED");
    var old=versions.findById(s.getBaseVersionId()).orElseThrow();
    var draft=versions.findById(s.getDraftVersionId()).orElseThrow();
    if(!old.getSchemaSha256().equals(draft.getSchemaSha256())||
       !old.getMappingSha256().equals(draft.getMappingSha256()))
      throw conflict("FINANCIAL_CONTRACT_HASH_CHANGED");
    draft.setPresentationSha256(presentationHash(draft.getId()));
    draft.setQualificationState("CANDIDATE_UNQUALIFIED");
    versions.save(draft);
    f.setCurrentVersionId(draft.getId());forms.saveAndFlush(f);
    s.setStatus("PUBLISHED");s.setPublishedAt(Instant.now());
    changed(s,who,"CATALOG_PUBLISHED",draft.getPresentationSha256());
    return receipt(f,s);
  }
  @Transactional
  public EditReceipt discard(UUID id,FinishRequest req){
    if(req==null)throw bad("DISCARD_REQUEST_REQUIRED");
    var locked=lockDraft(id,req.expectedRevision());
    String who=actor(req.actor());
    var s=locked.session();s.setStatus("DISCARDED");
    changed(s,who,"DISCARDED",presentationHash(s.getDraftVersionId()));
    return receipt(locked.form(),s);
  }
  @Transactional(readOnly=true)
  public List<HistoryView> history(UUID id){
    if(!sessions.existsById(id))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"EDIT_SESSION_NOT_FOUND");
    return events.findByEditSessionIdOrderByEditRevisionAsc(id).stream()
      .map(x->new HistoryView(x.getEditRevision(),x.getEventType(),x.getActor(),
        x.getOccurredAt(),x.getContentSha256())).toList();
  }
}
