package kz.fin.backend.execution;

import kz.fin.backend.analytics.ConcentrationAnalysisEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Only approved, reconciled and immutable SKU evidence can be consumed.
 * A fact-set snapshot and a separate reviewer-sealed SKU manifest are both
 * pinned: the fact-set hash alone does not cover transaction-level SKU rows.
 *
 * This is an INTERNAL source reader. It does not authorize external API
 * actors, create a SKU certificate, or qualify a methodology family.
 */
@Component
public final class ReviewedSkuPopulationLoader {
    private final JdbcTemplate jdbc;

    public ReviewedSkuPopulationLoader(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }

    public record Population(UUID sourceId,String sourceFingerprint,
                             String publishedFactHash,String sourceDocumentHash,
                             String currency,LocalDate from,LocalDate until,
                             String reportingBasis,String coveragePolicy,
                             BigDecimal netSales,
                             List<ConcentrationAnalysisEngine.SkuAmount> skus) {
        public Population { skus=List.copyOf(skus); }
    }
    private record Metadata(UUID id,String fingerprint,String publicationHash,
                            String sourceDocumentHash,String currency,
                            LocalDate from,LocalDate until,String basis,
                            String policy,int expectedCount,BigDecimal expectedNet) {}
    private record SkuRow(String sku,BigDecimal net,long events) {}

    public Optional<Population> load(MethodTaskAdapter.TrustedRevision r) {
        Objects.requireNonNull(r,"revision");
        if(!"ACTUAL".equals(r.dataSpace())) return Optional.empty();
        var certificates=jdbc.query("""
            SELECT source.id,review.population_fingerprint,
                   source.published_snapshot_hash,source.source_document_hash,
                   source.currency,source.period_start,source.period_end,
                   source.reporting_basis,source.coverage_policy,
                   source.expected_line_count,source.expected_net_total
            FROM fin_con.sku_population_submission source
            JOIN fin_con.sku_population_review review
              ON review.population_id=source.id
             AND review.organization_id=source.organization_id
             AND review.decision='APPROVED'
            JOIN fin_fact.fact_set_publication publication
              ON publication.fact_set_revision_id=source.fact_set_revision_id
             AND publication.organization_id=source.organization_id
             AND publication.snapshot_hash=source.published_snapshot_hash
             AND publication.economic_entity_id=source.economic_entity_id
             AND publication.business_scope_id=source.business_scope_id
             AND publication.reporting_basis=source.reporting_basis
             AND publication.data_space=source.data_space
            WHERE source.organization_id=?
              AND source.economic_entity_id=?
              AND source.business_scope_id=?
              AND source.fact_set_revision_id=?
              AND source.published_snapshot_hash=?
              AND source.data_space='ACTUAL'
            ORDER BY source.period_start DESC,source.id
            """,(rs,i)->new Metadata(rs.getObject(1,UUID.class),
                    rs.getString(2),rs.getString(3),rs.getString(4),
                    rs.getString(5),rs.getObject(6,LocalDate.class),
                    rs.getObject(7,LocalDate.class),rs.getString(8),
                    rs.getString(9),rs.getInt(10),rs.getBigDecimal(11)),
            r.organizationId(),r.economicEntityId(),r.businessScopeId(),
            r.factSetRevisionId(),r.snapshotHash());

        // Do not silently choose a date if several certified periods exist;
        // this pilot has no versioned user/report-period selection policy.
        if(certificates.size()!=1) return Optional.empty();
        Metadata p=certificates.getFirst();
        var data=jdbc.query("""
            SELECT sku_code,
                   sum(CASE WHEN event_kind='SALE'
                       THEN gross_amount ELSE -gross_amount END) AS net_amount,
                   count(*) AS events
            FROM fin_con.sku_sales_line
            WHERE population_id=? AND organization_id=?
            GROUP BY sku_code ORDER BY sku_code
            """,(rs,i)->new SkuRow(rs.getString(1),rs.getBigDecimal(2),rs.getLong(3)),
            p.id(),r.organizationId());
        if(data.size()<2 || data.size()>10_000) return Optional.empty();
        long count=0;
        BigDecimal sum=BigDecimal.ZERO;
        var amounts=new ArrayList<ConcentrationAnalysisEngine.SkuAmount>();
        for(var row:data) {
            if(row.net()==null || row.net().signum()<0)
                return Optional.empty();
            count+=row.events();
            sum=sum.add(row.net());
            amounts.add(new ConcentrationAnalysisEngine.SkuAmount(row.sku(),row.net()));
        }
        if(count!=p.expectedCount() || sum.compareTo(p.expectedNet())!=0)
            return Optional.empty();
        if(p.from()==null || p.until()==null ||
                !p.from().plusMonths(1).equals(p.until()))
            return Optional.empty();
        return Optional.of(new Population(p.id(),p.fingerprint(),
                p.publicationHash(),p.sourceDocumentHash(),p.currency(),
                p.from(),p.until(),p.basis(),p.policy(),p.expectedNet(),amounts));
    }
}
