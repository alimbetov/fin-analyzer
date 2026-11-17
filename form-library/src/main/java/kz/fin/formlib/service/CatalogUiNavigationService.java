package kz.fin.formlib.service;

import kz.fin.formlib.api.ReferenceCatalogController;
import kz.fin.formlib.domain.FormDefinition;
import kz.fin.formlib.repository.FormDefinitionRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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
  public record CategoryTile(String iconKey,CategoryView category){}
  public record FormTile(String iconKey,FormCard form){}
  public record TranslationCoverage(String locale,int forms,int categories,
      int categoriesTranslated,int categoryFormLinks,int namedForms,
      int describedForms,int purposefulForms,int fullyDocumentedForms,
      int typedDraftForms,int evidenceOnlyForms,int schemaNotApprovedForms,
      int financiallyUnqualifiedForms){}
  public record TranslationGap(String formCode,UUID formVersionId,
      String captureMode,String qualificationState,boolean missingTitle,
      boolean missingPurpose,boolean missingDescription){}
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
  private record CatalogDbSnapshot(Map<String,CategoryMeta> categories,
      Map<String,List<FormLink>> links) {}
  private static final List<String> LOCALES=List.of("ru","kk","en","zh-Hans");
  private static final Set<String> CATEGORY_TYPES=Set.of("ROOT","DOMAIN","SECTOR","PURPOSE");
  private static final Set<String> LINK_ROLES=Set.of(
      "PRIMARY","DOMAIN","SECTOR","CORE","SUPPORTING","EVIDENCE");
  private final ReferenceCatalogController catalog;
  private final FormDefinitionRepository forms;
  private final CatalogReadCache cache;
  private final JdbcTemplate jdbc;
  private final Map<String,CategoryMeta> categories;
  private final Map<String,List<FormLink>> links;

  public CatalogUiNavigationService(ReferenceCatalogController catalog,
      FormDefinitionRepository forms,CatalogReadCache cache,JdbcTemplate jdbc) {
    this.catalog=catalog;
    this.forms=forms;
    this.cache=cache;
    this.jdbc=jdbc;
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
  /**
   * A single DB-backed, immutable, cacheable snapshot. Static source maps above
   * verify seed integrity at application construction but are NOT used as the
   * authoritative runtime category text or relationships.
   * The seeder executes before any request can reach this method.
   */
  private CatalogDbSnapshot dbSnapshot(){
    return cache.get("ui-db-catalog","v1",()->{
      var translated=new HashMap<String,Map<String,Texts>>();
      jdbc.query("""
        SELECT category_code,locale,title,purpose,description
        FROM fin_lib.lib_catalog_category_translation
        """,(RowCallbackHandler)rs->{
        var row=translated.computeIfAbsent(rs.getString("category_code"),
          ignored->new HashMap<>());
        if(row.putIfAbsent(rs.getString("locale"),
            new Texts(rs.getString("title"),rs.getString("purpose"),
              rs.getString("description")))!=null)
          throw new IllegalStateException("DUPLICATE_DB_CATEGORY_LOCALE");
      });
      Map<String,CategoryMeta> liveCategories=new HashMap<>();
      jdbc.query("""
        SELECT code,parent_code,category_type,sort_order,lifecycle_status
        FROM fin_lib.lib_catalog_category
        """,(RowCallbackHandler)rs->{
        var code=rs.getString("code");
        var byLocale=translated.get(code);
        if(byLocale==null||byLocale.size()!=4||
          !byLocale.keySet().equals(new HashSet<>(LOCALES)))
          throw new IllegalStateException("DB_CATEGORY_LOCALE_COVERAGE");
        String parent=rs.getString("parent_code");
        var result=new CategoryMeta(code,parent==null?"":parent,
          rs.getString("category_type"),rs.getInt("sort_order"),
          Map.copyOf(byLocale),rs.getString("lifecycle_status"));
        if(liveCategories.putIfAbsent(code,result)!=null)
          throw new IllegalStateException("DB_CATEGORY_DUPLICATE");
      });
      if(liveCategories.size()!=47||!liveCategories.keySet().equals(categories.keySet())
        ||translated.size()!=47)throw new IllegalStateException("DB_CATEGORY_SOURCE_DRIFT");
      var liveLinks=new HashMap<String,List<FormLink>>();
      var uniqueness=new HashSet<String>();
      jdbc.query("""
        SELECT l.category_code,f.form_code,l.association_role,l.sort_order
        FROM fin_lib.lib_catalog_category_form l
        JOIN fin_lib.lib_form_definition f ON f.id=l.form_id
        """,(RowCallbackHandler)rs->{
        String cat=rs.getString("category_code"),code=rs.getString("form_code");
        if(!liveCategories.containsKey(cat)||!uniqueness.add(cat+"|"+code))
          throw new IllegalStateException("DB_CATEGORY_LINK_INVALID");
        liveLinks.computeIfAbsent(cat,ignored->new ArrayList<>())
          .add(new FormLink(code,rs.getString("association_role"),
            rs.getInt("sort_order")));
      });
      if(uniqueness.size()!=389)throw new IllegalStateException("DB_CATEGORY_LINK_DRIFT");
      Map<String,List<FormLink>> immutable=new HashMap<>();
      liveLinks.forEach((key,value)->immutable.put(key,value.stream()
        .sorted(Comparator.comparingInt(FormLink::sortOrder).thenComparing(FormLink::formCode))
        .toList()));
      return new CatalogDbSnapshot(Map.copyOf(liveCategories),Map.copyOf(immutable));
    });
  }
  private static String validateLocale(String locale){
    if(!LOCALES.contains(locale))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UNSUPPORTED_LOCALE");
    return locale;
  }
  private CategoryMeta requireCategory(String categoryCode){
    var def=dbSnapshot().categories().get(
      categoryCode==null?"":categoryCode.toUpperCase(Locale.ROOT));
    if(def==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"CATEGORY_NOT_FOUND");
    return def;
  }
  private List<CategoryMeta> children(String parent){
    return dbSnapshot().categories().values().stream()
      .filter(c->c.parent().equals(parent))
      .sorted(Comparator.comparingInt(CategoryMeta::sort).thenComparing(CategoryMeta::code))
      .toList();
  }
  private List<FormLink> collectLinks(CategoryMeta cat){
    var result=new LinkedHashMap<String,FormLink>();
    var dbLinks=dbSnapshot().links();
    for(var link:dbLinks.getOrDefault(cat.code(),List.of()))result.put(link.formCode(),link);
    for(var child:children(cat.code()))for(var link:dbLinks.getOrDefault(child.code(),List.of()))
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
    return cache.get("ui-categories",locale,()->{
    var all=dbSnapshot().categories().values().stream()
      .sorted(Comparator.comparing((CategoryMeta x)->x.parent().isBlank()?0:1)
        .thenComparing(CategoryMeta::parent)
        .thenComparingInt(CategoryMeta::sort).thenComparing(CategoryMeta::code))
      .map(c->asCategory(c,locale)).toList();
    return new CategoriesView(locale,1,166,47,389,all);
    });
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
    return cache.get("ui-category-forms",locale+"|"+cat.code(),()->{
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
    });
  }
  @Transactional(readOnly=true)
  public LaunchView launch(String formCode,String locale){
    validateLocale(locale);
    return cache.get("ui-launch",locale+"|"+formCode,()->{
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
    });
  }


  /** Live database editorial coverage; does not invent translations. */
  @Transactional(readOnly=true)
  public TranslationCoverage coverage(String locale){
    validateLocale(locale);
    Integer categoriesLocalized=jdbc.queryForObject("""
      SELECT COUNT(*) FROM fin_lib.lib_catalog_category_translation
      WHERE locale=?
      """,Integer.class,locale);
    return jdbc.queryForObject("""
      SELECT COUNT(*) AS forms,
        SUM(CASE WHEN t.title IS NOT NULL AND trim(t.title)<>'' THEN 1 ELSE 0 END) AS named,
        SUM(CASE WHEN t.description IS NOT NULL AND trim(t.description)<>'' THEN 1 ELSE 0 END) AS described,
        SUM(CASE WHEN t.purpose IS NOT NULL AND trim(t.purpose)<>'' THEN 1 ELSE 0 END) AS purposeful,
        SUM(CASE WHEN t.title IS NOT NULL AND trim(t.title)<>'' AND
                      t.description IS NOT NULL AND trim(t.description)<>'' AND
                      t.purpose IS NOT NULL AND trim(t.purpose)<>'' THEN 1 ELSE 0 END) AS documented,
        SUM(CASE WHEN f.legacy_capture_mode='TYPED_DRAFT' THEN 1 ELSE 0 END) AS typed,
        SUM(CASE WHEN f.legacy_capture_mode='EVIDENCE_ONLY' THEN 1 ELSE 0 END) AS evidence,
        SUM(CASE WHEN f.legacy_capture_mode='SCHEMA_NOT_APPROVED' THEN 1 ELSE 0 END) AS schema_missing,
        SUM(CASE WHEN v.qualification_state='CANDIDATE_UNQUALIFIED' THEN 1 ELSE 0 END) AS unqualified
      FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      LEFT JOIN fin_lib.lib_form_translation t
        ON t.form_version_id=f.current_version_id AND t.locale=?
      """,(rs,n)->new TranslationCoverage(locale,
         rs.getInt("forms"),47,
         Objects.requireNonNull(categoriesLocalized),
         389,rs.getInt("named"),rs.getInt("described"),rs.getInt("purposeful"),
         rs.getInt("documented"),rs.getInt("typed"),rs.getInt("evidence"),
         rs.getInt("schema_missing"),rs.getInt("unqualified")),locale);
  }

  /** Small 166-form candidate backlog; bounded paging and no private data. */
  @Transactional(readOnly=true)
  public CatalogPaging.Page<TranslationGap> translationGaps(String locale,int page,int size){
    validateLocale(locale);
    CatalogPaging.validateBounds(page,size);
    var missing=jdbc.query("""
      SELECT f.form_code,f.current_version_id,f.legacy_capture_mode,
             v.qualification_state,t.title,t.purpose,t.description
      FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      LEFT JOIN fin_lib.lib_form_translation t
        ON t.form_version_id=f.current_version_id AND t.locale=?
      ORDER BY f.form_code ASC
      """,(rs,n)->{
        String title=rs.getString("title"),
          purpose=rs.getString("purpose"),description=rs.getString("description");
        return new TranslationGap(rs.getString("form_code"),
          (UUID)rs.getObject("current_version_id"),
          rs.getString("legacy_capture_mode"),rs.getString("qualification_state"),
          title==null||title.isBlank(),purpose==null||purpose.isBlank(),
          description==null||description.isBlank());
      },locale).stream().filter(x->x.missingTitle()||x.missingPurpose()||x.missingDescription()).toList();
    return CatalogPaging.page(missing,page,size,"LIST","CODE");
  }

  private static String categoryIcon(CategoryView c){
    return switch(c.categoryCode()){
      case "GROUP_FINANCE" -> "chart-no-axes-combined";
      case "GROUP_LEDGERS" -> "notebook-tabs";
      case "GROUP_PLANNING" -> "calendar-range";
      case "GROUP_DETAIL" -> "layers";
      case "GROUP_SECTOR" -> "factory";
      case "GROUP_EVIDENCE" -> "files";
      case "GROUP_PURPOSE" -> "target";
      default -> switch(c.categoryType()){
        case "DOMAIN" -> "folder-open";
        case "SECTOR" -> "factory";
        case "PURPOSE" -> "target";
        default -> "folder";
      };
    };
  }
  private static String formIcon(FormCard f){
    return switch(f.contentMode()){
      case "SINGLE_RECORD" -> "file-spreadsheet";
      case "ROWSET" -> "table-2";
      case "EVIDENCE" -> "file-check-2";
      default -> "file";
    };
  }
  @Transactional(readOnly=true)
  public CatalogPaging.Page<CategoryTile> pageCategories(String locale,int page,int size,
      String q,String view,String sort){
    String search=CatalogPaging.normalizedSearch(q);
    String order=CatalogPaging.sortMode(sort);
    CatalogPaging.viewMode(view);
    var items=new ArrayList<>(listCategories(locale).categories().stream()
      .filter(c->CatalogPaging.matches(search,c.categoryCode(),c.title(),
        c.purpose(),c.description())).toList());
    if("TITLE".equals(order))items.sort(Comparator.comparing(
      (CategoryView c)->c.title(),String.CASE_INSENSITIVE_ORDER)
      .thenComparing(CategoryView::categoryCode));
    if("CODE".equals(order))items.sort(Comparator.comparing(CategoryView::categoryCode));
    var tiles=items.stream().map(c->new CategoryTile(categoryIcon(c),c)).toList();
    return CatalogPaging.page(tiles,page,size,view,order);
  }
  @Transactional(readOnly=true)
  public CatalogPaging.Page<FormTile> pageForms(String categoryCode,String locale,
      int page,int size,String q,String view,String sort){
    String search=CatalogPaging.normalizedSearch(q);
    String order=CatalogPaging.sortMode(sort);
    CatalogPaging.viewMode(view);
    List<FormCard> source;
    if(categoryCode==null||categoryCode.isBlank()){
      var distinct=new LinkedHashMap<String,FormCard>();
      for(String group:List.of("GROUP_FINANCE","GROUP_LEDGERS","GROUP_PLANNING",
          "GROUP_DETAIL","GROUP_SECTOR","GROUP_EVIDENCE"))
        for(var card:listForms(group,locale).forms())distinct.putIfAbsent(card.formCode(),card);
      if(distinct.size()!=166)throw new IllegalStateException("CATALOG_PAGE_COVERAGE");
      source=List.copyOf(distinct.values());
    }else source=listForms(categoryCode,locale).forms();
    var items=new ArrayList<>(source.stream().filter(f->CatalogPaging.matches(
      search,f.formCode(),f.title(),f.purpose(),f.description())).toList());
    if("TITLE".equals(order))items.sort(Comparator.comparing(
      (FormCard f)->f.title()==null?"":f.title(),String.CASE_INSENSITIVE_ORDER)
      .thenComparing(FormCard::formCode));
    if("CODE".equals(order))items.sort(Comparator.comparing(FormCard::formCode));
    var tiles=items.stream().map(f->new FormTile(formIcon(f),f)).toList();
    return CatalogPaging.page(tiles,page,size,view,order);
  }
}
