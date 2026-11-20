package kz.fin.formlib.calc.plan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * CALC-4D.5: one fenced UAT worker invocation. No autonomous scheduler and
 * no production execution. The plan/result commit and SUCCEEDED job
 * transition are ATOMIC in a single PostgreSQL REPEATABLE_READ transaction.
 *
 * The job row is locked FOR UPDATE before computing, so a concurrent worker
 * cannot reclaim this token while the exact source is being verified.
 * Commit checks the real clock and fence again; an expired lease rolls
 * back both result and job-success transition.
 */
@Service
public class Calc4DUatLeasedWorker {
  private final JdbcTemplate jdbc;
  private final Calc4DUatSqlJdbcBatchExecutor batch;
  private final Calc4DUatLeaseQueue queue;
  private final boolean enabled;

  public Calc4DUatLeasedWorker(JdbcTemplate jdbc,
      Calc4DUatSqlJdbcBatchExecutor batch,Calc4DUatLeaseQueue queue,
      @Value("${fin.calc4d.uat-execution-enabled:false}") boolean enabled){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.batch=Objects.requireNonNull(batch);
    this.queue=Objects.requireNonNull(queue);
    this.enabled=enabled;
  }

  @Transactional(isolation=Isolation.REPEATABLE_READ,rollbackFor=Exception.class)
  public Calc4DUatSqlJdbcBatchExecutor.Result execute(
      Calc4DUatLeaseQueue.Claim claim) throws Exception{
    if(!enabled)throw new IllegalStateException("CALC4D_UAT_EXECUTION_DISABLED");
    Calc4DUatLeaseQueue.validateClaim(claim);
    var held=jdbc.query("""
      SELECT j.plan_id,j.organization_id,j.lease_owner,j.fencing_token,
        j.state,(j.lease_until>clock_timestamp()) AS valid_lease
      FROM fin_calc.calc4d_uat_job j
      WHERE j.id=? AND j.plan_id=? AND j.organization_id=?
      FOR UPDATE OF j
      """,(r,i)->new Object[]{r.getObject(1,UUID.class),
        r.getObject(2,UUID.class),r.getString(3),
        r.getLong(4),r.getString(5),r.getBoolean(6)},
      claim.jobId(),claim.planId(),claim.organizationId());
    if(held.size()!=1||!claim.planId().equals(held.getFirst()[0])
        ||!claim.organizationId().equals(held.getFirst()[1])
        ||!claim.workerId().equals(held.getFirst()[2])
        ||claim.fencingToken()!=(Long)held.getFirst()[3]
        ||!"RUNNING".equals(held.getFirst()[4])
        ||!Boolean.TRUE.equals(held.getFirst()[5]))
      throw new IllegalStateException("CALC4D_UAT_WORKER_FENCE_INVALID");

    // PostgreSQL's result BEFORE INSERT trigger independently checks the
    // transaction-local owner/fence against the locked current job row.
    jdbc.queryForObject("SELECT set_config('fin.calc4d.uat_worker',?,true)",
      String.class,claim.workerId());
    jdbc.queryForObject("SELECT set_config('fin.calc4d.uat_fence',?,true)",
      String.class,Long.toString(claim.fencingToken()));

    var result=batch.executeExact(claim.organizationId(),claim.planId());
    if(!claim.planId().equals(result.planId())
        ||result.financiallyApproved()
        ||!"UAT_SYNTHETIC_ONLY".equals(result.status()))
      throw new IllegalStateException("CALC4D_UAT_UNEXPECTED_BATCH_RESULT");

    // The PostgreSQL BEFORE INSERT trigger must have embedded the exact
    // job and fencing epoch into the immutable result itself.
    Integer lineage=jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc4d_uat_result
      WHERE id=? AND plan_id=? AND organization_id=?
        AND worker_job_id=? AND worker_fencing_token=?
        AND result_sha256=?
      """,Integer.class,result.resultId(),claim.planId(),
      claim.organizationId(),claim.jobId(),claim.fencingToken(),
      result.resultSha256());
    if(lineage==null||lineage!=1)
      throw new IllegalStateException("CALC4D_UAT_RESULT_FENCE_LINEAGE_MISSING");

    int n=jdbc.update("""
      UPDATE fin_calc.calc4d_uat_job j
      SET state='SUCCEEDED',lease_owner=NULL,lease_until=NULL,
        last_error_code=NULL,updated_at=clock_timestamp()
      WHERE j.id=? AND j.plan_id=? AND j.organization_id=?
        AND j.state='RUNNING' AND j.fencing_token=?
        AND j.lease_owner=? AND j.lease_until>clock_timestamp()
        AND EXISTS (
          SELECT 1 FROM fin_calc.calc4d_uat_result r
          WHERE r.id=? AND r.plan_id=j.plan_id
            AND r.organization_id=j.organization_id
            AND r.result_sha256=?
        )
      """,claim.jobId(),claim.planId(),claim.organizationId(),
      claim.fencingToken(),claim.workerId(),result.resultId(),result.resultSha256());
    if(n!=1)
      throw new IllegalStateException("CALC4D_UAT_WORKER_LEASE_EXPIRED_BEFORE_COMMIT");
    queue.receipt(claim.jobId(),claim.organizationId(),
      claim.fencingToken(),"SUCCEEDED",claim.workerId());
    return result;
  }
}
