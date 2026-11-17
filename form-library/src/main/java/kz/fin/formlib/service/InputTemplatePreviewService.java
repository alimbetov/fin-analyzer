package kz.fin.formlib.service;

import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * R8.C1: read-only, catalog-backed, Excel-like input template preview.
 *
 * Styles, coordinates and focus colors are presentation metadata only:
 * they never identify a financial observation or create fin_intake records.
 * Editable cells require a system metric binding and a manual source field.
 */
@Service
public class InputTemplatePreviewService {
  public record RowNumberColumn(int widthPx,int minWidthPx,int maxWidthPx,
      String horizontalAlign,String verticalAlign,boolean resizable,
      String pinned,int fontSizePx) {}
  public record HighlightPolicy(String active,String editable,String requiredMissing,
      String invalid,String changed,String readOnly,String selectedRange) {}
  /**
   * Renderer scroll contract, NOT a server-side window of financial observations.
   * Two leading columns are Excel row numbers (36px) and form labels/periods.
   * frozenHeaderRows=2 means column letters + semantic title/period row.
   */
  public record ScrollPolicy(String overflowX,String overflowY,String viewportWidth,
      int maxViewportHeightPx,int frozenHeaderRows,int frozenLeadingColumns,
      int columnLettersHeightPx,int columnTitlesHeightPx,boolean overscrollContain,
      boolean keepActiveCellVisible) {}
  public record PresentationProfile(String styleCode,int styleVersion,
      boolean showCoordinates,boolean showGridlines,String banding,
      boolean freezeHeader,boolean freezeFirstColumn,boolean showMetricCode,
      String density,int indicatorColumnWidthPx,int valueColumnWidthPx,
      RowNumberColumn rowNumberColumn,HighlightPolicy highlights,
      ScrollPolicy scrolling) {}
  public record SectionDescriptor(String sectionCode,int sectionOrder,String layoutKind,
      String title,String translationStatus) {}
  public record FieldDescriptor(String fieldCode,String metricCode,String sectionCode,
      String title,String translationStatus,String fieldType,String inputMode,
      boolean required,String unitCode,int displayOrder) {}
  public record ColumnHeader(String excelColumn,String code,String label,
      boolean metric,boolean period) {}
  public record RowHeader(int excelRow,String code,String label,String sectionCode,
      boolean banded) {}
  public record CellDescriptor(String excelAddress,int excelRow,String excelColumn,
      String fieldCode,String metricCode,String periodCode,String state,String value,
      boolean editable,boolean required,String styleRole,boolean banded) {}
  public record TemplatePreview(boolean previewOnly,boolean persisted,
      String formCode,UUID formVersionId,String formVersionLabel,
      String qualificationState,String captureMode,String contentMode,String locale,
      String title,String titleTranslationStatus,String periodType,
      List<PeriodResolver.Range> periods,
      List<PeriodBatchPlanner.CalendarPeriod> periodCalendar,
      PresentationProfile presentation,
      List<SectionDescriptor> sections,List<FieldDescriptor> fields,
      List<ColumnHeader> columnHeaders,List<RowHeader> rowHeaders,
      List<CellDescriptor> cells,List<String> warnings) {}

  private static final Set<String> LOCALES=Set.of("ru","kk","en","zh-Hans");
  private static final Set<String> SINGLE_STYLES=Set.of("EXCEL_GRID","TRANSPOSED_GRID","SECTION_FORM");
  private static final RowNumberColumn ROW_NUMBERS=
      new RowNumberColumn(36,36,36,"CENTER","CENTER",false,"LEFT",12);
  private static final HighlightPolicy HIGHLIGHTS=new HighlightPolicy(
      "ACTIVE_BLUE_BORDER_2PX","EDITABLE_NEUTRAL","REQUIRED_MISSING_AMBER",
      "INVALID_RED_BORDER","CHANGED_DOT","READ_ONLY_MUTED","SELECTION_PALE_BLUE");

