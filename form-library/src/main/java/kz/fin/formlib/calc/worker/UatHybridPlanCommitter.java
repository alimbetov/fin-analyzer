package kz.fin.formlib.calc.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kz.fin.formlib.calc.fact.FactDigest;
import kz.fin.formlib.calc.fact.UatF02PinnedPlanReader;
import kz.fin.formlib.calc.hybrid.HybridMethodRegistry;
import kz.fin.formlib.calc.hybrid.HybridMethodRegistry.Strategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;

/**
 * HYB-2B UAT-only atomic planner. Takes an independently qualified *legacy*
 * F02 plan only as a source-verification oracle; commits a NEW immutable
 * strategy-pinned CalcPlan + source binding + executor binding + BLOCKED job
 * within one PostgreSQL transaction. Does not issue finance S2 or run jobs.
 *
 * The BLOCKED state is intentional until a separate versioned HYB worker can
 * prove strategy-aware execution and independent authority. Neither a feature
 * toggle nor this registry can turn a candidate into financial approval.
 */
@Service
public class UatHybridPlanCommitter {
  static final String CONTRACT="FIN-CALC-HYB2B-F02-TEST-V1";
  static final String BLOCKER="HYB_WORKER_NOT_ADMITTED";

  enum FaultPoint { NONE, AFTER_PLAN, AFTER_SOURCE, AFTER_EXECUTION, AFTER_JOB }
  public record Receipt(UUID planId,UUID jobId,UUID factsetId,
      UUID methodRevisionId,Strategy strategy,String executionFingerprint,
      String executionContractSha256,boolean reused,String jobState) {}

  private record PlanSeed(UUID planId,String dag,
      String executionFingerprint,String sourceBindingSha256,
      long epoch,String artifactSha,String configSha,String paramsSha) {}
  private record PlanStored(UUID id,UUID factset,String fingerprint,String binding,
      long epoch,String artifact,String config,String params,String dag,String mode) {}
  private record SourceStored(UUID id,String sha,String scope,UUID organization) {}
  private record ExecStored(UUID method,Strategy strategy,String sha,
      UUID organization,String scope) {}
  private record JobStored(UUID id,String state,String error,
      UUID org,String scope) {}

  private final JdbcTemplate sql;
  private final PlatformTransactionManager manager;
  private final UatF02PinnedPlanReader reader;
  private final HybridMethodRegistry registry;
  private final ObjectMapper mapper;
  private final boolean enabled;

  public UatHybridPlanCommitter(JdbcTemplate sql,PlatformTransactionManager manager,
      UatF02PinnedPlanReader reader,HybridMethodRegistry registry,ObjectMapper mapper,
      @Value("$"+"{fin.calc.hybrid.uat.planner.enabled:false}") boolean enabled){
    this.sql=Objects.requireNonNull(sql);
    this.manager=Objects.requireNonNull(manager);
    this.reader=Objects.requireNonNull(reader);
    this.registry=Objects.requireNonNull(registry);
    this.mapper=Objects.requireNonNull(mapper);
    this.enabled=enabled;
  }

  public Receipt planExact(UUID legacyPlanId,UUID organizationId,String scopeKey,
      UUID methodRevisionId,Strategy strategy){
    return planWithFault(legacyPlanId,organizationId,scopeKey,
        methodRevisionId,strategy,FaultPoint.NONE);
  }

  Receipt planWithFault(UUID legacyPlanId,UUID organizationId,String scopeKey,
      UUID methodRevisionId,Strategy strategy,FaultPoint fault){
    if(!enabled)throw new IllegalStateException("HYB2B_UAT_PLANNER_DISABLED");
    if(legacyPlanId==null||organizationId==null||scopeKey==null
        ||methodRevisionId==null||strategy==null||fault==null)
      throw new IllegalArgumentException("HYB2B_EXACT_SCOPE_AND_STRATEGY_REQUIRED");

    // The caller must not hold a transaction. One scope lock + the complete
    // four-write commit are a single database operation. Fault injection
    // verifies a failed insertion cannot leave partial plans or jobs.
    var tx=new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    tx.setTimeout(25);
    return tx.execute(status->commit(legacyPlanId,organizationId,scopeKey,
        methodRevisionId,strategy,fault));
  }

