package kz.fin.formlib.bootstrap;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CALC-3B: pin source-form revisions for catalog-declared model dependencies.
 * DESIGN_ONLY and CALC_PILOT are BOTH non-financial/unqualified. No formula is
 * constructed, no model output is claimed mandatory, no CalcPlan/job is run.
 *
 * One declared input is an EVIDENCE form (D05/CST-07), never numeric.
 */
@Component
@Order(9)
public class Calc3BModelBootstrap implements ApplicationRunner {
  static final String CATALOG_REVISION="CATALOG_CANDIDATE_V1";
  static final String SOURCE="catalog/analytics-model-catalog.tsv";
  static final String HEADER=String.join("\t",
      "modelCode","pack","title","inputFormCodes","outputMetricCodes","qualificationStatus");
  private final JdbcTemplate db;

  public Calc3BModelBootstrap(JdbcTemplate db){this.db=Objects.requireNonNull(db);}
  private record FormVersion(String code,UUID version,String schema,String mode){}
  private record Model(String code,String pack,String sourceState,List<String> inputs,
      List<String> outputs,String definitionSha,String bindingSha){}
  private record Stored(UUID id,String code,String pack,String state,
      String definitionSha,String bindingSha){}

  private Map<String,FormVersion> forms(){
    var found=db.query("""
        SELECT f.form_code,v.id,v.schema_sha256,v.content_mode
        FROM fin_lib.lib_form_definition f
        JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
        """,(r,i)->new FormVersion(r.getString(1),r.getObject(2,UUID.class),
          r.getString(3),r.getString(4)));
    if(found.size()!=166)throw new IllegalStateException("CALC3B_MODEL_FORM_CATALOG_DRIFT");
    Map<String,FormVersion> map=new HashMap<>();
    for(var v:found){
      if(map.putIfAbsent(v.code(),v)!=null
          ||!v.schema().matches("[0-9a-f]{64}"))
        throw new IllegalStateException("CALC3B_MODEL_SOURCE_VERSION_DRIFT:"+v.code());
    }
    return map;
  }

