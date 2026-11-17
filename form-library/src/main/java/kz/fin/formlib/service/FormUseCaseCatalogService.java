package kz.fin.formlib.service;

import kz.fin.formlib.api.ReferenceCatalogController;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * R10.A2: scenario/use-case classification orthogonal to company industry,
 * form capture capability, regulatory obligation, permissions and S2 review.
 * Delivers only curated, unqualified navigation suggestions.
 */
@Service
public class FormUseCaseCatalogService {
  public record UseCaseSummary(String useCaseCode,String titleRu,String titleEn,
      String audience,String deliverable,int version,String qualificationState,
      List<String> modelCodes,int coreCount,int supportingCount,int evidenceCount) {}
  public record SuggestedForm(String formCode,String role,int priority,
      String title,String translationStatus,String captureMode,String contentMode,
      String qualificationState,String applicability) {}
  public record UseCaseDetails(UseCaseSummary useCase,String locale,
      List<SuggestedForm> forms,List<String> warnings) {}
  private record Entry(String formCode,String role,int priority) {}
  private record Definition(String code,String ru,String en,String audience,
      String deliverable,int version,String qualification,List<String> modelCodes) {}
  private static final Set<String> VALID_ROLES=Set.of("CORE","SUPPORTING","EVIDENCE");
  private static final Set<String> VALID_LOCALES=Set.of("ru","kk","en","zh-Hans");
  private final ReferenceCatalogController catalog;
  private final Map<String,Definition> definitions;
  private final Map<String,List<Entry>> links;

  public FormUseCaseCatalogService(ReferenceCatalogController catalog) {
    this.catalog=catalog;
    var validForms=new HashSet<String>();
    for(var row:rows("catalog/form-navigation-r10.tsv",4)) {
      if(!validForms.add(row[0]))throw new IllegalStateException("DUPLICATE_FORM_NAVIGATION");
    }
    if(validForms.size()!=166)throw new IllegalStateException("USE_CASE_FORM_SOURCE_DRIFT");
    var modelCodes=new HashSet<String>();
    for(var row:rows("catalog/analytics-model-catalog.tsv",6)){
      if(!modelCodes.add(row[0]))throw new IllegalStateException("DUPLICATE_ANALYTICAL_MODEL");
    }
    Map<String,Definition> defs=new LinkedHashMap<>();
    for(var row:rows("catalog/use-case-catalog-r10.tsv",8)){
      if(row[0].isBlank()||row[1].isBlank()||row[2].isBlank()
        ||row[3].isBlank()||row[4].isBlank()||row[5].isBlank())
        throw new IllegalStateException("USE_CASE_METADATA_INCOMPLETE");
      int version;
      try{version=Integer.parseInt(row[5]);}
      catch(NumberFormatException e){throw new IllegalStateException("USE_CASE_VERSION_INVALID");}
      if(version<=0||!"CANDIDATE_NOT_S2".equals(row[6]))
        throw new IllegalStateException("USE_CASE_STATE_INVALID");
      var models=List.of(row[7].split(";"));
      if(models.isEmpty()||models.stream().anyMatch(s->!modelCodes.contains(s)))
        throw new IllegalStateException("USE_CASE_UNKNOWN_MODEL");
      var d=new Definition(row[0],row[1],row[2],row[3],row[4],
        version,row[6],models);
      if(defs.putIfAbsent(row[0],d)!=null)
        throw new IllegalStateException("USE_CASE_DUPLICATE_CODE");
    }
    if(defs.size()!=7)throw new IllegalStateException("USE_CASE_COUNT_DRIFT");

    Map<String,List<Entry>> assigned=new HashMap<>();
    Map<String,Set<String>> dedup=new HashMap<>();
    Map<String,Set<Integer>> positions=new HashMap<>();
    for(var row:rows("catalog/use-case-form-map-r10.tsv",4)){
      if(!defs.containsKey(row[0])||!validForms.contains(row[1])||
        !VALID_ROLES.contains(row[2]))throw new IllegalStateException("USE_CASE_BAD_FORM_MAPPING");
      int order;
      try{order=Integer.parseInt(row[3]);}
      catch(NumberFormatException e){throw new IllegalStateException("USE_CASE_BAD_PRIORITY");}
      if(order<1||!dedup.computeIfAbsent(row[0],k->new HashSet<>()).add(row[1])
        ||!positions.computeIfAbsent(row[0],k->new HashSet<>()).add(order))
        throw new IllegalStateException("USE_CASE_DUPLICATE_BINDING");
      assigned.computeIfAbsent(row[0],k->new ArrayList<>())
        .add(new Entry(row[1],row[2],order));
    }
    var normalized=new HashMap<String,List<Entry>>();
    for(String code:defs.keySet()){
      var list=assigned.getOrDefault(code,List.of()).stream()
        .sorted(Comparator.comparingInt(Entry::priority)).toList();
      if(list.isEmpty()||list.stream().noneMatch(e->e.role().equals("CORE")))
        throw new IllegalStateException("USE_CASE_MISSING_CORE");
      normalized.put(code,list);
    }
    this.definitions=Map.copyOf(defs);
    this.links=Map.copyOf(normalized);
  }

