package kz.fin.formlib.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FIN-KPI-R0: immutable, read-only educational navigation sourced from the
 * existing candidate catalogs. It never qualifies a methodology or writes
 * finance data. Its content is versioned with the shipped application.
 */
@Service
public class IndicatorNavigatorCatalog {
  private static final Set<String> LOCALES=Set.of("ru","kk","en","zh-Hans");

  public record FormLink(String formCode,String role,int priority) {}
  public record Model(String code,String pack,String title,String titleReviewStatus,
      String qualificationStatus,List<String> inputFormCodes,List<String> outputMetricCodes) {}
  public record Goal(String code,String title,String translationStatus,String audience,
      String deliverable,String version,String qualificationStatus,
      List<Model> models,List<FormLink> forms) {}
  /** Editorial interpretation only; NOT an approved metric formula or an execution grant. */
  public record IndicatorHelp(String meaning,String helpsDecision,String limitations,
      String reviewStatus,String source) {}
  public record Indicator(String code,String metricKind,String qualificationStatus,
      List<Model> models,List<String> inputFormCodes,List<String> goalCodes,
      IndicatorHelp explanation) {}
  public record FormUsage(String formCode,List<String> goalCodes,List<String> modelCodes,
      String qualificationStatus) {}

  private record GoalSource(String code,String titleRu,String titleEn,String audience,
      String deliverable,String version,String qualification,List<String> modelCodes) {}
  private final Map<String,GoalSource> goals;
  private final Map<String,Model> models;
  private final Map<String,List<FormLink>> goalForms;
  private final Map<String,Indicator> indicators;
  private final Map<String,IndicatorHelp> ruExplanations;
  private final Map<String,FormUsage> formUsages;

