package kz.fin.formlib.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.plan.Calc4DTypedDagCompiler;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * CALC-154: seed twelve independent, synthetic OPERATOR-level math examples
 * for all ten existing candidate formulas. Financial meaning is NOT approved.
 *
 * This source is append-only and intentionally distinct from the historical
 * F02-only financial_control_example_revision table (migration 040).
 */
@Component
@Order(14)
public class Calc154MathematicalControlBootstrap implements ApplicationRunner {
  private static final String RESOURCE="catalog/calc154-mathematical-control-v1.tsv";
  private static final String HEADER=String.join("\t",
    "form_code","output_metric_code","formula_version","case_code",
    "source_inputs_json","expected_state","expected_value","source_evidence");

  private record Vector(String form,String output,String formulaVersion,
      String caseCode,String inputsJson,String expectedState,String expectedValue,
      String sourceEvidence,String definitionSha) {}
  private record Formula(UUID id,UUID formVersion,String schema,String binding,
      String operation,String sourceJson,String status,String approval) {}
  private record Stored(UUID id,String inputs,String state,BigDecimal expected,
      String formulaBinding,String definitionSha,String bindingSha,
      String review,String approval) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public Calc154MathematicalControlBootstrap(JdbcTemplate jdbc,ObjectMapper json){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.json=Objects.requireNonNull(json);
  }

  @Override
  @Transactional(rollbackFor=Exception.class)
  public void run(ApplicationArguments args) throws Exception {
    String db=jdbc.execute((ConnectionCallback<String>) c->
      c.getMetaData().getDatabaseProductName());
    if(!"PostgreSQL".equals(db))return;
    List<Vector> vectors=load();
    for(Vector vector:vectors) {
      var found=jdbc.query("""
        SELECT c.id,c.form_version_id,c.source_schema_sha256,c.binding_sha256,
          c.operation_code,c.source_metrics_jsonb::text,
          c.review_state,c.financial_approval_state
        FROM fin_calc.formula_contract_revision c
        JOIN fin_lib.lib_form_definition f ON f.form_code=c.form_code
          AND f.current_version_id=c.form_version_id
        WHERE c.form_code=? AND c.output_metric_code=? AND c.formula_version=?
        """,(r,i)->new Formula(r.getObject(1,UUID.class),
          r.getObject(2,UUID.class),r.getString(3),
          r.getString(4),r.getString(5),r.getString(6),
          r.getString(7),r.getString(8)),
        vector.form(),vector.output(),vector.formulaVersion());
      if(found.size()!=1)throw new IllegalStateException(
        "CALC154_CONTROL_FORMULA_NOT_EXACT:"+vector.output());
      var f=found.getFirst();
      if(!"NOT_S2_APPROVED".equals(f.approval())
          ||!Set.of("UAT_TEST_ONLY","CANDIDATE_UNQUALIFIED").contains(f.status())
          ||!f.binding().matches("[0-9a-f]{64}"))
        throw new IllegalStateException("CALC154_CONTROL_FORMULA_NOT_UAT");

      List<String> declared=new ArrayList<>();
      for(JsonNode item:json.readTree(f.sourceJson())){
        if(!item.isTextual())throw new IllegalStateException("CALC154_CONTROL_SOURCE_TYPE");
        declared.add(item.asText());
      }
      var typed=jdbc.query("""
        SELECT source_metric_code
        FROM fin_calc.formula_typed_input_binding
        WHERE formula_revision_id=? ORDER BY ordinal
        """,(r,i)->r.getString(1),f.id());
      if(!declared.equals(typed))
        throw new IllegalStateException("CALC154_CONTROL_INPUT_BINDING_DRIFT");
      JsonNode inputs=json.readTree(vector.inputsJson());
      if(inputs==null||!inputs.isObject()||inputs.size()!=declared.size())
        throw new IllegalStateException("CALC154_CONTROL_INPUT_SHAPE");
      var values=new ArrayList<List<BigDecimal>>();
      for(String metric:declared){
        JsonNode raw=inputs.get(metric);
        if(raw==null||!raw.isArray()||raw.isEmpty()||raw.size()>1000)
          throw new IllegalStateException("CALC154_CONTROL_INPUT_ARRAY_MISSING:"+metric);
        var decimals=new ArrayList<BigDecimal>();
        for(JsonNode number:raw){
          if(!number.isTextual())throw new IllegalStateException(
            "CALC154_CONTROL_DECIMAL_MUST_BE_STRING");
          BigDecimal value=new BigDecimal(number.asText());
          if(value.precision()>38||value.scale()>12)
            throw new IllegalStateException("CALC154_CONTROL_DECIMAL_OUT_OF_BOUNDS");
          decimals.add(value);
        }
        values.add(List.copyOf(decimals));
      }
      BigDecimal actual=evaluate(f.operation(),values);
      String expectedState=actual==null?"INVALID_ZERO_DENOMINATOR":"PRESENT";
      if(!expectedState.equals(vector.expectedState())
          ||(actual==null&&!vector.expectedValue().isEmpty())
          ||(actual!=null&&(vector.expectedValue().isEmpty()
              ||actual.compareTo(new BigDecimal(vector.expectedValue()))!=0)))
        throw new IllegalStateException(
          "CALC154_CONTROL_ORACLE_MISMATCH:"+vector.caseCode());
      String binding=Calc4DTypedDagCompiler.sha("FIN-CALC154-MATH-CONTROL-BIND-V1|"+
        f.id()+"|"+f.formVersion()+"|"+f.schema()+"|"+f.binding()+
        "|"+vector.definitionSha());
      UUID id=UUID.randomUUID();
      int inserted=jdbc.update("""
        INSERT INTO fin_calc.calc154_synthetic_control_case(
          id,formula_revision_id,form_version_id,source_schema_sha256,
          formula_binding_sha256,case_code,source_inputs_jsonb,
          expected_state,expected_numeric,source_evidence,
          definition_sha256,binding_sha256)
        VALUES(?,?,?,?,?,?,?::jsonb,?,?,?,?,?)
        ON CONFLICT (formula_revision_id,case_code) DO NOTHING
        """,id,f.id(),f.formVersion(),f.schema(),f.binding(),
        vector.caseCode(),vector.inputsJson(),vector.expectedState(),actual,
        vector.sourceEvidence(),vector.definitionSha(),binding);
      var stored=jdbc.query("""
        SELECT id,source_inputs_jsonb::text,expected_state,expected_numeric,
          formula_binding_sha256,definition_sha256,binding_sha256,
          review_state,financial_approval_state
        FROM fin_calc.calc154_synthetic_control_case
        WHERE formula_revision_id=? AND case_code=?
        """,(r,i)->new Stored(r.getObject(1,UUID.class),
          r.getString(2),r.getString(3),r.getBigDecimal(4),
          r.getString(5),r.getString(6),r.getString(7),
          r.getString(8),r.getString(9)),f.id(),vector.caseCode());
      if(stored.size()!=1||!json.readTree(stored.getFirst().inputs())
            .equals(json.readTree(vector.inputsJson()))
          ||!stored.getFirst().state().equals(vector.expectedState())
          ||!Objects.equals(actual==null?null:actual.stripTrailingZeros(),
            stored.getFirst().expected()==null?null:
              stored.getFirst().expected().stripTrailingZeros())
          ||!stored.getFirst().formulaBinding().equals(f.binding())
          ||!stored.getFirst().definitionSha().equals(vector.definitionSha())
          ||!stored.getFirst().bindingSha().equals(binding)
          ||!"UNREVIEWED".equals(stored.getFirst().review())
          ||!"NOT_S2_APPROVED".equals(stored.getFirst().approval())
          ||(inserted==1&&!id.equals(stored.getFirst().id())))
        throw new IllegalStateException("CALC154_CONTROL_IMMUTABLE_REPLAY_DRIFT");
    }
  }

