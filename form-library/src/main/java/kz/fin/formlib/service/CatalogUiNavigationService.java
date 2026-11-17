package kz.fin.formlib.service;

import kz.fin.formlib.api.ReferenceCatalogController;
import kz.fin.formlib.domain.FormDefinition;
import kz.fin.formlib.repository.FormDefinitionRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * R11.A: 47 draft four-language categories and 389 form/category associations.
 * Read-only navigation. Financial definitions and actual form translations
 * still come from fin_lib; no repeated form IDs or fabricated translations.
 */
@Service
public class CatalogUiNavigationService {
  public record CategoryView(String categoryCode,String parentCategoryCode,
      String categoryType,int sortOrder,String locale,String title,String purpose,
      String description,String translationStatus,int distinctFormCount,
      List<String> childCategoryCodes) {}
  public record CategoriesView(String locale,int catalogVersion,
      int totalForms,int totalCategories,int totalAssociations,
      List<CategoryView> categories) {}
  public record FormCard(String formCode,UUID formVersionId,String versionLabel,
      String locale,String title,String purpose,String description,
      String titleTranslationStatus,String purposeTranslationStatus,
      String descriptionTranslationStatus,String captureMode,String contentMode,
      String qualificationState,String associationRole,
      String nextScreen,String formPeriodPolicyStatus) {}
  public record CategoryFormsView(CategoryView category,String locale,
      int totalForms,List<FormCard> forms) {}
  public record LaunchView(String formCode,UUID formVersionId,String versionLabel,
      String locale,String title,String purpose,String description,
      String contentMode,String captureMode,String qualificationState,
      String nextScreen,List<String> displayStyles,String periodPolicyStatus,
      List<String> approvedPeriodTypes,String templatePreviewEndpoint,
      List<String> warnings) {}

  private record Texts(String title,String purpose,String description){}
  private record CategoryMeta(String code,String parent,String type,int sort,
      Map<String,Texts> localized,String status) {}
  private record FormLink(String formCode,String role,int sortOrder) {}
  private static final List<String> LOCALES=List.of("ru","kk","en","zh-Hans");
  private static final Set<String> CATEGORY_TYPES=Set.of("ROOT","DOMAIN","SECTOR","PURPOSE");
  private static final Set<String> LINK_ROLES=Set.of(
      "PRIMARY","DOMAIN","SECTOR","CORE","SUPPORTING","EVIDENCE");
  private final ReferenceCatalogController catalog;
  private final FormDefinitionRepository forms;
  private final Map<String,CategoryMeta> categories;
  private final Map<String,List<FormLink>> links;

