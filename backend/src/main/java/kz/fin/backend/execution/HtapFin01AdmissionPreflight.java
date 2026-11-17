package kz.fin.backend.execution;

import kz.fin.backend.methodology.Fin01RevenueMomSqlModule;
import kz.fin.backend.security.FinanceAccessPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * HTAP-V1.3A: server-owned read-only admission inspection of the immutable
 * two-month FIN-01 SYNTHETIC blocked plan. No worker, task, lease, publish,
 * execution permit, config mutation, or ACTUAL fallback can originate here.
 *
 * An existing S2 approval is NOT a substitute for release authorization:
 * the current catalog does not contain a qualified FIN-01 validation profile,
 * and there is no signed shipped build/effective config attestation.
 */
@Service
public class HtapFin01AdmissionPreflight {
    public enum State { HOLD, REJECTED }
    public record Decision(UUID blockedPairId, State state, List<String> reasons,
                           String storedPlanSha256, String observedClassSha256,
                           boolean executionPermitted) {
        public Decision {
            Objects.requireNonNull(blockedPairId);
            Objects.requireNonNull(state);
            reasons=List.copyOf(Objects.requireNonNull(reasons));
            if(reasons.isEmpty() || executionPermitted)
                throw new IllegalArgumentException("FIN_HTAP_V13_EXECUTION_PERMIT_FORBIDDEN");
        }
    }
    private record Plan(UUID id, UUID factSetId, UUID mappingId, String mappingSha,
                        long epoch, String snapshot, String registry,
                        String classSha, String planSha, String state,
                        boolean permitted, String dataSpace,
                        UUID previousFact, UUID currentFact) {}
    private record ActiveConfig(String profile, String qualification,
                                String reviewDecision, String algorithmVersion,
                                String algorithmSha, String effectiveSha) {}

    private final JdbcTemplate jdbc;
    private final FinanceAccessPolicy access;
    private final MethodVariantRegistry registry;
    private final CatA31MethodReviewRegistry reviews;

    public HtapFin01AdmissionPreflight(JdbcTemplate jdbc,
            FinanceAccessPolicy access, MethodVariantRegistry registry,
            CatA31MethodReviewRegistry reviews) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.access=Objects.requireNonNull(access);
        this.registry=Objects.requireNonNull(registry);
        this.reviews=Objects.requireNonNull(reviews);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Decision inspect(UUID organizationId, UUID blockedPairId, Jwt verifiedJwt) {
        Objects.requireNonNull(organizationId);
        Objects.requireNonNull(blockedPairId);
        access.require(verifiedJwt,organizationId,FinanceAccessPolicy.Permission.FIN_READ);
        access.require(verifiedJwt,organizationId,FinanceAccessPolicy.Permission.FIN_ANALYZE);

        var found=jdbc.query("""
            SELECT p.id,p.fact_set_revision_id,p.mapping_id,p.mapping_sha256,
                   p.catalog_epoch,p.snapshot_hash,p.registry_fingerprint,
                   p.algorithm_class_sha256,p.plan_sha256,p.plan_state,
                   p.execution_permitted,b.data_space,p.previous_fact_id,p.current_fact_id
            FROM fin_exec.htap_fin01_two_month_blocked_plan p
            JOIN fin_exec.htap_blocked_method_plan b
              ON b.organization_id=p.organization_id AND b.plan_id=p.blocked_plan_id
            WHERE p.organization_id=? AND p.id=?
            """,(rs,i)->new Plan(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getString(4),rs.getLong(5),rs.getString(6),
                rs.getString(7),rs.getString(8),rs.getString(9),
                rs.getString(10),rs.getBoolean(11),rs.getString(12),
                rs.getObject(13,UUID.class),rs.getObject(14,UUID.class)),
                organizationId,blockedPairId);
        if(found.size()!=1)
            return new Decision(blockedPairId,State.REJECTED,
                    List.of("FIN_HTAP_V13_PLAN_NOT_FOUND_OR_FOREIGN"),
                    null,null,false);
        var plan=found.getFirst();
        if(!"SYNTHETIC".equals(plan.dataSpace())
                || !"BLOCKED_PLAN_ONLY".equals(plan.state()) || plan.permitted())
            throw new IllegalStateException("FIN_HTAP_V13_UNTRUSTED_PLAN_STATE");

        List<String> reasons=new ArrayList<>();
        if(!registry.fingerprint().equals(plan.registry()))
            reasons.add("FIN_HTAP_V13_REGISTRY_STALE");
        Long epoch=jdbc.queryForObject("""
            SELECT catalog_epoch FROM fin_calc.htap_catalog_epoch
            WHERE organization_id=?
            """,Long.class,organizationId);
        if(epoch==null || epoch.longValue()!=plan.epoch()
                || !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(
                      SELECT 1 FROM fin_calc.htap_mapping_active a
                      WHERE a.organization_id=? AND a.mapping_id=?
                        AND a.mapping_sha256=? AND a.catalog_epoch=?)
                    """,Boolean.class,organizationId,plan.mappingId(),
                    plan.mappingSha(),plan.epoch())))
            reasons.add("FIN_HTAP_V13_MAPPING_EPOCH_OR_ACTIVE_VERSION_STALE");

        boolean sourceUnchanged=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(
               SELECT 1 FROM fin_fact.fact_set_publication p
               JOIN fin_exec.fact_publication_outbox o
                 ON o.organization_id=p.organization_id
                AND o.fact_set_revision_id=p.fact_set_revision_id
                AND o.snapshot_hash=p.snapshot_hash
                AND o.event_type='FACT_SET_PUBLISHED'
               JOIN fin_fact.fact_selection first_fact
                 ON first_fact.organization_id=p.organization_id
                AND first_fact.fact_set_revision_id=p.fact_set_revision_id
                AND first_fact.fact_assertion_id=?
               JOIN fin_fact.fact_selection second_fact
                 ON second_fact.organization_id=p.organization_id
                AND second_fact.fact_set_revision_id=p.fact_set_revision_id
                AND second_fact.fact_assertion_id=?
              WHERE p.organization_id=? AND p.fact_set_revision_id=?
                AND p.snapshot_hash=? AND p.data_space='SYNTHETIC')
            """,Boolean.class,plan.previousFact(),plan.currentFact(),
                organizationId,plan.factSetId(),plan.snapshot()));
        if(!sourceUnchanged)
            reasons.add("FIN_HTAP_V13_PUBLISHED_SOURCE_STALE");