  public IndicatorNavigatorCatalog(){
    var knownForms=new LinkedHashSet<String>();
    for(var line:rows("catalog/form-compatibility.tsv",8)){
      if(!knownForms.add(line[0]))throw invalid("DUPLICATE_FORM:"+line[0]);
    }
    if(knownForms.size()!=166)throw invalid("FORM_COUNT_DRIFT:"+knownForms.size());

    var m=new LinkedHashMap<String,Model>();
    for(var line:rows("catalog/analytics-model-catalog.tsv",6)){
      var inputs=tokens(line[3]);
      var outputs=tokens(line[4]);
      for(var formCode:inputs)if(!knownForms.contains(formCode))
        throw invalid("UNKNOWN_MODEL_FORM:"+formCode);
      if(inputs.isEmpty()||outputs.isEmpty())throw invalid("EMPTY_MODEL:"+line[0]);
      var model=new Model(line[0],line[1],line[2],"SOURCE_TITLE_EN_UNREVIEWED",
          line[5],inputs,outputs);
      if(m.putIfAbsent(line[0],model)!=null)throw invalid("DUPLICATE_MODEL:"+line[0]);
    }
    if(m.size()!=42)throw invalid("MODEL_COUNT_DRIFT:"+m.size());

    var g=new LinkedHashMap<String,GoalSource>();
    for(var line:rows("catalog/use-case-catalog-r10.tsv",8)){
      var linkedModels=tokens(line[7]);
      if(linkedModels.isEmpty()||linkedModels.stream().anyMatch(x->!m.containsKey(x)))
        throw invalid("UNKNOWN_GOAL_MODEL:"+line[0]);
      var goal=new GoalSource(line[0],line[1],line[2],line[3],line[4],line[5],
          line[6],linkedModels);
      if(g.putIfAbsent(line[0],goal)!=null)throw invalid("DUPLICATE_GOAL:"+line[0]);
    }
    if(g.size()!=7)throw invalid("GOAL_COUNT_DRIFT:"+g.size());

    var gf=new LinkedHashMap<String,List<FormLink>>();
    for(String code:g.keySet())gf.put(code,new ArrayList<>());
    int mappings=0;
    for(var line:rows("catalog/use-case-form-map-r10.tsv",4)){
      if(!g.containsKey(line[0])||!knownForms.contains(line[1]))
        throw invalid("UNKNOWN_GOAL_FORM:"+Arrays.toString(line));
      if(!Set.of("CORE","SUPPORTING","EVIDENCE").contains(line[2]))
        throw invalid("UNKNOWN_FORM_ROLE:"+line[2]);
      final int priority;
      try{priority=Integer.parseInt(line[3]);}
      catch(NumberFormatException ex){throw invalid("INVALID_FORM_PRIORITY:"+line[3]);}
      if(priority<1)throw invalid("INVALID_FORM_PRIORITY:"+priority);
      gf.get(line[0]).add(new FormLink(line[1],line[2],priority));
      mappings++;
    }
    if(mappings!=101)throw invalid("GOAL_FORM_MAP_DRIFT:"+mappings);
    for(var entry:gf.entrySet()){
      var links=entry.getValue();
      if(links.isEmpty())throw invalid("GOAL_WITHOUT_FORMS:"+entry.getKey());
      if(links.stream().map(FormLink::formCode).distinct().count()!=links.size())
        throw invalid("DUPLICATE_GOAL_FORM:"+entry.getKey());
      links.sort(Comparator.comparingInt(FormLink::priority));
    }

    var indicatorModels=new LinkedHashMap<String,List<Model>>();
    for(var model:m.values())for(var metric:model.outputMetricCodes())
      indicatorModels.computeIfAbsent(metric,x->new ArrayList<>()).add(model);
    if(indicatorModels.size()!=46)
      throw invalid("OUTPUT_METRIC_COUNT_DRIFT:"+indicatorModels.size());

    // Independent editorial explanation rows. Enforce full coverage and exact
    // output metric identity, but never upgrade CATALOG_UNQUALIFIED.
    var explanationRows=new LinkedHashMap<String,IndicatorHelp>();
    for(var line:rows("catalog/indicator-explanations-r1.tsv",5)){
      if(!indicatorModels.containsKey(line[0]))throw invalid("ORPHAN_EXPLANATION:"+line[0]);
      if(Arrays.stream(line).anyMatch(String::isBlank)||
          !"EDITORIAL_DRAFT_NOT_S2".equals(line[4]))
        throw invalid("UNREVIEWED_EXPLANATION_STATUS:"+line[0]);
      var help=new IndicatorHelp(line[1],line[2],line[3],line[4],
          "catalog/indicator-explanations-r1.tsv");
      if(explanationRows.putIfAbsent(line[0],help)!=null)
        throw invalid("DUPLICATE_EXPLANATION:"+line[0]);
    }
    if(explanationRows.size()!=indicatorModels.size())
      throw invalid("EXPLANATION_COVERAGE_DRIFT:"+explanationRows.size());
    var resultIndicators=new LinkedHashMap<String,Indicator>();
    for(var entry:indicatorModels.entrySet()){
      var associated=entry.getValue();
      var inputForms=associated.stream().flatMap(x->x.inputFormCodes().stream())
          .distinct().toList();
      var usedBy=g.values().stream()
          .filter(x->x.modelCodes().stream().anyMatch(code->associated.stream()
              .anyMatch(model->model.code().equals(code))))
          .map(GoalSource::code).toList();
      resultIndicators.put(entry.getKey(),new Indicator(entry.getKey(),
          "ANALYTICAL_OUTPUT","CATALOG_UNQUALIFIED",List.copyOf(associated),
          List.copyOf(inputForms),List.copyOf(usedBy),explanationRows.get(entry.getKey())));
    }

    var usages=new LinkedHashMap<String,FormUsage>();
    for(String formCode:knownForms){
      var goalCodes=gf.entrySet().stream()
          .filter(e->e.getValue().stream().anyMatch(x->x.formCode().equals(formCode)))
          .map(Map.Entry::getKey).toList();
      var modelCodes=m.values().stream()
          .filter(x->x.inputFormCodes().contains(formCode)).map(Model::code).toList();
      usages.put(formCode,new FormUsage(formCode,goalCodes,modelCodes,
          goalCodes.isEmpty()&&modelCodes.isEmpty()
              ?"NOT_YET_MAPPED":"CANDIDATE_LINK_ONLY"));
    }
    if(usages.size()!=166)throw invalid("FORM_USAGE_COUNT_DRIFT");

    goals=Collections.unmodifiableMap(g);
    models=Collections.unmodifiableMap(m);
    goalForms=gf.entrySet().stream().collect(Collectors.toUnmodifiableMap(
        Map.Entry::getKey,e->List.copyOf(e.getValue())));
    indicators=Collections.unmodifiableMap(resultIndicators);
    ruExplanations=Collections.unmodifiableMap(explanationRows);
    formUsages=Collections.unmodifiableMap(usages);
  }