  public CatalogUiNavigationService(ReferenceCatalogController catalog,
      FormDefinitionRepository forms) {
    this.catalog=catalog;
    this.forms=forms;
    Map<String,CategoryMeta> defs=new LinkedHashMap<>();
    var validFormCodes=new HashSet<String>();
    for(var line:read("catalog/form-navigation-r10.tsv",4)){
      if(!validFormCodes.add(line[0]))throw new IllegalStateException("DUPLICATE_FORM_CODE");
    }
    if(validFormCodes.size()!=166)throw new IllegalStateException("FORM_CATALOG_DRIFT");
    for(var line:read("catalog/catalog-category-i18n-r11.tsv",17)){
      if(!CATEGORY_TYPES.contains(line[2])||line[0].isBlank()||
          !"DRAFT_UNREVIEWED".equals(line[16]))
        throw new IllegalStateException("BAD_CATEGORY_DESCRIPTOR");
      int sort;
      try{sort=Integer.parseInt(line[3]);}
      catch(NumberFormatException e){throw new IllegalStateException("BAD_CATEGORY_SORT",e);}
      if(sort<=0)throw new IllegalStateException("BAD_CATEGORY_SORT");
      Map<String,Texts> i18n=new LinkedHashMap<>();
      for(int i=0;i<4;i++){
        if(line[4+i].isBlank()||line[8+i].isBlank()||line[12+i].isBlank())
          throw new IllegalStateException("MISSING_CATEGORY_TRANSLATION");
        i18n.put(LOCALES.get(i),new Texts(line[4+i],line[8+i],line[12+i]));
      }
      var def=new CategoryMeta(line[0],line[1],line[2],sort,Map.copyOf(i18n),line[16]);
      if(defs.putIfAbsent(def.code(),def)!=null)throw new IllegalStateException("DUPLICATE_CATEGORY");
    }
    if(defs.size()!=47)throw new IllegalStateException("CATEGORY_COUNT_DRIFT");
    for(var cat:defs.values()){
      if("ROOT".equals(cat.type())){
        if(!cat.parent().isBlank())throw new IllegalStateException("ROOT_WITH_PARENT");
      }else{
        var parent=defs.get(cat.parent());
        if(parent==null||!"ROOT".equals(parent.type()))
          throw new IllegalStateException("BAD_CATEGORY_PARENT");
        if(("DOMAIN".equals(cat.type())&&!"GROUP_DETAIL".equals(cat.parent()))
          ||("SECTOR".equals(cat.type())&&!"GROUP_SECTOR".equals(cat.parent()))
          ||("PURPOSE".equals(cat.type())&&!"GROUP_PURPOSE".equals(cat.parent())))
          throw new IllegalStateException("BAD_CATEGORY_HIERARCHY");
      }
    }
    var joined=new HashMap<String,List<FormLink>>();
    var duplicate=new HashSet<String>();
    int total=0;
    for(var line:read("catalog/catalog-category-form-r11.tsv",4)){
      if(!defs.containsKey(line[0])||!validFormCodes.contains(line[1])||
          !LINK_ROLES.contains(line[2]))
        throw new IllegalStateException("INVALID_CATEGORY_FORM_BINDING");
      int sort;
      try{sort=Integer.parseInt(line[3]);}
      catch(NumberFormatException e){throw new IllegalStateException("BAD_BINDING_ORDER",e);}
      if(sort<1||!duplicate.add(line[0]+"|"+line[1]))
        throw new IllegalStateException("DUPLICATE_CATEGORY_FORM_BINDING");
      var def=defs.get(line[0]);
      if(("ROOT".equals(def.type())&&!"PRIMARY".equals(line[2]))
        ||("DOMAIN".equals(def.type())&&!"DOMAIN".equals(line[2]))
        ||("SECTOR".equals(def.type())&&!"SECTOR".equals(line[2]))
        ||("PURPOSE".equals(def.type())&&!Set.of("CORE","SUPPORTING","EVIDENCE").contains(line[2])))
        throw new IllegalStateException("WRONG_CATEGORY_FORM_ROLE");
      joined.computeIfAbsent(line[0],unused->new ArrayList<>())
        .add(new FormLink(line[1],line[2],sort));
      total++;
    }
    if(total!=389)throw new IllegalStateException("CATEGORY_FORM_COUNT_DRIFT");
    Map<String,Integer> primaryCounts=new HashMap<>();
    for(var kv:joined.entrySet()){
      if("ROOT".equals(defs.get(kv.getKey()).type())){
        for(var link:kv.getValue())primaryCounts.merge(link.formCode(),1,Integer::sum);
      }
    }
    if(primaryCounts.size()!=166||primaryCounts.values().stream().anyMatch(c->c!=1))
      throw new IllegalStateException("ROOT_CATEGORY_COVERAGE");
    // Validate every leaf link against its primary category: no false routes.
    for(var cat:defs.values())if(!"ROOT".equals(cat.type())){
      String root=cat.parent();
      Set<String> parents=joined.getOrDefault(root,List.of()).stream()
        .map(FormLink::formCode).collect(Collectors.toSet());
      if(!"PURPOSE".equals(cat.type())&&joined.getOrDefault(cat.code(),List.of())
          .stream().anyMatch(l->!parents.contains(l.formCode())))
        throw new IllegalStateException("CATEGORY_LINK_SCOPE_MISMATCH");
    }
    Map<String,List<FormLink>> frozen=new HashMap<>();
    joined.forEach((key,value)->frozen.put(key,value.stream()
      .sorted(Comparator.comparingInt(FormLink::sortOrder).thenComparing(FormLink::formCode))
      .toList()));
    this.categories=Map.copyOf(defs);
    this.links=Map.copyOf(frozen);
  }

