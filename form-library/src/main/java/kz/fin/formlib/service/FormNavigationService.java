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
import java.util.stream.Collectors;

/**
 * R10: discovery and recommendation metadata for all 166 system forms.
 * "Recommended" is not an access grant, company applicability approval,
 * reporting obligation, financial qualification, or ability to submit facts.
 * Multi-sector business profiles are deliberately supported.
 */
@Service
public class FormNavigationService {
  public record Section(String groupCode,String titleRu,String titleEn,int totalForms){}
  public record Sector(String sectorCode,String titleRu,String titleEn,String audience,int totalForms){}
  public record FormItem(String formCode,String primaryGroup,String functionalDomain,
      String sectorCode,String title,String translationStatus,String captureMode,
      String contentMode,String qualificationState,String recommendationReason){}
  public record NavigationView(int totalForms,int coveredByModelCatalog,
      int withoutModelReferences,int totalModelDescriptors,String locale,
      List<String> selectedSectors,String selectedAnalysisPack,
      List<Section> sections,List<Sector> sectors,List<String> analysisPacks,
      List<FormItem> recommended,List<FormItem> allForms,
      String selectedUseCase,List<FormUseCaseCatalogService.UseCaseSummary> useCases){}
  private record Meta(String group,String domain,String sector){}
  private static final Map<String,String[]> GROUPS=new LinkedHashMap<>();
  static {
    GROUPS.put("FINANCE",new String[]{"Финансовая отчётность","Financial statements"});
    GROUPS.put("LEDGERS",new String[]{"Учётные реестры","Accounting registers"});
    GROUPS.put("PLANNING",new String[]{"Бюджеты и прогнозы","Budgets and forecasts"});
    GROUPS.put("DETAIL",new String[]{"Детальные бизнес-данные","Operational detail"});
    GROUPS.put("SECTOR",new String[]{"Отраслевые формы","Industry-specific forms"});
    GROUPS.put("EVIDENCE",new String[]{"Документы и подтверждения","Documents and evidence"});
  }
  private static final List<String> STARTER_CODES=List.of("F01","F02","F03","F04");
  private final ReferenceCatalogController catalog;
  private final FormUseCaseCatalogService useCases;
  private final Map<String,Meta> meta;
  private final List<Sector> sectors;
  private final Map<String,Set<String>> modelFormCodes;
  private final Map<String,Set<String>> packFormCodes;
  private final int modelDescriptors;
  private final int modelReferencedForms;

  public FormNavigationService(ReferenceCatalogController catalog,
      FormUseCaseCatalogService useCases){
    this.catalog=catalog;
    this.useCases=useCases;
    Map<String,Meta> navigation=new HashMap<>();
    for(var r:read("catalog/form-navigation-r10.tsv",4)){
      if(!GROUPS.containsKey(r[1]))throw new IllegalStateException("NAV_GROUP_UNKNOWN");
      if(r[2].isBlank()||navigation.putIfAbsent(r[0],new Meta(r[1],r[2],r[3]))!=null)
        throw new IllegalStateException("NAV_DUPLICATE_FORM");
    }
    if(navigation.size()!=166)throw new IllegalStateException("NAV_FORM_COVERAGE");
    this.meta=Map.copyOf(navigation);

    Map<String,Integer> sectorCounts=new HashMap<>();
    for(var e:navigation.values())if("SECTOR".equals(e.group())){
      if(e.sector().isBlank())throw new IllegalStateException("NAV_SECTOR_REQUIRED");
      sectorCounts.merge(e.sector(),1,Integer::sum);
    }else if(!e.sector().isBlank())throw new IllegalStateException("NAV_NON_SECTOR_TAG");
    var allSectors=new ArrayList<Sector>();
    var seen=new HashSet<String>();
    for(var r:read("catalog/sector-packs-r10.tsv",4)){
      if(!seen.add(r[0])||r[1].isBlank()||r[2].isBlank())
        throw new IllegalStateException("NAV_DUPLICATE_SECTOR");
      allSectors.add(new Sector(r[0],r[1],r[2],r[3],sectorCounts.getOrDefault(r[0],0)));
    }
    if(allSectors.size()!=21||allSectors.stream().anyMatch(s->s.totalForms()!=3)
      ||!seen.containsAll(sectorCounts.keySet()))throw new IllegalStateException("NAV_SECTOR_COVERAGE");
    this.sectors=List.copyOf(allSectors);

    Map<String,Set<String>> modelRefs=new HashMap<>(),packs=new HashMap<>();
    int count=0;
    for(var r:read("catalog/analytics-model-catalog.tsv",6)){
      count++;
      if(modelRefs.containsKey(r[0]))throw new IllegalStateException("NAV_DUPLICATE_MODEL");
      Set<String> refs=Set.of(r[3].split(";"));
      if(refs.stream().anyMatch(x->!navigation.containsKey(x)))
        throw new IllegalStateException("NAV_UNKNOWN_MODEL_INPUT");
      modelRefs.put(r[0],refs);
      packs.computeIfAbsent(r[1],k->new HashSet<>()).addAll(refs);
    }
    if(count!=42)throw new IllegalStateException("NAV_MODEL_COUNT_DRIFT");
    this.modelDescriptors=count;
    this.modelFormCodes=Map.copyOf(modelRefs);
    this.packFormCodes=Map.copyOf(packs.entrySet().stream().collect(
      Collectors.toMap(Map.Entry::getKey,e->Set.copyOf(e.getValue()))));
    this.modelReferencedForms=(int)modelRefs.values().stream().flatMap(Set::stream).distinct().count();
  }