  public List<Goal> goals(String locale){
    requireLocale(locale);
    return goals.values().stream().map(x->goal(x,locale)).toList();
  }
  public Optional<Goal> goal(String code,String locale){
    requireLocale(locale);
    var source=goals.get(code);
    return source==null?Optional.empty():Optional.of(goal(source,locale));
  }
  public List<Indicator> indicators(){return indicators("ru");}
  public List<Indicator> indicators(String locale){
    requireLocale(locale);
    return indicators.values().stream().map(x->localized(x,locale)).toList();
  }
  public Optional<Indicator> indicator(String code){return indicator(code,"ru");}
  public Optional<Indicator> indicator(String code,String locale){
    requireLocale(locale);
    return Optional.ofNullable(indicators.get(code)).map(x->localized(x,locale));
  }
  private Indicator localized(Indicator source,String locale){
    IndicatorHelp help="ru".equals(locale)?ruExplanations.get(source.code()):
      new IndicatorHelp(null,null,null,"PENDING_TRANSLATION",
          "catalog/indicator-explanations-r1.tsv");
    return new Indicator(source.code(),source.metricKind(),source.qualificationStatus(),
        source.models(),source.inputFormCodes(),source.goalCodes(),help);
  }
  public Optional<FormUsage> formUsage(String code){
    return Optional.ofNullable(formUsages.get(code));
  }
  public int modelCount(){return models.size();}

  private Goal goal(GoalSource source,String locale){
    String title=switch(locale){
      case "ru" -> source.titleRu();
      case "en" -> source.titleEn();
      default -> null; // don't fabricate kk/zh translations
    };
    return new Goal(source.code(),title,
        title==null?"PENDING_TRANSLATION":"SOURCE_CANDIDATE_NOT_S2",
        source.audience(),source.deliverable(),source.version(),
        source.qualification(),source.modelCodes().stream().map(models::get).toList(),
        goalForms.get(source.code()));
  }
  private static void requireLocale(String locale){
    if(!LOCALES.contains(locale))throw new IllegalArgumentException("UNSUPPORTED_LOCALE");
  }
  private static List<String> tokens(String src){
    return Arrays.stream(src.split(";",-1)).map(String::trim)
        .filter(x->!x.isEmpty()).distinct().toList();
  }
  private static List<String[]> rows(String resource,int columns){
    try(var reader=new BufferedReader(new InputStreamReader(
        new ClassPathResource(resource).getInputStream(),StandardCharsets.UTF_8))){
      if(reader.readLine()==null)throw invalid("EMPTY_CATALOG:"+resource);
      var rows=new ArrayList<String[]>();
      String line;
      while((line=reader.readLine())!=null){
        if(line.isBlank())continue;
        var values=line.split("\\t",-1);
        if(values.length!=columns)throw invalid("BAD_CATALOG_ROW:"+resource);
        rows.add(values);
      }
      return rows;
    }catch(java.io.IOException ex){
      throw new IllegalStateException("INDICATOR_CATALOG_UNAVAILABLE:"+resource,ex);
    }
  }
  private static IllegalStateException invalid(String code){
    return new IllegalStateException("FIN_KPI_CATALOG_"+code);
  }
}
