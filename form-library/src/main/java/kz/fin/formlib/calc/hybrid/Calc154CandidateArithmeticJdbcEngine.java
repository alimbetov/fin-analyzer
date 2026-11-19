package kz.fin.formlib.calc.hybrid;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CALC-4A (CANDIDATE ONLY): compile immutable formula/typed-input contracts to
 * parameterized native PostgreSQL numeric SQL. Does not write fin_intake,
 * fin_fact, CalcPlan, CalcJob or CalcResult; does not grant financial S2.
 *
 * The initial pilot is five existing arithmetic candidates on F01/F02/F03,
 * not a claim that 154 forms have approved financial formulas.
 * GROUP_BY/RATIO_OF_SUMS remain delegated to plan-bound observation readers.
 *
 * No SQL identifier, operator or expression originates from an input value:
 * only code-generated vN/nN aliases and a fixed operation whitelist appear in
 * SQL. Source metrics are exact, typed, pinned contract identities.
 */
@Service
public class Calc154CandidateArithmeticJdbcEngine {
  private static final Map<String,String> PILOT_VERSIONS = Map.of(
      "F01","F01_CANDIDATE_V1","F02","F02_TEST_ONLY_V1",
      "F03","F03_CANDIDATE_V1");
  private static final Map<String,Set<String>> PILOT_OUTPUTS = Map.of(
      "F01",Set.of("F01.balanceResidual"),
      "F02",Set.of("F02.grossProfit","F02.grossMargin","F02.operatingResult"),
      "F03",Set.of("F03.cashResidual"));

  public record Computed(String metricCode,String status,BigDecimal value) {}
  public record CandidatePreview(String formCode,UUID formVersionId,
      String schemaSha256,String compilationSha256,String inputSha256,
      List<Computed> outputs,boolean financiallyApproved) {
    public CandidatePreview { outputs=List.copyOf(outputs); }
  }
  public record Compiled(UUID formVersionId,String schemaSha256,String sql,
      String compilationSha256,List<String> physicalInputCodes,
      List<String> outputCodes,boolean financiallyApproved) {
    public Compiled {
      physicalInputCodes=List.copyOf(physicalInputCodes);
      outputCodes=List.copyOf(outputCodes);
    }
  }
  private record Row(UUID id,String form,String metric,String version,String op,
      String review,String financial,String schema,UUID formVersion,
      String definitionSha,String bindingSha,String period,String missing,String zero) {}
  private record Source(UUID formulaId,int ordinal,String metric,String kind,
      UUID version,String schema,String fieldType,UUID dependencyId,
      String period,String requiredness) {}
  private record Node(Row formula,List<String> sources) {}
  private record Graph(String form,UUID version,String schema,List<Node> order,
      List<String> physical,List<String> outputs) {}

