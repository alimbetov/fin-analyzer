package kz.fin.formlib.api;

import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import kz.fin.formlib.service.PeriodResolver;
import kz.fin.formlib.service.CatalogReadCache;
import kz.fin.formlib.service.CatalogPaging;
import kz.fin.formlib.service.FormEditorialDraftLookup;
import kz.fin.formlib.service.FieldMetricLabelCandidateLookup;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.*;

/**
 * Version-aware reference endpoints. Unavailable translations are represented
 * by null + PENDING_TRANSLATION, never by a silent fallback to another language.
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class ReferenceCatalogController {
  private static final Set<String> LOCALES=Set.of("ru","kk","en","zh-Hans");
  private final CatalogReadCache cache;
  private final FormEditorialDraftLookup editorial;
  private final FieldMetricLabelCandidateLookup labelCandidates;
  private final LibCurrencyRepository currencies;
  private final LibCurrencyTranslationRepository currencyTranslations;
  private final LibPeriodTypeRepository periods;
  private final LibPeriodTypeTranslationRepository periodTranslations;
  private final FormDefinitionRepository forms;
  private final FormVersionRepository versions;
  private final LibFormTranslationRepository formTranslations;
  private final FormSectionRepository sections;
  private final FormFieldRepository fields;
  private final FormFieldTranslationRepository fieldTranslations;
  private final LibMetricDefinitionRepository metrics;
  private final LibMetricTranslationRepository metricTranslations;

  public ReferenceCatalogController(LibCurrencyRepository currencies,
      LibCurrencyTranslationRepository currencyTranslations,
      LibPeriodTypeRepository periods,LibPeriodTypeTranslationRepository periodTranslations,
      FormDefinitionRepository forms,FormVersionRepository versions,
      LibFormTranslationRepository formTranslations,FormSectionRepository sections,
      FormFieldRepository fields,FormFieldTranslationRepository fieldTranslations,
      LibMetricDefinitionRepository metrics,LibMetricTranslationRepository metricTranslations,
      CatalogReadCache cache,FormEditorialDraftLookup editorial,
      FieldMetricLabelCandidateLookup labelCandidates) {
    this.cache=cache;
    this.editorial=editorial;
    this.labelCandidates=labelCandidates;
    this.currencies=currencies;this.currencyTranslations=currencyTranslations;
    this.periods=periods;this.periodTranslations=periodTranslations;
    this.forms=forms;this.versions=versions;this.formTranslations=formTranslations;
    this.sections=sections;this.fields=fields;this.fieldTranslations=fieldTranslations;
    this.metrics=metrics;this.metricTranslations=metricTranslations;
  }
  private static String locale(String value) {
    if(!LOCALES.contains(value))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UNSUPPORTED_LOCALE");
    return value;
  }
  public record CurrencyView(String code,String isoNumeric,int minorUnits,String status,
                             String locale,String title,String description,String translationStatus) {}
  public record PeriodTypeView(String code,int sortOrder,String locale,String title,String description) {}
  public record PeriodRangeView(String type,String code,LocalDate startInclusive,LocalDate endExclusive) {}
  public record MetricView(String metricCode,String kind,String formCode,String fieldCode,
                           String unitPolicy,String qualificationState,String locale,
                           String title,String description,String translationStatus) {}
  public record FormView(String formCode,String version,String captureMode,String contentMode,
                         String qualificationState,String locale,String title,String description,
                         String translationStatus,String purpose,String purposeTranslationStatus) {
    // Maintain the original nine-argument construction contract for existing
    // preview generators and legacy integrations.
    public FormView(String formCode,String version,String captureMode,String contentMode,
        String qualificationState,String locale,String title,String description,
        String translationStatus){
      this(formCode,version,captureMode,contentMode,qualificationState,locale,
        title,description,translationStatus,null,"PENDING_TRANSLATION");
    }
  }
  public record FieldView(String fieldCode,String fieldType,String inputMode,boolean required,
                          int displayOrder,String sectionCode,String title,String translationStatus,
                          String unitPolicy) {}
  public record SectionView(String sectionCode,int sectionOrder,String layoutKind) {}
  public record FormDetail(FormView form,List<SectionView> sections,List<FieldView> fields) {}

  @GetMapping("/currencies")
  public List<CurrencyView> currencies(@RequestParam(defaultValue="ru") String locale) {
    String l=locale(locale);
    return cache.get("currencies",l,()->{
    Map<String,LibCurrencyTranslation> localized=new HashMap<>();
    currencyTranslations.findByLocale(l).forEach(t->localized.put(t.getCurrencyCode(),t));
    return currencies.findAllByOrderByCodeAsc().stream().map(c->{
      var t=localized.get(c.getCode());
      return new CurrencyView(c.getCode(),c.getIsoNumeric(),c.getMinorUnits(),
        c.getStatus(),l,t==null?null:t.getTitle(),t==null?null:t.getDescription(),
        t==null?"PENDING_TRANSLATION":t.getReviewStatus());
    }).toList();
    });
  }

  @GetMapping("/period-types")
  public List<PeriodTypeView> periodTypes(@RequestParam(defaultValue="ru") String locale) {
    String l=locale(locale);
    return cache.get("period-types",l,()->{
    Map<String,LibPeriodTypeTranslation> tr=new HashMap<>();
    periodTranslations.findByLocale(l).forEach(t->tr.put(t.getPeriodType(),t));
    return periods.findAllByOrderBySortOrderAsc().stream().map(p->{
      var t=tr.get(p.getCode());
      if(t==null)throw new IllegalStateException("MISSING_PERIOD_LOCALE");
      return new PeriodTypeView(p.getCode(),p.getSortOrder(),l,t.getTitle(),t.getDescription());
    }).toList();
    });
  }

  @GetMapping("/periods/resolve")
  public PeriodRangeView resolvePeriod(@RequestParam String type,@RequestParam String code) {
    try{
      var p=PeriodResolver.resolve(type,code);
      return new PeriodRangeView(p.periodType(),p.periodCode(),p.startInclusive(),p.endExclusive());
    }catch(IllegalArgumentException e){
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,e.getMessage());
    }
  }

  private List<MetricView> listMetrics(List<LibMetricDefinition> items,String locale) {
    String l=locale(locale);
    Map<UUID,LibMetricTranslation> tr=new HashMap<>();
    metricTranslations.findByLocale(l).forEach(t->tr.put(t.getMetricId(),t));
    var candidates=labelCandidates.metrics(l);
    return items.stream().map(m->{
      var t=tr.get(m.getId());
      var c=candidates.get(m.getMetricCode());
      if(c==null)throw new IllegalStateException("MISSING_METRIC_DRAFT_LABEL");
      boolean versioned=t!=null&&filled(t.getTitle());
      return new MetricView(m.getMetricCode(),m.getMetricKind(),m.getFormCode(),
        m.getSourceFieldCode(),m.getUnitPolicy(),m.getQualificationState(),l,
        versioned?t.getTitle():c.title(),t==null?null:t.getDescription(),
        versioned?t.getReviewStatus():c.reviewStatus());
    }).toList();
  }

  @GetMapping("/metrics")
  public List<MetricView> metrics(@RequestParam(defaultValue="ru") String locale,
                                 @RequestParam(required=false) String kind,
                                 @RequestParam(required=false) String formCode){
    String l=locale(locale);
    if(kind!=null&&!Set.of("SOURCE_FIELD","ANALYTICAL_OUTPUT").contains(kind))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_METRIC_KIND");
    if(formCode!=null&&!forms.findByFormCode(formCode).isPresent())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"FORM_NOT_FOUND");
    return cache.get("metrics",l+"|"+kind+"|"+formCode,()->{
    List<LibMetricDefinition> items=formCode==null?
        metrics.findAllByOrderByMetricCodeAsc():
        metrics.findByFormCodeOrderByMetricCodeAsc(formCode);
    if(kind!=null)items=items.stream().filter(m->kind.equals(m.getMetricKind())).toList();
    return listMetrics(items,l);
    });
  }

  @GetMapping("/metrics/{code}")
  public MetricView metric(@PathVariable String code,
                           @RequestParam(defaultValue="ru") String locale){
    var m=metrics.findByMetricCode(code)
      .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"METRIC_NOT_FOUND"));
    return cache.get("metric-detail",locale(locale)+"|"+code,
      ()->listMetrics(List.of(m),locale).getFirst());
  }

  private Map<UUID,LibFormTranslation> formTranslationMap(String locale) {
    Map<UUID,LibFormTranslation> tr=new HashMap<>();
    formTranslations.findByLocale(locale).forEach(t->tr.put(t.getFormVersionId(),t));
    return tr;
  }
  private static boolean filled(String text){return text!=null&&!text.isBlank();}
  private FormView formView(FormDefinition f,String locale,
      Map<UUID,LibFormTranslation> tr,
      Map<String,FormEditorialDraftLookup.Candidate> candidates){
    var v=versions.findById(f.getCurrentVersionId()).orElseThrow();
    var t=tr.get(v.getId());
    var c=candidates.get(f.getFormCode());
    if(c==null)throw new IllegalStateException("EDITORIAL_FORM_MISSING:"+f.getFormCode());
    boolean named=t!=null&&filled(t.getTitle());
    boolean described=t!=null&&filled(t.getDescription());
    boolean purposeful=t!=null&&filled(t.getPurpose());
    // Never modify the immutable published form version for display.
    // Every generated fallback carries AUTO_DRAFT_REVIEW_REQUIRED.
    return new FormView(f.getFormCode(),v.getVersionLabel(),f.getLegacyCaptureMode(),
      v.getContentMode(),v.getQualificationState(),locale,
      named?t.getTitle():c.title(),described?t.getDescription():c.description(),
      named&&described?t.getReviewStatus():c.reviewStatus(),
      purposeful?t.getPurpose():c.purpose(),
      purposeful?t.getPurposeReviewStatus():c.reviewStatus());
  }

  @GetMapping("/forms")
  public List<FormView> forms(@RequestParam(defaultValue="ru") String locale){
    String l=locale(locale);
    return cache.get("forms",l,()->{
      var tr=formTranslationMap(l);
      var candidates=editorial.byLocale(l);
      return forms.findAllByOrderByFormCodeAsc().stream()
        .map(f->formView(f,l,tr,candidates)).toList();
    });
  }

  @GetMapping("/forms/{code}")
  public FormDetail form(@PathVariable String code,
                         @RequestParam(defaultValue="ru") String locale){
    String l=locale(locale);
    return cache.get("form-detail",l+"|"+code,()->{
    var f=forms.findByFormCode(code)
      .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"FORM_NOT_FOUND"));
    var view=formView(f,l,formTranslationMap(l),editorial.byLocale(l));
    var orderedSections=sections.findByFormVersionIdOrderBySectionOrderAsc(f.getCurrentVersionId());
    var sectionById=new HashMap<UUID,FormSection>();
    orderedSections.forEach(s->sectionById.put(s.getId(),s));
    var translated=new HashMap<String,FormFieldTranslation>();
    fieldTranslations.findAll().stream().filter(t->
       t.getFormVersionId().equals(f.getCurrentVersionId()) &&
       t.getLocale().equals(l) && t.getSubjectType().equals("FIELD"))
       .forEach(t->translated.put(t.getSubjectCode(),t));
    var unitPolicies=new HashMap<String,String>();
    metrics.findByFormCodeOrderByMetricCodeAsc(code)
      .forEach(m->unitPolicies.put(m.getSourceFieldCode(),m.getUnitPolicy()));
    var labelDrafts=labelCandidates.fields(code,l);
    var fieldViews=fields.findByFormVersionIdOrderByDisplayOrderAsc(f.getCurrentVersionId())
      .stream()
      .sorted(Comparator.comparingInt((FormField field)->
        sectionById.get(field.getSectionId()).getSectionOrder())
        .thenComparingInt(FormField::getDisplayOrder).thenComparing(FormField::getFieldCode))
      .map(field->{
        var t=translated.get(field.getFieldCode());
        var c=labelDrafts.get(field.getFieldCode());
        if(c==null)throw new IllegalStateException("FIELD_DRAFT_LABEL_MISSING");
        boolean versioned=t!=null&&filled(t.getTitle())
          &&!"PENDING_TRANSLATION".equals(t.getReviewStatus());
        var sec=sectionById.get(field.getSectionId());
        return new FieldView(field.getFieldCode(),field.getFieldType(),
          field.getInputMode(),Boolean.TRUE.equals(field.getRequired()),
          field.getDisplayOrder(),sec.getSectionCode(),
          versioned?t.getTitle():c.title(),
          versioned?t.getReviewStatus():c.reviewStatus(),
          unitPolicies.getOrDefault(field.getFieldCode(),"PENDING_UNIT_REVIEW"));
      }).toList();
    var sectionViews=orderedSections.stream()
      .map(s->new SectionView(s.getSectionCode(),s.getSectionOrder(),s.getLayoutKind())).toList();
    return new FormDetail(view,sectionViews,fieldViews);
    });
  }

  @GetMapping("/forms/page")
  public CatalogPaging.Page<FormView> formsPage(
      @RequestParam(defaultValue="ru") String locale,
      @RequestParam(defaultValue="0") int page,
      @RequestParam(defaultValue="24") int size,
      @RequestParam(required=false) String q,
      @RequestParam(defaultValue="CARDS") String viewMode,
      @RequestParam(defaultValue="CODE") String sort){
    String needle=CatalogPaging.normalizedSearch(q);
    String order=CatalogPaging.sortMode(sort);
    CatalogPaging.viewMode(viewMode);
    var filtered=new ArrayList<>(forms(locale).stream().filter(f->
      CatalogPaging.matches(needle,f.formCode(),f.title(),f.purpose(),f.description())).toList());
    if("TITLE".equals(order))filtered.sort(
      Comparator.comparing((FormView f)->f.title()==null?"":f.title(),
        String.CASE_INSENSITIVE_ORDER).thenComparing(FormView::formCode));
    else filtered.sort(Comparator.comparing(FormView::formCode));
    return CatalogPaging.page(filtered,page,size,viewMode,order);
  }

  @GetMapping("/metrics/page")
  public CatalogPaging.Page<MetricView> metricsPage(
      @RequestParam(defaultValue="ru") String locale,
      @RequestParam(required=false) String kind,
      @RequestParam(required=false) String formCode,
      @RequestParam(defaultValue="0") int page,
      @RequestParam(defaultValue="24") int size,
      @RequestParam(required=false) String q,
      @RequestParam(defaultValue="LIST") String viewMode,
      @RequestParam(defaultValue="CODE") String sort){
    String needle=CatalogPaging.normalizedSearch(q);
    String order=CatalogPaging.sortMode(sort);
    CatalogPaging.viewMode(viewMode);
    var filtered=new ArrayList<>(metrics(locale,kind,formCode).stream().filter(m->
      CatalogPaging.matches(needle,m.metricCode(),m.formCode(),m.title(),m.description())).toList());
    if("TITLE".equals(order))filtered.sort(
      Comparator.comparing((MetricView m)->m.title()==null?"":m.title(),
        String.CASE_INSENSITIVE_ORDER).thenComparing(MetricView::metricCode));
    else filtered.sort(Comparator.comparing(MetricView::metricCode));
    return CatalogPaging.page(filtered,page,size,viewMode,order);
  }
}
