package kz.fin.formlib.calc.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.fact.FactDigest;
import kz.fin.formlib.calc.fact.UatF02PinnedPlanReader;
import kz.fin.formlib.calc.kernel.F02CalculationKernel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * CALC-R0.2 UAT-only worker.
 *
 * TX-C: claim READY/RETRY or expired RUNNING with lease and fencing.
 * CPU: verify immutable pinned FactSet/Plan, calculate F02 BigDecimal.
 * TX-D: lock job, validate lease+fence, append immutable result and SUCCEEDED
 *       atomically (idempotent per execution fingerprint).
 * TX-E: lock authority control and independently gate CURRENT publication.
 *
 * No public endpoints, no external S2, no production signature assertion.
 */
@Service
public final class UatF02CalcWorker {
  public record Claim(UUID jobId,UUID planId,UUID organizationId,
      String scopeKey,String workerId,long fencingToken,
      OffsetDateTime leaseUntil) {}
  public record Completion(UUID resultId,UUID planId,UUID organizationId,
      String scopeKey,String executionFingerprint,
      String outputValuesSha256,String resultSha256,boolean reused) {}
  public enum Publication { PUBLISHED, ALREADY_CURRENT, OBSOLETE_OR_REVOKED }
  public record WorkResult(Claim claim,Completion completion,Publication publication) {}

  enum FaultPoint { NONE, BEFORE_RESULT_INSERT, AFTER_RESULT_INSERT, BEFORE_JOB_SUCCESS }
  private static final int MAX_ATTEMPTS=5;

  private final JdbcTemplate sql;
  private final PlatformTransactionManager manager;
  private final UatF02PinnedPlanReader verifiedReader;
  private final F02CalculationKernel kernel=new F02CalculationKernel();
  private final ObjectMapper mapper;
  private final boolean enabled;

  public UatF02CalcWorker(JdbcTemplate sql,PlatformTransactionManager manager,
      UatF02PinnedPlanReader verifiedReader,ObjectMapper mapper,
      @Value("$"+"{fin.calc.uat.worker.enabled:false}") boolean enabled){
    this.sql=sql;this.manager=manager;this.verifiedReader=verifiedReader;
    this.mapper=mapper;this.enabled=enabled;
  }

  public WorkResult runOne(String owner){
    Claim claim=claimNext(owner,60);
    if(claim==null)return null;
    Completion result=executeClaim(claim);
    Publication publication=publishCurrent(result);
    return new WorkResult(claim,result,publication);
  }

  /** Safe to invoke from multiple independent processes; commits before CPU. */
  public Claim claimNext(String owner,int leaseSeconds){
    requireEnabled();
    if(owner==null||!owner.matches("[a-zA-Z0-9._:-]{1,120}")
        ||leaseSeconds<10||leaseSeconds>600)
      throw new IllegalArgumentException("CALC_R02_WORKER_ADMISSION_INVALID");
    var tx=new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    tx.setTimeout(10);
    return tx.execute(status->{
      // Recover expired RUNNING attempts, but never steal a live lease.
      sql.update("""
          UPDATE fin_calc.calc_job SET state='FAILED',lease_owner=NULL,
            lease_until=NULL,last_error_code='CALC_R02_MAX_ATTEMPTS',
            updated_at=now()
          WHERE state='RUNNING' AND lease_until<now() AND attempts>=?
          """,MAX_ATTEMPTS);
      var next=sql.query("""
          WITH candidate AS (
            SELECT id FROM fin_calc.calc_job
            WHERE attempts<? AND (
              (state IN ('READY','RETRY') AND next_attempt_at<=now())
              OR (state='RUNNING' AND lease_until<now()))
            ORDER BY created_at,id
            FOR UPDATE SKIP LOCKED LIMIT 1
          )
          UPDATE fin_calc.calc_job j
          SET state='RUNNING',attempts=j.attempts+1,
              fencing_token=j.fencing_token+1,
              lease_owner=?,lease_until=now()+(? * interval '1 second'),
              last_error_code=NULL,updated_at=now()
          FROM candidate c WHERE j.id=c.id
          RETURNING j.id,j.plan_id,j.organization_id,j.scope_key,
              j.lease_owner,j.fencing_token,j.lease_until
          """,(r,i)->new Claim(
              r.getObject(1,UUID.class),r.getObject(2,UUID.class),
              r.getObject(3,UUID.class),r.getString(4),
              r.getString(5),r.getLong(6),r.getObject(7,OffsetDateTime.class)),
          MAX_ATTEMPTS,owner,leaseSeconds);
      if(next.size()>1)throw new IllegalStateException("CALC_R02_CLAIM_MULTIPLE");
      return next.isEmpty()?null:next.getFirst();
    });
  }