  private static List<String[]> rows(String path,int expected){
    try(var reader=new BufferedReader(new InputStreamReader(
      new ClassPathResource(path).getInputStream(),StandardCharsets.UTF_8))){
      if(reader.readLine()==null)throw new IllegalStateException("USE_CASE_EMPTY_RESOURCE");
      List<String[]> data=new ArrayList<>();
      String line;
      while((line=reader.readLine())!=null){
        if(line.isBlank())continue;
        String[] tokens=line.split("\t",-1);
        if(tokens.length!=expected)throw new IllegalStateException("USE_CASE_BAD_COLUMNS");
        data.add(tokens);
      }
      return data;
    }catch(java.io.IOException e){throw new IllegalStateException("USE_CASE_RESOURCE_MISSING",e);}
  }

  public List<UseCaseSummary> all(){
    return definitions.keySet().stream().sorted().map(this::summary).toList();
  }
  private UseCaseSummary summary(String code){
    Definition def=definitions.get(code);
    List<Entry> entries=links.get(code);
    return new UseCaseSummary(code,def.ru(),def.en(),def.audience(),def.deliverable(),
      def.version(),def.qualification(),def.modelCodes(),
      (int)entries.stream().filter(e->"CORE".equals(e.role())).count(),
      (int)entries.stream().filter(e->"SUPPORTING".equals(e.role())).count(),
      (int)entries.stream().filter(e->"EVIDENCE".equals(e.role())).count());
  }
  public Set<String> formCodes(String code){
    String norm=normalize(code);
    return links.get(norm).stream().map(Entry::formCode)
      .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }
  public String role(String code,String formCode){
    String norm=normalize(code);
    return links.get(norm).stream().filter(e->e.formCode().equals(formCode))
      .map(Entry::role).findFirst().orElse(null);
  }
  private String normalize(String code){
    if(code==null||!definitions.containsKey(code.trim().toUpperCase(Locale.ROOT)))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"USE_CASE_NOT_FOUND");
    return code.trim().toUpperCase(Locale.ROOT);
  }
  public UseCaseDetails details(String code,String locale) {
    String norm=normalize(code);
    if(!VALID_LOCALES.contains(locale))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UNSUPPORTED_LOCALE");
    Map<String,ReferenceCatalogController.FormView> byCode=new HashMap<>();
    for(var form:catalog.forms(locale)){
      if(byCode.putIfAbsent(form.formCode(),form)!=null)
        throw new IllegalStateException("USE_CASE_CATALOG_DUPLICATE");
    }
    var suggestions=new ArrayList<SuggestedForm>();
    var warnings=new ArrayList<String>();
    for(var entry:links.get(norm)){
      var form=byCode.get(entry.formCode());
      if(form==null)throw new IllegalStateException("USE_CASE_CATALOG_DRIFT");
      if("PENDING_TRANSLATION".equals(form.translationStatus()))
        warnings.add("TRANSLATION_MISSING:"+form.formCode());
      if(!"CANDIDATE_UNQUALIFIED".equals(form.qualificationState()))
        warnings.add("FORM_QUALIFICATION_REQUIRES_REVIEW:"+form.formCode());
      suggestions.add(new SuggestedForm(form.formCode(),entry.role(),entry.priority(),
        form.title(),form.translationStatus(),form.captureMode(),form.contentMode(),
        form.qualificationState(),"SUGGESTED_NOT_MANDATORY"));
    }
    warnings.add("NO_REGULATORY_OR_FINANCIAL_APPROVAL_INFERRED");
    warnings.add("NOT_A_CREDIT_DECISION_OR_INVESTMENT_RECOMMENDATION");
    return new UseCaseDetails(summary(norm),locale,List.copyOf(suggestions),
      List.copyOf(warnings));
  }
}
