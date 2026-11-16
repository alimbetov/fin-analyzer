package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Composes immutable pilot CORE snapshots from task results which the
 * SingleExecutor has already committed. No speculative financial numbers.
 * Report publication to outsiders and ML enrichment are deliberately absent.
 */
@Service
public final class CompositeCoreReportPublisher {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final boolean enabled;

    public CompositeCoreReportPublisher(JdbcTemplate jdbc, ObjectMapper json,
            PlatformTransactionManager transactions,
            @Value("${fin.execution.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;
        this.json=json;
        this.tx=new TransactionTemplate(transactions);
        this.enabled=enabled;
    }

    @Scheduled(fixedDelayString="${fin.execution.poll-ms:10000}")
    public void scheduledPublish() {
        if(enabled) publishReady();
    }

    /** One terminal run per short transaction. Returns true only for new snapshot. */
    public boolean publishReady() {
        return Boolean.TRUE.equals(tx.execute(status->{
            var runs=jdbc.query("""
                SELECT r.id,r.organization_id,r.fact_set_revision_id,
                       r.snapshot_hash,r.run_status,o.data_space
                FROM fin_exec.analysis_run r
                JOIN fin_exec.fact_publication_outbox o ON o.event_id=r.event_id
                WHERE r.run_status IN ('COMPLETED','PARTIAL','FAILED')
                  AND NOT EXISTS (
                    SELECT 1 FROM fin_exec.core_report_snapshot s WHERE s.run_id=r.id)
                ORDER BY r.created_at,r.id
                LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                """,(rs,i)->new Run(rs.getObject(1,UUID.class),
                    rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                    rs.getString(4),rs.getString(5),rs.getString(6)));
            if(runs.isEmpty()) return false;
            var run=runs.getFirst();
            var tasks=jdbc.query("""
                SELECT method_code,task_status,result_json::text,result_error
                FROM fin_exec.analysis_task
                WHERE run_id=? ORDER BY method_code
                """,(rs,i)->new Task(rs.getString(1),rs.getString(2),
                    rs.getString(3),rs.getString(4)),run.id());
            var methods=new ArrayList<Map<String,Object>>();
            var findings=new ArrayList<JsonNode>();
            for(var task:tasks) {
                var method=new LinkedHashMap<String,Object>();
                method.put("methodCode",task.code());
                method.put("executionStatus",task.status());
                method.put("qualification","PILOT_UNQUALIFIED");
                if(task.error()!=null) method.put("reason",task.error());
                if(task.result()!=null) {
                    try {
                        JsonNode result=json.readTree(task.result());
                        method.put("result",result);
                        if(SyntheticBusinessDiagnosticAdapter.CODE.equals(task.code())) {
                            String assessment=result.path("assessmentId").asText();
                            if(!assessment.isBlank()) {
                                var snapshots=jdbc.query("""
                                    SELECT snapshot::text FROM fin_diag.assessment_run
                                    WHERE id=? AND organization_id=? AND fact_set_revision_id=?
                                    """,(rs,i)->rs.getString(1),
                                        UUID.fromString(assessment),run.org(),run.revision());
                                if(snapshots.size()!=1)
                                    throw new IllegalStateException("MISSING_LINKED_DIAGNOSTIC");
                                JsonNode assessmentJson=json.readTree(snapshots.getFirst());
                                for(JsonNode finding:assessmentJson.path("findings")) findings.add(finding);
                            }
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("INVALID_PUBLISHED_TASK_EVIDENCE",e);
                    }
                }
                methods.add(method);
            }
            boolean synthetic="SYNTHETIC".equals(run.dataSpace());
            var report=new LinkedHashMap<String,Object>();
            report.put("schemaVersion","CORE_PILOT_V1");
            report.put("reportMode","CORE");
            report.put("coreReadiness","PARTIAL");
            report.put("mlReadiness","NOT_REQUESTED");
            report.put("sourceClassification",run.dataSpace());
            report.put("sourceRevisionId",run.revision().toString());
            report.put("sourceFingerprint",run.hash());
            report.put("executionRunId",run.id().toString());
            report.put("executionStatus",run.status());
            report.put("qualification","PILOT_UNQUALIFIED");
            report.put("methods",methods);
            report.put("diagnosticFindings",findings);
            report.put("limitations",List.of(
                "NOT_A_QUALIFIED_FINANCIAL_OR_AUDIT_OPINION",
                synthetic ? "SYNTHETIC_TEST_DATA" : "NO_QUALIFIED_FINANCIAL_METHOD_EXECUTED",
                "ML_NOT_EXECUTED"));
            try {
                jdbc.update("""
                    INSERT INTO fin_exec.core_report_snapshot
                        (id,run_id,organization_id,fact_set_revision_id,snapshot)
                    VALUES (?,?,?,?,CAST(? AS jsonb))
                    ON CONFLICT(run_id) DO NOTHING
                    """,UUID.randomUUID(),run.id(),run.org(),run.revision(),
                    json.writeValueAsString(report));
            }catch(Exception e){throw new IllegalStateException("CORE_REPORT_PUBLISH_FAILED",e);}
            return true;
        }));
    }

    public JsonNode load(UUID authorizedOrganizationId,UUID reportId) {
        var found=jdbc.query("""
            SELECT snapshot::text, snapshot_hash,
                   encode(sha256(convert_to(snapshot::text,'UTF8')),'hex')
            FROM fin_exec.core_report_snapshot
            WHERE id=? AND organization_id=?
            """,(rs,i)->new String[]{rs.getString(1),rs.getString(2),rs.getString(3)},
                reportId,authorizedOrganizationId);
        if(found.size()!=1)throw new IllegalArgumentException("CORE_REPORT_NOT_FOUND_OR_FORBIDDEN");
        if(!found.getFirst()[1].equals(found.getFirst()[2]))
            throw new IllegalStateException("CORE_REPORT_INTEGRITY_FAILURE");
        try{return json.readTree(found.getFirst()[0]);}
        catch(Exception e){throw new IllegalStateException("CORRUPTED_REPORT_JSON",e);}
    }

    private record Run(UUID id,UUID org,UUID revision,String hash,String status,String dataSpace){}
    private record Task(String code,String status,String result,String error){}
}
