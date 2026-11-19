package kz.fin.formlib.calc.hybrid;

import kz.fin.formlib.calc.fact.UatF02PinnedPlanReader;
import kz.fin.formlib.calc.kernel.F02CalculationKernel;
import kz.fin.formlib.calc.kernel.SqlF02CalculationKernel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HYB-2A local diagnostic: three real execution routes against the EXACT
 * verified F02 FactSet and a typed, versioned method reference.
 *
 * Comparison ONLY: no calc_job, calc_result or CURRENT write. A future
 * strategy-aware CalcPlan must bind the strategy into its fingerprint before
 * a generic worker may publish results.
 */
@Service
public class HybridF02ComparisonService {
  public record Comparison(UUID planId,UUID methodRevisionId,
      HybridMethodRegistry.Strategy strategy,
      F02CalculationKernel.Result result,String admissionMode) {
    public Comparison {
      if(!"LOCAL_TEST_ONLY_NOT_PUBLISHED".equals(admissionMode))
        throw new IllegalArgumentException("HYB_ONLY_TEST_ADMISSION");
    }
  }

  private final JdbcTemplate jdbc;
  private final HybridMethodRegistry registry;
  private final UatF02PinnedPlanReader sourceReader;
  private final SqlF02CalculationKernel sqlKernel;
  private final F02CalculationKernel javaKernel=new F02CalculationKernel();
  private final boolean enabled;

  public HybridF02ComparisonService(JdbcTemplate jdbc,HybridMethodRegistry registry,
      UatF02PinnedPlanReader sourceReader,SqlF02CalculationKernel sqlKernel,
      @Value("$"+"{fin.calc.hybrid.uat.compare.enabled:false}") boolean enabled) {
    this.jdbc=jdbc;this.registry=registry;
    this.sourceReader=sourceReader;this.sqlKernel=sqlKernel;this.enabled=enabled;
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Comparison compare(UUID planId,UUID organizationId,String scopeKey,
      UUID methodRevisionId,HybridMethodRegistry.Strategy strategy){
    if(!enabled)throw new IllegalStateException("HYB_UAT_COMPARISON_DISABLED");
    if(planId==null||organizationId==null||scopeKey==null||methodRevisionId==null
        ||strategy==null)
      throw new IllegalArgumentException("HYB_PINNED_PLAN_AND_STRATEGY_REQUIRED");

    // Independent SHA, persisted observation, plan DAG & receipt validation.
    var verified=sourceReader.verify(planId,organizationId,scopeKey);
    var method=registry.requireF02TestOnly(methodRevisionId,verified.formVersionId());
    var plan=jdbc.query("""
        SELECT algorithm_artifact_sha256,config_sha256,params_sha256,
          dag_jsonb->>'dagSha256' AS dag_sha
        FROM fin_calc.calc_plan
        WHERE id=? AND organization_id=? AND scope_key=?
        """,(r,i)->new String[]{r.getString(1),r.getString(2),
            r.getString(3),r.getString(4)},planId,organizationId,scopeKey);
    if(plan.size()!=1||!method.algorithmArtifactSha256().equals(plan.getFirst()[0])
        ||!method.configSha256().equals(plan.getFirst()[1])
        ||!method.paramsSha256().equals(plan.getFirst()[2])
        ||!method.dagSha256().equals(plan.getFirst()[3]))
      throw new IllegalStateException("HYB_METHOD_PLAN_ARTIFACT_MISMATCH");

    var oracle=javaKernel.evaluate(verified.observations());
    F02CalculationKernel.Result actual=switch(strategy){
      case JAVA_ONLY -> oracle;
      case SQL_ONLY -> sqlKernel.evaluate(verified.observations());
      case SQL_AGG_JAVA_FINAL -> javaKernel.evaluate(readExactAggregatedF02(verified));
    };
    if(!oracle.equals(actual))
      throw new IllegalStateException("HYB_SQL_JAVA_CANONICAL_SHA_MISMATCH");
    return new Comparison(planId,methodRevisionId,strategy,actual,
        "LOCAL_TEST_ONLY_NOT_PUBLISHED");
  }

  /**
   * F02 has one observation per metric, so SUM is a no-op. This proves
   * JDBC set retrieval and scope isolation, NOT the ROWSET performance gain.
   * AS_OF, EVENT and multiple rows are deliberately unsupported here.
   */
  private Map<String,BigDecimal> readExactAggregatedF02(
      UatF02PinnedPlanReader.Verified verified){
    var rows=jdbc.query("""
        SELECT o.metric_code,COUNT(*) AS number_of_rows,
          SUM(o.numeric_value) AS aggregate_value
        FROM fin_fact.fact_observation o
        JOIN fin_fact.fact_set f ON f.id=o.factset_id
        WHERE o.factset_id=? AND f.organization_id=?
          AND f.project_id=? AND f.scope_key=?
          AND o.source_form_code='F02' AND o.value_state='PRESENT'
          AND o.period_semantics='INTERVAL'
        GROUP BY o.metric_code ORDER BY o.metric_code
        """,(r,i)->new Object[]{r.getString("metric_code"),
            r.getLong("number_of_rows"),r.getBigDecimal("aggregate_value")},
        verified.factsetId(),verified.organizationId(),verified.projectId(),verified.scopeKey());
    if(rows.size()!=3)
      throw new IllegalStateException("HYB_SQL_F02_SOURCE_CARDINALITY_INVALID");
    Map<String,BigDecimal> result=new HashMap<>();
    for(var row:rows){
      if((long)row[1]!=1||row[2]==null
          ||!verified.observations().containsKey((String)row[0]))
        throw new IllegalStateException("HYB_SQL_F02_SOURCE_CARDINALITY_INVALID");
      var expected=verified.observations().get((String)row[0]);
      if(expected.compareTo((BigDecimal)row[2])!=0
          ||result.put((String)row[0],(BigDecimal)row[2])!=null)
        throw new IllegalStateException("HYB_SQL_F02_SOURCE_VALUE_MISMATCH");
    }
    if(!result.keySet().equals(verified.observations().keySet()))
      throw new IllegalStateException("HYB_SQL_F02_SOURCE_METRICS_MISMATCH");
    return Map.copyOf(result);
  }
}