  private final FormDefinitionRepository definitions;
  private final FormVersionRepository versions;
  private final FormSectionRepository sections;
  private final FormFieldRepository fields;
  private final LibFormTranslationRepository formTranslations;
  private final FormFieldTranslationRepository fieldTranslations;
  private final LibMetricDefinitionRepository metrics;
  private final FieldMetricLabelCandidateLookup labelCandidates;
  private final FormEditorialDraftLookup formEditorial;

  public InputTemplatePreviewService(FormDefinitionRepository definitions,
      FormVersionRepository versions,FormSectionRepository sections,
      FormFieldRepository fields,LibFormTranslationRepository formTranslations,
      FormFieldTranslationRepository fieldTranslations,
      LibMetricDefinitionRepository metrics,
      FieldMetricLabelCandidateLookup labelCandidates,
      FormEditorialDraftLookup formEditorial) {
    this.definitions=definitions;
    this.versions=versions;
    this.sections=sections;
    this.fields=fields;
    this.formTranslations=formTranslations;
    this.fieldTranslations=fieldTranslations;
    this.metrics=metrics;
    this.labelCandidates=labelCandidates;
    this.formEditorial=formEditorial;
  }

  private static ResponseStatusException invalid(String code){
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }

  /** Column 1 = A, 26 = Z, 27 = AA. Never use addresses as financial keys. */
  public static String excelColumn(int oneBased) {
    if(oneBased<1||oneBased>16_384)throw new IllegalArgumentException("EXCEL_COLUMN_RANGE");
    var out=new StringBuilder();
    int n=oneBased;
    while(n>0){n--;out.insert(0,(char)('A'+n%26));n/=26;}
    return out.toString();
  }

  public static PresentationProfile defaultProfile(String styleCode){
    if(!SINGLE_STYLES.contains(styleCode)&&
       !"ROWSET_TABLE".equals(styleCode)&&!"EVIDENCE_INFO".equals(styleCode))
      throw invalid("UNSUPPORTED_TEMPLATE_STYLE");
    boolean grid="EXCEL_GRID".equals(styleCode)
        ||"TRANSPOSED_GRID".equals(styleCode)||"ROWSET_TABLE".equals(styleCode);
    // A single bounded viewport scrolls on both axes; the renderer must keep
    // column letters/title headings and the 36px gutter visible. SECTION_FORM
    // and EVIDENCE_INFO are vertically scrollable card-style presentations.
    ScrollPolicy scrolling=new ScrollPolicy(grid?"AUTO":"HIDDEN","AUTO","100%",
      520,grid?2:1,grid?2:0,28,36,true,true);
    return new PresentationProfile(styleCode,2,true,true,"ROWS",true,grid,false,
      "COMPACT",260,140,ROW_NUMBERS,HIGHLIGHTS,scrolling);
  }

  private static String indicatorHeader(String locale){
    return switch(locale){
      case "ru" -> "Показатель";
      case "kk" -> "Көрсеткіш";
      case "en" -> "Indicator";
      case "zh-Hans" -> "指标";
      default -> throw invalid("UNSUPPORTED_LOCALE");
    };
  }

  private static String codeOrNull(FormFieldTranslation translation){
    if(translation==null||"PENDING_TRANSLATION".equals(translation.getReviewStatus())||
       translation.getTitle()==null||translation.getTitle().isBlank())return null;
    return translation.getTitle();
  }

  private static String translationStatus(FormFieldTranslation translation){
    return translation==null?"PENDING_TRANSLATION":translation.getReviewStatus();
  }

  private static List<PeriodResolver.Range> canonicalPeriods(String type,List<String> periodCodes){
    if(periodCodes==null||periodCodes.isEmpty()||periodCodes.size()>60)
      throw invalid("PERIOD_COUNT_INVALID");
    var result=new ArrayList<PeriodResolver.Range>();
    var used=new HashSet<String>();
    for(String code:periodCodes){
      PeriodResolver.Range range;
      try{range=PeriodResolver.resolve(type,code);}
      catch(IllegalArgumentException e){throw invalid("INVALID_PERIOD");}
      if(!used.add(range.periodCode()))throw invalid("DUPLICATE_PERIOD");
      result.add(range);
    }
    return List.copyOf(result);
  }

