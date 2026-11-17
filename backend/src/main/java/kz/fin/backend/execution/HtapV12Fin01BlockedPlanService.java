package kz.fin.backend.execution;

import kz.fin.backend.domain.EconomicKernel;
import kz.fin.backend.intake.HtapFinancialSliceKey;
import kz.fin.backend.intake.HtapV11MonthlyRevenueSourceService;
import kz.fin.backend.intake.ReportingPeriodV5;
import kz.fin.backend.methodology.Fin01RevenueMomSqlModule;
import kz.fin.backend.security.FinanceAccessPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * HTAP-V1.2: exact two-month FIN-01 source + existing blocked R2.B plan.
 * No runnable configuration, S2 approval, execution tasks or result writer.
 */
@Service
public class HtapV12Fin01BlockedPlanService {
    public record Request(UUID organizationId,UUID entityId,UUID scopeId,
                          UUID publishedFactSetId,String snapshotSha256,
                          UUID sourceRevisionId,UUID mappingId,long expectedCatalogEpoch,
                          YearMonth previousMonth,YearMonth currentMonth) {}
    public record Preview(
            HtapV11MonthlyRevenueSourceService.VerifiedPair pair,
            HtapFinancialSliceKey previousSlice,
            String registryFingerprint,
            String variantVersion,
            String algorithmClassSha256,
            List<String> reasons,
            boolean executionPermitted) {
        public Preview {
            reasons=List.copyOf(reasons);
            if(executionPermitted || reasons.isEmpty())
                throw new IllegalArgumentException("FIN_HTAP_V12_BLOCKED_ONLY");
        }
    }
    public record Commit(UUID id,String sha256,UUID blockedPlanId,String state,
                         boolean executionPermitted) {
        public Commit {
            if(!"BLOCKED_PLAN_ONLY".equals(state)||executionPermitted)
                throw new IllegalArgumentException("FIN_HTAP_V12_BLOCKED_ONLY");
        }
    }

