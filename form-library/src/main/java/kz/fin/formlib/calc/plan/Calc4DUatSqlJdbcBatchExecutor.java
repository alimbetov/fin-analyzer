package kz.fin.formlib.calc.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CALC-4D.4: batch, HYBRID SQL/JDBC + Java BigDecimal executor, strictly UAT.
 * One PostgreSQL GROUP BY query is used for all exact FactSet observations;
 * topologically ordered, typed arithmetic is evaluated in Java, avoiding a
 * JDBC roundtrip per scalar operator. Outputs go ONLY to calc4d_uat_result.
 *
 * Financial periods, dimensions, FX and rounding are still UNREVIEWED; the
 * UAT preview is not an approved calculation, job, or current result.
 */
@Service
public class Calc4DUatSqlJdbcBatchExecutor {
  public record Result(UUID resultId,UUID planId,String resultSha256,
      String outputValuesSha256,int outputRows,boolean replayed,
      boolean financiallyApproved,String status) {}
  private record Aggregate(String key,String metric,String digest,String dimensions,
      String unit,String unitMax,long count,BigDecimal sum,String dimsMax) {}
  private record Value(BigDecimal number,String dimensions,String unit,
      long count,String status) {}
  private record Previous(UUID id,String resultSha,String outputSha,
      String inputSha,String outputJson,String state) {}

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final ObjectMapper json;
  private final Calc4DUatPlanStore store;
  private final Calc4DTypedDagCompiler compiler;
  private final Calc4DExactFactSetInputResolver resolver;

  public Calc4DUatSqlJdbcBatchExecutor(JdbcTemplate jdbc,ObjectMapper json,
      Calc4DUatPlanStore store,Calc4DTypedDagCompiler compiler,
      Calc4DExactFactSetInputResolver resolver){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.named=new NamedParameterJdbcTemplate(jdbc);
    this.json=Objects.requireNonNull(json);
    this.store=Objects.requireNonNull(store);
    this.compiler=Objects.requireNonNull(compiler);
    this.resolver=Objects.requireNonNull(resolver);
  }

