package kz.fin.backend.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.intake.HtapFinancialSliceKey;
import kz.fin.backend.intake.HtapPublishedSliceCandidateResolver;
import kz.fin.backend.security.FinanceAccessPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * FIN-BE-R2.B PLAN_ONLY incremental foundation.
 *
 * Exactly one verified published FactSet => deterministic blocked decisions in
 * dependency order; immutable DB snapshot of this blocked plan. No analysis
 * task, executor invocation, ExecutionPermit, financial method S2 or report.
 * A future independently reviewed runnable PlanCommit MUST be a separate
 * contract, not reinterpretation of the blocked-only persisted snapshots.
 */
@Service
public class HtapBlockedPlanOnlyService {
    public record Node(String methodCode, String variantVersion, String status,
                       String applicabilityStatus, List<String> reasonCodes,
                       List<String> dependencyCodes, String sourceFingerprint,
                       String registryFingerprint, boolean executionPermitted) {
        public Node {
            reasonCodes=List.copyOf(reasonCodes);
            dependencyCodes=List.copyOf(dependencyCodes);
            if (!"BLOCKED".equals(status) || executionPermitted
                    || reasonCodes.isEmpty())
                throw new IllegalArgumentException("FIN_HTAP_ONLY_BLOCKED_PLAN_ALLOWED");
        }
    }
    public record Preview(MethodApplicabilityEvaluator.SourceKey source,
                          long catalogEpoch, String registryFingerprint,
                          List<Node> nodes, boolean executionPermitted) {
        public Preview {
            nodes=List.copyOf(nodes);
            if (nodes.isEmpty() || executionPermitted)
                throw new IllegalArgumentException("FIN_HTAP_BLOCKED_PLAN_REQUIRED");
        }
    }
    public record Committed(UUID planId, String planSha256, String state) {
        public Committed {
            if (!"BLOCKED_PLAN_ONLY".equals(state))
                throw new IllegalArgumentException("FIN_HTAP_PLAN_STATE_UNQUALIFIED");
        }
        public boolean executable() { return false; }
    }

