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

/** V1.3B.2: persists exact evidence references, NEVER an ExecutionPermit.
 * The artifact candidate remains UNVERIFIED until a trusted deployment verifier
 * validates installed bytes, dependency lock and an authorized Ed25519 signature.
 */
@Service
public class HtapFin01EvidenceBindingService {
    public record Evidence(UUID planId, UUID configId, UUID s2ReviewId,
                           String artifactSha256, String dependencyLockSha256,
                           String buildManifestJson, String signerKeyId,
                           String signatureBase64) {
        public Evidence {
            Objects.requireNonNull(planId); Objects.requireNonNull(configId);
            Objects.requireNonNull(s2ReviewId);
            Objects.requireNonNull(artifactSha256);
            Objects.requireNonNull(dependencyLockSha256);
            Objects.requireNonNull(buildManifestJson);
            Objects.requireNonNull(signerKeyId);
            Objects.requireNonNull(signatureBase64);
        }
    }
    public record Binding(UUID id, String bindingSha256, String state,
                          boolean executionPermitted) {
        public Binding {
            if (!"EVIDENCE_BOUND_HOLD".equals(state) || executionPermitted)
                throw new IllegalArgumentException("FIN01_BINDING_NOT_EXECUTABLE");
        }
    }
    public record Inspection(UUID bindingId, String state, List<String> reasons,
                             String bindingSha256, boolean executionPermitted) {
        public Inspection {
            reasons=List.copyOf(reasons);
            if(executionPermitted || reasons.isEmpty())
                throw new IllegalArgumentException("FIN01_BINDING_INSPECTION_NOT_HOLD");
        }
    }
    private record Plan(String sha, String classSha, long epoch, UUID mappingId,
                        String mappingSha, String registry) {}
    private record Config(UUID contractId, String contractVersion, int version,
                          long pointerVersion, String effectiveSha, String paramsSha,
                          String schemaSha, String algorithmVersion, String algorithmSha) {}
    private record S2(String evidenceSha) {}