  private static CellDescriptor missing(int excelRow,int excelColumn,
      FieldDescriptor field,String periodCode,boolean inputAllowed,boolean banded){
    boolean editable=inputAllowed&&"MANUAL".equals(field.inputMode())
        &&field.metricCode()!=null;
    String role=editable?"EDITABLE":"READ_ONLY";
    return new CellDescriptor(excelColumn(excelColumn)+excelRow,excelRow,
        excelColumn(excelColumn),field.fieldCode(),field.metricCode(),periodCode,
        "MISSING",null,editable,field.required(),role,banded);
  }

  @Transactional(readOnly=true)
  public TemplatePreview preview(String formCode,String locale,String periodType,
      List<String> periodCodes,String requestedStyle){
    if(formCode==null||formCode.isBlank())throw invalid("FORM_CODE_REQUIRED");
    if(!LOCALES.contains(locale))throw invalid("UNSUPPORTED_LOCALE");
    var definition=definitions.findByFormCode(formCode).orElseThrow(
      ()->new ResponseStatusException(HttpStatus.NOT_FOUND,"FORM_NOT_FOUND"));
    var version=versions.findById(definition.getCurrentVersionId()).orElseThrow();
    String contentMode=version.getContentMode();
    String style=requestedStyle==null||requestedStyle.isBlank()
      ?switch(contentMode){
        case "SINGLE_RECORD"->"EXCEL_GRID";
        case "ROWSET"->"ROWSET_TABLE";
        case "EVIDENCE"->"EVIDENCE_INFO";
        default->throw invalid("UNSUPPORTED_CONTENT_MODE");
      }:requestedStyle;
    if("SINGLE_RECORD".equals(contentMode)){
      if(!SINGLE_STYLES.contains(style))throw invalid("STYLE_NOT_COMPATIBLE_WITH_FORM");
    }else if("ROWSET".equals(contentMode)){
      if(!"ROWSET_TABLE".equals(style))throw invalid("STYLE_NOT_COMPATIBLE_WITH_FORM");
    }else if("EVIDENCE".equals(contentMode)){
      if(!"EVIDENCE_INFO".equals(style))throw invalid("STYLE_NOT_COMPATIBLE_WITH_FORM");
    }else throw invalid("UNSUPPORTED_CONTENT_MODE");
    var periods=canonicalPeriods(periodType,periodCodes);
    if("SECTION_FORM".equals(style)&&periods.size()!=1)
      throw invalid("SECTION_FORM_REQUIRES_ONE_PERIOD");
    var profile=defaultProfile(style);
    var translatedForm=formTranslations.findByFormVersionId(version.getId()).stream()
      .filter(t->locale.equals(t.getLocale())).findFirst();
    String title=translatedForm.map(LibFormTranslation::getTitle).orElse(null);
    String titleStatus=translatedForm.map(LibFormTranslation::getReviewStatus)
      .orElse("PENDING_TRANSLATION");
    if(title==null||title.isBlank()||"PENDING_TRANSLATION".equals(titleStatus)){
      var candidate=formEditorial.byLocale(locale).get(formCode);
      if(candidate==null)throw new IllegalStateException("MISSING_FORM_DRAFT_LABEL");
      title=candidate.title();titleStatus=candidate.reviewStatus();
    }

    Map<String,FormFieldTranslation> translations=new HashMap<>();
    for(var translation:fieldTranslations.findByFormVersionId(version.getId())){
      if(locale.equals(translation.getLocale()))
        translations.put(translation.getSubjectType()+"|"+translation.getSubjectCode(),translation);
    }
    var sourceMetrics=new HashMap<String,String>();
    for(var metric:metrics.findByFormCodeOrderByMetricCodeAsc(formCode)){
      if(!"SOURCE_FIELD".equals(metric.getMetricKind()))continue;
      String existing=sourceMetrics.putIfAbsent(metric.getSourceFieldCode(),metric.getMetricCode());
      if(existing!=null)throw new IllegalStateException("DUPLICATE_SOURCE_METRIC");
    }

    var orderedSections=sections.findByFormVersionIdOrderBySectionOrderAsc(version.getId())
      .stream().sorted(Comparator.comparingInt(FormSection::getSectionOrder)
        .thenComparing(FormSection::getSectionCode)).toList();
    var sectionCodes=new HashMap<UUID,String>();
    var sectionRanks=new HashMap<UUID,Integer>();
    var outputSections=new ArrayList<SectionDescriptor>();
    var usedSections=new HashSet<String>();
    for(var section:orderedSections){
      if(!usedSections.add(section.getSectionCode()))throw new IllegalStateException("DUPLICATE_SECTION");
      sectionCodes.put(section.getId(),section.getSectionCode());
      sectionRanks.put(section.getId(),section.getSectionOrder());
      var translation=translations.get("SECTION|"+section.getSectionCode());
      outputSections.add(new SectionDescriptor(section.getSectionCode(),
        section.getSectionOrder(),section.getLayoutKind(),codeOrNull(translation),
        translationStatus(translation)));
    }
    var orderedFields=fields.findByFormVersionIdOrderByDisplayOrderAsc(version.getId())
      .stream().sorted(Comparator.comparingInt((FormField f)->{
        Integer rank=sectionRanks.get(f.getSectionId());
        if(rank==null)throw new IllegalStateException("UNKNOWN_FIELD_SECTION");
        return rank;
      }).thenComparingInt(FormField::getDisplayOrder).thenComparing(FormField::getFieldCode)).toList();
    if(orderedFields.size()>40)throw invalid("TOO_MANY_TEMPLATE_FIELDS");
    var draftLabels=labelCandidates.fields(formCode,locale);
    var outputFields=new ArrayList<FieldDescriptor>();
    var fieldCodes=new HashSet<String>();
    var fieldPositions=new HashSet<String>();
    var warnings=new ArrayList<String>();
    if(!"QUALIFIED".equals(version.getQualificationState()))
      warnings.add("UNQUALIFIED_TEMPLATE");
    // F02 has a populated, locally writable pilot despite the legacy
    // SCHEMA_NOT_APPROVED tag. Match its exact structure, not versionLabel:
    // presentation-only catalog revisions change the label but not field semantics.
    boolean f02Pilot="F02".equals(definition.getFormCode())
      &&"SINGLE_RECORD".equals(contentMode)
      &&orderedFields.size()==3
      &&orderedFields.stream().map(FormField::getFieldCode).collect(java.util.stream.Collectors.toSet())
          .equals(Set.of("netRevenue","cogs","operatingExpenses"))
      &&orderedFields.stream().allMatch(f->"MONEY".equals(f.getFieldType())
          &&"MANUAL".equals(f.getInputMode()));
    boolean inputAllowed=("TYPED_DRAFT".equals(definition.getLegacyCaptureMode())
      &&!"EVIDENCE".equals(contentMode))||f02Pilot;
    if(f02Pilot)warnings.add("F02_PILOT_UNQUALIFIED");
    if(!inputAllowed)warnings.add("INPUT_NOT_ALLOWED_FOR_CAPTURE_MODE");
    for(var field:orderedFields){
      String section=sectionCodes.get(field.getSectionId());
      if(section==null||!fieldCodes.add(field.getFieldCode())||
         !fieldPositions.add(section+"|"+field.getDisplayOrder()))
        throw new IllegalStateException("CATALOG_FIELD_BINDING_DRIFT");
      var translation=translations.get("FIELD|"+field.getFieldCode());
      var candidate=draftLabels.get(field.getFieldCode());
      if(candidate==null)throw new IllegalStateException("MISSING_FIELD_DRAFT_LABEL");
      String versionedTitle=codeOrNull(translation);
      if(versionedTitle==null)
        warnings.add("FIELD_TRANSLATION_DRAFT:"+field.getFieldCode());
      String metricCode=sourceMetrics.get(field.getFieldCode());
      if(metricCode==null&&"MANUAL".equals(field.getInputMode())
          &&!"EVIDENCE".equals(contentMode))
        warnings.add("SYSTEM_METRIC_BINDING_MISSING:"+field.getFieldCode());
      outputFields.add(new FieldDescriptor(field.getFieldCode(),metricCode,section,
        versionedTitle==null?candidate.title():versionedTitle,
        versionedTitle==null?candidate.reviewStatus():translationStatus(translation),
        field.getFieldType(),field.getInputMode(),field.getRequired(),
        field.getUnitCode(),field.getDisplayOrder()));
    }
    if("EVIDENCE".equals(contentMode))warnings.add("EVIDENCE_UPLOAD_NOT_IMPLEMENTED");
    var columns=new ArrayList<ColumnHeader>();
    var rows=new ArrayList<RowHeader>();
    var cells=new ArrayList<CellDescriptor>();
    if("SINGLE_RECORD".equals(contentMode)){
      if("EXCEL_GRID".equals(style)||"SECTION_FORM".equals(style)){
        columns.add(new ColumnHeader("A","__LABEL__",indicatorHeader(locale),false,false));
        for(int p=0;p<periods.size();p++)
          columns.add(new ColumnHeader(excelColumn(p+2),periods.get(p).periodCode(),
            periods.get(p).periodCode(),false,true));
        for(int i=0;i<outputFields.size();i++){
          var f=outputFields.get(i);
          int row=i+2;
          boolean banded=i%2==1;
          rows.add(new RowHeader(row,f.fieldCode(),f.title(),f.sectionCode(),banded));
          for(int p=0;p<periods.size();p++)
            cells.add(missing(row,p+2,f,periods.get(p).periodCode(),inputAllowed,banded));
        }
      }else{
        columns.add(new ColumnHeader("A","__PERIOD__",
            indicatorHeader(locale),false,false));
        for(int i=0;i<outputFields.size();i++){
          var f=outputFields.get(i);
          columns.add(new ColumnHeader(excelColumn(i+2),f.metricCode(),
            f.title(),true,false));
        }
        for(int p=0;p<periods.size();p++){
          int row=p+2;
          boolean banded=p%2==1;
          rows.add(new RowHeader(row,periods.get(p).periodCode(),
            periods.get(p).periodCode(),null,banded));
          for(int i=0;i<outputFields.size();i++)
            cells.add(missing(row,i+2,outputFields.get(i),
              periods.get(p).periodCode(),inputAllowed,banded));
        }
      }
    }else if("ROWSET".equals(contentMode)){
      columns.add(new ColumnHeader("A","__ROW_ID__","Row",false,false));
      for(int i=0;i<outputFields.size();i++){
        var f=outputFields.get(i);
        columns.add(new ColumnHeader(excelColumn(i+2),f.metricCode(),f.title(),true,false));
      }
      warnings.add("ROWSET_NO_SYNTHETIC_RECORDS");
    }
    return new TemplatePreview(true,false,definition.getFormCode(),version.getId(),
      version.getVersionLabel(),version.getQualificationState(),
      definition.getLegacyCaptureMode(),contentMode,locale,title,titleStatus,
      periods.getFirst().periodType(),periods,
      periods.stream().map(PeriodBatchPlanner::decorate).toList(),
      profile,List.copyOf(outputSections),
      List.copyOf(outputFields),List.copyOf(columns),List.copyOf(rows),
      List.copyOf(cells),List.copyOf(warnings));
  }
}