        String liveClass=HtapRuntimeClassDigest.inspect(Fin01RevenueMomSqlModule.class).classSha256();
        if(!liveClass.equals(plan.classSha()))
            reasons.add("FIN_HTAP_V13_CLASS_FINGERPRINT_CHANGED");
        // Existing method config moderation is not a financial S2 approval.
        // The NOT_EXECUTABLE profile must always fail closed.
        var configs=jdbc.query("""
            SELECT c.validation_profile,m.qualification,r.decision,
                   v.algorithm_version,v.algorithm_sha256,v.effective_sha256
            FROM fin_calc.method_active_config a
            JOIN fin_calc.method_config_version v
              ON v.config_id=a.config_id
             AND v.method_code=a.method_code
             AND v.variant_code=a.variant_code
            JOIN fin_calc.method_parameter_contract c
              ON c.contract_id=v.contract_id
             AND c.method_code=v.method_code
             AND c.variant_code=v.variant_code
            LEFT JOIN fin_calc.method_config_review r
              ON r.config_id=v.config_id
            LEFT JOIN fin_calc.metric_contract m
              ON m.metric_code='REV_NET' AND m.metric_version='1.0.0'
            WHERE a.method_code='FIN-01' AND a.variant_code='REV_NET_MOM'
            """,(rs,i)->new ActiveConfig(rs.getString(1),rs.getString(2),
                rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getString(6)));
        if(configs.size()!=1
                || !"APPROVED".equals(configs.getFirst().reviewDecision())
                || !"QUALIFIED".equals(configs.getFirst().qualification())
                || !"R2_SQL_PILOT_1".equals(configs.getFirst().algorithmVersion())
                || !"FIN01_MOM_V1".equals(configs.getFirst().profile())) {
            reasons.add("FIN_HTAP_V13_APPROVED_EXECUTABLE_CONFIG_MISSING");
        }
        // There is currently NO qualified FIN-01 parameter validation profile;
        // an "approved" editorial config cannot bypass it.
        reasons.add("FIN_HTAP_V13_METHOD_CONFIGURATION_FINANCIAL_QUALIFICATION_PENDING");

        var s2=reviews.lookup(new CatA31MethodReviewRegistry.Key(
                "FIN-01/REV_NET_MOM",Fin01RevenueMomSqlModule.VERSION,
                "F02","FIN-PILOT-V1","MANAGEMENT"));
        if(!s2.independentlyReviewed())
            reasons.add("FIN_HTAP_V13_METHOD_S2_"+s2.status().name());
        // Even with an independent S2 decision, no package+dependencies+
        // effective-config manifest signed by an authorized build publisher
        // exists in the V1.2 blocked plan. A class SHA is not that proof.
        reasons.add("FIN_HTAP_V13_DEPLOYED_ARTIFACT_ATTESTATION_MISSING");
        reasons.add("FIN_HTAP_V13_DURABLE_EXECUTION_PERMIT_NOT_ISSUED");
        reasons.add("FIN_HTAP_V13_LEASE_FENCE_NOT_AUTHORIZED");
        return new Decision(blockedPairId,State.HOLD,List.copyOf(reasons),
                plan.planSha(),liveClass,false);
    }
}
