package kz.fin.formlib.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * R15: 166 distinct form codes x four languages = 664 provisional title /
 * purpose / description records in fin_lib, without mutating any pinned
 * immutable FormVersion or any fin_intake report revision.
 *
 * Input contains individually localized per-form titles. Explanatory text is
 * generated conservatively from those titles and the already versioned source
 * field contract. Every candidate is marked AUTO_DRAFT_REVIEW_REQUIRED.
 */
@Component
@Order(4)
public class FormEditorialDraftBootstrap implements ApplicationRunner {
  private static final List<String> LOCALES=List.of("ru","kk","en","zh-Hans");
  private static final List<String> TITLE_SOURCES=List.of(
    "catalog/editorial-titles-finance-r15.tsv",
    "catalog/editorial-titles-details-r15.tsv",
    "catalog/editorial-titles-sector-r15.tsv");
  private static final String SOURCE_FORMS="catalog/form-compatibility.tsv";
  private static final String SEED_CODE="FORM_EDITORIAL_R15_V1";
  private static final String STATUS="AUTO_DRAFT_REVIEW_REQUIRED";
  private final JdbcTemplate jdbc;
  public FormEditorialDraftBootstrap(JdbcTemplate jdbc){this.jdbc=jdbc;}

  private record Source(String code,String catalog,String grain,
      String contract,String aggregateRoot,String evidence) {}
  private record Draft(String code,String locale,String title,String purpose,
      String description,String sourceContractSha256) {}

