package kz.fin.backend.intake;

import kz.fin.backend.domain.EconomicKernel;
import kz.fin.backend.persistence.TrustedFinancialPopulationService;
import kz.fin.backend.security.FinanceAccessPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * HTAP-V1.1: one trustworthy, read-only SYNTHETIC FIN-01 source pair.
 * Source provenance and population come from the existing R4 guard, not a new
 * selection engine. This result is NOT a method execution permit or a MoM result.
 */
@Service
public class HtapV11MonthlyRevenueSourceService {
    public record Month(YearMonth period, UUID factId, String sourceRowId,
                        BigDecimal netRevenue, String currency) {
        public Month {
            Objects.requireNonNull(period);
            Objects.requireNonNull(factId);
            Objects.requireNonNull(sourceRowId);
            Objects.requireNonNull(netRevenue);
            Objects.requireNonNull(currency);
        }
    }
    public record VerifiedPair(UUID organizationId, UUID factSetRevisionId,
                               String publishedSnapshotSha256, UUID sourceRevisionId,
                               UUID mappingId, String mappingSha256, long catalogEpoch,
                               String periodPolicyCode, Month previous, Month current,
                               boolean executionPermitted) {
        public VerifiedPair {
            if (executionPermitted || !previous.period().plusMonths(1).equals(current.period())
                    || !previous.currency().equals(current.currency()))
                throw new IllegalArgumentException("FIN_HTAP_PAIR_NOT_QUALIFIED");
        }
    }
    private record Published(String snapshotHash, String dataSpace, String basis) {}
    private record Mapping(UUID id,String hash,long epoch,String sourceField,
                           String metricCode,String metricVersion,String unitCode) {}

    private final JdbcTemplate jdbc;
    private final TrustedFinancialPopulationService populations;
    private final FinanceAccessPolicy access;

