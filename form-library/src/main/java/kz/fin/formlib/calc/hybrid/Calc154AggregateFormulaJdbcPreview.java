package kz.fin.formlib.calc.hybrid;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CALC-4B read-only SQL/JDBC candidate previews for GROUP_BY and RATIO_OF_SUMS
 * on ANY form that has a version-pinned candidate formula contract.
 *
 * Source metric names/operation are resolved from immutable PostgreSQL
 * contracts, NEVER accepted from callers or interpolated into SQL.
 * Uses existing fin_calc.candidate_* PostgreSQL functions. Supports synthetic
 * TEST_QUALIFIED factsets only; does not generate CalcPlan or calc_result.
 * A candidate is not an approved business rule, and synthetic admission is not
 * independent finance S2. Period/unit/dimension policy still needs review.
 */
@Service
public class Calc154AggregateFormulaJdbcPreview {
  public record Group(String dimensionDigest,String dimensionsJson,
      long count,BigDecimal totalValue) {}
  public record Ratio(BigDecimal numeratorTotal,BigDecimal denominatorTotal,
      BigDecimal value) {}
  public record CandidateResult(UUID formulaRevisionId,String outputMetric,
      String operation,String bindingSha256,String scopeSha256,
      List<Group> groups,Ratio ratio,boolean financiallyApproved) {
    public CandidateResult { groups=List.copyOf(groups); }
  }