  public Completion executeClaim(Claim claim){
    return executeClaimWithFault(claim,FaultPoint.NONE);
  }

  Completion executeClaimWithFault(Claim claim,FaultPoint fault){
    requireEnabled();
    if(claim==null||fault==null)
      throw new IllegalArgumentException("CALC_R02_CLAIM_REQUIRED");

    // SOURCE DATA ARE IMMUTABLE; safe to read separately from short TX-C/D.
    // Recompute exact candidate SHA, plan fingerprint and each observation SHA.
    var verified=verifiedReader.verify(claim.planId(),claim.organizationId(),
        claim.scopeKey());
    var evaluated=kernel.evaluate(verified.observations());
    String resultSha=FactDigest.sha("FIN-FACT-CALC-RESULT-V1",
        verified.executionFingerprint(),verified.bindingSha256(),
        Long.toString(verified.epoch()),evaluated.outputValuesSha256());
    Map<String,Object> resultTree=new LinkedHashMap<>();
    resultTree.put("contract",F02CalculationKernel.CONTRACT);
    resultTree.put("method","F02_TEST_ONLY_V1");
    resultTree.put("outputs",evaluated.nodes());
    resultTree.put("outputValuesSha256",evaluated.outputValuesSha256());
    Map<String,Object> lineage=new LinkedHashMap<>();
    lineage.put("mode","LOCAL_TEST_ONLY");
    lineage.put("planId",verified.planId().toString());
    lineage.put("organizationId",verified.organizationId().toString());
    lineage.put("projectId",verified.projectId().toString());
    lineage.put("scopeKey",verified.scopeKey());
    lineage.put("factsetId",verified.factsetId().toString());
    lineage.put("factsetSha256",verified.factsetSha256());
    lineage.put("bindingSha256",verified.bindingSha256());
    lineage.put("qualificationEpoch",verified.epoch());
    lineage.put("executionFingerprint",verified.executionFingerprint());
    lineage.put("sourceRevisionId",verified.sourceRevisionId().toString());
    lineage.put("sourceFormVersionId",verified.formVersionId().toString());
    lineage.put("sourceContentSha256",verified.sourceContentSha256());
    String json=write(resultTree),lineageJson=write(lineage);

    var tx=new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    tx.setTimeout(20);
    return tx.execute(status->commitFenced(claim,verified,evaluated,
        resultSha,json,lineageJson,fault));
  }

