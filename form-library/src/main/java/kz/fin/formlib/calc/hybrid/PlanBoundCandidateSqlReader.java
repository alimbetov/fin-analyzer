package kz.fin.formlib.calc.hybrid;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * CALC-166 SQL_AGG JDBC read-only evaluator for the exact, pinned UAT plan.
 *
 * Policy derives ONLY from immutable method_input_binding, never caller metric
 * strings. No production admission, S2, writes, job execution or publication.
 * HYB2B plans retain their BLOCKED jobs; this is an internal pre-execution
 * preview/verification adapter, not a worker or qualified result publisher.
 */
@Service
public class PlanBoundCandidateSqlReader {
  public record Group(String dimensionDigest, String dimensionsJson,
      long observations, BigDecimal total) {}
  public record Ratio(BigDecimal numeratorSum, BigDecimal denominatorSum,
      BigDecimal ratio) {}
  private record Bound(UUID factsetId,UUID methodId,String currency,
      LocalDate start,LocalDate end) {}
  private record Input(String formCode,String metricCode,
      String aggregationPolicy,String periodSemantics,UUID formVersionId) {}

  private final JdbcTemplate sql;
  public PlanBoundCandidateSqlReader(JdbcTemplate sql){this.sql=Objects.requireNonNull(sql);}

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public List<Group> groupBy(UUID plan,UUID org,String scope,int ordinal,String unit){
    Bound b=requirePinned(plan,org,scope);
    Input input=requireInput(b,ordinal,"SUM_FLOW");
    validateObservationVersions(b,input);
    return sql.query("""
      SELECT dimension_digest,dimensions_jsonb::text,observation_count,total_value
      FROM fin_calc.candidate_interval_group_by(
        ?::uuid,?::uuid,?::varchar,?::varchar,?::date,?::date,
        ?::varchar,?::char(3))
      """,(r,i)->new Group(r.getString(1).trim(),r.getString(2),
            r.getLong(3),r.getBigDecimal(4)),
        org,b.factsetId(),input.formCode(),input.metricCode(),
        b.start(),b.end(),required(unit),b.currency());
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Ratio ratioOfSums(UUID plan,UUID org,String scope,
      int numeratorOrdinal,int denominatorOrdinal,String dimensionDigest,String unit){
    if(numeratorOrdinal==denominatorOrdinal)
      throw new IllegalArgumentException("CALC166_RATIO_SAME_BINDING");
    Bound b=requirePinned(plan,org,scope);
    Input numerator=requireInput(b,numeratorOrdinal,"RATIO_OF_SUMS");
    Input denominator=requireInput(b,denominatorOrdinal,"RATIO_OF_SUMS");
    if(!numerator.formCode().equals(denominator.formCode())
       ||!numerator.formVersionId().equals(denominator.formVersionId())
       ||numerator.metricCode().equals(denominator.metricCode()))
      throw new IllegalStateException("CALC166_RATIO_DIFFERENT_GRAIN_OR_SAME_METRIC");
    validateObservationVersions(b,numerator);
    validateObservationVersions(b,denominator);
    var values=sql.query("""
      SELECT numerator_sum,denominator_sum,ratio
      FROM fin_calc.candidate_ratio_of_sums(
        ?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,
        ?::date,?::date,?::char(64),?::varchar,?::char(3))
      """,(r,i)->new Ratio(r.getBigDecimal(1),r.getBigDecimal(2),r.getBigDecimal(3)),
        org,b.factsetId(),numerator.formCode(),numerator.metricCode(),
        denominator.metricCode(),b.start(),b.end(),required(dimensionDigest),
        required(unit),b.currency());
    if(values.size()!=1)throw new IllegalStateException("CALC166_RATIO_SHAPE_INVALID");
    return values.getFirst();
  }

  /**
   * Verify active FactSet, immutable plan/source/executor and tested method
   * in one repeatable-read transaction; do not trust a caller-provided form,
   * metric, snapshot SHA or period.
   */
  private Bound requirePinned(UUID plan,UUID org,String scope){
    if(plan==null||org==null||scope==null||scope.length()!=64)
      throw new IllegalArgumentException("CALC166_PLAN_SCOPE_REQUIRED");
    var rows=sql.query("""
      SELECT p.factset_id,e.method_revision_id,f.currency_code,
        f.target_period_start,f.target_period_end_exclusive
      FROM fin_calc.calc_plan p
      JOIN fin_fact.fact_set f ON f.id=p.factset_id
        AND f.organization_id=p.organization_id AND f.scope_key=p.scope_key
      JOIN fin_calc.plan_factset_binding b ON b.plan_id=p.id AND b.ordinal=0
        AND b.organization_id=p.organization_id AND b.plan_scope_key=p.scope_key
        AND b.source_factset_id=f.id AND b.source_scope_key=f.scope_key
        AND b.source_factset_sha256=f.factset_sha256
      JOIN fin_calc.plan_execution_binding e ON e.plan_id=p.id
        AND e.organization_id=p.organization_id AND e.scope_key=p.scope_key
      JOIN fin_calc.method_revision m ON m.id=e.method_revision_id
      JOIN fin_fact.factset_control c ON c.organization_id=p.organization_id
        AND c.stable_scope_key=p.scope_key
        AND c.active_factset_id=f.id AND c.revoked=false
        AND c.qualification_epoch=p.qualification_epoch
      WHERE p.id=? AND p.organization_id=? AND p.scope_key=?
        AND p.qualification_mode='TEST_QUALIFIED'
        AND f.qualification_mode='TEST_QUALIFIED'
        AND f.qualification_epoch=p.qualification_epoch
        AND f.qualification_binding_sha256=p.qualified_binding_sha256
        AND m.registry_state='TEST_ONLY_REFERENCE'
        AND m.artifact_attested=false
        AND m.algorithm_artifact_sha256=p.algorithm_artifact_sha256
        AND m.config_sha256=p.config_sha256
        AND m.params_sha256=p.params_sha256
        AND e.strategy IN ('SQL_ONLY','SQL_AGG_JAVA_FINAL')
        AND p.dag_jsonb->>'executionStrategy'=e.strategy
        AND p.dag_jsonb->>'methodRevisionId'=e.method_revision_id::text
        AND p.dag_jsonb->>'executionContractSha256'=e.execution_contract_sha256
        AND p.dag_jsonb->>'executionFingerprint'=p.execution_fingerprint
        AND p.dag_jsonb->>'exactFactsetId'=f.id::text
      """,(r,i)->new Bound(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
          r.getString(3),r.getDate(4).toLocalDate(),r.getDate(5).toLocalDate()),
        plan,org,scope);
    if(rows.size()!=1)throw new IllegalStateException("CALC166_PLAN_NOT_ADMITTED");
    return rows.getFirst();
  }

  private Input requireInput(Bound b,int ordinal,String policy){
    if(ordinal<0||ordinal>1023)throw new IllegalArgumentException("CALC166_ORDINAL_INVALID");
    var rows=sql.query("""
      SELECT md.form_code,i.metric_code,i.aggregation_policy,
        i.period_semantics,i.form_version_id
      FROM fin_calc.method_input_binding i
      JOIN fin_lib.lib_metric_definition md ON md.metric_code=i.metric_code
        AND md.source_field_code=i.field_code
      JOIN fin_lib.lib_form_version v ON v.id=i.form_version_id
        AND v.schema_sha256=i.source_schema_sha256
      JOIN fin_lib.lib_form_definition f ON f.id=v.form_id
        AND f.form_code=md.form_code
      JOIN fin_lib.lib_field_binding_contract c ON c.form_code=f.form_code
        AND c.field_code=i.field_code AND c.metric_code=i.metric_code
        AND c.source_schema_sha256=i.source_schema_sha256
        AND c.unit_policy=i.unit_policy AND c.review_state='CANDIDATE_UNQUALIFIED'
      WHERE i.method_revision_id=? AND i.ordinal=?
        AND i.missing_policy='REQUIRE_PRESENT'
      """,(r,i)->new Input(r.getString(1),r.getString(2),r.getString(3),
          r.getString(4),r.getObject(5,UUID.class)),b.methodId(),ordinal);
    if(rows.size()!=1||!policy.equals(rows.getFirst().aggregationPolicy())
        ||!"INTERVAL".equals(rows.getFirst().periodSemantics()))
      throw new IllegalStateException("CALC166_METHOD_AGG_POLICY_NOT_ADMITTED");
    return rows.getFirst();
  }

  private void validateObservationVersions(Bound b,Input input){
    Integer count=sql.queryForObject("""
      SELECT count(*) FROM fin_fact.fact_observation o
      WHERE o.factset_id=? AND o.source_form_code=? AND o.metric_code=?
        AND o.source_form_version_id<>?
      """,Integer.class,b.factsetId(),input.formCode(),input.metricCode(),
       input.formVersionId());
    if(count==null||count>0)
      throw new IllegalStateException("CALC166_SOURCE_VERSION_DRIFT");
  }

  private static String required(String value){
    if(value==null||value.isBlank())
      throw new IllegalArgumentException("CALC166_UNIT_OR_DIMENSION_REQUIRED");
    return value;
  }
}