  private Receipt commit(UUID legacyPlanId,UUID organization,String scope,
      UUID methodId,Strategy strategy,FaultPoint fault){
    var verified=reader.verify(legacyPlanId,organization,scope);
    var method=registry.requireF02TestOnly(methodId,verified.formVersionId());
    var control=sql.query("""
        SELECT active_factset_id,qualification_epoch,revoked
        FROM fin_fact.factset_control
        WHERE organization_id=? AND stable_scope_key=? FOR SHARE
        """,(r,i)->new Object[]{r.getObject(1,UUID.class),r.getLong(2),
            r.getBoolean(3)},organization,scope);
    if(control.size()!=1||!verified.factsetId().equals(control.getFirst()[0])
        ||verified.epoch()!=(long)control.getFirst()[1]
        ||(boolean)control.getFirst()[2])
      throw new IllegalStateException("HYB2B_SOURCE_REVOKED_OR_SUPERSEDED");

    var seed=sql.query("""
        SELECT p.id,p.dag_jsonb::text,p.execution_fingerprint,
          p.qualified_binding_sha256,p.qualification_epoch,
          p.algorithm_artifact_sha256,p.config_sha256,p.params_sha256
        FROM fin_calc.calc_plan p
        WHERE p.id=? AND p.organization_id=? AND p.scope_key=?
        """,(r,i)->new PlanSeed(r.getObject(1,UUID.class),r.getString(2),
            r.getString(3),r.getString(4),r.getLong(5),
            r.getString(6),r.getString(7),r.getString(8)),
        legacyPlanId,organization,scope);
    if(seed.size()!=1)throw new IllegalStateException("HYB2B_SEED_PLAN_MISSING");
    var base=seed.getFirst();
    if(!base.executionFingerprint().equals(verified.executionFingerprint())
        ||!base.sourceBindingSha256().equals(verified.bindingSha256())
        ||base.epoch()!=verified.epoch()
        ||!base.artifactSha().equals(method.algorithmArtifactSha256())
        ||!base.configSha().equals(method.configSha256())
        ||!base.paramsSha().equals(method.paramsSha256()))
      throw new IllegalStateException("HYB2B_SEED_ARTIFACT_DRIFT");

    String contractSha=FactDigest.sha("FIN-FACT-HYB2B-CONTRACT-V1",
        method.id().toString(),method.methodCode(),method.methodVersion(),
        method.sourceContractSha256(),method.outputContractSha256(),
        method.dagSha256(),method.algorithmArtifactSha256(),method.configSha256(),
        method.paramsSha256(),method.sqlArtifactSha256(),strategy.name());
    String fingerprint=FactDigest.sha("FIN-FACT-HYB2B-EXECUTION-V1",
        base.executionFingerprint(),method.id().toString(),
        contractSha,strategy.name(),verified.factsetId().toString(),
        verified.factsetSha256(),verified.sourceRevisionId().toString(),
        verified.formVersionId().toString(),verified.sourceContentSha256(),
        verified.bindingSha256(),Long.toString(verified.epoch()));
    ObjectNode dag=dagFromSeed(base.dag());
    dag.put("hybridContract",CONTRACT);
    dag.put("methodRevisionId",method.id().toString());
    dag.put("methodVersion",method.methodVersion());
    dag.put("methodSourceContractSha256",method.sourceContractSha256());
    dag.put("methodOutputContractSha256",method.outputContractSha256());
    dag.put("sqlArtifactSha256",method.sqlArtifactSha256());
    dag.put("legacySourcePlanId",legacyPlanId.toString());
    dag.put("exactFactsetId",verified.factsetId().toString());
    dag.put("sourceFactsetSha256",verified.factsetSha256());
    dag.put("sourceRevisionId",verified.sourceRevisionId().toString());
    dag.put("sourceContentSha256",verified.sourceContentSha256());
    dag.put("executionStrategy",strategy.name());
    dag.put("executionContractSha256",contractSha);
    dag.put("executionFingerprint",fingerprint);
    String dagJson=dag.toString();

    UUID proposed=UUID.randomUUID();
    int inserted=sql.update("""
        INSERT INTO fin_calc.calc_plan (
          id,organization_id,scope_key,factset_id,execution_fingerprint,
          qualified_binding_sha256,qualification_epoch,
          algorithm_artifact_sha256,config_sha256,params_sha256,
          dag_jsonb,qualification_mode)
        VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,'TEST_QUALIFIED')
        ON CONFLICT (organization_id,scope_key,execution_fingerprint) DO NOTHING
        """,proposed,organization,scope,verified.factsetId(),fingerprint,
        verified.bindingSha256(),verified.epoch(),
        method.algorithmArtifactSha256(),method.configSha256(),
        method.paramsSha256(),dagJson);
    var saved=sql.query("""
        SELECT id,factset_id,execution_fingerprint,qualified_binding_sha256,
          qualification_epoch,algorithm_artifact_sha256,config_sha256,params_sha256,
          dag_jsonb::text,qualification_mode
        FROM fin_calc.calc_plan
        WHERE organization_id=? AND scope_key=? AND execution_fingerprint=?
        """,(r,i)->new PlanStored(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getString(3),r.getString(4),r.getLong(5),r.getString(6),
            r.getString(7),r.getString(8),r.getString(9),r.getString(10)),
        organization,scope,fingerprint);
    if(saved.size()!=1)throw new IllegalStateException("HYB2B_PLAN_NOT_PERSISTED");
    var p=saved.getFirst();
    if(!p.factset().equals(verified.factsetId())
        ||!p.fingerprint().equals(fingerprint)
        ||!p.binding().equals(verified.bindingSha256())
        ||p.epoch()!=verified.epoch()
        ||!p.artifact().equals(method.algorithmArtifactSha256())
        ||!p.config().equals(method.configSha256())
        ||!p.params().equals(method.paramsSha256())
        ||!"TEST_QUALIFIED".equals(p.mode())
        ||!dag.equals(parse(p.dag())))
      throw new IllegalStateException("HYB2B_PLAN_CONFLICT");
    fail(fault,FaultPoint.AFTER_PLAN);

    sql.update("""
        INSERT INTO fin_calc.plan_factset_binding(
          plan_id,organization_id,plan_scope_key,ordinal,
          source_factset_id,source_scope_key,source_factset_sha256)
        VALUES(?,?,?,?,?,?,?)
        ON CONFLICT (plan_id,ordinal) DO NOTHING
        """,p.id(),organization,scope,0,verified.factsetId(),scope,
        verified.factsetSha256());
    var source=sql.query("""
        SELECT source_factset_id,source_factset_sha256,
          source_scope_key,organization_id
        FROM fin_calc.plan_factset_binding
        WHERE plan_id=? AND ordinal=0
        """,(r,i)->new SourceStored(r.getObject(1,UUID.class),
            r.getString(2),r.getString(3),r.getObject(4,UUID.class)),p.id());
    if(source.size()!=1||!source.getFirst().id().equals(verified.factsetId())
        ||!source.getFirst().sha().equals(verified.factsetSha256())
        ||!source.getFirst().scope().equals(scope)
        ||!source.getFirst().organization().equals(organization))
      throw new IllegalStateException("HYB2B_SOURCE_BINDING_CONFLICT");
    fail(fault,FaultPoint.AFTER_SOURCE);

    sql.update("""
        INSERT INTO fin_calc.plan_execution_binding(
          plan_id,organization_id,scope_key,method_revision_id,
          strategy,execution_contract_sha256)
        VALUES(?,?,?,?,?,?)
        ON CONFLICT (plan_id) DO NOTHING
        """,p.id(),organization,scope,method.id(),strategy.name(),contractSha);
    var execution=sql.query("""
        SELECT method_revision_id,strategy,execution_contract_sha256,
          organization_id,scope_key
        FROM fin_calc.plan_execution_binding WHERE plan_id=?
        """,(r,i)->new ExecStored(r.getObject(1,UUID.class),
            Strategy.valueOf(r.getString(2)),r.getString(3),
            r.getObject(4,UUID.class),r.getString(5)),p.id());
    if(execution.size()!=1||!execution.getFirst().method().equals(method.id())
        ||execution.getFirst().strategy()!=strategy
        ||!execution.getFirst().sha().equals(contractSha)
        ||!execution.getFirst().organization().equals(organization)
        ||!execution.getFirst().scope().equals(scope))
      throw new IllegalStateException("HYB2B_EXECUTION_BINDING_CONFLICT");
    fail(fault,FaultPoint.AFTER_EXECUTION);

    UUID jobId=UUID.randomUUID();
    sql.update("""
        INSERT INTO fin_calc.calc_job (
          id,plan_id,organization_id,scope_key,state,last_error_code)
        VALUES(?,?,?,?,'BLOCKED','HYB_WORKER_NOT_ADMITTED')
        ON CONFLICT (plan_id) DO NOTHING
        """,jobId,p.id(),organization,scope);
    var jobs=sql.query("""
        SELECT id,state,last_error_code,organization_id,scope_key
        FROM fin_calc.calc_job WHERE plan_id=?
        """,(r,i)->new JobStored(r.getObject(1,UUID.class),r.getString(2),
            r.getString(3),r.getObject(4,UUID.class),r.getString(5)),p.id());
    if(jobs.size()!=1||!"BLOCKED".equals(jobs.getFirst().state())
        ||!BLOCKER.equals(jobs.getFirst().error())
        ||!organization.equals(jobs.getFirst().org())
        ||!scope.equals(jobs.getFirst().scope()))
      throw new IllegalStateException("HYB2B_JOB_CONFLICT");
    fail(fault,FaultPoint.AFTER_JOB);

    return new Receipt(p.id(),jobs.getFirst().id(),verified.factsetId(),
        method.id(),strategy,fingerprint,contractSha,inserted==0,
        jobs.getFirst().state());
  }

  private ObjectNode dagFromSeed(String json){
    JsonNode node=parse(json);
    if(!(node instanceof ObjectNode object))
      throw new IllegalStateException("HYB2B_DAG_SHAPE_INVALID");
    if(object.has("hybridContract")||object.has("executionStrategy")
        ||object.has("executionFingerprint"))
      throw new IllegalStateException("HYB2B_LEGACY_SOURCE_PLAN_REQUIRED");
    return object.deepCopy();
  }

  private JsonNode parse(String json){
    try{return mapper.readTree(json);}
    catch(Exception e){throw new IllegalStateException("HYB2B_DAG_JSON_INVALID",e);}
  }

  private static void fail(FaultPoint actual,FaultPoint checkpoint){
    if(actual==checkpoint)throw new IllegalStateException("HYB2B_FORCED_ROLLBACK");
  }
}