  private final JdbcTemplate jdbc;
  public Calc154CandidateArithmeticJdbcEngine(JdbcTemplate jdbc){
    this.jdbc=Objects.requireNonNull(jdbc);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public CandidatePreview preview(String formCode,UUID exactFormVersion,
      Map<String,BigDecimal> inputValues) {
    Compiled plan=compile(formCode,exactFormVersion);
    if(inputValues==null||!inputValues.keySet().equals(
        new HashSet<>(plan.physicalInputCodes())))
      throw new IllegalArgumentException("CALC154_CANDIDATE_EXACT_INPUT_SET_REQUIRED");
    var args=new ArrayList<Object>();
    var snapshot=new StringBuilder("CALC154-CANDIDATE-INPUT-V1|")
        .append(plan.compilationSha256());
    for(String code:plan.physicalInputCodes()){
      BigDecimal value=inputValues.get(code);
      if(value==null||value.scale()>12||value.precision()>38)
        throw new IllegalArgumentException("CALC154_CANDIDATE_NUMERIC_PRECISION_INVALID:"+code);
      args.add(value);
      snapshot.append('|').append(code).append('=').append(value.toPlainString());
    }
    var rows=jdbc.query(plan.sql(),(rs,idx)->{
      var values=new ArrayList<Computed>();
      for(int i=0;i<plan.outputCodes().size();i++){
        BigDecimal v=rs.getBigDecimal(i+1);
        values.add(new Computed(plan.outputCodes().get(i),
          v==null?"INVALID":"PRESENT",v));
      }
      return values;
    },args.toArray());
    if(rows.size()!=1)
      throw new IllegalStateException("CALC154_CANDIDATE_RESULT_SHAPE");
    return new CandidatePreview(formCode,plan.formVersionId(),plan.schemaSha256(),
      plan.compilationSha256(),sha(snapshot.toString()),rows.getFirst(),false);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Compiled compile(String formCode,UUID exactFormVersion) {
    Graph graph=loadGraph(formCode,exactFormVersion);
    var inputAliases=new HashMap<String,String>();
    var selectInputs=new ArrayList<String>();
    for(int i=0;i<graph.physical().size();i++){
      String alias="v"+i;
      inputAliases.put(graph.physical().get(i),alias);
      selectInputs.add("?::numeric AS "+alias);
    }
    var ctes=new ArrayList<String>();
    ctes.add("src AS (SELECT "+String.join(", ",selectInputs)+")");
    Map<String,String> values=new HashMap<>(inputAliases);
    List<String> outputs=new ArrayList<>();
    var canonical=new StringBuilder("FIN-CALC154-CANDIDATE-SQL-V1|")
        .append(graph.form()).append('|').append(graph.version())
        .append('|').append(graph.schema());
    String stage="src";
    int ordinal=0;
    for(Node node:graph.order()){
      Row f=node.formula();
      var refs=new ArrayList<String>();
      for(String source:node.sources()){
        String alias=values.get(source);
        if(alias==null)throw new IllegalStateException("CALC154_DAG_SOURCE_UNRESOLVED");
        refs.add("p."+alias);
      }
      String expression=switch(f.op()){
        case "SUBTRACT" -> {
          requireArity(refs,2,"SUBTRACT");
          yield "("+refs.get(0)+" - "+refs.get(1)+")";
        }
        case "RATIO" -> {
          requireArity(refs,2,"RATIO");
          yield "(CASE WHEN "+refs.get(1)+" = 0 THEN NULL::numeric ELSE "+
            refs.get(0)+" / "+refs.get(1)+" END)";
        }
        case "RECONCILE" -> {
          requireArity(refs,3,"RECONCILE");
          yield "("+refs.get(0)+" - "+refs.get(1)+" - "+refs.get(2)+")";
        }
        case "ROLL_FORWARD" -> {
          requireArity(refs,5,"ROLL_FORWARD");
          yield "("+refs.get(0)+" + "+refs.get(1)+" + "+refs.get(2)+
            " + "+refs.get(3)+" - "+refs.get(4)+")";
        }
        default -> throw new IllegalStateException("CALC154_OPERATION_NOT_COMPILED:"+f.op());
      };
      String alias="n"+ordinal, next="step"+ordinal;
      ctes.add(next+" AS (SELECT p.*, "+expression+" AS "+alias+
        " FROM "+stage+" p)");
      stage=next;
      values.put(f.metric(),alias);
      outputs.add(f.metric());
      canonical.append('|').append(f.id()).append(':').append(f.definitionSha())
        .append(':').append(f.bindingSha()).append(':').append(f.op())
        .append(':').append(String.join(",",node.sources()));
      ordinal++;
    }
    var selected=new ArrayList<String>();
    for(Node node:graph.order())selected.add("p."+values.get(node.formula().metric()));
    String sql="WITH "+String.join(", ",ctes)+
        " SELECT "+String.join(", ",selected)+" FROM "+stage+" p";
    canonical.append('|').append(String.join(",",graph.physical()))
        .append('|').append(sql);
    return new Compiled(graph.version(),graph.schema(),sql,sha(canonical.toString()),
      graph.physical(),outputs,false);
  }

  private Graph loadGraph(String formCode,UUID exactVersion){
    if(!PILOT_VERSIONS.containsKey(formCode)||exactVersion==null)
      throw new IllegalArgumentException("CALC154_ARITHMETIC_PILOT_ONLY");
    var forms=jdbc.query("""
      SELECT v.schema_sha256 FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      WHERE f.form_code=? AND v.id=?
      """,(r,i)->r.getString(1),formCode,exactVersion);
    if(forms.size()!=1||!forms.getFirst().matches("[0-9a-f]{64}"))
      throw new IllegalStateException("CALC154_FORM_VERSION_NOT_CURRENT");
    String schema=forms.getFirst();
    var formulas=jdbc.query("""
      SELECT c.id,c.form_code,c.output_metric_code,c.formula_version,
        c.operation_code,c.review_state,c.financial_approval_state,
        c.source_schema_sha256,c.form_version_id,c.definition_sha256,
        c.binding_sha256,c.period_semantics,c.missing_policy,c.zero_policy
      FROM fin_calc.formula_contract_revision c
      WHERE c.form_code=? AND c.form_version_id=?
        AND c.formula_version=?
      ORDER BY c.output_metric_code
      """,(rs,i)->new Row(rs.getObject(1,UUID.class),rs.getString(2),
          rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),
          rs.getString(7),rs.getString(8),rs.getObject(9,UUID.class),
          rs.getString(10),rs.getString(11),rs.getString(12),
          rs.getString(13),rs.getString(14)),
        formCode,exactVersion,PILOT_VERSIONS.get(formCode));
    if(formulas.size()!=PILOT_OUTPUTS.get(formCode).size()
        ||!formulas.stream().map(Row::metric).collect(java.util.stream.Collectors.toSet())
          .equals(PILOT_OUTPUTS.get(formCode)))
      throw new IllegalStateException("CALC154_ARITHMETIC_CONTRACT_INCOMPLETE");

    Map<String,Node> byOutput=new HashMap<>();
    Map<String,Row> byId=new HashMap<>();
    for(Row f:formulas){
      if(!f.formVersion().equals(exactVersion)||!schema.equals(f.schema())
        ||!"NOT_S2_APPROVED".equals(f.financial())
        ||!("F02".equals(formCode)?"UAT_TEST_ONLY":"CANDIDATE_UNQUALIFIED").equals(f.review())
        ||!"REQUIRE_PRESENT_CANDIDATE".equals(f.missing())
        ||!f.bindingSha().equals(sha("FIN-CALC154-FORMULA-BINDING-V1|"+
          exactVersion+"|"+schema+"|"+f.definitionSha()))
        ||!f.definitionSha().matches("[0-9a-f]{64}")
        ||!switch(f.op()){
          case "RATIO" -> "INVALID_CANDIDATE".equals(f.zero());
          case "SUBTRACT","RECONCILE","ROLL_FORWARD" -> "NOT_APPLICABLE".equals(f.zero());
          default -> false;
        })
        throw new IllegalStateException("CALC154_ARITHMETIC_CONTRACT_DRIFT:"+f.metric());
      byId.put(f.id().toString(),f);
      byOutput.put(f.metric(),new Node(f,new ArrayList<>()));
    }
    var typed=jdbc.query("""
      SELECT i.formula_revision_id,i.ordinal,i.source_metric_code,i.source_kind,
        i.source_form_version_id,i.source_schema_sha256,i.field_type,
        i.source_formula_revision_id,i.period_policy,i.requiredness_state
      FROM fin_calc.formula_typed_input_binding i
      JOIN fin_calc.formula_contract_revision c ON c.id=i.formula_revision_id
      WHERE c.form_code=? AND c.form_version_id=? AND c.formula_version=?
      ORDER BY i.formula_revision_id,i.ordinal
      """,(rs,i)->new Source(rs.getObject(1,UUID.class),rs.getInt(2),
        rs.getString(3),rs.getString(4),rs.getObject(5,UUID.class),
        rs.getString(6),rs.getString(7),rs.getObject(8,UUID.class),
        rs.getString(9),rs.getString(10)),
      formCode,exactVersion,PILOT_VERSIONS.get(formCode));
    var sourceMap=new HashMap<String,List<Source>>();
    for(Source source:typed)sourceMap.computeIfAbsent(source.formulaId().toString(),
      ignored->new ArrayList<>()).add(source);
    var physical=new HashSet<String>();
    for(Row f:formulas){
      List<Source> sources=sourceMap.getOrDefault(f.id().toString(),List.of());
      int size=switch(f.op()){
        case "SUBTRACT","RATIO" -> 2;
        case "RECONCILE" -> 3;
        case "ROLL_FORWARD" -> 5;
        default -> throw new IllegalStateException("CALC154_OP_UNRECOGNIZED");
      };
      if(sources.size()!=size)throw new IllegalStateException("CALC154_TYPED_INPUTS_INCOMPLETE");
      for(int i=0;i<sources.size();i++){
        Source s=sources.get(i);
        if(s.ordinal()!=i||!s.version().equals(exactVersion)
            ||!schema.equals(s.schema())||!s.period().equals(f.period())
            ||!"REVIEW_REQUIRED".equals(s.requiredness())
            ||!s.metric().startsWith(formCode+"."))
          throw new IllegalStateException("CALC154_TYPED_INPUT_SCOPE_MISMATCH");
        if("FORMULA_CANDIDATE".equals(s.kind())){
          Row dependency=s.dependencyId()==null?null:byId.get(s.dependencyId().toString());
          if(dependency==null||!dependency.metric().equals(s.metric())
              ||!byOutput.containsKey(s.metric()))
            throw new IllegalStateException("CALC154_DERIVED_DEPENDENCY_NOT_PINNED");
        }else if("PHYSICAL_NUMERIC_FIELD".equals(s.kind())){
          if(s.dependencyId()!=null||!Set.of("MONEY","DECIMAL","INTEGER").contains(s.fieldType()))
            throw new IllegalStateException("CALC154_SOURCE_TYPE_UNSUPPORTED");
          physical.add(s.metric());
        }else throw new IllegalStateException("CALC154_SOURCE_KIND_UNSUPPORTED");
        ((ArrayList<String>)byOutput.get(f.metric()).sources()).add(s.metric());
      }
    }
    var order=new ArrayList<Node>();
    var seen=new HashSet<String>();
    var visiting=new HashSet<String>();
    for(String metric:byOutput.keySet().stream().sorted().toList())
      visit(metric,byOutput,seen,visiting,order);
    return new Graph(formCode,exactVersion,schema,List.copyOf(order),
      physical.stream().sorted().toList(),order.stream().map(n->n.formula().metric()).toList());
  }

  private static void visit(String metric,Map<String,Node> nodes,Set<String> done,
      Set<String> visiting,List<Node> output){
    if(done.contains(metric))return;
    if(!visiting.add(metric))
      throw new IllegalStateException("CALC154_DAG_CYCLE:"+metric);
    Node n=nodes.get(metric);
    if(n==null)throw new IllegalStateException("CALC154_DAG_UNKNOWN:"+metric);
    for(String source:n.sources())
      if(nodes.containsKey(source))visit(source,nodes,done,visiting,output);
    visiting.remove(metric);
    done.add(metric);
    output.add(n);
  }

  private static void requireArity(List<String> refs,int expected,String operation){
    if(refs.size()!=expected)
      throw new IllegalStateException("CALC154_"+operation+"_ARITY");
  }

  private static String sha(String value){
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch(Exception e){
      throw new IllegalStateException("CALC154_SHA256_UNAVAILABLE",e);
    }
  }
}
