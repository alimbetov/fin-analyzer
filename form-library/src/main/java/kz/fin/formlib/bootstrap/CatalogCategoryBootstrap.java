package kz.fin.formlib.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * R14.A: one atomic load of the 47 navigation categories, 188 four-language
 * category translations, and 389 links to the 166 canonical form identities.
 * This is an editorial DRAFT catalog and does not qualify report schemas.
 */
@Component
@Order(3) // after CatalogBootstrap (1) and ReferenceCatalogBootstrap (2)
public class CatalogCategoryBootstrap implements ApplicationRunner {
  private static final List<String> LOCALES=List.of("ru","kk","en","zh-Hans");
  private static final String CATEGORIES="catalog/catalog-category-i18n-r11.tsv";
  private static final String LINKS="catalog/catalog-category-form-r11.tsv";
  private static final String SEED_CODE="FIN_CATEGORY_R14_V1";
  private final JdbcTemplate jdbc;
  public CatalogCategoryBootstrap(JdbcTemplate jdbc){this.jdbc=jdbc;}

  private static List<String[]> read(String resource,int columns){
    try(var input=new BufferedReader(new InputStreamReader(
      new ClassPathResource(resource).getInputStream(),StandardCharsets.UTF_8))){
      if(input.readLine()==null)throw new IllegalStateException("EMPTY_CATALOG:"+resource);
      List<String[]> result=new ArrayList<>();
      String line;
      while((line=input.readLine())!=null){
        if(line.isBlank())continue;
        String[] fields=line.split("\t",-1);
        if(fields.length!=columns)throw new IllegalStateException("BAD_CATALOG_ROW:"+resource);
        result.add(fields);
      }
      return result;
    }catch(java.io.IOException e){throw new IllegalStateException("CATALOG_RESOURCE_UNAVAILABLE",e);}
  }
  private static String fingerprint(List<String[]> a,List<String[]> b){
    try{
      String canonical="FIN-CATEGORY-SEED-V1\n"+
        a.stream().map(row->String.join("\t",row)).reduce("",(x,y)->x+y+"\n")+
        "--links--\n"+
        b.stream().map(row->String.join("\t",row)).reduce("",(x,y)->x+y+"\n");
      byte[] hash=MessageDigest.getInstance("SHA-256")
        .digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    }catch(Exception e){throw new IllegalStateException(e);}
  }
  private long count(String table){
    return Objects.requireNonNull(jdbc.queryForObject(
      "SELECT COUNT(*) FROM fin_lib."+table,Long.class));
  }