  @Transactional(isolation=Isolation.REPEATABLE_READ,rollbackFor=Exception.class)
  public Result executeExact(UUID organizationId,UUID exactUatPlanId)throws Exception{
    // Obtains FOR SHARE locks on active sources, re-hashes every source
    // revision and observation, then validates the persisted fingerprint.
    var plan=store.requireCurrent(organizationId,exactUatPlanId);
    var graph=compiler.compile(plan.exactFormulas());
    if(graph.executable()||!graph.dagSha256().equals(plan.dagSha256()))
      throw new IllegalStateException("CALC4D_UAT_DAG_CHANGED");
    var snapshots=new ArrayList<Calc4DExactFactSetInputResolver.Snapshot>();
    for(var id:plan.exactFactsets())snapshots.add(resolver.resolve(organizationId,id));
    snapshots.sort(Comparator.comparing(x->x.factsetId().toString()));
    StringBuilder sourceSignature=new StringBuilder("FIN-CALC4D-UAT-EXEC-SOURCE-V1|")
      .append(plan.fingerprint());
    int observationCount=0;
    for(var snapshot:snapshots){
      sourceSignature.append('|').append(snapshot.factsetId())
        .append(':').append(snapshot.observationSha256())
        .append(':').append(snapshot.qualificationEpoch());
      observationCount+=snapshot.observations().size();
    }
    String inputSha=Calc4DTypedDagCompiler.sha(sourceSignature.toString());

    // SQL performs all set-based aggregation in ONE roundtrip across all
    // relevant source rows, using bound exact UUIDs (never client SQL text).
    var aggregates=named.query("""
      SELECT o.source_form_version_id::text || '|' || o.metric_code,
        o.metric_code,o.dimension_digest,
        min(o.dimensions_jsonb::text),min(o.unit_code),max(o.unit_code),
        count(*),sum(o.numeric_value),max(o.dimensions_jsonb::text)
      FROM fin_fact.fact_observation o
      WHERE o.factset_id IN (:exactFactsets)
      GROUP BY o.source_form_version_id,o.metric_code,o.dimension_digest
      ORDER BY o.source_form_version_id,o.metric_code,o.dimension_digest
      """,new MapSqlParameterSource("exactFactsets",plan.exactFactsets()),
      (r,i)->new Aggregate(r.getString(1),r.getString(2),r.getString(3).trim(),
        r.getString(4),r.getString(5),r.getString(6),r.getLong(7),
        r.getBigDecimal(8),r.getString(9)));
    var numeric=new HashMap<String,Map<String,Value>>();
    long returnedCount=0;
    for(var a:aggregates){
      returnedCount+=a.count();
      if(!a.unit().equals(a.unitMax())
          ||!a.dimensions().equals(a.dimsMax())
          ||!a.digest().matches("[0-9a-f]{64}")
          ||a.sum()==null)
        throw new IllegalStateException("CALC4D_UAT_MIXED_DIMENSION_OR_UNIT");
      var grouped=numeric.computeIfAbsent(a.key(),k->new LinkedHashMap<>());
      if(grouped.putIfAbsent(a.digest(),
            new Value(a.sum(),a.dimensions(),a.unit(),a.count(),"PRESENT"))!=null)
        throw new IllegalStateException("CALC4D_UAT_DUPLICATE_AGGREGATE");
    }
    if(returnedCount!=observationCount)
      throw new IllegalStateException("CALC4D_UAT_BATCH_OBSERVATION_COUNT_CHANGED");

    // Every declared scalar source must contain exactly one observation;
    // multiple rows never silently collapse into SUM.
    var outputsByFormula=new HashMap<UUID,Map<String,Value>>();
    var outputRows=new ArrayList<Map<String,Object>>();
    for(var node:graph.orderedNodes()){
      var inputs=new ArrayList<Map<String,Value>>();
      for(var source:node.inputs()){
        Map<String,Value> values;
        if("PHYSICAL_NUMERIC_FIELD".equals(source.sourceKind())){
          values=numeric.get(source.formVersionId()+"|"+source.metricCode());
        }else if("FORMULA_CANDIDATE".equals(source.sourceKind())){
          values=outputsByFormula.get(source.formulaRevisionId());
        }else throw new IllegalStateException("CALC4D_UAT_UNKNOWN_INPUT_KIND");
        if(values==null||values.isEmpty())
          throw new IllegalStateException("CALC4D_UAT_REQUIRED_INPUT_ABSENT:"+source.metricCode());
        inputs.add(values);
      }
      var results=evaluate(node.operation(),inputs);
      if(results.isEmpty())
        throw new IllegalStateException("CALC4D_UAT_NO_OUTPUT");
      outputsByFormula.put(node.formulaRevisionId(),results);
      for(var entry:results.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()){
        var v=entry.getValue();
        var item=new LinkedHashMap<String,Object>();
        item.put("formulaRevisionId",node.formulaRevisionId().toString());
        item.put("formCode",node.formCode());
        item.put("outputMetricCode",node.outputMetricCode());
        item.put("operation",node.operation());
        item.put("dimensionDigest",entry.getKey());
        item.put("dimensionsJson",v.dimensions());
        item.put("unitCode",v.unit());
        item.put("observationCount",v.count());
        item.put("valueState",v.status());
        item.put("numericValue",v.number()==null?null:v.number().toPlainString());
        item.put("financiallyApproved",false);
        outputRows.add(item);
      }
    }
    if(outputRows.size()>50000)
      throw new IllegalStateException("CALC4D_UAT_RESULT_OUTPUT_LIMIT");
    String outputJson=json.writeValueAsString(outputRows);
    String outputSha=Calc4DTypedDagCompiler.sha("FIN-CALC4D-UAT-OUTPUTS-V1|"+outputJson);
    String resultSha=Calc4DTypedDagCompiler.sha("FIN-CALC4D-UAT-RESULT-V1|"+
      plan.fingerprint()+"|"+inputSha+"|"+outputSha);
    UUID newId=UUID.randomUUID();
    jdbc.update("""
      INSERT INTO fin_calc.calc4d_uat_result(
        id,plan_id,organization_id,execution_fingerprint,
        input_observations_sha256,output_values_sha256,
        result_sha256,output_jsonb)
      VALUES(?,?,?,?,?,?,?,?::jsonb)
      ON CONFLICT (plan_id) DO NOTHING
      """,newId,plan.id(),organizationId,plan.fingerprint(),inputSha,
        outputSha,resultSha,outputJson);
    var current=jdbc.query("""
      SELECT id,result_sha256,output_values_sha256,
        input_observations_sha256,output_jsonb::text,result_state
      FROM fin_calc.calc4d_uat_result
      WHERE plan_id=? AND organization_id=?
      """,(r,i)->new Previous(r.getObject(1,UUID.class),r.getString(2).trim(),
        r.getString(3).trim(),r.getString(4).trim(),r.getString(5),r.getString(6)),
      plan.id(),organizationId);
    if(current.size()!=1||!resultSha.equals(current.getFirst().resultSha())
        ||!inputSha.equals(current.getFirst().inputSha())
        ||!outputSha.equals(current.getFirst().outputSha())
        ||!json.readTree(outputJson).equals(json.readTree(current.getFirst().outputJson()))
        ||!"UAT_SYNTHETIC_ONLY".equals(current.getFirst().state()))
      throw new IllegalStateException("CALC4D_UAT_RESULT_REPLAY_DRIFT");
    return new Result(current.getFirst().id(),plan.id(),resultSha,
      outputSha,outputRows.size(),!newId.equals(current.getFirst().id()),
      false,"UAT_SYNTHETIC_ONLY");
  }