    private final JdbcTemplate jdbc;
    private final FinanceAccessPolicy access;
    private final MethodVariantRegistry registry;
    public HtapFin01EvidenceBindingService(JdbcTemplate jdbc, FinanceAccessPolicy access,
                                            MethodVariantRegistry registry) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.access=Objects.requireNonNull(access);
        this.registry=Objects.requireNonNull(registry);
    }

    // One immutable binding per plan is serialized by the PostgreSQL unique
    // constraint. READ_COMMITTED lets concurrent losing writers observe
    // the winner after ON CONFLICT DO NOTHING without a 40001 snapshot error.
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Binding bindUnverified(UUID organizationId, Evidence evidence, Jwt authenticated) {
        access.require(authenticated,organizationId,FinanceAccessPolicy.Permission.FIN_READ);
        access.require(authenticated,organizationId,FinanceAccessPolicy.Permission.FIN_ANALYZE);
        var plans=jdbc.query("""
            SELECT plan_sha256,algorithm_class_sha256,catalog_epoch,
                   mapping_id,mapping_sha256,registry_fingerprint
              FROM fin_exec.htap_fin01_two_month_blocked_plan
             WHERE organization_id=? AND id=?
            """,(rs,i)->new Plan(rs.getString(1),rs.getString(2),rs.getLong(3),
                    rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6)),
                organizationId,evidence.planId());
        if(plans.size()!=1)throw new IllegalArgumentException("FIN01_BINDING_PLAN_NOT_FOUND");
        Plan plan=plans.getFirst();
        if(!registry.fingerprint().equals(plan.registry()))
            throw new IllegalStateException("FIN01_BINDING_REGISTRY_STALE");
        var configs=jdbc.query("""
            SELECT v.contract_id,c.contract_version,v.version_no,a.pointer_version,
                   v.effective_sha256,v.params_sha256,c.schema_sha256,
                   v.algorithm_version,v.algorithm_sha256
              FROM fin_calc.method_active_config a
              JOIN fin_calc.method_config_version v ON v.config_id=a.config_id
              JOIN fin_calc.method_parameter_contract c ON c.contract_id=v.contract_id
             WHERE a.method_code='FIN-01' AND a.variant_code='REV_NET_MOM'
               AND a.config_id=? AND v.engine='SQL'
               AND c.validation_profile='FIN01_MONTHLY_MOM_TYPED_V1'
               AND EXISTS (SELECT 1 FROM fin_calc.method_config_review r
                           WHERE r.config_id=v.config_id AND r.decision='APPROVED')
            """,(rs,i)->new Config(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getInt(3),rs.getLong(4),rs.getString(5),rs.getString(6),
                    rs.getString(7),rs.getString(8),rs.getString(9)),evidence.configId());
        if(configs.size()!=1)throw new IllegalArgumentException("FIN01_BINDING_CONFIG_NOT_APPROVED_ACTIVE");
        Config config=configs.getFirst();
        if(!"R2_SQL_PILOT_1".equals(config.algorithmVersion())
                || !plan.classSha().equals(config.algorithmSha())
                || !HtapRuntimeClassDigest.inspect(Fin01RevenueMomSqlModule.class)
                          .classSha256().equals(plan.classSha()))
            throw new IllegalArgumentException("FIN01_BINDING_ALGORITHM_MISMATCH");
        var reviews=jdbc.query("""
            SELECT review_evidence_sha FROM fin_calc.method_s2_review
            WHERE id=?
            """,(rs,i)->new S2(rs.getString(1)),evidence.s2ReviewId());
        if(reviews.size()!=1)throw new IllegalArgumentException("FIN01_BINDING_S2_MISSING");
        var receipt=jdbc.query("""
            INSERT INTO fin_exec.htap_fin01_evidence_binding(
                id,organization_id,blocked_pair_id,plan_sha256,config_id,
                contract_id,contract_version,config_version,pointer_version,
                effective_sha256,params_sha256,schema_sha256,
                algorithm_id,algorithm_version,algorithm_class_sha256,
                method_s2_review_id,s2_evidence_sha256,
                artifact_sha256,dependency_lock_sha256,build_manifest,
                manifest_sha256,signer_key_id,signature_base64,binding_sha256,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,
                    'FIN-01/REV_NET_MOM','R2_SQL_PILOT_1',?,?,?,?,?,?::jsonb,
                    ?,?,?,?,?)
            ON CONFLICT (organization_id,blocked_pair_id) DO NOTHING
            RETURNING id,binding_sha256,binding_state,execution_permitted
            """,(rs,i)->new Binding(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getString(3),rs.getBoolean(4)),
                UUID.randomUUID(),organizationId,evidence.planId(),plan.sha(),
                evidence.configId(),config.contractId(),config.contractVersion(),
                config.version(),config.pointerVersion(),config.effectiveSha(),
                config.paramsSha(),config.schemaSha(),plan.classSha(),
                evidence.s2ReviewId(),reviews.getFirst().evidenceSha(),
                evidence.artifactSha256(),evidence.dependencyLockSha256(),
                evidence.buildManifestJson(),"0".repeat(64),
                evidence.signerKeyId(),evidence.signatureBase64(),
                "0".repeat(64),access.subject(authenticated));
        if(receipt.size()==1)return receipt.getFirst();
        var existing=jdbc.query("""
            SELECT id,binding_sha256,binding_state,execution_permitted
            FROM fin_exec.htap_fin01_evidence_binding
            WHERE organization_id=? AND blocked_pair_id=?
              AND plan_sha256=? AND config_id=? AND contract_id=?
              AND contract_version=? AND config_version=? AND pointer_version=?
              AND effective_sha256=? AND params_sha256=? AND schema_sha256=?
              AND algorithm_class_sha256=? AND method_s2_review_id=?
              AND s2_evidence_sha256=? AND artifact_sha256=?
              AND dependency_lock_sha256=? AND build_manifest=?::jsonb
              AND signer_key_id=? AND signature_base64=?
              AND artifact_verification='UNVERIFIED'
              AND binding_state='EVIDENCE_BOUND_HOLD' AND NOT execution_permitted
            """,(rs,i)->new Binding(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getString(3),rs.getBoolean(4)),
                organizationId,evidence.planId(),plan.sha(),evidence.configId(),
                config.contractId(),config.contractVersion(),config.version(),
                config.pointerVersion(),config.effectiveSha(),config.paramsSha(),
                config.schemaSha(),plan.classSha(),evidence.s2ReviewId(),
                reviews.getFirst().evidenceSha(),evidence.artifactSha256(),
                evidence.dependencyLockSha256(),evidence.buildManifestJson(),
                evidence.signerKeyId(),evidence.signatureBase64());
        if(existing.size()!=1)throw new IllegalStateException("FIN01_BINDING_IMMUTABLE_CONFLICT");
        return existing.getFirst();
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Inspection inspect(UUID org,UUID bindingId,Jwt jwt) {
        access.require(jwt,org,FinanceAccessPolicy.Permission.FIN_READ);
        access.require(jwt,org,FinanceAccessPolicy.Permission.FIN_ANALYZE);
        var rows=jdbc.query("""
          SELECT b.binding_sha256,b.config_id,b.pointer_version,b.method_s2_review_id,
                 b.algorithm_class_sha256,b.plan_sha256,p.plan_sha256,
                 p.catalog_epoch,p.mapping_id,p.mapping_sha256,
                 b.effective_sha256,b.params_sha256,b.contract_id,b.schema_sha256,
                 b.artifact_verification,b.execution_permitted
          FROM fin_exec.htap_fin01_evidence_binding b
          JOIN fin_exec.htap_fin01_two_month_blocked_plan p
            ON p.id=b.blocked_pair_id AND p.organization_id=b.organization_id
          WHERE b.organization_id=? AND b.id=?
          """,(rs,i)->new Object[]{rs.getString(1),rs.getObject(2,UUID.class),
            rs.getLong(3),rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6),
            rs.getString(7),rs.getLong(8),rs.getObject(9,UUID.class),
            rs.getString(10),rs.getString(11),rs.getString(12),
            rs.getObject(13,UUID.class),rs.getString(14),rs.getString(15),
            rs.getBoolean(16)},org,bindingId);
        if(rows.size()!=1)
            return new Inspection(bindingId,"REJECTED",List.of("FIN01_BINDING_UNKNOWN_OR_FOREIGN"),null,false);
        Object[] b=rows.getFirst();
        List<String> reasons=new ArrayList<>();
        if(!Objects.equals(b[5],b[6]))
            reasons.add("FIN01_BINDING_PLAN_SHA_STALE");
        if(!HtapRuntimeClassDigest.inspect(Fin01RevenueMomSqlModule.class)
                     .classSha256().equals(b[4]))
            reasons.add("FIN01_BINDING_DEPLOYED_CLASS_CHANGED");
        boolean current=Boolean.TRUE.equals(jdbc.queryForObject("""
           SELECT EXISTS(
            SELECT 1 FROM fin_calc.method_active_config a
            JOIN fin_calc.method_config_version v ON v.config_id=a.config_id
            JOIN fin_calc.method_parameter_contract c ON c.contract_id=v.contract_id
            JOIN fin_calc.method_config_review r ON r.config_id=v.config_id
            WHERE a.method_code='FIN-01' AND a.variant_code='REV_NET_MOM'
              AND a.config_id=? AND a.pointer_version=?
              AND v.effective_sha256=? AND v.params_sha256=? AND v.contract_id=?
              AND c.schema_sha256=? AND c.validation_profile='FIN01_MONTHLY_MOM_TYPED_V1'
              AND r.decision='APPROVED')
           """,Boolean.class,b[1],b[2],b[10],b[11],b[12],b[13]));
        if(!current)reasons.add("FIN01_BINDING_ACTIVE_CONFIG_CHANGED");
        boolean activeMap=Boolean.TRUE.equals(jdbc.queryForObject("""
           SELECT EXISTS(
             SELECT 1 FROM fin_calc.htap_catalog_epoch e
             JOIN fin_calc.htap_mapping_active a ON a.organization_id=e.organization_id
               AND a.catalog_epoch=e.catalog_epoch
             WHERE e.organization_id=? AND e.catalog_epoch=?
               AND a.mapping_id=? AND a.mapping_sha256=?)
           """,Boolean.class,org,b[7],b[8],b[9]));
        if(!activeMap)reasons.add("FIN01_BINDING_MAPPING_EPOCH_STALE");
        boolean effectiveS2=Boolean.TRUE.equals(jdbc.queryForObject("""
           SELECT EXISTS(
             SELECT 1 FROM fin_calc.method_s2_review s
             WHERE s.id=? AND s.review_decision='APPROVED'
               AND s.method_code='FIN-01/REV_NET_MOM'
               AND s.method_version='R2_SQL_PILOT_1'
               AND now()>=s.effective_from AND now()<s.effective_until
               AND NOT EXISTS (
                 SELECT 1 FROM fin_calc.method_s2_revocation r WHERE r.review_id=s.id))
           """,Boolean.class,b[3]));
        if(!effectiveS2)reasons.add("FIN01_BINDING_S2_REVOKED_OR_EXPIRED");
        reasons.add("FIN01_BINDING_ARTIFACT_SIGNATURE_AND_INSTALLED_BYTES_UNVERIFIED");
        reasons.add("FIN01_BINDING_EXECUTION_PERMIT_NOT_AVAILABLE");
        return new Inspection(bindingId,"HOLD",reasons,(String)b[0],false);
    }
}
