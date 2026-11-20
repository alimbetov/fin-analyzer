package kz.fin.formlib.calc.plan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * CALC-4D.5: PostgreSQL is the UAT-only scheduling authority.
 * No @Scheduled worker, HTTP route, S2 or production job is enabled.
 * Claim/reclaim use UPDATE...FROM SELECT FOR UPDATE SKIP LOCKED, so multiple
 * caller-controlled Java workers can safely compete.
 */
@Service
public class Calc4DUatLeaseQueue {
  public record Job(UUID id,UUID planId,UUID organizationId,String state,
      int attempts,int maxAttempts,long fence,String leaseOwner,
      boolean admittedToProduction) {}
  public record Claim(UUID jobId,UUID planId,UUID organizationId,
      String workerId,long fencingToken,int attempt) {}
  public record Enqueued(Job job,boolean replayed) {}

  private final JdbcTemplate jdbc;
  private final boolean enabled;
  public Calc4DUatLeaseQueue(JdbcTemplate jdbc,
      @Value("${fin.calc4d.uat-execution-enabled:false}") boolean enabled){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.enabled=enabled;
  }

  @Transactional
  public Enqueued enqueue(UUID org,UUID exactPlan){
    requireEnabled();
    if(org==null||exactPlan==null)
      throw new IllegalArgumentException("CALC4D_UAT_ENQUEUE_IDENTITY");
    Integer qualified=jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc4d_uat_plan p
      WHERE p.id=? AND p.organization_id=?
        AND p.admission_state='UAT_ONLY_NOT_S2_APPROVED'
        AND p.qualification_mode='SYNTHETIC_TEST_ONLY'
      """,Integer.class,exactPlan,org);
    if(qualified==null||qualified!=1)
      throw new IllegalStateException("CALC4D_UAT_ENQUEUE_PLAN_NOT_PINNED");
    Integer existingResult=jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc4d_uat_result
      WHERE plan_id=? AND organization_id=?
      """,Integer.class,exactPlan,org);
    if(existingResult==null||existingResult!=0)
      throw new IllegalStateException("CALC4D_UAT_ALREADY_EXECUTED_OUTSIDE_QUEUE");
    UUID jobId=UUID.randomUUID();
    int inserted=jdbc.update("""
      INSERT INTO fin_calc.calc4d_uat_job(id,plan_id,organization_id)
      VALUES(?,?,?)
      ON CONFLICT(plan_id) DO NOTHING
      """,jobId,exactPlan,org);
    Job job=requireJob(org,exactPlan);
    if(!"READY".equals(job.state())&&!"RETRY".equals(job.state())
        &&!"RUNNING".equals(job.state())&&!"SUCCEEDED".equals(job.state())
        &&!"FAILED".equals(job.state()))
      throw new IllegalStateException("CALC4D_UAT_QUEUE_STATE_UNKNOWN");
    if(inserted==1)receipt(job.id(),org,0,"ENQUEUED",null);
    return new Enqueued(job,inserted==0);
  }

  /**
   * One claim transaction per poll. Reclaim an expired RUNNING lease only
   * after bumping its fencing token, leaving the old owner powerless.
   * Exhausted stale jobs become FAILED rather than staying RUNNING forever.
   */
  @Transactional
  public Optional<Claim> claimNext(UUID org,String workerId,int leaseSeconds){
    requireEnabled();validate(org,workerId);
    if(leaseSeconds<5||leaseSeconds>600)
      throw new IllegalArgumentException("CALC4D_UAT_LEASE_BOUNDS");
    var exhausted=jdbc.query("""
      WITH expired AS (
        SELECT j.id FROM fin_calc.calc4d_uat_job j
        WHERE j.organization_id=? AND j.state='RUNNING'
          AND j.attempts>=j.max_attempts AND j.lease_until<clock_timestamp()
        ORDER BY j.created_at,j.id FOR UPDATE SKIP LOCKED LIMIT 32
      )
      UPDATE fin_calc.calc4d_uat_job j
      SET state='FAILED',lease_owner=NULL,lease_until=NULL,
        last_error_code='CALC4D_UAT_EXHAUSTED',
        updated_at=clock_timestamp()
      FROM expired WHERE j.id=expired.id
      RETURNING j.id,j.organization_id,j.fencing_token
      """,(r,i)->new Object[]{r.getObject(1,UUID.class),
        r.getObject(2,UUID.class),r.getLong(3)},org);
    for(var expired:exhausted)
      receipt((UUID)expired[0],(UUID)expired[1],(Long)expired[2],"EXHAUSTED",null);

    var result=jdbc.query("""
      WITH pick AS (
        SELECT j.id FROM fin_calc.calc4d_uat_job j
        WHERE j.organization_id=?
          AND j.attempts<j.max_attempts
          AND NOT EXISTS (
            SELECT 1 FROM fin_calc.calc4d_uat_result r WHERE r.plan_id=j.plan_id
          )
          AND (
            (j.state IN ('READY','RETRY') AND j.next_attempt_at<=clock_timestamp())
            OR (j.state='RUNNING' AND j.lease_until<clock_timestamp())
          )
        ORDER BY j.created_at,j.id
        FOR UPDATE SKIP LOCKED LIMIT 1
      )
      UPDATE fin_calc.calc4d_uat_job j
      SET state='RUNNING',attempts=j.attempts+1,
        fencing_token=j.fencing_token+1,lease_owner=?,
        lease_until=clock_timestamp()+(? * interval '1 second'),
        updated_at=clock_timestamp(),last_error_code=NULL
      FROM pick WHERE j.id=pick.id
      RETURNING j.id,j.plan_id,j.organization_id,j.lease_owner,
        j.fencing_token,j.attempts
      """,(r,i)->new Claim(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
        r.getObject(3,UUID.class),r.getString(4),r.getLong(5),r.getInt(6)),
      org,workerId,leaseSeconds);
    if(result.size()>1)throw new IllegalStateException("CALC4D_UAT_MULTIPLE_CLAIMS");
    if(result.isEmpty())return Optional.empty();
    Claim claim=result.getFirst();
    receipt(claim.jobId(),org,claim.fencingToken(),"CLAIMED",workerId);
    return Optional.of(claim);
  }

  @Transactional
  public void renew(Claim claim,int seconds){
    requireEnabled();validateClaim(claim);
    if(seconds<5||seconds>600)
      throw new IllegalArgumentException("CALC4D_UAT_LEASE_BOUNDS");
    int n=jdbc.update("""
      UPDATE fin_calc.calc4d_uat_job SET
        lease_until=clock_timestamp()+(? * interval '1 second'),
        updated_at=clock_timestamp()
      WHERE id=? AND organization_id=? AND plan_id=?
        AND state='RUNNING' AND fencing_token=? AND lease_owner=?
        AND lease_until>clock_timestamp()
      """,seconds,claim.jobId(),claim.organizationId(),claim.planId(),
      claim.fencingToken(),claim.workerId());
    if(n!=1)throw new IllegalStateException("CALC4D_UAT_STALE_LEASE_RENEWAL");
    receipt(claim.jobId(),claim.organizationId(),claim.fencingToken(),
      "RENEWED",claim.workerId());
  }

  @Transactional
  public Job failOrRetry(Claim claim,String errorCode,int backoffSeconds){
    requireEnabled();validateClaim(claim);
    if(errorCode==null||!errorCode.matches("[A-Z][A-Z0-9_]{2,95}")
        ||backoffSeconds<0||backoffSeconds>600)
      throw new IllegalArgumentException("CALC4D_UAT_FAILURE_POLICY_INVALID");
    int n=jdbc.update("""
      UPDATE fin_calc.calc4d_uat_job
      SET state=CASE WHEN attempts<max_attempts THEN 'RETRY' ELSE 'FAILED' END,
        lease_owner=NULL,lease_until=NULL,last_error_code=?,
        next_attempt_at=clock_timestamp()+(? * interval '1 second'),
        updated_at=clock_timestamp()
      WHERE id=? AND organization_id=? AND plan_id=?
        AND state='RUNNING' AND fencing_token=? AND lease_owner=?
        AND lease_until>clock_timestamp()
      """,errorCode,backoffSeconds,claim.jobId(),claim.organizationId(),
      claim.planId(),claim.fencingToken(),claim.workerId());
    if(n!=1)throw new IllegalStateException("CALC4D_UAT_STALE_LEASE_FAILURE");
    Job job=requireJob(claim.organizationId(),claim.planId());
    receipt(job.id(),job.organizationId(),job.fence(),
      "FAILED".equals(job.state())?"FAILED":"RETRY",claim.workerId());
    return job;
  }

  @Transactional(readOnly=true)
  public Job requireJob(UUID org,UUID planId){
    if(org==null||planId==null)
      throw new IllegalArgumentException("CALC4D_UAT_JOB_ID_REQUIRED");
    var rows=jdbc.query("""
      SELECT id,plan_id,organization_id,state,attempts,max_attempts,
        fencing_token,lease_owner FROM fin_calc.calc4d_uat_job
      WHERE organization_id=? AND plan_id=?
      """,(r,i)->new Job(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
        r.getObject(3,UUID.class),r.getString(4),r.getInt(5),r.getInt(6),
        r.getLong(7),r.getString(8),false),org,planId);
    if(rows.size()!=1)throw new IllegalStateException("CALC4D_UAT_JOB_UNKNOWN");
    return rows.getFirst();
  }

  void receipt(UUID jobId,UUID org,long fence,String action,String worker){
    UUID eventId=UUID.randomUUID();
    String digest=Calc4DTypedDagCompiler.sha("FIN-CALC4D5-UAT-EVENT-V1|"+
      eventId+"|"+jobId+"|"+org+"|"+fence+"|"+action+"|"+worker);
    jdbc.update("""
      INSERT INTO fin_calc.calc4d_uat_job_receipt(
        id,job_id,organization_id,fencing_token,event_code,worker_id,event_sha256)
      VALUES(?,?,?,?,?,?,?)
      """,eventId,jobId,org,fence,action,worker,digest);
  }

  static void validateClaim(Claim claim){
    if(claim==null||claim.jobId()==null||claim.planId()==null
        ||claim.organizationId()==null||claim.fencingToken()<1
        ||claim.attempt()<1)
      throw new IllegalArgumentException("CALC4D_UAT_CLAIM_REQUIRED");
    validate(claim.organizationId(),claim.workerId());
  }
  private static void validate(UUID org,String worker){
    if(org==null||worker==null||!worker.matches("[A-Za-z0-9_.-]{1,128}"))
      throw new IllegalArgumentException("CALC4D_UAT_WORKER_ID_INVALID");
  }
  private void requireEnabled(){
    if(!enabled)throw new IllegalStateException("CALC4D_UAT_EXECUTION_DISABLED");
  }
}