  private Completion commitFenced(Claim claim,UatF02PinnedPlanReader.Verified binding,
      F02CalculationKernel.Result calculated,String resultSha,
      String resultJson,String lineage,FaultPoint fault){
    var jobs=sql.query("""
        SELECT id,plan_id,organization_id,scope_key,state,lease_owner,
          fencing_token,lease_until
        FROM fin_calc.calc_job WHERE id=? FOR UPDATE
        """,(r,i)->new Object[]{
            r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getObject(3,UUID.class),r.getString(4),r.getString(5),
            r.getString(6),r.getLong(7),r.getObject(8,OffsetDateTime.class)},
        claim.jobId());
    if(jobs.size()!=1)throw new IllegalStateException("CALC_R02_JOB_MISSING");
    Object[] j=jobs.getFirst();
    var expires=(OffsetDateTime)j[7];
    if(!claim.planId().equals(j[1])||!claim.organizationId().equals(j[2])
        ||!claim.scopeKey().equals(j[3])
        ||!"RUNNING".equals(j[4])||!claim.workerId().equals(j[5])
        ||claim.fencingToken()!=(long)j[6]||expires==null
        ||!expires.isAfter(OffsetDateTime.now()))
      throw new IllegalStateException("CALC_R02_LEASE_LOST");

    // A revocation may race CPU work. Historical results can remain stored,
    // but TX-E must independently deny CURRENT if authority changed.
    if(fault==FaultPoint.BEFORE_RESULT_INSERT)
      throw new IllegalStateException("CALC_R02_FORCED_ROLLBACK");
    UUID proposedId=UUID.randomUUID();
    int inserted=sql.update("""
        INSERT INTO fin_calc.calc_result (
          id,plan_id,organization_id,scope_key,execution_fingerprint,
          output_values_sha256,result_sha256,result_jsonb,lineage_jsonb,
          qualification_mode)
        VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb,'LOCAL_TEST_ONLY')
        ON CONFLICT (organization_id,scope_key,execution_fingerprint) DO NOTHING
        """,proposedId,binding.planId(),binding.organizationId(),binding.scopeKey(),
        binding.executionFingerprint(),calculated.outputValuesSha256(),
        resultSha,resultJson,lineage);
    if(fault==FaultPoint.AFTER_RESULT_INSERT)
      throw new IllegalStateException("CALC_R02_FORCED_ROLLBACK");
    var stored=sql.query("""
        SELECT id,plan_id,output_values_sha256,result_sha256,
          result_jsonb::text,lineage_jsonb::text,qualification_mode
        FROM fin_calc.calc_result
        WHERE organization_id=? AND scope_key=? AND execution_fingerprint=?
        """,(r,i)->new Object[]{
            r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getString(3),r.getString(4),r.getString(5),
            r.getString(6),r.getString(7)},
        binding.organizationId(),binding.scopeKey(),binding.executionFingerprint());
    if(stored.size()!=1)throw new IllegalStateException("CALC_R02_RESULT_MISSING");
    var old=stored.getFirst();
    if(!binding.planId().equals(old[1])
        ||!calculated.outputValuesSha256().equals(old[2])
        ||!resultSha.equals(old[3])
        ||!parse(resultJson).equals(parse((String)old[4]))
        ||!parse(lineage).equals(parse((String)old[5]))
        ||!"LOCAL_TEST_ONLY".equals(old[6]))
      throw new IllegalStateException("NON_DETERMINISTIC_CALCULATION");
    if(fault==FaultPoint.BEFORE_JOB_SUCCESS)
      throw new IllegalStateException("CALC_R02_FORCED_ROLLBACK");
    int updated=sql.update("""
        UPDATE fin_calc.calc_job
        SET state='SUCCEEDED',lease_owner=NULL,lease_until=NULL,
            last_error_code=NULL,updated_at=now()
        WHERE id=? AND state='RUNNING' AND lease_owner=? AND fencing_token=?
        """,claim.jobId(),claim.workerId(),claim.fencingToken());
    if(updated!=1)throw new IllegalStateException("CALC_R02_JOB_FENCE_UPDATE_FAILED");
    return new Completion((UUID)old[0],binding.planId(),binding.organizationId(),
        binding.scopeKey(),binding.executionFingerprint(),
        calculated.outputValuesSha256(),resultSha,inserted==0);
  }