  private record Formula(UUID id,String form,String output,String op,
      String version,String review,UUID formVersion,String schema,String binding,
      String period,String currencyPolicy,String dimensionPolicy,
      String missingPolicy,String zeroPolicy,String approval) {}
  private record Input(int ordinal,String metric,String sourceKind,
      UUID formVersion,String schema,String fieldType,String policy) {}
  private record Source(UUID factset,LocalDate start,LocalDate end,
      String currency,String factsetSha) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  public Calc154AggregateFormulaJdbcPreview(JdbcTemplate jdbc,ObjectMapper mapper){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.mapper=Objects.requireNonNull(mapper);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public CandidateResult preview(UUID exactFormulaId,UUID organizationId,
      UUID exactFactsetId,String unitCode,String dimensionDigest) {
    if(exactFormulaId==null||organizationId==null||exactFactsetId==null
        ||unitCode==null||unitCode.isBlank())
      throw new IllegalArgumentException("CALC154_AGGREGATE_PREVIEW_ARGUMENTS");
    Formula f=loadFormula(exactFormulaId);
    List<String> inputs=loadInputs(f);
    Source source=requireSyntheticSource(f,organizationId,exactFactsetId,inputs);
    String sha=hash("CALC154-AGGREGATE-PREVIEW-V1|"+f.id()+"|"+f.binding()
        +"|"+organizationId+"|"+exactFactsetId+"|"+source.factsetSha()
        +"|"+unitCode+"|"+String.valueOf(dimensionDigest));

    if("GROUP_BY".equals(f.op())){
      if(dimensionDigest!=null)
        throw new IllegalArgumentException("CALC154_GROUP_USE_ALL_DIMENSIONS");
      var groups=jdbc.query("""
        SELECT dimension_digest,dimensions_jsonb::text,observation_count,total_value
        FROM fin_calc.candidate_interval_group_by(
          ?::uuid,?::uuid,?::varchar,?::varchar,?::date,?::date,
          ?::varchar,?::char(3))
        """,(r,i)->new Group(r.getString(1).trim(),r.getString(2),
          r.getLong(3),r.getBigDecimal(4)),organizationId,source.factset(),
          f.form(),inputs.getFirst(),source.start(),source.end(),
          unitCode,source.currency());
      if(groups.isEmpty())throw new IllegalStateException("CALC154_GROUP_NO_OUTPUT");
      return new CandidateResult(f.id(),f.output(),f.op(),f.binding(),sha,
        groups,null,false);
    }
    if(!"RATIO_OF_SUMS".equals(f.op())||dimensionDigest==null
       ||!dimensionDigest.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("CALC154_RATIO_DIMENSION_DIGEST_REQUIRED");
    var values=jdbc.query("""
      SELECT numerator_sum,denominator_sum,ratio
      FROM fin_calc.candidate_ratio_of_sums(
        ?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,
        ?::date,?::date,?::char(64),?::varchar,?::char(3))
      """,(r,i)->new Ratio(r.getBigDecimal(1),
        r.getBigDecimal(2),r.getBigDecimal(3)),organizationId,source.factset(),
        f.form(),inputs.get(0),inputs.get(1),source.start(),source.end(),
        dimensionDigest,unitCode,source.currency());
    if(values.size()!=1||values.getFirst().value()==null)
      throw new IllegalStateException("CALC154_RATIO_NO_OUTPUT");
    return new CandidateResult(f.id(),f.output(),f.op(),f.binding(),sha,
      List.of(),values.getFirst(),false);
  }

  private Formula loadFormula(UUID id){
    var rows=jdbc.query("""
      SELECT c.id,c.form_code,c.output_metric_code,c.operation_code,
        c.formula_version,c.review_state,c.form_version_id,c.source_schema_sha256,
        c.binding_sha256,c.period_semantics,c.currency_policy,c.dimension_policy,
        c.missing_policy,c.zero_policy,c.financial_approval_state
      FROM fin_calc.formula_contract_revision c
      JOIN fin_lib.lib_form_definition f ON f.form_code=c.form_code
        AND f.current_version_id=c.form_version_id
      JOIN fin_lib.lib_form_version v ON v.id=c.form_version_id
        AND v.schema_sha256=c.source_schema_sha256
      WHERE c.id=?
      """,(r,i)->new Formula(r.getObject(1,UUID.class),r.getString(2),
        r.getString(3),r.getString(4),r.getString(5),r.getString(6),
        r.getObject(7,UUID.class),r.getString(8),r.getString(9),
        r.getString(10),r.getString(11),r.getString(12),
        r.getString(13),r.getString(14),r.getString(15)),id);
    if(rows.size()!=1)throw new IllegalStateException("CALC154_AGGREGATE_FORMULA_STALE");
    var f=rows.getFirst();
    if(!Set.of("GROUP_BY","RATIO_OF_SUMS").contains(f.op())
        ||!"CANDIDATE_UNQUALIFIED".equals(f.review())
        ||!"NOT_S2_APPROVED".equals(f.approval())
        ||!"INTERVAL_REQUIRED".equals(f.period())
        ||!"UNREVIEWED".equals(f.currencyPolicy())
        ||!"UNREVIEWED".equals(f.dimensionPolicy())
        ||!"REQUIRE_PRESENT_CANDIDATE".equals(f.missingPolicy())
        ||!("RATIO_OF_SUMS".equals(f.op())?"INVALID_CANDIDATE":
          "NOT_APPLICABLE").equals(f.zeroPolicy())
        ||!f.binding().matches("[0-9a-f]{64}"))
      throw new IllegalStateException("CALC154_AGGREGATE_FORMULA_NOT_CANDIDATE");
    return f;
  }

  private List<String> loadInputs(Formula f){
    var binds=jdbc.query("""
      SELECT i.ordinal,i.source_metric_code,i.source_kind,
        i.source_form_version_id,i.source_schema_sha256,i.field_type,i.period_policy
      FROM fin_calc.formula_typed_input_binding i
      WHERE i.formula_revision_id=?
      ORDER BY i.ordinal
      """,(r,i)->new Input(r.getInt(1),r.getString(2),r.getString(3),
        r.getObject(4,UUID.class),r.getString(5),r.getString(6),
        r.getString(7)),f.id());
    int needed="GROUP_BY".equals(f.op())?1:2;
    if(binds.size()!=needed)
      throw new IllegalStateException("CALC154_AGGREGATE_SOURCE_COUNT");
    var metrics=new ArrayList<String>();
    for(int i=0;i<binds.size();i++){
      var b=binds.get(i);
      if(b.ordinal()!=i||!b.formVersion().equals(f.formVersion())
        ||!b.schema().equals(f.schema())
        ||!"PHYSICAL_NUMERIC_FIELD".equals(b.sourceKind())
        ||!Set.of("MONEY","DECIMAL","INTEGER").contains(b.fieldType())
        ||!f.period().equals(b.policy())
        ||!b.metric().startsWith(f.form()+"."))
        throw new IllegalStateException("CALC154_AGGREGATE_SOURCE_NOT_TYPED");
      metrics.add(b.metric());
    }
    var original=jdbc.queryForObject("""
      SELECT source_metrics_jsonb::text
      FROM fin_calc.formula_contract_revision WHERE id=?
      """,String.class,f.id());
    try{
      var asArray=List.of(mapper.readValue(original,String[].class));
      if(!asArray.equals(metrics))
        throw new IllegalStateException("CALC154_AGGREGATE_FORMULA_BINDING_DRIFT");
    }catch(com.fasterxml.jackson.core.JsonProcessingException e){
      throw new IllegalStateException("CALC154_AGGREGATE_FORMULA_JSON_INVALID",e);
    }
    var mapped=jdbc.query("""
      SELECT numerator_metric_code,denominator_metric_code
      FROM fin_calc.candidate_metric_operation_binding
      WHERE form_code=? AND operation_code=? AND output_code=?
        AND review_state='CANDIDATE_UNQUALIFIED'
      """,(r,i)->List.of(r.getString(1),r.getString(2)==null?"":r.getString(2)),
      f.form(),f.op(),f.output().substring(f.form().length()+1));
    if(mapped.size()!=1||!mapped.getFirst().get(0).equals(metrics.getFirst())
       ||(needed==1?!mapped.getFirst().get(1).isEmpty():
          !mapped.getFirst().get(1).equals(metrics.get(1))))
      throw new IllegalStateException("CALC154_AGGREGATE_OPERATION_MAPPING_DRIFT");
    return List.copyOf(metrics);
  }

  private Source requireSyntheticSource(Formula f,UUID org,UUID factset,
      List<String> metrics){
    var sources=jdbc.query("""
      SELECT s.id,s.target_period_start,s.target_period_end_exclusive,
        s.currency_code,s.factset_sha256
      FROM fin_fact.fact_set s
      JOIN fin_fact.factset_control ctl
        ON ctl.organization_id=s.organization_id
        AND ctl.stable_scope_key=s.scope_key
        AND ctl.active_factset_id=s.id
        AND ctl.qualification_epoch=s.qualification_epoch
        AND ctl.revoked=false
      WHERE s.id=? AND s.organization_id=?
        AND s.qualification_mode='TEST_QUALIFIED'
        AND s.data_space='SYNTHETIC'
      """,(r,i)->new Source(r.getObject(1,UUID.class),
        r.getDate(2).toLocalDate(),r.getDate(3).toLocalDate(),
        r.getString(4),r.getString(5)),factset,org);
    if(sources.size()!=1)
      throw new IllegalStateException("CALC154_AGGREGATE_FACTSET_NOT_ACTIVE");
    for(String metric:metrics){
      Integer invalid=jdbc.queryForObject("""
        SELECT count(*) FROM fin_fact.fact_observation o
        WHERE o.factset_id=? AND o.source_form_code=? AND o.metric_code=?
          AND o.source_form_version_id<>?
        """,Integer.class,factset,f.form(),metric,f.formVersion());
      if(invalid==null||invalid!=0)
        throw new IllegalStateException("CALC154_AGGREGATE_OBSERVATION_SCHEMA_DRIFT");
    }
    return sources.getFirst();
  }
  private static String hash(String text){
    try{
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(text.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception ex){throw new IllegalStateException("CALC154_AGGREGATE_SHA_ERROR",ex);}
  }
}