    private final MethodApplicabilityEvaluator applicability;
    private final MethodVariantRegistry registry;
    private final HtapPublishedSliceCandidateResolver slices;
    private final FinanceAccessPolicy access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public HtapBlockedPlanOnlyService(MethodApplicabilityEvaluator applicability,
                                      MethodVariantRegistry registry,
                                      HtapPublishedSliceCandidateResolver slices,
                                      FinanceAccessPolicy access,
                                      JdbcTemplate jdbc, ObjectMapper json) {
        this.applicability=Objects.requireNonNull(applicability);
        this.registry=Objects.requireNonNull(registry);
        this.slices=Objects.requireNonNull(slices);
        this.access=Objects.requireNonNull(access);
        this.jdbc=Objects.requireNonNull(jdbc);
        this.json=Objects.requireNonNull(json);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public Preview preview(HtapFinancialSliceKey key, Jwt trustedJwt) {
        Objects.requireNonNull(key);
        access.require(trustedJwt,key.organizationId(),
                FinanceAccessPolicy.Permission.FIN_ANALYZE);
        var slice=slices.resolve(key,trustedJwt); // additionally requires FIN_READ
        if (slice.status()==HtapPublishedSliceCandidateResolver.Status.SOURCE_NOT_FOUND
                || slice.status()==HtapPublishedSliceCandidateResolver.Status.SOURCE_AMBIGUOUS)
            throw new IllegalArgumentException(slice.reasonCode());

        var source=new MethodApplicabilityEvaluator.SourceKey(
                key.organizationId(),key.economicEntityId(),key.businessScopeId(),
                key.publishedFactSetRevisionId(),key.publishedSnapshotSha256(),
                key.dataSpace());
        var decisions=applicability.evaluate(source);
        Map<String,MethodApplicabilityEvaluator.Decision> byCode=new HashMap<>();
        for(var d:decisions) {
            if(byCode.put(d.methodCode(),d)!=null)
                throw new IllegalStateException("FIN_HTAP_DUPLICATE_METHOD_DECISION");
        }

        var ordered=new ArrayList<Node>();
        var visited=new HashSet<String>();
        var visiting=new HashSet<String>();
        for(var descriptor:registry.all())
            appendDependencyFirst(descriptor.methodCode(),byCode,visited,visiting,
                    ordered,slice.reasonCode());

        List<Long> epochs=jdbc.query("""
            SELECT catalog_epoch FROM fin_calc.htap_catalog_epoch
             WHERE organization_id=?
            """,(rs,i)->rs.getLong(1),key.organizationId());
        long epoch=epochs.isEmpty()?0:epochs.getFirst();
        return new Preview(source,epoch,registry.fingerprint(),ordered,false);
    }

    private void appendDependencyFirst(
            String methodCode,Map<String,MethodApplicabilityEvaluator.Decision> byCode,
            Set<String> visited, Set<String> visiting,
            List<Node> ordered,String mappingReason) {
        if(visited.contains(methodCode))return;
        if(!visiting.add(methodCode))
            throw new IllegalStateException("FIN_HTAP_DAG_CYCLE");
        var descriptor=registry.require(methodCode);
        for(var dependency:descriptor.dependencies())
            appendDependencyFirst(dependency.methodCode(),byCode,visited,visiting,
                    ordered,mappingReason);
        visiting.remove(methodCode);
        visited.add(methodCode);
        var decision=byCode.get(methodCode);
        if(decision==null)
            throw new IllegalStateException("FIN_HTAP_METHOD_APPLICABILITY_MISSING");
        var reasons=new LinkedHashSet<>(decision.reasonCodes());
        reasons.add(mappingReason);
        reasons.add("FIN_HTAP_IMPLEMENTATION_ARTIFACT_UNPINNED");
        reasons.add("FIN_HTAP_INDEPENDENT_METHOD_S2_PENDING");
        ordered.add(new Node(methodCode,descriptor.variantVersion(),"BLOCKED",
                decision.status().name(),List.copyOf(reasons),
                descriptor.dependencies().stream().map(MethodVariantDescriptor.Dependency::methodCode)
                        .toList(),
                decision.sourceFingerprint(),decision.registryFingerprint(),false));
    }

    /**
     * Persist a fully BLOCKED planning receipt, never a runnable method plan.
     * Recomputes the preview from trusted database state in the SAME snapshot:
     * no client-provided preview, catalog epoch, method SHA or actor is accepted.
     */
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Committed commitBlocked(HtapFinancialSliceKey key, Jwt trustedJwt) {
        Preview snapshot=preview(key,trustedJwt);
        String payload;
        try {
            payload=json.writeValueAsString(snapshot.nodes());
        } catch(JsonProcessingException e) {
            throw new IllegalStateException("FIN_HTAP_PLAN_SERIALIZATION_FAILED",e);
        }
        UUID id=UUID.randomUUID();
        var saved=jdbc.query("""
            INSERT INTO fin_exec.htap_blocked_method_plan(
                plan_id,organization_id,economic_entity_id,business_scope_id,
                fact_set_revision_id,snapshot_hash,data_space,catalog_epoch,
                registry_fingerprint,plan_payload,plan_sha256,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?)
            ON CONFLICT (organization_id,plan_sha256) DO NOTHING
            RETURNING plan_id,plan_sha256,plan_state
            """,(rs,i)->new Committed(
                    rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)),
                id,snapshot.source().organizationId(),snapshot.source().economicEntityId(),
                snapshot.source().businessScopeId(),snapshot.source().factSetRevisionId(),
                snapshot.source().snapshotHash(),snapshot.source().dataSpace(),
                snapshot.catalogEpoch(),snapshot.registryFingerprint(),payload,
                "0".repeat(64),access.subject(trustedJwt));
        if(saved.size()==1)return saved.getFirst();
        // SQL before-insert guard computes the SHA. Match the *exact*
        // immutable source/payload, not just tenant or supplied plan id.
        var existing=jdbc.query("""
            SELECT plan_id,plan_sha256,plan_state
              FROM fin_exec.htap_blocked_method_plan
             WHERE organization_id=? AND economic_entity_id=?
               AND business_scope_id=? AND fact_set_revision_id=?
               AND snapshot_hash=? AND data_space=? AND catalog_epoch=?
               AND registry_fingerprint=? AND plan_payload=CAST(? AS jsonb)
            """,(rs,i)->new Committed(
                rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)),
                snapshot.source().organizationId(),snapshot.source().economicEntityId(),
                snapshot.source().businessScopeId(),snapshot.source().factSetRevisionId(),
                snapshot.source().snapshotHash(),snapshot.source().dataSpace(),
                snapshot.catalogEpoch(),snapshot.registryFingerprint(),payload);
        if(existing.size()!=1)
            throw new IllegalStateException("FIN_HTAP_PLAN_HASH_COLLISION_OR_CONFLICT");
        return existing.getFirst();
    }
}