  private static List<String[]> read(String resource,int expectedCols){
    try(var reader=new BufferedReader(new InputStreamReader(
      new ClassPathResource(resource).getInputStream(),StandardCharsets.UTF_8))){
      if(reader.readLine()==null)throw new IllegalStateException("NAV_EMPTY_SOURCE");
      List<String[]> out=new ArrayList<>();
      String line;
      while((line=reader.readLine())!=null){
        if(line.isBlank())continue;
        var fields=line.split("\t",-1);
        if(fields.length!=expectedCols)throw new IllegalStateException("NAV_INVALID_SOURCE");
        out.add(fields);
      }
      return out;
    }catch(java.io.IOException e){throw new IllegalStateException("NAV_RESOURCE_MISSING",e);}
  }

  public NavigationView navigation(String locale,List<String> requestedSectors,String requestedPack){
    return navigation(locale,requestedSectors,requestedPack,null);
  }

  public NavigationView navigation(String locale,List<String> requestedSectors,
      String requestedPack,String requestedUseCase){
    if(!Set.of("ru","kk","en","zh-Hans").contains(locale))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UNSUPPORTED_LOCALE");
    var activeSectors=new TreeSet<String>();
    if(requestedSectors!=null)for(String selected:requestedSectors){
      if(selected==null||selected.isBlank())continue;
      for(String candidate:selected.split(",")){
        String code=candidate.trim().toUpperCase(Locale.ROOT);
        if(sectors.stream().noneMatch(s->s.sectorCode().equals(code)))
          throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"UNKNOWN_SECTOR");
        activeSectors.add(code);
      }
    }
    String pack=requestedPack==null||requestedPack.isBlank()?null:requestedPack.trim().toUpperCase(Locale.ROOT);
    if(pack!=null&&!packFormCodes.containsKey(pack))
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"UNKNOWN_ANALYSIS_PACK");
    var inputs=pack==null?Set.<String>of():packFormCodes.get(pack);
    String selectedUseCase=requestedUseCase==null||requestedUseCase.isBlank()
      ?null:requestedUseCase.trim().toUpperCase(Locale.ROOT);
    var useCaseInputs=selectedUseCase==null?Set.<String>of():useCases.formCodes(selectedUseCase);
    var current=catalog.forms(locale);
    Set<String> seen=new HashSet<>();
    var all=new ArrayList<FormItem>();
    for(var form:current){
      var m=meta.get(form.formCode());
      if(m==null||!seen.add(form.formCode()))throw new IllegalStateException("NAV_CATALOG_DRIFT");
      String reason=useCaseInputs.contains(form.formCode())
        ?"SELECTED_USE_CASE"
        :!m.sector().isBlank()&&activeSectors.contains(m.sector())
        ?"SELECTED_SECTOR"
        :inputs.contains(form.formCode())
        ?"ANALYSIS_MODEL_INPUT"
        :STARTER_CODES.contains(form.formCode())
        ?"COMMON_STARTER"
        :"CATALOG_ONLY";
      all.add(new FormItem(form.formCode(),m.group(),m.domain(),
        m.sector().isBlank()?null:m.sector(),form.title(),form.translationStatus(),
        form.captureMode(),form.contentMode(),form.qualificationState(),reason));
    }
    if(all.size()!=166||!seen.equals(meta.keySet()))
      throw new IllegalStateException("NAV_CATALOG_DRIFT");
    var sections=new ArrayList<Section>();
    for(var group:GROUPS.entrySet()){
      int n=(int)all.stream().filter(x->group.getKey().equals(x.primaryGroup())).count();
      sections.add(new Section(group.getKey(),group.getValue()[0],group.getValue()[1],n));
    }
    var recommended=all.stream()
      .filter(x->!"CATALOG_ONLY".equals(x.recommendationReason()))
      .sorted(Comparator.comparingInt((FormItem x)->switch(x.recommendationReason()){
        case "SELECTED_USE_CASE"->0;
        case "SELECTED_SECTOR"->1;
        case "ANALYSIS_MODEL_INPUT"->2;
        default->3;
      }).thenComparingInt(x->{
        if(selectedUseCase==null)return 0;
        return switch(String.valueOf(useCases.role(selectedUseCase,x.formCode()))){
          case "CORE" -> 0;
          case "SUPPORTING" -> 1;
          case "EVIDENCE" -> 2;
          default -> 3;
        };
      }).thenComparing(FormItem::formCode)).toList();
    return new NavigationView(166,modelReferencedForms,
      166-modelReferencedForms,modelDescriptors,locale,List.copyOf(activeSectors),
      pack,List.copyOf(sections),sectors,
      packFormCodes.keySet().stream().sorted().toList(),
      recommended,List.copyOf(all),selectedUseCase,useCases.all());
  }
}
