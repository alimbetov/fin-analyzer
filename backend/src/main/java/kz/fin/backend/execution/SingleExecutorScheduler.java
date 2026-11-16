package kz.fin.backend.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Durable at-least-once outbox consumer and dependency-aware task dispatcher.
 * Automatic polling is OFF until explicitly enabled by FIN_EXECUTION_ENABLED=true.
 * This service has no public API and MUST NOT accept client-supplied approval.
 */
@Service
public final class SingleExecutorScheduler {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Map<String,MethodTaskAdapter> adapters;
    private final boolean enabled;

    public SingleExecutorScheduler(JdbcTemplate jdbc, PlatformTransactionManager tx,
                                   List<MethodTaskAdapter> registered,
                                   @Value("${fin.execution.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;
        this.transactions=new TransactionTemplate(tx);
        this.enabled=enabled;
        var map=new LinkedHashMap<String,MethodTaskAdapter>();
        for(var adapter:registered) {
            if(map.putIfAbsent(adapter.methodCode(),adapter)!=null)
                throw new IllegalStateException("DUPLICATE_METHOD_ADAPTER: "+adapter.methodCode());
        }
        this.adapters=Map.copyOf(map);
        validateGraph();
    }

    /** Real scheduler, default disabled; tests invoke tick() explicitly. */
    @Scheduled(fixedDelayString="${fin.execution.poll-ms:10000}")
    public void scheduledTick() {
        if (enabled) tick();
    }

    /** At most one event and one compute task; no DB transaction during execution. */
    public boolean tick() {
        boolean planned=Boolean.TRUE.equals(transactions.execute(status->planOne()));
        Task task=transactions.execute(status->claimOne());
        if(task==null) return planned;
        var adapter=adapters.get(task.methodCode());
        if(adapter==null) { fail(task,"UNREGISTERED_METHOD_ADAPTER"); return true; }
        try {
            String json=adapter.execute(task.revision());
            if(json==null || json.isBlank()) throw new IllegalStateException("EMPTY_TASK_RESULT");
            transactions.executeWithoutResult(status->complete(task,json));
        } catch (Exception e) {
            String reason=e instanceof IllegalStateException ? "VALIDATION_FAILURE" : "EXECUTION_FAILURE";
            transactions.executeWithoutResult(status->retryOrFail(task,reason));
        }
        return true;
    }

    private boolean planOne() {
        var pending=jdbc.query("""
            SELECT o.event_id,o.organization_id,o.economic_entity_id,
                   o.business_scope_id,o.fact_set_revision_id,o.snapshot_hash,o.data_space
            FROM fin_exec.fact_publication_outbox o
            WHERE NOT EXISTS (SELECT 1 FROM fin_exec.outbox_delivery d WHERE d.event_id=o.event_id)
            ORDER BY o.created_at,o.event_id
            LIMIT 1 FOR UPDATE OF o SKIP LOCKED
            """,(rs,i)->new Event(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
            rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),
            rs.getObject(5,UUID.class),rs.getString(6),rs.getString(7)));
        if(pending.isEmpty()) return false;
        var event=pending.getFirst();
        var revision=event.trustedRevision();
        var methods=adapters.values().stream().filter(MethodTaskAdapter::autoEligible)
                .filter(a->a.applicable(revision))
                .sorted(Comparator.comparing(MethodTaskAdapter::methodCode)).toList();
        var included=methods.stream().map(MethodTaskAdapter::methodCode).collect(Collectors.toSet());
        for(var adapter:methods)
            if(!included.containsAll(adapter.dependsOn()))
                throw new IllegalStateException("UNSATISFIED_METHOD_DEPENDENCY: "+adapter.methodCode());
        UUID runId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_exec.analysis_run
                (id,event_id,organization_id,fact_set_revision_id,snapshot_hash,profile_code)
            VALUES (?,?,?,?,?,'AUTO_CORE_V1')
            """,runId,event.eventId(),revision.organizationId(),
            revision.factSetRevisionId(),revision.snapshotHash());
        var taskIds=new HashMap<String,UUID>();
        for(var adapter:methods) {
            UUID id=UUID.randomUUID();
            taskIds.put(adapter.methodCode(),id);
            jdbc.update("""
                INSERT INTO fin_exec.analysis_task(id,run_id,method_code,task_status)
                VALUES (?,?,?,'PENDING')
                """,id,runId,adapter.methodCode());
        }
        for(var adapter:methods)
            for(String depends:adapter.dependsOn())
                jdbc.update("""
                    INSERT INTO fin_exec.analysis_dependency(task_id,prerequisite_task_id)
                    VALUES (?,?)
                    """,taskIds.get(adapter.methodCode()),taskIds.get(depends));
        jdbc.update("""
            INSERT INTO fin_exec.outbox_delivery(event_id,analysis_run_id)
            VALUES (?,?)
            """,event.eventId(),runId);
        if(methods.isEmpty()) jdbc.update("""
            UPDATE fin_exec.analysis_run SET run_status='PARTIAL',finished_at=now()
            WHERE id=?
            """,runId);
        return true;
    }

    private Task claimOne() {
        // An exhausted expired lease is terminal. This runs under a short TX.
        jdbc.update("""
            UPDATE fin_exec.analysis_task
               SET task_status='FAILED_FINAL',result_error='ATTEMPT_LIMIT_EXCEEDED',
                   lease_until=NULL,updated_at=now()
             WHERE task_status='RUNNING' AND lease_until<now()
               AND attempt>=max_attempts
            """);
        // A failed predecessor blocks descendants instead of leaving them pending forever.
        jdbc.update("""
            UPDATE fin_exec.analysis_task t SET task_status='FAILED_FINAL',
                   result_error='PREREQUISITE_FAILED',updated_at=now()
             WHERE t.task_status='PENDING' AND EXISTS (
                 SELECT 1 FROM fin_exec.analysis_dependency d
                 JOIN fin_exec.analysis_task p ON p.id=d.prerequisite_task_id
                 WHERE d.task_id=t.id AND p.task_status IN ('FAILED_FINAL','BLOCKED_MISSING','BLOCKED_CONFLICT'))
            """);
        jdbc.update("""
            UPDATE fin_exec.analysis_task t SET task_status='READY',updated_at=now()
             WHERE t.task_status='PENDING' AND NOT EXISTS (
                 SELECT 1 FROM fin_exec.analysis_dependency d
                 JOIN fin_exec.analysis_task p ON p.id=d.prerequisite_task_id
                 WHERE d.task_id=t.id AND p.task_status<>'COMPUTED')
            """);
        var claimed=jdbc.query("""
            SELECT t.id,t.method_code,t.attempt,t.fencing_token,
                   r.id,r.organization_id,r.fact_set_revision_id,r.snapshot_hash,
                   o.economic_entity_id,o.business_scope_id,o.data_space
              FROM fin_exec.analysis_task t
              JOIN fin_exec.analysis_run r ON r.id=t.run_id
              JOIN fin_exec.fact_publication_outbox o ON o.event_id=r.event_id
             WHERE (t.task_status='READY' AND t.next_attempt_at<=now())
                OR (t.task_status='RUNNING' AND t.lease_until<now()
                    AND t.attempt<t.max_attempts)
             ORDER BY t.next_attempt_at,t.id
             LIMIT 1 FOR UPDATE OF t SKIP LOCKED
             """,(rs,i)->new Task(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getInt(3)+1,rs.getLong(4)+1,rs.getObject(5,UUID.class),
                new MethodTaskAdapter.TrustedRevision(
                    rs.getObject(6,UUID.class),rs.getObject(9,UUID.class),
                    rs.getObject(10,UUID.class),rs.getObject(7,UUID.class),
                    rs.getString(8),rs.getString(11))));
        if(claimed.isEmpty()) { refreshRunStates(); return null; }
        var t=claimed.getFirst();
        int changed=jdbc.update("""
            UPDATE fin_exec.analysis_task
               SET task_status='RUNNING',attempt=?,fencing_token=?,
                   lease_until=now()+interval '60 seconds',updated_at=now()
             WHERE id=? AND fencing_token=?
            """,t.attempt(),t.token(),t.taskId(),t.token()-1);
        if(changed!=1) throw new IllegalStateException("TASK_FENCING_CONFLICT");
        jdbc.update("UPDATE fin_exec.analysis_run SET run_status='RUNNING' WHERE id=? AND run_status='QUEUED'",t.runId());
        return t;
    }

    private void complete(Task task,String json) {
        int updated=jdbc.update("""
            UPDATE fin_exec.analysis_task
               SET task_status='COMPUTED',result_json=CAST(? AS jsonb),
                   result_error=NULL,lease_until=NULL,updated_at=now()
             WHERE id=? AND fencing_token=? AND task_status='RUNNING'
            """,json,task.taskId(),task.token());
        if(updated==0) return; // Late/stale worker: its output is discarded.
        refreshRunStates();
    }

    private void fail(Task task,String error) {
        transactions.executeWithoutResult(s->retryOrFail(task,error));
    }

    private void retryOrFail(Task task,String error) {
        jdbc.update("""
            UPDATE fin_exec.analysis_task SET
              task_status=CASE WHEN attempt>=max_attempts THEN 'FAILED_FINAL' ELSE 'READY' END,
              result_error=?,lease_until=NULL,
              next_attempt_at=now()+interval '1 second'*LEAST(60,(2 ^ LEAST(attempt,6))::integer),
              updated_at=now()
            WHERE id=? AND fencing_token=? AND task_status='RUNNING'
            """,error,task.taskId(),task.token());
        refreshRunStates();
    }

    private void refreshRunStates() {
        jdbc.update("""
            UPDATE fin_exec.analysis_run r SET
              run_status=CASE
                WHEN NOT EXISTS (SELECT 1 FROM fin_exec.analysis_task t
                                 WHERE t.run_id=r.id AND t.task_status<>'COMPUTED')
                  THEN 'COMPLETED'
                ELSE 'PARTIAL' END,
              finished_at=now()
            WHERE r.run_status IN ('QUEUED','RUNNING')
              AND EXISTS (SELECT 1 FROM fin_exec.analysis_task t WHERE t.run_id=r.id)
              AND NOT EXISTS (SELECT 1 FROM fin_exec.analysis_task t
                  WHERE t.run_id=r.id AND t.task_status IN ('PENDING','READY','RUNNING'))
            """);
    }

    private void validateGraph() {
        for(var adapter:adapters.values()) {
            for(String dep:adapter.dependsOn())
                if(!adapters.containsKey(dep)) throw new IllegalStateException("UNKNOWN_METHOD_DEPENDENCY: "+dep);
        }
        var finished=new HashSet<String>();
        for(String code:adapters.keySet()) visit(code,new HashSet<>(),finished);
    }
    private void visit(String code,Set<String> visiting,Set<String> done) {
        if(done.contains(code)) return;
        if(!visiting.add(code)) throw new IllegalStateException("DEPENDENCY_CYCLE: "+code);
        for(String dep:adapters.get(code).dependsOn()) visit(dep,visiting,done);
        visiting.remove(code);done.add(code);
    }

    private record Event(UUID eventId,UUID org,UUID entity,UUID scope,
                         UUID revision,String hash,String dataSpace) {
        MethodTaskAdapter.TrustedRevision trustedRevision() {
            return new MethodTaskAdapter.TrustedRevision(org,entity,scope,revision,hash,dataSpace);
        }
    }
    private record Task(UUID taskId,String methodCode,int attempt,long token,UUID runId,
                        MethodTaskAdapter.TrustedRevision revision) {}
}
