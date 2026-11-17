package kz.fin.formlib.support;

import kz.fin.formlib.api.ReferenceCatalogController;
import kz.fin.formlib.service.PeriodResolver;

import java.util.*;

/**
 * Test-only structural template generator.
 * No repository, transaction, synthetic financial values or fin_intake writes.
 * The output is explicitly MOCK_ONLY and is not a qualified reporting form.
 */
public final class MockTemplateGenerator {
  private MockTemplateGenerator(){}

  public record Scenario(String name,String locale,String periodType,
                         List<String> periodCodes,String timeAxis) {}
  public record Axis(String code,String label,int displayOrder) {}
  public record Cell(String rowKey,String columnKey,String fieldCode,
                     String periodCode,String state,String value,boolean derived) {}
  public record MockTemplate(String fixtureType,String scenario,String formCode,
      String formVersion,String captureMode,String contentMode,String qualificationState,
      String locale,String timeAxis,boolean inputPreviewSupported,
      List<ReferenceCatalogController.SectionView> sections,
      List<ReferenceCatalogController.FieldView> fields,
      List<PeriodResolver.Range> periods,List<Axis> rowHeaders,
      List<Axis> columnHeaders,List<Cell> cells,List<String> warnings) {}

  public static MockTemplate generate(ReferenceCatalogController.FormDetail form,Scenario scenario) {
    Objects.requireNonNull(form,"form");
    Objects.requireNonNull(form.form(),"form.form");
    Objects.requireNonNull(scenario,"scenario");
    if(scenario.name()==null||scenario.name().isBlank())
      throw new IllegalArgumentException("SCENARIO_REQUIRED");
    if(!Set.of("ru","kk","en","zh-Hans").contains(scenario.locale())
       ||!scenario.locale().equals(form.form().locale()))
      throw new IllegalArgumentException("FORM_LOCALE_MISMATCH");
    if(!Set.of("ROWS","COLUMNS").contains(scenario.timeAxis()))
      throw new IllegalArgumentException("INVALID_TIME_AXIS");
    if(scenario.periodCodes()==null||scenario.periodCodes().isEmpty()||
       scenario.periodCodes().size()>60)
      throw new IllegalArgumentException("INVALID_PERIOD_COUNT");
    var ranges=new ArrayList<PeriodResolver.Range>();
    var periodKeys=new HashSet<String>();
    for(String code:scenario.periodCodes()){
      var range=PeriodResolver.resolve(scenario.periodType(),code);
      if(!periodKeys.add(range.periodCode()))
        throw new IllegalArgumentException("DUPLICATE_PERIOD");
      ranges.add(range);
    }
    if(form.sections()==null||form.fields()==null)
      throw new IllegalArgumentException("CATALOG_STRUCTURE_REQUIRED");
    var sections=form.sections();
    var fields=form.fields();
    if(fields.size()>40)throw new IllegalArgumentException("MAX_MOCK_FIELDS_40");
    var sectionCodes=new HashSet<String>();
    for(var section:sections){
      if(section==null||section.sectionCode()==null||section.sectionCode().isBlank()||
         section.sectionOrder()<=0||!sectionCodes.add(section.sectionCode()))
        throw new IllegalArgumentException("INVALID_SECTION");
    }
    var fieldCodes=new HashSet<String>();
    var uniquePositions=new HashSet<String>();
    for(var field:fields){
      if(field==null||field.fieldCode()==null||field.fieldCode().isBlank()||
         !fieldCodes.add(field.fieldCode())||field.displayOrder()<=0||
         !sectionCodes.contains(field.sectionCode())||
         !uniquePositions.add(field.sectionCode()+"|"+field.displayOrder()))
        throw new IllegalArgumentException("INVALID_FIELD_BINDING");
    }
    var sortedSections=sections.stream().sorted(Comparator
      .comparingInt(ReferenceCatalogController.SectionView::sectionOrder)
      .thenComparing(ReferenceCatalogController.SectionView::sectionCode)).toList();
    Map<String,Integer> sectionOrder=new HashMap<>();
    for(var section:sortedSections)sectionOrder.put(section.sectionCode(),section.sectionOrder());
    var sortedFields=fields.stream().sorted(Comparator
      .comparingInt((ReferenceCatalogController.FieldView f)->sectionOrder.get(f.sectionCode()))
      .thenComparingInt(ReferenceCatalogController.FieldView::displayOrder)
      .thenComparing(ReferenceCatalogController.FieldView::fieldCode)).toList();
    var warnings=new ArrayList<String>();
    if(!"CANDIDATE_UNQUALIFIED".equals(form.form().qualificationState()))
      warnings.add("QUALIFICATION_NOT_VERIFIED");
    else warnings.add("CATALOG_UNQUALIFIED");
    for(var field:sortedFields)if("PENDING_TRANSLATION".equals(field.translationStatus()))
      warnings.add("MISSING_TRANSLATION:"+field.fieldCode());
    boolean typed="TYPED_DRAFT".equals(form.form().captureMode())
      &&!"EVIDENCE".equals(form.form().contentMode());
    if(!typed)warnings.add("DATA_ENTRY_NOT_SUPPORTED_FOR_CAPTURE_MODE");
    var rowHeaders=new ArrayList<Axis>();
    var columnHeaders=new ArrayList<Axis>();
    var cells=new ArrayList<Cell>();
    if("SINGLE_RECORD".equals(form.form().contentMode())){
      if("COLUMNS".equals(scenario.timeAxis())){
        for(var f:sortedFields)rowHeaders.add(new Axis(f.fieldCode(),f.title(),f.displayOrder()));
        for(int i=0;i<ranges.size();i++)
          columnHeaders.add(new Axis(ranges.get(i).periodCode(),ranges.get(i).periodCode(),(i+1)*10));
        for(var f:sortedFields)for(var p:ranges)
          cells.add(new Cell(f.fieldCode(),p.periodCode(),f.fieldCode(),p.periodCode(),
            "MISSING",null,"DERIVED".equals(f.inputMode())));
      }else{
        for(int i=0;i<ranges.size();i++)
          rowHeaders.add(new Axis(ranges.get(i).periodCode(),ranges.get(i).periodCode(),(i+1)*10));
        for(var f:sortedFields)columnHeaders.add(new Axis(f.fieldCode(),f.title(),f.displayOrder()));
        for(var p:ranges)for(var f:sortedFields)
          cells.add(new Cell(p.periodCode(),f.fieldCode(),f.fieldCode(),p.periodCode(),
            "MISSING",null,"DERIVED".equals(f.inputMode())));
      }
    }else if("ROWSET".equals(form.form().contentMode())){
      // Tabular transaction forms have field columns but no fabricated business rows.
      for(var f:sortedFields)columnHeaders.add(new Axis(f.fieldCode(),f.title(),f.displayOrder()));
      warnings.add("ROWSET_NO_SYNTHETIC_TRANSACTIONS");
    }else if("EVIDENCE".equals(form.form().contentMode())){
      warnings.add("EVIDENCE_UPLOAD_NOT_IMPLEMENTED");
    }else throw new IllegalArgumentException("UNKNOWN_CONTENT_MODE");
    return new MockTemplate("MOCK_ONLY_NOT_FACT",scenario.name(),form.form().formCode(),
      form.form().version(),form.form().captureMode(),form.form().contentMode(),
      form.form().qualificationState(),scenario.locale(),scenario.timeAxis(),typed,
      List.copyOf(sortedSections),List.copyOf(sortedFields),List.copyOf(ranges),
      List.copyOf(rowHeaders),List.copyOf(columnHeaders),List.copyOf(cells),
      List.copyOf(warnings));
  }
}
