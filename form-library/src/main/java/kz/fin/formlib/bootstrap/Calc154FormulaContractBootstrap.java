package kz.fin.formlib.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CALC-154 CALC-3: source-of-truth TSV -> immutable PostgreSQL formula
 * contract revisions. Executes AFTER R16 field and candidate bootstraps.
 *
 * These ten records are candidates, NOT executable method revisions:
 * this class never writes CalcPlan, method_revision, FactSet, CalcJob or result.
 * Changing a pinned form schema requires explicit contract versioning/review.
 */
@Component
@Order(7)
public class Calc154FormulaContractBootstrap implements ApplicationRunner {
  private static final String RESOURCE="catalog/calc154-formula-contracts-v1.tsv";
  private static final String HEADER=String.join("\t",
      "form_code","output_metric_code","source_metrics","operation",
      "formula_version","status","blocker","period_semantics","dimension_policy",
      "currency_policy","unit_policy","rounding_policy","missing_policy",
      "zero_policy","source_evidence");
  private static final Set<String> OPERATIONS=Set.of(
      "RECONCILE","SUBTRACT","RATIO","ROLL_FORWARD","GROUP_BY","RATIO_OF_SUMS");

  private record Contract(String form,String output,List<String> inputs,String operation,
      String version,String state,String blockers,String period,String dimension,
      String currency,String unit,String rounding,String missing,String zero,
      String evidence,String definitionSha) {}
  private record Version(UUID id,String schema) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public Calc154FormulaContractBootstrap(JdbcTemplate jdbc,ObjectMapper mapper){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.mapper=Objects.requireNonNull(mapper);
  }