    public HtapV11MonthlyRevenueSourceService(JdbcTemplate jdbc,
            TrustedFinancialPopulationService populations, FinanceAccessPolicy access) {
        this.jdbc=Objects.requireNonNull(jdbc);
        this.populations=Objects.requireNonNull(populations);
        this.access=Objects.requireNonNull(access);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public VerifiedPair verify(UUID organization, UUID entity, UUID scope,
            UUID factSetRevision, String expectedSnapshotSha256, UUID sourceRevision,
            UUID mappingId, long expectedCatalogEpoch,
            YearMonth previousMonth, YearMonth currentMonth, Jwt verifiedJwt) {
        access.require(verifiedJwt,organization,FinanceAccessPolicy.Permission.FIN_READ);
        access.require(verifiedJwt,organization,FinanceAccessPolicy.Permission.FIN_ANALYZE);
        Objects.requireNonNull(entity);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(factSetRevision);
        Objects.requireNonNull(sourceRevision);
        Objects.requireNonNull(mappingId);
        Objects.requireNonNull(previousMonth);
        Objects.requireNonNull(currentMonth);
        if (expectedSnapshotSha256==null || !expectedSnapshotSha256.matches("[0-9a-f]{64}")
                || expectedCatalogEpoch<1)
            throw new IllegalArgumentException("FIN_HTAP_PAIR_IDENTITY_INVALID");

        var prior=HtapIsoFinancialCalendar.requireMonthlyFlow(
                ReportingPeriodV5.Type.MONTH,
                previousMonth.getYear()+"."+String.format("%02d",previousMonth.getMonthValue()),
                ReportingPeriodV5.Coverage.PERIOD_ONLY,EconomicKernel.TimeKind.INTERVAL);
        var now=HtapIsoFinancialCalendar.requireMonthlyFlow(
                ReportingPeriodV5.Type.MONTH,
                currentMonth.getYear()+"."+String.format("%02d",currentMonth.getMonthValue()),
                ReportingPeriodV5.Coverage.PERIOD_ONLY,EconomicKernel.TimeKind.INTERVAL);
        HtapIsoFinancialCalendar.requireConsecutiveMonthlyFlow(prior,now);

        var published=jdbc.query("""
            SELECT p.snapshot_hash,p.data_space,p.reporting_basis
              FROM fin_fact.fact_set_publication p
              JOIN fin_exec.fact_publication_outbox o
                ON o.organization_id=p.organization_id
               AND o.fact_set_revision_id=p.fact_set_revision_id
               AND o.snapshot_hash=p.snapshot_hash
               AND o.economic_entity_id=p.economic_entity_id
               AND o.business_scope_id=p.business_scope_id
               AND o.event_type='FACT_SET_PUBLISHED'
             WHERE p.organization_id=? AND p.economic_entity_id=?
               AND p.business_scope_id=? AND p.fact_set_revision_id=?
            """,(rs,i)->new Published(rs.getString(1),rs.getString(2),rs.getString(3)),
                organization,entity,scope,factSetRevision);
        if(published.size()!=1 || !expectedSnapshotSha256.equals(published.getFirst().snapshotHash())
                || !"SYNTHETIC".equals(published.getFirst().dataSpace())
                || !"MANAGEMENT".equals(published.getFirst().basis()))
            throw new IllegalArgumentException("FIN_HTAP_PAIR_UNTRUSTED_PUBLICATION");

        var mapping=jdbc.query("""
            SELECT a.mapping_id,a.mapping_sha256,a.catalog_epoch,
                   s.source_field,s.metric_code,s.metric_version,s.unit_code
              FROM fin_calc.htap_mapping_active a
              JOIN fin_calc.htap_mapping_semantic_evidence s
                ON s.organization_id=a.organization_id AND s.mapping_id=a.mapping_id
              JOIN fin_calc.htap_mapping_candidate_version c
                ON c.organization_id=a.organization_id AND c.mapping_id=a.mapping_id
              JOIN fin_calc.htap_catalog_epoch e
                ON e.organization_id=a.organization_id
               AND e.catalog_epoch=a.catalog_epoch
             WHERE a.organization_id=? AND a.mapping_id=?
               AND a.catalog_epoch=? AND c.mapping_sha256=a.mapping_sha256
               AND c.form_code='F02' AND c.form_version='FIN-PILOT-V1'
               AND s.semantic_state='VALIDATED_PILOT_NOT_S2'
               AND s.period_policy_code='ISO_MONTH_FLOW_PERIOD_ONLY_V1'
               AND s.metric_code='REV_NET' AND s.metric_version='1.0.0'
               AND s.measure_kind='FLOW' AND s.time_kind='INTERVAL'
               AND s.unit_code='MONEY' AND NOT s.execution_permitted
            """,(rs,i)->new Mapping(rs.getObject(1,UUID.class),rs.getString(2),
                    rs.getLong(3),rs.getString(4),rs.getString(5),
                    rs.getString(6),rs.getString(7)),
                organization,mappingId,expectedCatalogEpoch);
        if(mapping.size()!=1)
            throw new IllegalArgumentException("FIN_HTAP_PAIR_MAPPING_NOT_SEMANTICALLY_VERIFIED");
        var reviewed=mapping.getFirst();

        var verified=populations.requireComplete(organization,factSetRevision,List.of(
                new TrustedFinancialPopulationService.RequiredGroup(sourceRevision,
                        "REV_NET",prior.startInclusive(),prior.endExclusive()),
                new TrustedFinancialPopulationService.RequiredGroup(sourceRevision,
                        "REV_NET",now.startInclusive(),now.endExclusive())));
        if(verified.rows().size()!=2)
            throw new IllegalArgumentException("FIN_HTAP_PAIR_AMBIGUOUS_GRAIN");
        var a=verified.rows().get(0);
        var b=verified.rows().get(1);
        if(!a.start().equals(prior.startInclusive()) || !a.end().equals(prior.endExclusive())
                || !b.start().equals(now.startInclusive()) || !b.end().equals(now.endExclusive())
                || !a.sourceRevisionId().equals(sourceRevision)
                || !b.sourceRevisionId().equals(sourceRevision)
                || !reviewed.sourceField().equals(a.sourceField())
                || !reviewed.sourceField().equals(b.sourceField())
                || !a.formCode().equals("F02") || !b.formCode().equals("F02")
                || !a.formVersion().equals("FIN-PILOT-V1")
                || !b.formVersion().equals("FIN-PILOT-V1")
                || !a.currency().equals(b.currency())
                || !a.currency().equals("KZT"))
            throw new IllegalArgumentException("FIN_HTAP_PAIR_SOURCE_OR_CURRENCY_CONFLICT");

        // Source geometry is checked in the same snapshot; not just the
        // independently registered source population totals.
        Long matching=jdbc.queryForObject("""
            SELECT count(*)
            FROM fin_fact.fact_selection s
            JOIN fin_fact.fact_assertion f
              ON f.id=s.fact_assertion_id AND f.organization_id=s.organization_id
            WHERE s.organization_id=? AND s.fact_set_revision_id=?
              AND f.economic_entity_id=? AND f.business_scope_id=?
              AND f.source_revision_id=? AND f.metric_code='REV_NET'
              AND f.metric_version='1.0.0' AND f.time_kind='INTERVAL'
              AND f.reporting_basis='MANAGEMENT'
              AND f.unit_code='MONEY' AND f.currency_code='KZT'
              AND f.dimensions='{}'::jsonb
              AND ((f.start_inclusive=? AND f.end_exclusive=? AND f.id=?)
                OR (f.start_inclusive=? AND f.end_exclusive=? AND f.id=?))
            """,Long.class,organization,factSetRevision,entity,scope,sourceRevision,
                prior.startInclusive(),prior.endExclusive(),a.factId(),
                now.startInclusive(),now.endExclusive(),b.factId());
        if(matching==null || matching!=2)
            throw new IllegalArgumentException("FIN_HTAP_PAIR_FINANCIAL_CONTEXT_MISMATCH");

        return new VerifiedPair(organization,factSetRevision,expectedSnapshotSha256,
                sourceRevision,mappingId,reviewed.hash(),reviewed.epoch(),
                "ISO_MONTH_FLOW_PERIOD_ONLY_V1",
                new Month(previousMonth,a.factId(),a.sourceRowId(),a.value(),a.currency()),
                new Month(currentMonth,b.factId(),b.sourceRowId(),b.value(),b.currency()),
                false);
    }
}