  @Transactional
  @Override
  public void run(ApplicationArguments args){
    var categories=read(CATEGORIES,17);
    var links=read(LINKS,4);
    if(categories.size()!=47||links.size()!=389)
      throw new IllegalStateException("CATALOG_SEED_SOURCE_DRIFT");
    String sha=fingerprint(categories,links);
    long n=count("lib_catalog_category"),t=count("lib_catalog_category_translation"),
      m=count("lib_catalog_category_form"),manifest=count("lib_catalog_seed_manifest");
    if(n!=0||t!=0||m!=0||manifest!=0){
      if(n!=47||t!=188||m!=389||manifest!=1)
        throw new IllegalStateException("CATALOG_SEED_INCOMPLETE");
      String existing=jdbc.queryForObject(
        "SELECT source_sha256 FROM fin_lib.lib_catalog_seed_manifest WHERE catalog_code=?",
        String.class,SEED_CODE);
      if(!sha.equals(existing))throw new IllegalStateException("CATALOG_SEED_VERSION_MISMATCH");
      return;
    }
    if(count("lib_form_definition")!=166)
      throw new IllegalStateException("FORM_DEFINITIONS_NOT_SEEDED");

    var formIds=new HashMap<String,UUID>();
    jdbc.query("SELECT id, form_code FROM fin_lib.lib_form_definition",
      rs->{if(formIds.putIfAbsent(rs.getString("form_code"),
          (UUID)rs.getObject("id"))!=null)throw new IllegalStateException("DUPLICATE_FORM");});
    if(formIds.size()!=166)throw new IllegalStateException("FORM_REFERENCE_DRIFT");

    var categoryCodes=new HashSet<String>();
    var roots=new ArrayList<String[]>();
    var leaves=new ArrayList<String[]>();
    for(var row:categories){
      if(!categoryCodes.add(row[0])||row[0].isBlank()||row[3].isBlank()
        ||!"DRAFT_UNREVIEWED".equals(row[16]))
        throw new IllegalStateException("INVALID_CATEGORY_SEED");
      int order=Integer.parseInt(row[3]);
      if(order<=0)throw new IllegalStateException("INVALID_CATEGORY_ORDER");
      if("ROOT".equals(row[2])&&row[1].isBlank())roots.add(row);
      else if(Set.of("DOMAIN","SECTOR","PURPOSE").contains(row[2])&&!row[1].isBlank())
        leaves.add(row);
      else throw new IllegalStateException("INVALID_CATEGORY_PARENT");
      for(int i=0;i<4;i++)
        if(row[4+i].isBlank()||row[8+i].isBlank()||row[12+i].isBlank())
          throw new IllegalStateException("INCOMPLETE_CATEGORY_TRANSLATIONS");
    }
    if(roots.size()!=7||leaves.size()!=40)throw new IllegalStateException("INVALID_CATEGORY_HIERARCHY");
    for(var leaf:leaves)if(!roots.stream().anyMatch(r->r[0].equals(leaf[1])))
      throw new IllegalStateException("UNKNOWN_CATEGORY_PARENT");
    for(var row:roots)insertCategory(row);
    for(var row:leaves)insertCategory(row);

    for(var row:categories)for(int i=0;i<4;i++){
      jdbc.update("INSERT INTO fin_lib.lib_catalog_category_translation "+
        "(id,category_code,locale,title,purpose,description,review_status) "+
        "VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),row[0],LOCALES.get(i),
        row[4+i],row[8+i],row[12+i],row[16]);
    }

    Set<String> unique=new HashSet<>();
    Map<String,Integer> primary=new HashMap<>();
    for(var row:links){
      UUID id=formIds.get(row[1]);
      if(!categoryCodes.contains(row[0])||id==null
        ||!unique.add(row[0]+"|"+row[1]))throw new IllegalStateException("INVALID_CATEGORY_LINK");
      if("PRIMARY".equals(row[2]))primary.merge(row[1],1,Integer::sum);
      jdbc.update("INSERT INTO fin_lib.lib_catalog_category_form "+
        "(category_code,form_id,association_role,sort_order) VALUES(?,?,?,?)",
        row[0],id,row[2],Integer.parseInt(row[3]));
    }
    if(primary.size()!=166||primary.values().stream().anyMatch(v->v!=1))
      throw new IllegalStateException("INVALID_PRIMARY_COVERAGE");
    jdbc.update("INSERT INTO fin_lib.lib_catalog_seed_manifest "+
      "(catalog_code,source_sha256,seeded_at) VALUES(?,?,?)",SEED_CODE,sha,OffsetDateTime.now(ZoneOffset.UTC));
    if(count("lib_catalog_category")!=47||
      count("lib_catalog_category_translation")!=188||
      count("lib_catalog_category_form")!=389)
      throw new IllegalStateException("CATALOG_POST_SEED_COUNTS_INVALID");
  }
  private void insertCategory(String[] row){
    jdbc.update("INSERT INTO fin_lib.lib_catalog_category "+
      "(code,parent_code,category_type,sort_order,lifecycle_status) VALUES(?,?,?,?,?)",
      row[0],row[1].isBlank()?null:row[1],row[2],
      Integer.parseInt(row[3]),row[16]);
  }
}