  private static String hash(String data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(data.getBytes(StandardCharsets.UTF_8)));
    } catch(Exception ex) {
      throw new IllegalStateException("CALC154_SHA256_UNAVAILABLE",ex);
    }
  }

  private List<Contract> readSource() throws Exception {
    var result=new ArrayList<Contract>();
    var identities=new HashSet<String>();
    try(var reader=new BufferedReader(new InputStreamReader(
        new ClassPathResource(RESOURCE).getInputStream(),StandardCharsets.UTF_8))) {
      if(!HEADER.equals(reader.readLine()))
        throw new IllegalStateException("CALC154_FORMULA_SOURCE_HEADER_DRIFT");
      String line;
      while((line=reader.readLine())!=null){
        if(line.isBlank())continue;
        String[] x=line.split("\t",-1);
        if(x.length!=15||Arrays.stream(x).anyMatch(String::isBlank))
          throw new IllegalStateException("CALC154_FORMULA_SOURCE_COLUMN_DRIFT");
        var inputs=List.of(x[2].split(";",-1));
        if(inputs.size()>16||inputs.isEmpty()||inputs.stream().anyMatch(String::isBlank)
           ||new HashSet<>(inputs).size()!=inputs.size())
          throw new IllegalStateException("CALC154_FORMULA_INPUTS_INVALID");
        if(!x[1].startsWith(x[0]+".")||!OPERATIONS.contains(x[3])
           ||!identities.add(x[0]+"|"+x[1]+"|"+x[4])
           ||inputs.contains(x[1])||"F02.netProfit".equals(x[1]))
          throw new IllegalStateException("CALC154_FORMULA_IDENTITY_INVALID");
        if(!Set.of("CANDIDATE_UNQUALIFIED","UAT_TEST_ONLY").contains(x[5])
           ||("UAT_TEST_ONLY".equals(x[5])
             &&(!"F02".equals(x[0])||!"F02_TEST_ONLY_V1".equals(x[4])))
           ||!x[6].contains("FINANCE_S2")
           ||!Set.of("AS_OF_CANDIDATE","INTERVAL_CANDIDATE","INTERVAL_REQUIRED").contains(x[7])
           ||!"UNREVIEWED".equals(x[8])||!"UNREVIEWED".equals(x[9])
           ||!"UNREVIEWED".equals(x[11])
           ||!"REQUIRE_PRESENT_CANDIDATE".equals(x[12])
           ||(!Set.of("RATIO","RATIO_OF_SUMS").contains(x[3])
             &&!"NOT_APPLICABLE".equals(x[13]))
           ||(Set.of("RATIO","RATIO_OF_SUMS").contains(x[3])
             &&!"INVALID_CANDIDATE".equals(x[13])))
          throw new IllegalStateException("CALC154_FORMULA_QUALIFICATION_BLOCKER_REQUIRED");
        result.add(new Contract(x[0],x[1],inputs,x[3],x[4],x[5],x[6],
            x[7],x[8],x[9],x[10],x[11],x[12],x[13],x[14],hash(line)));
      }
    }
    if(result.size()!=10
       ||result.stream().filter(r->"UAT_TEST_ONLY".equals(r.state())).count()!=3)
      throw new IllegalStateException("CALC154_FORMULA_SOURCE_COUNT_DRIFT");
    return result;
  }

  private Map<String,Version> liveForms(List<Contract> contracts){
    var versions=new HashMap<String,Version>();
    for(var contract:contracts) {
      if(versions.containsKey(contract.form()))continue;
      var found=jdbc.query("""
          SELECT v.id,v.schema_sha256 FROM fin_lib.lib_form_definition f
          JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
          WHERE f.form_code=? AND v.content_mode<>'EVIDENCE'
          """,(r,i)->new Version(r.getObject(1,UUID.class),r.getString(2)),
          contract.form());
      if(found.size()!=1||found.getFirst().schema()==null
          ||!found.getFirst().schema().matches("[0-9a-f]{64}"))
        throw new IllegalStateException("CALC154_FORM_VERSION_MISSING:"+contract.form());
      versions.put(contract.form(),found.getFirst());
    }
    return versions;
  }

  private void validateInputs(List<Contract> contracts,Map<String,Version> versions){
    Map<String,Contract> outputs=new HashMap<>();
    for(var c:contracts)
      if(outputs.putIfAbsent(c.output(),c)!=null)
        throw new IllegalStateException("CALC154_DUPLICATE_OUTPUT");
    Map<String,List<String>> dag=new HashMap<>();
    for(var c:contracts){
      List<String> edges=new ArrayList<>();
      for(var metric:c.inputs()){
        if(!metric.startsWith(c.form()+".")||metric.equals("F02.netProfit"))
          throw new IllegalStateException("CALC154_INPUT_FORM_SCOPE_OR_UNBOUND:"+metric);
        if(outputs.containsKey(metric)){
          edges.add(metric);
          continue;
        }
        var version=versions.get(c.form());
        Integer matched=jdbc.queryForObject("""
          SELECT count(*) FROM fin_lib.lib_metric_definition m
          JOIN fin_lib.lib_form_field ff
            ON ff.form_version_id=? AND ff.field_code=m.source_field_code
          JOIN fin_lib.lib_field_binding_contract bc
            ON bc.form_code=m.form_code AND bc.field_code=ff.field_code
             AND bc.metric_code=m.metric_code
             AND bc.source_schema_sha256=?
             AND bc.review_state='CANDIDATE_UNQUALIFIED'
          WHERE m.metric_code=? AND m.form_code=? AND m.metric_kind='SOURCE_FIELD'
            AND ff.field_type IN ('MONEY','DECIMAL','INTEGER')
          """,Integer.class,version.id(),version.schema(),metric,c.form());
        if(matched==null||matched!=1)
          throw new IllegalStateException("CALC154_FORMULA_SOURCE_NOT_PINNED:"+metric);
      }
      dag.put(c.output(),edges);
    }
    Set<String> visiting=new HashSet<>(),visited=new HashSet<>();
    for(String name:dag.keySet())visit(name,dag,visiting,visited);
  }

  private static void visit(String name,Map<String,List<String>> dag,
      Set<String> visiting,Set<String> visited){
    if(visited.contains(name))return;
    if(!visiting.add(name))throw new IllegalStateException("CALC154_FORMULA_CYCLE:"+name);
    for(String dependency:dag.get(name))visit(dependency,dag,visiting,visited);
    visiting.remove(name);
    visited.add(name);
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) throws Exception{
    String database=jdbc.execute((ConnectionCallback<String>)c->
        c.getMetaData().getDatabaseProductName());
    if(!"PostgreSQL".equals(database))return;
    List<Contract> contracts=readSource();
    var versions=liveForms(contracts);
    validateInputs(contracts,versions);
    Integer count=jdbc.queryForObject(
        "SELECT count(*) FROM fin_calc.formula_contract_revision",Integer.class);
    if(count==null||(count!=0&&count!=contracts.size()))
      throw new IllegalStateException("CALC154_FORMULA_PARTIAL_SEED");

    for(var c:contracts) {
      Version version=versions.get(c.form());
      String bindingSha=hash("FIN-CALC154-FORMULA-BINDING-V1|"+
          version.id()+"|"+version.schema()+"|"+c.definitionSha());
      String json=mapper.writeValueAsString(c.inputs());
      if(count==0){
        jdbc.update("""
          INSERT INTO fin_calc.formula_contract_revision(
            id,form_code,form_version_id,source_schema_sha256,
            output_metric_code,formula_version,operation_code,source_metrics_jsonb,
            period_semantics,dimension_policy,currency_policy,unit_policy,
            rounding_policy,missing_policy,zero_policy,review_state,
            blocker_codes,source_evidence,definition_sha256,binding_sha256)
          VALUES(?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?)
          """,UUID.randomUUID(),c.form(),version.id(),version.schema(),
            c.output(),c.version(),c.operation(),json,c.period(),
            c.dimension(),c.currency(),c.unit(),c.rounding(),c.missing(),
            c.zero(),c.state(),c.blockers(),c.evidence(),c.definitionSha(),bindingSha);
      }
      Integer same=jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.formula_contract_revision
        WHERE form_code=? AND form_version_id=? AND source_schema_sha256=?
          AND output_metric_code=? AND formula_version=? AND operation_code=?
          AND source_metrics_jsonb=?::jsonb
          AND period_semantics=? AND dimension_policy=? AND currency_policy=?
          AND unit_policy=? AND rounding_policy=? AND missing_policy=?
          AND zero_policy=? AND review_state=? AND financial_approval_state='NOT_S2_APPROVED'
          AND blocker_codes=? AND source_evidence=?
          AND definition_sha256=? AND binding_sha256=?
        """,Integer.class,c.form(),version.id(),version.schema(),
          c.output(),c.version(),c.operation(),json,c.period(),
          c.dimension(),c.currency(),c.unit(),c.rounding(),c.missing(),
          c.zero(),c.state(),c.blockers(),c.evidence(),c.definitionSha(),bindingSha);
      if(same==null||same!=1)
        throw new IllegalStateException("CALC154_FORMULA_CONTRACT_DRIFT:"+c.output());
    }
  }
}