  private static Map<String,Value> evaluate(String operation,
      List<Map<String,Value>> inputs){
    if("GROUP_BY".equals(operation)){
      if(inputs.size()!=1)throw new IllegalStateException("CALC4D_GROUP_ARITY");
      // The SQL aggregation above already produced each dimension group.
      return inputs.getFirst();
    }
    if("RATIO_OF_SUMS".equals(operation)){
      if(inputs.size()!=2||!inputs.get(0).keySet().equals(inputs.get(1).keySet()))
        throw new IllegalStateException("CALC4D_RATIO_DIMENSION_MISMATCH");
      var ratios=new LinkedHashMap<String,Value>();
      for(String dimension:inputs.getFirst().keySet().stream().sorted().toList()){
        Value num=inputs.get(0).get(dimension),den=inputs.get(1).get(dimension);
        requireSameDimensionAndUnit(num,den);
        BigDecimal value=den.number().signum()==0?null:
          num.number().divide(den.number(),12,RoundingMode.HALF_EVEN);
        ratios.put(dimension,new Value(value,num.dimensions(),"RATIO",
          Math.max(num.count(),den.count()),
          value==null?"INVALID_ZERO_DENOMINATOR":"PRESENT"));
      }
      return ratios;
    }
    int arity=switch(operation){
      case "SUBTRACT","RATIO"->2;
      case "RECONCILE"->3;
      case "ROLL_FORWARD"->5;
      default->throw new IllegalStateException("CALC4D_UAT_OPERATOR_NOT_SUPPORTED:"+operation);
    };
    if(inputs.size()!=arity)
      throw new IllegalStateException("CALC4D_UAT_ARITY_MISMATCH");
    var scalars=new ArrayList<Value>();
    String digest=null;
    for(var values:inputs){
      if(values.size()!=1)
        throw new IllegalStateException("CALC4D_SCALAR_AMBIGUOUS_GRAIN");
      String onlyDigest=values.keySet().iterator().next();
      Value value=values.get(onlyDigest);
      if(value.count()!=1)
        throw new IllegalStateException("CALC4D_SCALAR_SOURCE_REQUIRES_ONE_ROW");
      if(digest!=null&&!digest.equals(onlyDigest))
        throw new IllegalStateException("CALC4D_SCALAR_DIMENSION_MISMATCH");
      digest=onlyDigest;
      scalars.add(value);
    }
    for(int i=1;i<scalars.size();i++)
      requireSameDimensionAndUnit(scalars.getFirst(),scalars.get(i));
    BigDecimal result=null;
    boolean invalid=scalars.stream().anyMatch(v->!"PRESENT".equals(v.status())
      ||v.number()==null);
    if(!invalid){
      var n=scalars.stream().map(Value::number).toList();
      result=switch(operation){
        case "SUBTRACT" -> n.get(0).subtract(n.get(1));
        case "RATIO" -> n.get(1).signum()==0?null:
          n.get(0).divide(n.get(1),12,RoundingMode.HALF_EVEN);
        case "RECONCILE" -> n.get(0).subtract(n.get(1)).subtract(n.get(2));
        case "ROLL_FORWARD" -> n.get(0).add(n.get(1)).add(n.get(2))
          .add(n.get(3)).subtract(n.get(4));
        default -> throw new IllegalStateException("CALC4D_UAT_OP_INCONSISTENT");
      };
    }
    String status=invalid?"INVALID_DEPENDENCY":
      result==null?"INVALID_ZERO_DENOMINATOR":"PRESENT";
    var first=scalars.getFirst();
    return Map.of(digest,new Value(result,first.dimensions(),
      "RATIO".equals(operation)?"RATIO":first.unit(),1,status));
  }

  private static void requireSameDimensionAndUnit(Value a,Value b){
    if(!a.dimensions().equals(b.dimensions())||!a.unit().equals(b.unit()))
      throw new IllegalStateException("CALC4D_UAT_UNIT_OR_DIMENSIONS_MISMATCH");
  }
}