  private static List<String[]> read(String resource,int cols){
    try(var input=new BufferedReader(new InputStreamReader(
        new ClassPathResource(resource).getInputStream(),StandardCharsets.UTF_8))){
      if(input.readLine()==null)throw new IllegalStateException("EMPTY_EDITORIAL_RESOURCE:"+resource);
      var out=new ArrayList<String[]>();
      String line;
      while((line=input.readLine())!=null){
        if(line.isBlank())continue;
        var parts=line.split("\t",-1);
        if(parts.length!=cols)throw new IllegalStateException("BAD_EDITORIAL_RESOURCE:"+resource);
        out.add(parts);
      }
      return out;
    }catch(java.io.IOException e){throw new IllegalStateException("EDITORIAL_RESOURCE_MISSING",e);}
  }
  private static String sha(String input){
    try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
      .digest(input.getBytes(StandardCharsets.UTF_8)));}
    catch(Exception e){throw new IllegalStateException(e);}
  }
  private long count(String table){
    return Objects.requireNonNull(jdbc.queryForObject(
      "SELECT COUNT(*) FROM fin_lib."+table,Long.class));
  }
  private static String sourceFields(Source source){
    if(!"TYPED_FIELD_HINTS".equals(source.evidence()))return "";
    return Arrays.stream(source.contract().split(";"))
      .map(x->x.split(":",-1)[0]).collect(java.util.stream.Collectors.joining(", "));
  }
  private static String purpose(int locale,String title,Source source){
    boolean document="DOCUMENT".equals(source.catalog())
      ||"DOCUMENT".equals(source.evidence())
      ||source.code().startsWith("D0")||source.code().startsWith("T0");
    boolean forecast=source.code().startsWith("P0");
    return switch(locale){
      case 0 -> (document?"Предоставить и проверить подтверждающие материалы: "
          :forecast?"Планировать и оценивать показатели: "
          :"Учитывать и анализировать показатели: ")+title+".";
      case 1 -> (document?"Растайтын материалдарды ұсыну және тексеру: "
          :forecast?"Көрсеткіштерді жоспарлау және бағалау: "
          :"Көрсеткіштерді есепке алу және талдау: ")+title+".";
      case 2 -> (document?"Provide and verify supporting evidence: "
          :forecast?"Plan and evaluate performance: "
          :"Record and analyze performance: ")+title+".";
      case 3 -> (document?"用于提供并核验相关证明材料："
          :forecast?"用于规划和评估指标："
          :"用于记录和分析指标：")+title+"。";
      default -> throw new IllegalArgumentException("locale");
    };
  }
  private static String description(int locale,String title,Source source){
    String fields=sourceFields(source);
    if(!fields.isBlank()){
      return switch(locale){
        case 0 -> "Данные формы «"+title+"». Исходные поля: "+fields+
          ". Разрез записи: "+source.grain()+".";
        case 1 -> "«"+title+"» нысанының деректері. Бастапқы өрістер: "+fields+
          ". Есеп бөлінісі: "+source.grain()+".";
        case 2 -> "Source data for "+title+". Field codes: "+fields+
          ". Record grain: "+source.grain()+".";
        case 3 -> title+"的源数据。字段代码："+fields+"。记录粒度："+source.grain()+"。";
        default -> throw new IllegalArgumentException("locale");
      };
    }
    return switch(locale){
      case 0 -> "Исходное описание состава формы: "+source.contract()+".";
      case 1 -> "«"+title+"» нысанының деректері. Дереккөз құрылымы: "+
        source.aggregateRoot()+"; есептік кезең: "+source.grain()+
        ". Толық түсіндірме редакциялық тексеруді қажет етеді.";
      case 2 -> "Information covered by "+title+". Source model: "+
        source.aggregateRoot()+"; reporting grain: "+source.grain()+
        ". Detailed wording requires editorial review.";
      case 3 -> title+"相关信息。源模型："+source.aggregateRoot()+"；报告粒度："+source.grain()+
        "。详细说明尚待编辑审校。";
      default -> throw new IllegalArgumentException("locale");
    };
  }
  private static void checkText(String text,int max,String code,String field){
    if(text==null||text.isBlank()||text.length()>max)
      throw new IllegalStateException("INVALID_EDITORIAL_"+field+":"+code);
  }
  private static List<Draft> build(){
    var sources=new TreeMap<String,Source>();
    for(var row:read(SOURCE_FORMS,8)){
      Source source=new Source(row[0],row[2],row[4],row[5],row[3],row[6]);
      if(sources.putIfAbsent(row[0],source)!=null)
        throw new IllegalStateException("DUPLICATE_FORM_SOURCE:"+row[0]);
    }
    if(sources.size()!=166)throw new IllegalStateException("FORM_SOURCE_COUNT_DRIFT");
    var titles=new TreeMap<String,String[]>();
    for(String path:TITLE_SOURCES)for(var row:read(path,5)){
      for(int i=1;i<5;i++)checkText(row[i],255,row[0],"TITLE");
      if(titles.putIfAbsent(row[0],row)!=null)
        throw new IllegalStateException("DUPLICATE_EDITORIAL_FORM:"+row[0]);
    }
    if(titles.size()!=166||!titles.keySet().equals(sources.keySet()))
      throw new IllegalStateException("EDITORIAL_166_COVERAGE_INCOMPLETE");
    var result=new ArrayList<Draft>(664);
    for(var sourceEntry:sources.entrySet()){
      Source source=sourceEntry.getValue();
      String[] row=titles.get(source.code());
      String sourceSha=sha("FIN-EDITORIAL-SOURCE-V1|"+String.join("|",
        source.code(),source.aggregateRoot(),source.grain(),source.contract(),source.evidence()));
      for(int locale=0;locale<4;locale++){
        String title=row[locale+1],p=purpose(locale,title,source),d=description(locale,title,source);
        checkText(p,1000,source.code(),"PURPOSE");
        checkText(d,1500,source.code(),"DESCRIPTION");
        result.add(new Draft(source.code(),LOCALES.get(locale),title,p,d,sourceSha));
      }
    }
    if(result.size()!=664)throw new IllegalStateException("EDITORIAL_664_COVERAGE_INCOMPLETE");
    return List.copyOf(result);
  }

  @Transactional
  @Override
  public void run(ApplicationArguments args){
    List<Draft> drafts=build();
    String digest=sha("FIN-EDITORIAL-R15-V1\n"+drafts.stream()
      .map(d->String.join("\t",d.code(),d.locale(),d.title(),d.purpose(),
        d.description(),d.sourceContractSha256()))
      .collect(java.util.stream.Collectors.joining("\n")));
    if(count("lib_form_definition")!=166)
      throw new IllegalStateException("CANONICAL_FORMS_NOT_READY");
    long present=count("lib_form_editorial_draft");
    long manifest=count("lib_form_editorial_seed");
    if(present>0||manifest>0){
      if(present!=664||manifest!=1)
        throw new IllegalStateException("EDITORIAL_SEED_PARTIAL");
      String stored=jdbc.queryForObject(
        "SELECT source_sha256 FROM fin_lib.lib_form_editorial_seed WHERE seed_code=?",
        String.class,SEED_CODE);
      if(!digest.equals(stored))
        throw new IllegalStateException("EDITORIAL_SEED_SOURCE_CHANGED");
      return;
    }
    final String sql="INSERT INTO fin_lib.lib_form_editorial_draft "+
      "(form_code,locale,title,purpose,description,review_status,source_contract_sha256) "+
      "VALUES(?,?,?,?,?,?,?)";
    jdbc.batchUpdate(sql,new BatchPreparedStatementSetter(){
      @Override public int getBatchSize(){return drafts.size();}
      @Override public void setValues(PreparedStatement ps,int i)throws SQLException{
        Draft d=drafts.get(i);
        ps.setString(1,d.code());ps.setString(2,d.locale());
        ps.setString(3,d.title());ps.setString(4,d.purpose());
        ps.setString(5,d.description());ps.setString(6,STATUS);
        ps.setString(7,d.sourceContractSha256());
      }
    });
    jdbc.update("INSERT INTO fin_lib.lib_form_editorial_seed "+
      "(seed_code,source_sha256,seeded_at) VALUES(?,?,?)",
      SEED_CODE,digest,Timestamp.from(Instant.now()));
    if(count("lib_form_editorial_draft")!=664||
      count("lib_form_editorial_seed")!=1)
      throw new IllegalStateException("EDITORIAL_SEED_POSTCONDITION");
  }
}