  private List<Model> source(Map<String,FormVersion> forms) throws Exception {
    var models=new ArrayList<Model>();
    var names=new HashSet<String>();
    try(var reader=new BufferedReader(new InputStreamReader(
        new ClassPathResource(SOURCE).getInputStream(),StandardCharsets.UTF_8))){
      if(!HEADER.equals(reader.readLine()))
        throw new IllegalStateException("CALC3B_MODEL_CATALOG_HEADER_DRIFT");
      String line;
      while((line=reader.readLine())!=null){
        if(line.isBlank())continue;
        String[] x=line.split("\t",-1);
        if(x.length!=6 || !names.add(x[0])
            || !Set.of("DESIGN_ONLY","CALC_PILOT").contains(x[5]))
          throw new IllegalStateException("CALC3B_MODEL_CATALOG_ROW_INVALID");
        List<String> inputs=List.of(x[3].split(";",-1));
        List<String> outputs=List.of(x[4].split(";",-1));
        if(inputs.isEmpty() || outputs.isEmpty() || inputs.stream().anyMatch(String::isBlank)
           || outputs.stream().anyMatch(String::isBlank)
           || new HashSet<>(inputs).size()!=inputs.size()
           || new HashSet<>(outputs).size()!=outputs.size())
          throw new IllegalStateException("CALC3B_MODEL_DUPLICATE_OR_EMPTY_DEPENDENCY:"+x[0]);
        String definition=Calc3BRequirementBootstrap.sha(line);
        var digest=new StringBuilder("FIN-CALC3B-MODEL-BIND-V1|")
          .append(x[0]).append('|').append(definition);
        for(int ordinal=0;ordinal<inputs.size();ordinal++){
          String form=inputs.get(ordinal);
          FormVersion v=forms.get(form);
          if(v==null)throw new IllegalStateException("CALC3B_UNKNOWN_MODEL_INPUT:"+form);
          digest.append('|').append(ordinal).append(':').append(v.version())
            .append(':').append(v.schema()).append(':').append(v.mode());
        }
        models.add(new Model(x[0],x[1],x[5],inputs,outputs,
          definition,Calc3BRequirementBootstrap.sha(digest.toString())));
      }
    }
    if(models.size()!=42 || models.stream().mapToInt(x->x.inputs().size()).sum()!=97
        ||models.stream().mapToInt(x->x.outputs().size()).sum()!=46)
      throw new IllegalStateException("CALC3B_MODEL_CATALOG_COVERAGE_DRIFT");
    return List.copyOf(models);
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) throws Exception {
    String database=db.execute((ConnectionCallback<String>) c->
        c.getMetaData().getDatabaseProductName());
    if(!"PostgreSQL".equals(database))return;
    var currentForms=forms();
    var proposed=source(currentForms);
    // Only the original manifest revision is bootstrap-managed. Other
    // independent model revisions are append-only and must coexist.
    Integer existing=db.queryForObject("""
      SELECT count(*) FROM fin_calc.model_candidate_revision
      WHERE catalog_revision=?
      """,Integer.class,CATALOG_REVISION);
    if(existing==null || existing!=0&&existing!=proposed.size())
      throw new IllegalStateException("CALC3B_BASELINE_MODEL_PARTIAL_SEED");

    if(existing==0){
      List<Object[]> values=new ArrayList<>();
      for(var m:proposed)
        values.add(new Object[]{UUID.randomUUID(),m.code(),CATALOG_REVISION,m.pack(),
          m.sourceState(),m.definitionSha(),m.bindingSha()});
      db.batchUpdate("""
          INSERT INTO fin_calc.model_candidate_revision(
            id,model_code,catalog_revision,model_pack,catalog_state,
            formula_state,requirement_state,financial_approval_state,
            definition_sha256,binding_sha256)
          VALUES(?,?,?,?,?,'NOT_AUTHORED','REVIEW_REQUIRED','NOT_S2_APPROVED',?,?)
          """,values);
    }
    var saved=db.query("""
        SELECT id,model_code,model_pack,catalog_state,definition_sha256,binding_sha256
        FROM fin_calc.model_candidate_revision
        WHERE catalog_revision='CATALOG_CANDIDATE_V1'
          AND formula_state='NOT_AUTHORED'
          AND requirement_state='REVIEW_REQUIRED'
          AND financial_approval_state='NOT_S2_APPROVED'
        """,(r,i)->new Stored(r.getObject(1,UUID.class),r.getString(2),
          r.getString(3),r.getString(4),r.getString(5),r.getString(6)));
    if(saved.size()!=42)throw new IllegalStateException("CALC3B_MODEL_REVISION_DRIFT");
    Map<String,Stored> indexed=new HashMap<>();
    for(var item:saved)
      if(indexed.putIfAbsent(item.code(),item)!=null)
        throw new IllegalStateException("CALC3B_MODEL_DUPLICATE_REVISION");

    List<Object[]> expectedInputs=new ArrayList<>();
    List<Object[]> expectedOutputs=new ArrayList<>();
    for(var m:proposed){
      Stored s=indexed.get(m.code());
      if(s==null||!s.pack().equals(m.pack())
          ||!s.state().equals(m.sourceState())
          ||!s.definitionSha().equals(m.definitionSha())
          ||!s.bindingSha().equals(m.bindingSha()))
        throw new IllegalStateException("CALC3B_MODEL_SOURCE_DRIFT:"+m.code());

      for(int n=0;n<m.inputs().size();n++){
        FormVersion v=currentForms.get(m.inputs().get(n));
        String sha=Calc3BRequirementBootstrap.sha("CALC3B-MODEL-INPUT-V1|"+
          s.id()+"|"+n+"|"+v.code()+"|"+v.version()+"|"+v.schema()+"|"+v.mode());
        expectedInputs.add(new Object[]{s.id(),n,v.code(),v.version(),v.schema(),
          v.mode(),!"EVIDENCE".equals(v.mode()),sha});
      }
      for(int n=0;n<m.outputs().size();n++)
        expectedOutputs.add(new Object[]{s.id(),n,m.outputs().get(n)});
    }
    if(expectedInputs.size()!=97||expectedOutputs.size()!=46)
      throw new IllegalStateException("CALC3B_MODEL_EDGES_DRIFT");

    Integer inputCount=db.queryForObject(
      """
      SELECT count(*) FROM fin_calc.model_candidate_input_form i
      JOIN fin_calc.model_candidate_revision m ON m.id=i.model_revision_id
      WHERE m.catalog_revision='CATALOG_CANDIDATE_V1'
      """,Integer.class);
    Integer outputCount=db.queryForObject(
      """
      SELECT count(*) FROM fin_calc.model_candidate_output_metric o
      JOIN fin_calc.model_candidate_revision m ON m.id=o.model_revision_id
      WHERE m.catalog_revision='CATALOG_CANDIDATE_V1'
      """,Integer.class);
    if(inputCount==null||outputCount==null
       ||inputCount!=0&&inputCount!=97||outputCount!=0&&outputCount!=46)
      throw new IllegalStateException("CALC3B_PARTIAL_MODEL_DEPENDENCIES");
    if(inputCount==0){
      db.batchUpdate("""
          INSERT INTO fin_calc.model_candidate_input_form(
            model_revision_id,ordinal,source_form_code,source_form_version_id,
            source_schema_sha256,content_mode,numeric_eligible,
            dependency_state,approval_state,binding_sha256)
          VALUES(?,?,?,?,?,?,?,'MODEL_INPUT_CANDIDATE','NOT_S2_APPROVED',?)
          """,expectedInputs);
    }
    if(outputCount==0){
      db.batchUpdate("""
          INSERT INTO fin_calc.model_candidate_output_metric(
            model_revision_id,ordinal,output_metric_code,formula_state,
            requiredness_state,approval_state)
          VALUES(?,?,?,'NOT_AUTHORED','REVIEW_REQUIRED','NOT_S2_APPROVED')
          """,expectedOutputs);
    }

    var foundInputs=db.query("""
        SELECT model_revision_id,ordinal,source_form_code,source_form_version_id,
          source_schema_sha256,content_mode,numeric_eligible,binding_sha256,
          dependency_state,approval_state
        FROM fin_calc.model_candidate_input_form i
        JOIN fin_calc.model_candidate_revision m ON m.id=i.model_revision_id
        WHERE m.catalog_revision='CATALOG_CANDIDATE_V1'
        """,(r,i)->new Object[]{r.getObject(1,UUID.class),r.getInt(2),r.getString(3),
          r.getObject(4,UUID.class),r.getString(5),r.getString(6),
          r.getBoolean(7),r.getString(8),r.getString(9),r.getString(10)});
    var actualInputs=new HashMap<String,Object[]>();
    for(var x:foundInputs)
      if(actualInputs.putIfAbsent(x[0]+"|"+x[1],x)!=null)
        throw new IllegalStateException("CALC3B_MODEL_INPUT_DUPLICATE");
    if(actualInputs.size()!=97)throw new IllegalStateException("CALC3B_MODEL_INPUT_COUNT");
    for(var e:expectedInputs){
      Object[] a=actualInputs.get(e[0]+"|"+e[1]);
      if(a==null||!Objects.equals(a[2],e[2])||!Objects.equals(a[3],e[3])
          ||!Objects.equals(a[4],e[4])||!Objects.equals(a[5],e[5])
          ||!Objects.equals(a[6],e[6])||!Objects.equals(a[7],e[7])
          ||!"MODEL_INPUT_CANDIDATE".equals(a[8])
          ||!"NOT_S2_APPROVED".equals(a[9]))
        throw new IllegalStateException("CALC3B_MODEL_INPUT_DRIFT:"+e[2]);
    }
    var foundOutputs=db.query("""
        SELECT model_revision_id,ordinal,output_metric_code,formula_state,
          requiredness_state,approval_state
        FROM fin_calc.model_candidate_output_metric o
        JOIN fin_calc.model_candidate_revision m ON m.id=o.model_revision_id
        WHERE m.catalog_revision='CATALOG_CANDIDATE_V1'
        """,(r,i)->new Object[]{r.getObject(1,UUID.class),r.getInt(2),r.getString(3),
          r.getString(4),r.getString(5),r.getString(6)});
    var actualOutputs=new HashMap<String,Object[]>();
    for(var x:foundOutputs)
      if(actualOutputs.putIfAbsent(x[0]+"|"+x[1],x)!=null)
        throw new IllegalStateException("CALC3B_MODEL_OUTPUT_DUPLICATE");
    if(actualOutputs.size()!=46)throw new IllegalStateException("CALC3B_MODEL_OUTPUT_COUNT");
    for(var expected:expectedOutputs){
      Object[] a=actualOutputs.get(expected[0]+"|"+expected[1]);
      if(a==null || !Objects.equals(a[2],expected[2])
          || !"NOT_AUTHORED".equals(a[3]) || !"REVIEW_REQUIRED".equals(a[4])
          || !"NOT_S2_APPROVED".equals(a[5]))
        throw new IllegalStateException("CALC3B_MODEL_OUTPUT_DRIFT:"+expected[2]);
    }
    long evidence=foundInputs.stream().filter(x->"EVIDENCE".equals(x[5])).count();
    if(evidence!=1||foundInputs.stream().anyMatch(x->
        "EVIDENCE".equals(x[5]) && !("D05".equals(x[2]) && !(boolean)x[6])))
      throw new IllegalStateException("CALC3B_NON_NUMERIC_EVIDENCE_DRIFT");
  }
}
