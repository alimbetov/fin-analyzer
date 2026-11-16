package kz.fin.backend.execution;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Objects;

/**
 * A real, minimal executable task that validates trusted publication
 * provenance. This is system housekeeping, NOT one of the 60 economic methods.
 */
@Component
public final class PublishedFactIntegrityAdapter implements MethodTaskAdapter {
    private final JdbcTemplate jdbc;
    public PublishedFactIntegrityAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public String methodCode() { return "SYS/VERIFY_PUBLISHED_FACT_SET"; }

    @Override public String execute(TrustedRevision revision) {
        var record = jdbc.query("""
            SELECT p.snapshot_hash,
                   (SELECT count(*) FROM fin_fact.fact_selection s
                    JOIN fin_fact.fact_assertion a ON a.id = s.fact_assertion_id
                    JOIN fin_fact.approved_source_revision sr
                      ON sr.organization_id=s.organization_id
                     AND sr.source_revision_id=a.source_revision_id
                    WHERE s.organization_id=p.organization_id
                      AND s.fact_set_revision_id=p.fact_set_revision_id) AS verified_count
            FROM fin_fact.fact_set_publication p
            WHERE p.fact_set_revision_id=? AND p.organization_id=?
              AND p.economic_entity_id=? AND p.business_scope_id=?
            """, (rs,i)->new Integrity(rs.getString(1),rs.getLong(2)),
            revision.factSetRevisionId(),revision.organizationId(),
            revision.economicEntityId(),revision.businessScopeId());
        if (record.size()!=1 || !Objects.equals(record.getFirst().hash(),revision.snapshotHash())
                || record.getFirst().count()<1) {
            throw new IllegalStateException("UNTRUSTED_OR_CHANGED_FACT_SET");
        }
        return "{\"kind\":\"VERIFIED_FACT_SET\",\"approvedFactCount\":"
                +record.getFirst().count()+"}";
    }
    private record Integrity(String hash,long count) {}
}