  /**
   * Separate TX-E: a completed historical calculation is NOT an authorization
   * to become CURRENT after newer FactSet/epoch or revocation.
   */
  public Publication publishCurrent(Completion completed){
    requireEnabled();
    if(completed==null)
      throw new IllegalArgumentException("CALC_R02_RESULT_REQUIRED");
    var tx=new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    tx.setTimeout(15);
    return tx.execute(status->{
      // Consistent lock order (control before mutable current pointer).
      var guards=sql.query("""
          SELECT organization_id,active_factset_id,qualification_epoch,revoked
          FROM fin_fact.factset_control WHERE stable_scope_key=? FOR UPDATE
          """,(r,i)->new Object[]{r.getObject(1,UUID.class),
              r.getObject(2,UUID.class),r.getLong(3),r.getBoolean(4)},
          completed.scopeKey());
      if(guards.size()!=1||!completed.organizationId().equals(guards.getFirst()[0]))
        throw new IllegalStateException("CALC_R02_AUTHORITY_CONTROL_MISSING");
      var plans=sql.query("""
          SELECT p.factset_id,p.qualification_epoch,p.execution_fingerprint,
            p.qualified_binding_sha256,r.result_sha256,
            r.output_values_sha256,r.qualification_mode,
            j.state
          FROM fin_calc.calc_plan p
          JOIN fin_calc.calc_result r ON r.plan_id=p.id
            AND r.organization_id=p.organization_id
            AND r.scope_key=p.scope_key
            AND r.execution_fingerprint=p.execution_fingerprint
          JOIN fin_calc.calc_job j ON j.plan_id=p.id
          WHERE p.id=? AND p.organization_id=? AND p.scope_key=? AND r.id=?
          """,(r,i)->new Object[]{
              r.getObject(1,UUID.class),r.getLong(2),r.getString(3),
              r.getString(4),r.getString(5),r.getString(6),r.getString(7),
              r.getString(8)},
          completed.planId(),completed.organizationId(),
          completed.scopeKey(),completed.resultId());
      if(plans.size()!=1)throw new IllegalStateException("CALC_R02_RESULT_PLAN_MISSING");
      var plan=plans.getFirst();
      if(!completed.executionFingerprint().equals(plan[2])
          ||!completed.resultSha256().equals(plan[4])
          ||!completed.outputValuesSha256().equals(plan[5])
          ||!"LOCAL_TEST_ONLY".equals(plan[6])
          ||!"SUCCEEDED".equals(plan[7]))
        throw new IllegalStateException("CALC_R02_RESULT_NOT_COMPLETED");
      var guard=guards.getFirst();
      if((boolean)guard[3]||!plan[0].equals(guard[1])
          ||(long)plan[1]!=(long)guard[2])
        return Publication.OBSOLETE_OR_REVOKED;
      var current=sql.query("""
          SELECT result_id,qualification_epoch FROM fin_calc.calc_current_result
          WHERE organization_id=? AND scope_key=? FOR UPDATE
          """,(r,i)->new Object[]{r.getObject(1,UUID.class),r.getLong(2)},
          completed.organizationId(),completed.scopeKey());
      if(!current.isEmpty()){
        if(completed.resultId().equals(current.getFirst()[0]))
          return Publication.ALREADY_CURRENT;
        if((long)current.getFirst()[1]>(long)plan[1])
          return Publication.OBSOLETE_OR_REVOKED;
      }
      int changes=sql.update("""
          INSERT INTO fin_calc.calc_current_result (
            id,organization_id,scope_key,result_id,qualification_epoch,row_version)
          VALUES(?,?,?,?,?,1)
          ON CONFLICT(organization_id,scope_key) DO UPDATE
          SET result_id=EXCLUDED.result_id,
              qualification_epoch=EXCLUDED.qualification_epoch,
              row_version=fin_calc.calc_current_result.row_version+1,
              updated_at=now()
          WHERE fin_calc.calc_current_result.qualification_epoch<=EXCLUDED.qualification_epoch
          """,UUID.randomUUID(),completed.organizationId(),completed.scopeKey(),
          completed.resultId(),plan[1]);
      if(changes!=1)throw new IllegalStateException("CALC_R02_CURRENT_CAS_CONFLICT");
      return Publication.PUBLISHED;
    });
  }

  /** Optional controlled heartbeat when CPU calculation becomes expensive. */
  public boolean heartbeat(Claim claim,int leaseSeconds){
    requireEnabled();
    if(claim==null||leaseSeconds<10||leaseSeconds>600)
      throw new IllegalArgumentException("CALC_R02_HEARTBEAT_INVALID");
    int count=sql.update("""
        UPDATE fin_calc.calc_job
        SET lease_until=now()+(? * interval '1 second'),updated_at=now()
        WHERE id=? AND state='RUNNING' AND lease_owner=?
          AND fencing_token=? AND lease_until>now()
        """,leaseSeconds,claim.jobId(),claim.workerId(),claim.fencingToken());
    return count==1;
  }

  private void requireEnabled(){
    if(!enabled)throw new IllegalStateException("CALC_R02_UAT_WORKER_DISABLED");
  }
  private String write(Map<String,Object> values){
    try{return mapper.writeValueAsString(values);}
    catch(Exception e){throw new IllegalStateException("CALC_R02_JSON_SERIALIZATION_FAILED",e);}
  }
  private JsonNode parse(String value){
    try{return mapper.readTree(value);}
    catch(Exception e){throw new IllegalArgumentException("CALC_R02_RESULT_JSON_INVALID",e);}
  }
}
