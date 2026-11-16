package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.persistence.TrustedBusinessDiagnosticService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Uses the existing immutable diagnostic assessment and its qualified source
 * resolver. Experimental rules are strictly SYNTHETIC-only, never ACTUAL.
 * This is a pilot capability, not a qualified 60-method family.
 */
@Component
public final class SyntheticBusinessDiagnosticAdapter implements MethodTaskAdapter {
    public static final String CODE = "SYS/DIAGNOSTIC_PILOT";
    private final JdbcTemplate jdbc;
    private final TrustedBusinessDiagnosticService diagnostics;
    private final ObjectMapper json;

    public SyntheticBusinessDiagnosticAdapter(
            JdbcTemplate jdbc, TrustedBusinessDiagnosticService diagnostics,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.diagnostics = diagnostics;
        this.json = json;
    }

    @Override public String methodCode() { return CODE; }

    @Override public List<String> dependsOn() {
        return List.of("SYS/VERIFY_PUBLISHED_FACT_SET");
    }

    @Override public boolean autoEligible() { return true; }

    @Override public boolean applicable(TrustedRevision revision) {
        return "SYNTHETIC".equals(revision.dataSpace());
    }

    @Override public String execute(TrustedRevision revision) throws Exception {
        if (!applicable(revision))
            throw new IllegalArgumentException("PILOT_DIAGNOSTICS_SYNTHETIC_ONLY");

        List<LocalDate> months = jdbc.query("""
            SELECT max(a.start_inclusive)::date
              FROM fin_fact.fact_selection s
              JOIN fin_fact.fact_assertion a
                ON a.id=s.fact_assertion_id AND a.organization_id=s.organization_id
             WHERE s.organization_id=? AND s.fact_set_revision_id=?
               AND a.metric_code='REV_NET' AND a.time_kind='INTERVAL'
               AND a.economic_entity_id=? AND a.business_scope_id=?
            HAVING count(*)>0
            """, (rs,n)->rs.getObject(1,LocalDate.class),
                revision.organizationId(),revision.factSetRevisionId(),
                revision.economicEntityId(),revision.businessScopeId());
        if (months.size()!=1 || months.getFirst()==null)
            throw new IllegalStateException("DIAGNOSTIC_REVENUE_PERIOD_MISSING");
        YearMonth asOf=YearMonth.from(months.getFirst());
        var receipt=diagnostics.assessAndPublish(
                revision.organizationId(),revision.factSetRevisionId(),asOf);
        var saved=diagnostics.loadPublished(revision.organizationId(),receipt.id());
        return json.writeValueAsString(Map.of(
                "kind","SYNTHETIC_DIAGNOSTIC_PILOT",
                "qualification","PILOT_UNQUALIFIED",
                "sourceRevisionId",revision.factSetRevisionId().toString(),
                "assessmentId",receipt.id().toString(),
                "snapshotHash",receipt.snapshotHash(),
                "evaluationMonth",asOf.toString(),
                "findingCount",saved.snapshot().path("findings").size()));
    }
}