  private static List<String[]> read(String path,int count){
    try(var reader=new BufferedReader(new InputStreamReader(
      new ClassPathResource(path).getInputStream(),StandardCharsets.UTF_8))){
      if(reader.readLine()==null)throw new IllegalStateException("EMPTY_CATALOG_RESOURCE");
      var result=new ArrayList<String[]>();String line;
      while((line=reader.readLine())!=null){
        if(line.isBlank())continue;
        var parts=line.split("\t",-1);
        if(parts.length!=count)throw new IllegalStateException("BAD_CATALOG_RESOURCE");
        result.add(parts);
      }
      return result;
    }catch(java.io.IOException e){throw new IllegalStateException("CATALOG_RESOURCE_UNAVAILABLE",e);}
  }
  private static String validateLocale(String locale){
    if(!LOCALES.contains(locale))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UNSUPPORTED_LOCALE");
    return locale;
  }
  private CategoryMeta requireCategory(String categoryCode){
    var def=categories.get(categoryCode==null?"":categoryCode.toUpperCase(Locale.ROOT));
    if(def==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"CATEGORY_NOT_FOUND");
    return def;
  }
  private List<CategoryMeta> children(String parent){
    return categories.values().stream().filter(c->c.parent().equals(parent))
      .sorted(Comparator.comparingInt(CategoryMeta::sort).thenComparing(CategoryMeta::code))
      .toList();
  }
  private List<FormLink> collectLinks(CategoryMeta cat){
    var result=new LinkedHashMap<String,FormLink>();
    for(var link:links.getOrDefault(cat.code(),List.of()))result.put(link.formCode(),link);
    for(var child:children(cat.code()))for(var link:links.getOrDefault(child.code(),List.of()))
      result.putIfAbsent(link.formCode(),link);
    return List.copyOf(result.values());
  }
  private CategoryView asCategory(CategoryMeta c,String locale){
    Texts t=c.localized().get(locale);
    return new CategoryView(c.code(),c.parent().isBlank()?null:c.parent(),c.type(),
      c.sort(),locale,t.title(),t.purpose(),t.description(),c.status(),
      collectLinks(c).size(),children(c.code()).stream().map(CategoryMeta::code).toList());
  }
  @Transactional(readOnly=true)
  public CategoriesView listCategories(String locale){
    validateLocale(locale);
    var all=categories.values().stream()
      .sorted(Comparator.comparing((CategoryMeta x)->x.parent().isBlank()?0:1)
        .thenComparing(CategoryMeta::parent)
        .thenComparingInt(CategoryMeta::sort).thenComparing(CategoryMeta::code))
      .map(c->asCategory(c,locale)).toList();
    return new CategoriesView(locale,1,166,47,389,all);
  }
  private static String fieldStatus(String text,String recordStatus) {
    return text==null||text.isBlank()?"PENDING_TRANSLATION":recordStatus;
  }
  private static String nextScreen(String mode) {
    return "EVIDENCE".equals(mode)?"EVIDENCE_VIEWER":"PERIOD_AND_STYLE_SELECTOR";
  }
  @Transactional(readOnly=true)
  public CategoryFormsView listForms(String categoryCode,String locale){
    validateLocale(locale);
    var cat=requireCategory(categoryCode);
    Map<String,ReferenceCatalogController.FormView> localized=new HashMap<>();
    for(var value:catalog.forms(locale))
      if(localized.putIfAbsent(value.formCode(),value)!=null)
        throw new IllegalStateException("DUPLICATE_LIVE_FORM");
    Map<String,FormDefinition> definitions=new HashMap<>();
    for(var def:forms.findAllByOrderByFormCodeAsc())definitions.put(def.getFormCode(),def);
    var cards=new ArrayList<FormCard>();
    for(var link:collectLinks(cat)){
      var f=localized.get(link.formCode());
      var def=definitions.get(link.formCode());
      if(f==null||def==null)throw new IllegalStateException("MISSING_LIVE_FORM");
      cards.add(new FormCard(f.formCode(),def.getCurrentVersionId(),f.version(),
        locale,f.title(),f.purpose(),f.description(),
        fieldStatus(f.title(),f.translationStatus()),f.purposeTranslationStatus(),
        fieldStatus(f.description(),f.translationStatus()),
        f.captureMode(),f.contentMode(),f.qualificationState(),
        link.role(),nextScreen(f.contentMode()),"NOT_REVIEWED"));
    }
    return new CategoryFormsView(asCategory(cat,locale),locale,cards.size(),
      List.copyOf(cards));
  }
  @Transactional(readOnly=true)
  public LaunchView launch(String formCode,String locale){
    validateLocale(locale);
    var f=catalog.form(formCode,locale).form();
    var definition=forms.findByFormCode(formCode).orElseThrow();
    var styles=switch(f.contentMode()){
      case "SINGLE_RECORD" -> List.of("EXCEL_GRID","TRANSPOSED_GRID","SECTION_FORM");
      case "ROWSET" -> List.of("ROWSET_TABLE");
      case "EVIDENCE" -> List.of("EVIDENCE_INFO");
      default -> throw new IllegalStateException("UNSUPPORTED_CONTENT_MODE");
    };
    return new LaunchView(formCode,definition.getCurrentVersionId(),f.version(),
      locale,f.title(),f.purpose(),f.description(),f.contentMode(),f.captureMode(),
      f.qualificationState(),nextScreen(f.contentMode()),styles,
      "NOT_REVIEWED",List.of(),"/api/v1/templates/{formCode}/preview",
      List.of("PER_FORM_PERIOD_POLICY_NOT_QUALIFIED",
        "FORM_PURPOSE_REQUIRES_EDITORIAL_REVIEW",
        "FORM_VERSION_MUST_BE_PINNED_BEFORE_SUBMIT"));
  }
}
