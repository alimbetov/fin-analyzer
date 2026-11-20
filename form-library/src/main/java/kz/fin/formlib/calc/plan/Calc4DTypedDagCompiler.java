package kz.fin.formlib.calc.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CALC-4D.0: all-form typed DAG compiler, WITHOUT a hard-coded form allowlist.
 * Reads immutable candidate formula revisions and exact typed-input bindings.
 * Missing dependencies, cycles, version/SHA/type mismatches fail closed.
 * This contract is PREVIEW_ONLY, not a qualified executable CalcPlan.
 */
@Service
public class Calc4DTypedDagCompiler {
  public record Input(int ordinal, String metricCode, String sourceKind,
      UUID formVersionId, String schemaSha256, String fieldCode,
      String fieldType, UUID formulaRevisionId, String bindingSha256) {}
  public record Node(UUID formulaRevisionId, String formCode, UUID formVersionId,
      String schemaSha256, String outputMetricCode, String formulaVersion,
      String operation, String periodSemantics, String unitPolicy,
      String definitionSha256, String bindingSha256, List<Input> inputs) {
    public Node { inputs=List.copyOf(inputs); }
  }
  public record Graph(List<Node> orderedNodes, List<Input> physicalInputs,
      String dagSha256, List<String> blockers, boolean executable) {
    public Graph {
      orderedNodes=List.copyOf(orderedNodes);
      physicalInputs=List.copyOf(physicalInputs);
      blockers=List.copyOf(blockers);
    }
  }
  private record Formula(UUID id,String form,UUID version,String schema,
      String output,String revision,String operation,String sources,
      String period,String unit,String dimension,String currency,String rounding,
      String missing,String zero,String state,String approval,
      String definitionSha,String bindingSha) {}
  private record RawInput(UUID owner,int ordinal,String metric,String kind,
      UUID version,String schema,String field,String type,UUID dependency,
      String period,String requiredness,String bindingSha) {}
  private record Field(String form,String type,String schema,String metric,String field) {}

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final ObjectMapper json;
  public Calc4DTypedDagCompiler(JdbcTemplate jdbc,ObjectMapper json){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.named=new NamedParameterJdbcTemplate(jdbc);
    this.json=Objects.requireNonNull(json);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Graph compile(List<UUID> exactFormulaRevisionIds){
    if(exactFormulaRevisionIds==null||exactFormulaRevisionIds.isEmpty()
        ||exactFormulaRevisionIds.size()>1024
        ||exactFormulaRevisionIds.stream().anyMatch(Objects::isNull)
        ||new HashSet<>(exactFormulaRevisionIds).size()!=exactFormulaRevisionIds.size())
      throw new IllegalArgumentException("CALC4D_EXACT_FORMULA_REVISION_SET_REQUIRED");

    var formulas=named.query("""
      SELECT c.id,c.form_code,c.form_version_id,c.source_schema_sha256,
        c.output_metric_code,c.formula_version,c.operation_code,
        c.source_metrics_jsonb::text,c.period_semantics,c.unit_policy,
        c.dimension_policy,c.currency_policy,c.rounding_policy,c.missing_policy,
        c.zero_policy,c.review_state,c.financial_approval_state,
        c.definition_sha256,c.binding_sha256
      FROM fin_calc.formula_contract_revision c
      JOIN fin_lib.lib_form_version v ON v.id=c.form_version_id
        AND v.schema_sha256=c.source_schema_sha256
      JOIN fin_lib.lib_form_definition f ON f.form_code=c.form_code
        AND f.current_version_id=v.id
      WHERE c.id IN (:ids)
      """,new MapSqlParameterSource("ids",exactFormulaRevisionIds),
      (r,i)->new Formula(r.getObject(1,UUID.class),r.getString(2),
        r.getObject(3,UUID.class),r.getString(4),r.getString(5),
        r.getString(6),r.getString(7),r.getString(8),r.getString(9),
        r.getString(10),r.getString(11),r.getString(12),r.getString(13),
        r.getString(14),r.getString(15),r.getString(16),r.getString(17),
        r.getString(18),r.getString(19)));
    if(formulas.size()!=exactFormulaRevisionIds.size())
      throw new IllegalStateException("CALC4D_FORMULA_REVISION_STALE_OR_MISSING");
    var byId=new HashMap<UUID,Formula>();
    for(var f:formulas){
      if(byId.putIfAbsent(f.id(),f)!=null
          ||!"NOT_S2_APPROVED".equals(f.approval())
          ||!Set.of("UAT_TEST_ONLY","CANDIDATE_UNQUALIFIED").contains(f.state())
          ||!"REQUIRE_PRESENT_CANDIDATE".equals(f.missing())
          ||!Set.of("SUBTRACT","RATIO","RECONCILE","ROLL_FORWARD",
              "GROUP_BY","RATIO_OF_SUMS").contains(f.operation())
          ||!f.bindingSha().equals(sha("FIN-CALC154-FORMULA-BINDING-V1|"+
              f.version()+"|"+f.schema()+"|"+f.definitionSha())))
        throw new IllegalStateException("CALC4D_FORMULA_CONTRACT_NOT_PINNED:"+f.output());
    }
    var raw=named.query("""
      SELECT i.formula_revision_id,i.ordinal,i.source_metric_code,i.source_kind,
        i.source_form_version_id,i.source_schema_sha256,i.source_field_code,
        i.field_type,i.source_formula_revision_id,i.period_policy,
        i.requiredness_state,i.binding_sha256
      FROM fin_calc.formula_typed_input_binding i
      WHERE i.formula_revision_id IN (:ids)
      ORDER BY i.formula_revision_id,i.ordinal
      """,new MapSqlParameterSource("ids",exactFormulaRevisionIds),
      (r,i)->new RawInput(r.getObject(1,UUID.class),r.getInt(2),
        r.getString(3),r.getString(4),r.getObject(5,UUID.class),
        r.getString(6),r.getString(7),r.getString(8),
        r.getObject(9,UUID.class),r.getString(10),r.getString(11),
        r.getString(12)));
    var grouped=new HashMap<UUID,List<RawInput>>();
    for(var in:raw)grouped.computeIfAbsent(in.owner(),x->new ArrayList<>()).add(in);
    var nodes=new HashMap<UUID,Node>();
    var physical=new HashMap<String,Input>();
    var blockers=new HashSet<String>();
    blockers.add("FINANCIAL_S2_NOT_APPROVED");
    blockers.add("MANDATORY_OUTPUT_SET_NOT_APPROVED");

    for(var f:formulas){
      if("UNREVIEWED".equals(f.dimension())||"UNREVIEWED".equals(f.currency())
          ||"UNREVIEWED".equals(f.rounding()))
        blockers.add("FORMULA_POLICIES_UNREVIEWED");
      var source=grouped.getOrDefault(f.id(),List.of());
      int arity=switch(f.operation()){
        case "SUBTRACT","RATIO","RATIO_OF_SUMS" -> 2;
        case "RECONCILE" -> 3;
        case "ROLL_FORWARD" -> 5;
        case "GROUP_BY" -> 1;
        default -> throw new IllegalStateException("CALC4D_UNKNOWN_OPERATION");
      };
      if(source.size()!=arity)
        throw new IllegalStateException("CALC4D_INPUT_ARITY_MISMATCH:"+f.output());
      var declared=parseInputs(f.sources());
      if(!declared.equals(source.stream().map(RawInput::metric).toList()))
        throw new IllegalStateException("CALC4D_INPUT_ORDER_DRIFT:"+f.output());
      var inputList=new ArrayList<Input>();
      for(int i=0;i<source.size();i++){
        var in=source.get(i);
        if(in.ordinal()!=i||!"REVIEW_REQUIRED".equals(in.requiredness())
            ||!f.period().equals(in.period())||!in.schema().matches("[0-9a-f]{64}"))
          throw new IllegalStateException("CALC4D_INPUT_POLICY_DRIFT:"+f.output());
        String sourceIdentity=in.dependency()!=null
          ?in.dependency().toString()
          :in.field()+"|"+in.type();
        String binding=sha("CALC3B-TYPED-INPUT-V1|"+f.id()+"|"+i+"|"+
          in.metric()+"|"+in.kind()+"|"+in.version()+"|"+in.schema()+
          "|"+sourceIdentity);
        if(!binding.equals(in.bindingSha()))
          throw new IllegalStateException("CALC4D_INPUT_BINDING_SHA_DRIFT");
        if("FORMULA_CANDIDATE".equals(in.kind())){
          var dependency=byId.get(in.dependency());
          if(dependency==null||!dependency.output().equals(in.metric())
              ||!dependency.version().equals(in.version())
              ||!dependency.schema().equals(in.schema())
              ||in.field()!=null||in.type()!=null)
            throw new IllegalStateException("CALC4D_DERIVED_SOURCE_NOT_PINNED");
        }else if("PHYSICAL_NUMERIC_FIELD".equals(in.kind())){
          if(in.dependency()!=null||!Set.of("MONEY","DECIMAL","INTEGER").contains(in.type())
              ||in.field()==null)
            throw new IllegalStateException("CALC4D_PHYSICAL_FIELD_INVALID");
          Field field=jdbc.query("""
            SELECT d.form_code,ff.field_type,v.schema_sha256,m.metric_code,ff.field_code
            FROM fin_lib.lib_form_field ff
            JOIN fin_lib.lib_form_version v ON v.id=ff.form_version_id
            JOIN fin_lib.lib_form_definition d ON d.id=v.form_id
            JOIN fin_lib.lib_metric_definition m ON m.form_code=d.form_code
              AND m.source_field_code=ff.field_code AND m.metric_kind='SOURCE_FIELD'
            JOIN fin_lib.lib_field_binding_contract b ON b.form_code=d.form_code
              AND b.field_code=ff.field_code AND b.metric_code=m.metric_code
              AND b.source_schema_sha256=v.schema_sha256
              AND b.review_state='CANDIDATE_UNQUALIFIED'
            WHERE ff.form_version_id=? AND ff.field_code=? AND m.metric_code=?
            """,(r,j)->new Field(r.getString(1),r.getString(2),r.getString(3),
              r.getString(4),r.getString(5)),in.version(),in.field(),in.metric())
              .stream().findFirst().orElseThrow(()->
                new IllegalStateException("CALC4D_PHYSICAL_BINDING_MISSING"));
          if(!field.schema().equals(in.schema())||!field.type().equals(in.type())
              ||!field.metric().equals(in.metric()))
            throw new IllegalStateException("CALC4D_PHYSICAL_BINDING_DRIFT");
        }else throw new IllegalStateException("CALC4D_UNSUPPORTED_SOURCE_KIND");
        var typed=new Input(i,in.metric(),in.kind(),in.version(),in.schema(),
          in.field(),in.type(),in.dependency(),in.bindingSha());
        inputList.add(typed);
        if("PHYSICAL_NUMERIC_FIELD".equals(in.kind())){
          String key=in.version()+"|"+in.metric();
          var other=physical.putIfAbsent(key,typed);
          if(other!=null&&!other.equals(typed)&&
              (!other.metricCode().equals(typed.metricCode())
                 ||!other.formVersionId().equals(typed.formVersionId())))
            throw new IllegalStateException("CALC4D_DUPLICATE_SOURCE_IDENTITY");
        }
      }
      nodes.put(f.id(),new Node(f.id(),f.form(),f.version(),f.schema(),
        f.output(),f.revision(),f.operation(),f.period(),f.unit(),
        f.definitionSha(),f.bindingSha(),inputList));
    }

    var ordered=new ArrayList<Node>();
    var visiting=new HashSet<UUID>();
    var done=new HashSet<UUID>();
    for(UUID id:nodes.keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList())
      dfs(id,nodes,done,visiting,ordered);
    var inputs=physical.values().stream()
      .sorted(Comparator.comparing((Input x)->x.formVersionId().toString())
        .thenComparing(Input::metricCode)).toList();
    var canonical=new StringBuilder("FIN-CALC4D-TYPED-DAG-V1");
    for(var n:ordered){
      canonical.append("|N:").append(n.formulaRevisionId()).append(':')
        .append(n.formCode()).append(':').append(n.formVersionId())
        .append(':').append(n.schemaSha256()).append(':')
        .append(n.outputMetricCode()).append(':').append(n.formulaVersion())
        .append(':').append(n.operation()).append(':').append(n.periodSemantics())
        .append(':').append(n.unitPolicy()).append(':')
        .append(n.definitionSha256()).append(':').append(n.bindingSha256());
      for(var i:n.inputs())canonical.append("|I:").append(i.ordinal())
        .append(':').append(i.metricCode()).append(':').append(i.sourceKind())
        .append(':').append(i.formVersionId()).append(':').append(i.schemaSha256())
        .append(':').append(i.formulaRevisionId()).append(':').append(i.bindingSha256());
    }
    return new Graph(ordered,inputs,sha(canonical.toString()),
      blockers.stream().sorted().toList(),false);
  }

  private List<String> parseInputs(String source){
    try{
      var array=json.readValue(source,String[].class);
      if(array.length<1||array.length>16)throw new IllegalStateException("CALC4D_SOURCE_ARRAY_SHAPE");
      return List.of(array);
    }catch(com.fasterxml.jackson.core.JsonProcessingException ex){
      throw new IllegalStateException("CALC4D_SOURCE_ARRAY_INVALID",ex);
    }
  }

  private void dfs(UUID id,Map<UUID,Node> nodes,Set<UUID> done,
      Set<UUID> visiting,List<Node> result){
    if(done.contains(id))return;
    if(!visiting.add(id))throw new IllegalStateException("CALC4D_FORMULA_DAG_CYCLE");
    Node node=nodes.get(id);
    if(node==null)throw new IllegalStateException("CALC4D_DAG_DEPENDENCY_MISSING");
    for(var i:node.inputs())if("FORMULA_CANDIDATE".equals(i.sourceKind()))
      dfs(i.formulaRevisionId(),nodes,done,visiting,result);
    visiting.remove(id);done.add(id);result.add(node);
  }

  static String sha(String text){
    try{
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(text.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception e){throw new IllegalStateException("CALC4D_SHA_UNAVAILABLE",e);}
  }
}