  private List<Vector> load() throws Exception{
    var values=new ArrayList<Vector>();
    var unique=new HashSet<String>();
    var forms=new HashSet<String>();
    try(var input=new BufferedReader(new InputStreamReader(
        new ClassPathResource(RESOURCE).getInputStream(),StandardCharsets.UTF_8))){
      if(!HEADER.equals(input.readLine()))
        throw new IllegalStateException("CALC154_CONTROL_MANIFEST_HEADER_DRIFT");
      String row;
      while((row=input.readLine())!=null){
        if(row.isBlank())continue;
        var fields=row.split("\t",-1);
        if(fields.length!=8||!unique.add(fields[1]+"|"+fields[3])
            ||!fields[1].startsWith(fields[0]+".")
            ||!"SYNTHETIC_OPERATOR_MATH_ONLY_NOT_FINANCIAL_S2".equals(fields[7]))
          throw new IllegalStateException("CALC154_CONTROL_MANIFEST_CASE_DRIFT");
        forms.add(fields[1]);
        values.add(new Vector(fields[0],fields[1],fields[2],fields[3],
          fields[4],fields[5],fields[6],fields[7],
          Calc4DTypedDagCompiler.sha("CALC154-CONTROL-CASE-V1|"+row)));
      }
    }
    if(values.size()!=12||forms.size()!=10)
      throw new IllegalStateException("CALC154_CONTROL_12_CASES_10_FORMULAS_REQUIRED");
    return List.copyOf(values);
  }

  /**
   * Separate elementary reference arithmetic; does not use SQL/JDBC batch
   * executor or trust its computed value. No accounting meaning asserted.
   */
  static BigDecimal evaluate(String op,List<List<BigDecimal>> in){
    var sums=in.stream().map(row->row.stream().reduce(
      BigDecimal.ZERO,BigDecimal::add)).toList();
    return switch(op){
      case "GROUP_BY" -> {
        require(in.size()==1,"GROUP_BY");
        yield sums.getFirst();
      }
      case "RATIO_OF_SUMS" -> {
        require(in.size()==2,"RATIO_OF_SUMS");
        yield sums.get(1).signum()==0?null:
          sums.get(0).divide(sums.get(1),12,RoundingMode.HALF_EVEN);
      }
      case "SUBTRACT" -> {
        require(in.size()==2&&in.stream().allMatch(x->x.size()==1),"SUBTRACT");
        yield in.get(0).getFirst().subtract(in.get(1).getFirst());
      }
      case "RATIO" -> {
        require(in.size()==2&&in.stream().allMatch(x->x.size()==1),"RATIO");
        yield sums.get(1).signum()==0?null:
          sums.get(0).divide(sums.get(1),12,RoundingMode.HALF_EVEN);
      }
      case "RECONCILE" -> {
        require(in.size()==3&&in.stream().allMatch(x->x.size()==1),"RECONCILE");
        yield sums.get(0).subtract(sums.get(1)).subtract(sums.get(2));
      }
      case "ROLL_FORWARD" -> {
        require(in.size()==5&&in.stream().allMatch(x->x.size()==1),"ROLL_FORWARD");
        yield sums.get(0).add(sums.get(1)).add(sums.get(2))
          .add(sums.get(3)).subtract(sums.get(4));
      }
      default -> throw new IllegalStateException("CALC154_CONTROL_UNKNOWN_OPERATOR:"+op);
    };
  }
  private static void require(boolean valid,String operation){
    if(!valid)throw new IllegalStateException("CALC154_CONTROL_ARITY:"+operation);
  }
}