    private final HtapV11MonthlyRevenueSourceService sources;
    private final HtapBlockedPlanOnlyService plans;
    private final MethodVariantRegistry registry;
    private final FinanceAccessPolicy access;
    private final JdbcTemplate jdbc;
    public HtapV12Fin01BlockedPlanService(
            HtapV11MonthlyRevenueSourceService sources,
            HtapBlockedPlanOnlyService plans,MethodVariantRegistry registry,
            FinanceAccessPolicy access,JdbcTemplate jdbc) {
        this.sources=Objects.requireNonNull(sources);
        this.plans=Objects.requireNonNull(plans);
        this.registry=Objects.requireNonNull(registry);
        this.access=Objects.requireNonNull(access);
        this.jdbc=Objects.requireNonNull(jdbc);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Preview preview(Request request,Jwt trustedJwt) {
        Objects.requireNonNull(request);
        access.require(trustedJwt,request.organizationId(),
                FinanceAccessPolicy.Permission.FIN_ANALYZE);
        var pair=sources.verify(request.organizationId(),request.entityId(),
                request.scopeId(),request.publishedFactSetId(),
                request.snapshotSha256(),request.sourceRevisionId(),
                request.mappingId(),request.expectedCatalogEpoch(),
                request.previousMonth(),request.currentMonth(),trustedJwt);
        var mapping=jdbc.query("""
            SELECT c.mapping_version,f.dimension_hash
              FROM fin_calc.htap_mapping_candidate_version c
              JOIN fin_fact.fact_assertion f ON f.id=?
               AND f.organization_id=c.organization_id
             WHERE c.organization_id=? AND c.mapping_id=?
               AND c.mapping_sha256=?
            """,(rs,i)->new String[]{rs.getString(1),rs.getString(2)},
                pair.previous().factId(),request.organizationId(),request.mappingId(),
                pair.mappingSha256());
        if(mapping.size()!=1)
            throw new IllegalArgumentException("FIN_HTAP_V12_MAPPING_VERSION_MISSING");
        String periodCode=request.previousMonth().getYear()+"."+
                String.format(java.util.Locale.ROOT,"%02d",request.previousMonth().getMonthValue());
        HtapFinancialSliceKey slice=new HtapFinancialSliceKey(
                request.organizationId(),request.entityId(),request.scopeId(),
                pair.factSetRevisionId(),pair.publishedSnapshotSha256(),
                pair.sourceRevisionId(),"F02","FIN-PILOT-V1","SYNTHETIC",
                "MANAGEMENT","REV_NET","1.0.0",EconomicKernel.TimeKind.INTERVAL,
                ReportingPeriodV5.Type.MONTH,ReportingPeriodV5.Coverage.PERIOD_ONLY,
                periodCode,"ISO_CALENDAR","1","MONEY","KZT",
                mapping.getFirst()[1],mapping.getFirst()[0],pair.mappingSha256());
        var existingPreview=plans.preview(slice,trustedJwt);
        if(existingPreview.catalogEpoch()!=pair.catalogEpoch())
            throw new IllegalArgumentException("FIN_HTAP_V12_STALE_CATALOG_EPOCH");
        var fin01=existingPreview.nodes().stream()
                .filter(n->n.methodCode().equals("FIN-01/REV_NET_MOM"))
                .findFirst().orElseThrow();
        var binary=HtapRuntimeClassDigest.inspect(Fin01RevenueMomSqlModule.class);
        var reasons=new java.util.LinkedHashSet<>(fin01.reasonCodes());
        reasons.add("FIN_HTAP_EFFECTIVE_METHOD_CONFIG_UNPINNED");
        reasons.add("FIN_HTAP_BUILD_ARTIFACT_NOT_ATTESTED");
        reasons.add("FIN_HTAP_V12_PLAN_ONLY_NO_EXECUTION");
        return new Preview(pair,slice,existingPreview.registryFingerprint(),
                fin01.variantVersion(),binary.classSha256(),List.copyOf(reasons),false);
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public Commit commitBlocked(Request request,Jwt trustedJwt) {
        Preview preview=preview(request,trustedJwt);
        var parent=plans.commitBlocked(preview.previousSlice(),trustedJwt);
        UUID id=UUID.randomUUID();
        var p=preview.pair();
        var inserted=jdbc.query("""
            INSERT INTO fin_exec.htap_fin01_two_month_blocked_plan(
              id,organization_id,economic_entity_id,business_scope_id,
              fact_set_revision_id,snapshot_hash,blocked_plan_id,
              source_revision_id,mapping_id,mapping_sha256,catalog_epoch,
              registry_fingerprint,previous_fact_id,current_fact_id,
              previous_month,current_month,currency_code,period_policy_code,
              algorithm_class_sha256,plan_sha256,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (organization_id,plan_sha256) DO NOTHING
            RETURNING id,plan_sha256,blocked_plan_id,plan_state,execution_permitted
            """,(rs,i)->new Commit(rs.getObject(1,UUID.class),
                    rs.getString(2),rs.getObject(3,UUID.class),rs.getString(4),
                    rs.getBoolean(5)),
                id,request.organizationId(),request.entityId(),request.scopeId(),
                p.factSetRevisionId(),p.publishedSnapshotSha256(),parent.planId(),
                p.sourceRevisionId(),p.mappingId(),p.mappingSha256(),p.catalogEpoch(),
                preview.registryFingerprint(),p.previous().factId(),
                p.current().factId(),p.previous().period().atDay(1),
                p.current().period().atDay(1),"KZT",p.periodPolicyCode(),
                preview.algorithmClassSha256(),"0".repeat(64),
                access.subject(trustedJwt));
        if(inserted.size()==1)return inserted.getFirst();
        var existing=jdbc.query("""
            SELECT id,plan_sha256,blocked_plan_id,plan_state,execution_permitted
            FROM fin_exec.htap_fin01_two_month_blocked_plan
            WHERE organization_id=? AND economic_entity_id=? AND business_scope_id=?
              AND fact_set_revision_id=? AND snapshot_hash=? AND blocked_plan_id=?
              AND source_revision_id=? AND mapping_id=? AND mapping_sha256=?
              AND catalog_epoch=? AND registry_fingerprint=?
              AND previous_fact_id=? AND current_fact_id=?
              AND previous_month=? AND current_month=?
              AND algorithm_class_sha256=? AND currency_code='KZT'
              AND period_policy_code='ISO_MONTH_FLOW_PERIOD_ONLY_V1'
              AND plan_state='BLOCKED_PLAN_ONLY' AND NOT execution_permitted
            """,(rs,i)->new Commit(rs.getObject(1,UUID.class),
                    rs.getString(2),rs.getObject(3,UUID.class),rs.getString(4),
                    rs.getBoolean(5)),
                request.organizationId(),request.entityId(),request.scopeId(),
                p.factSetRevisionId(),p.publishedSnapshotSha256(),parent.planId(),
                p.sourceRevisionId(),p.mappingId(),p.mappingSha256(),p.catalogEpoch(),
                preview.registryFingerprint(),p.previous().factId(),
                p.current().factId(),p.previous().period().atDay(1),
                p.current().period().atDay(1),preview.algorithmClassSha256());
        if(existing.size()!=1)
            throw new IllegalStateException("FIN_HTAP_V12_PLAN_HASH_CONFLICT");
        return existing.getFirst();
    }
}
