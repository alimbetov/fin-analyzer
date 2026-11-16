package kz.fin.backend.persistence;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.domain.EconomicKernel;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Transactional publication of an already reconciled fact set.
 *
 * Security boundary: caller MUST derive tenant and authorization from OIDC/ABAC,
 * never from a React-supplied organizationId. Published assertions are selected
 * only after DB validation of their source and canonical economic identity.
 */
@Service
public class FactSetPublicationService {
    private final JdbcTemplate jdbc;

    public FactSetPublicationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Request(UUID organizationId, UUID economicEntityId, UUID businessScopeId,
                          EconomicKernel.ReportingBasis reportingBasis, AnalysisContext.DataSpace dataSpace,
                          UUID candidateRevisionId, UUID expectedCurrentRevisionId) {
        public Request {
            Objects.requireNonNull(organizationId);
            Objects.requireNonNull(economicEntityId);
            Objects.requireNonNull(businessScopeId);
            Objects.requireNonNull(reportingBasis);
            Objects.requireNonNull(dataSpace);
            Objects.requireNonNull(candidateRevisionId);
        }
    }

    public record Publication(UUID revisionId, String snapshotHash, long currentVersion) {}

    /**
     * One transaction: compare current pointer, verify matching parent, publish
     * immutable selections, atomically advance scoped current pointer.
     * Concurrent initial publishes cannot both win the pointer's PK.
     */
    @Transactional
    public Publication publish(Request request) {
        Objects.requireNonNull(request);
        String sql = """
                SELECT fact_set_revision_id, version
                FROM fin_fact.current_fact_set
                WHERE organization_id=? AND economic_entity_id=? AND business_scope_id=?
                    AND reporting_basis=? AND data_space=? FOR UPDATE
                """;
        List<Current> pointers = jdbc.query(sql,(rs,rowNum) ->
                        new Current(rs.getObject("fact_set_revision_id",UUID.class),
                                    rs.getLong("version")),
                request.organizationId(),request.economicEntityId(),request.businessScopeId(),
                request.reportingBasis().name(),request.dataSpace().name());
        Current prior = pointers.isEmpty() ? null : pointers.getFirst();
        UUID currentId = prior == null ? null : prior.revisionId();

        if (!Objects.equals(currentId,request.expectedCurrentRevisionId())) {
            throw new OptimisticLockingFailureException("STALE_FACT_SET_REVISION");
        }
        List<UUID> parents=jdbc.query("""
                SELECT parent_revision_id
                FROM fin_fact.fact_set_revision
                WHERE id=? AND organization_id=?
                """,(rs,n)->(UUID)rs.getObject(1),request.candidateRevisionId(),request.organizationId());
        if(parents.size()!=1) throw new IllegalArgumentException("FACT_SET_REVISION_NOT_FOUND");
        if (!Objects.equals(parents.getFirst(),currentId))
            throw new OptimisticLockingFailureException("FACT_SET_PARENT_MISMATCH");

        // Placeholder overwritten by the trusted database trigger.
        jdbc.update("""
                INSERT INTO fin_fact.fact_set_publication(
                  fact_set_revision_id,organization_id,economic_entity_id,business_scope_id,
                  reporting_basis,data_space,snapshot_hash)
                VALUES (?,?,?,?,?,?,?)
                """,request.candidateRevisionId(),request.organizationId(),
                request.economicEntityId(),request.businessScopeId(),request.reportingBasis().name(),
                request.dataSpace().name(),"0".repeat(64));

        long version;
        if(prior==null) {
            int inserted=jdbc.update("""
                    INSERT INTO fin_fact.current_fact_set(
                        organization_id,economic_entity_id,business_scope_id,reporting_basis,
                        data_space,fact_set_revision_id,version)
                    VALUES (?,?,?,?,?,?,1) ON CONFLICT DO NOTHING
                    """,request.organizationId(),request.economicEntityId(),request.businessScopeId(),
                    request.reportingBasis().name(),request.dataSpace().name(),request.candidateRevisionId());
            if(inserted!=1) throw new OptimisticLockingFailureException("CONCURRENT_FIRST_PUBLICATION");
            version=1L;
        }else{
            int updated=jdbc.update("""
                    UPDATE fin_fact.current_fact_set
                       SET fact_set_revision_id=?, version=version+1
                    WHERE organization_id=? AND economic_entity_id=? AND business_scope_id=?
                      AND reporting_basis=? AND data_space=? AND fact_set_revision_id=? AND version=?
                    """,request.candidateRevisionId(),request.organizationId(),
                    request.economicEntityId(),request.businessScopeId(),
                    request.reportingBasis().name(),request.dataSpace().name(),currentId,prior.version());
            if(updated!=1) throw new OptimisticLockingFailureException("STALE_FACT_SET_REVISION");
            version=prior.version()+1;
        }

        String hash=jdbc.queryForObject("""
                SELECT snapshot_hash FROM fin_fact.fact_set_publication
                WHERE fact_set_revision_id=? AND organization_id=?
                """,String.class,request.candidateRevisionId(),request.organizationId());
        // The outbox append is part of the SAME transaction as fact publication
        // and pointer advancement. Consumers must deduplicate event delivery.
        // This event does not itself grant access to ACTUAL data.
        jdbc.update("""
                INSERT INTO fin_exec.fact_publication_outbox(
                    event_id, organization_id, economic_entity_id, business_scope_id,
                    fact_set_revision_id, event_type, reporting_basis, data_space,
                    snapshot_hash, current_version)
                VALUES (?,?,?,?,?,'FACT_SET_PUBLISHED',?,?,?,?)
                """,
                UUID.randomUUID(), request.organizationId(), request.economicEntityId(),
                request.businessScopeId(), request.candidateRevisionId(),
                request.reportingBasis().name(), request.dataSpace().name(), hash, version);

        return new Publication(request.candidateRevisionId(),Objects.requireNonNull(hash),version);
    }

    private record Current(UUID revisionId, long version) {}
}
