package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.analytics.AutoIndicatorEngine;
import kz.fin.backend.analytics.AutoReportProjection;
import kz.fin.backend.domain.EconomicKernel;
import kz.fin.backend.persistence.TrustedAutoReportService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * FIN-02 pilot: share of net revenue by exhaustive category population.
 *
 * Does not invent a denominator or derive a category population from a partial
 * sample. The trusted total REV_NET must reconcile exactly with all category
 * members for the same calendar month, scope, currency and approved revision.
 * Reuses ShareOfTotal rather than implementing a second percentage formula.
 * This is ONE variant, not qualification of the FIN-02 family.
 */
@Component
public final class QualifiedRevenueStructureAdapter implements MethodTaskAdapter {
    public static final String CODE="FIN-02/REV_CATEGORY_SHARE";
    private static final int MAX_CATEGORIES=40;
    private final JdbcTemplate jdbc;
    private final TrustedAutoReportService reports;
    private final ObjectMapper json;

    public QualifiedRevenueStructureAdapter(JdbcTemplate jdbc,
                                            TrustedAutoReportService reports,
                                            ObjectMapper json) {
        this.jdbc=jdbc; this.reports=reports; this.json=json;
    }

    @Override public String methodCode() { return CODE; }
    @Override public boolean autoEligible() { return true; }
    @Override public List<String> dependsOn() {
        return List.of("SYS/VERIFY_PUBLISHED_FACT_SET");
    }
    @Override public boolean applicable(TrustedRevision revision) {
        return verifiedPopulation(revision).isPresent();
    }

    @Override public String execute(TrustedRevision revision) throws Exception {
        var pop=verifiedPopulation(revision)
            .orElseThrow(()->new IllegalStateException("FIN02_RECONCILED_POPULATION_REQUIRED"));
        var month=EconomicKernel.TemporalReference.interval(
            pop.period().atDay(1),pop.period().plusMonths(1).atDay(1));
        var columns=new ArrayList<AutoReportProjection.Column>();
        var memberCodes=new LinkedHashMap<String,String>();
        int n=0;
        for(String member:pop.members()) {
            String columnCode="CATEGORY_SHARE_"+String.format(Locale.ROOT,"%03d",++n);
            memberCodes.put(columnCode,member);
            var key=new AutoIndicatorEngine.SeriesKey(revision.organizationId(),
                revision.economicEntityId(),revision.businessScopeId(),
                revision.factSetRevisionId(),"REV_NET",pop.metricVersion(),
                AutoIndicatorEngine.MeasureKind.FLOW,"MONEY",pop.currency(),
                pop.basis(),AnalysisContext.DataSpace.valueOf(revision.dataSpace()),
                new TreeMap<>(Map.of("category",member)));
            columns.add(new AutoReportProjection.Column(columnCode,n*100,
                "finance.revenue.category.share",
                new AutoReportProjection.ShareOfTotal(key,"category",pop.members(),
                    month,AutoIndicatorEngine.Aggregation.FLOW_SUM)));
        }
        var definition=new AutoReportProjection.ReportDefinition(
            "FIN_REVENUE_CATEGORY_STRUCTURE","v1",columns);
        var receipt=reports.calculateAndPublish(revision.organizationId(),
            revision.factSetRevisionId(),definition);
        var saved=reports.loadPublished(revision.organizationId(),receipt.reportRunId());
        if(saved.cells().size()!=pop.members().size())
            throw new IllegalStateException("FIN02_REPORT_CELLS_MISMATCH");

        var values=new TreeMap<String,String>();
        for(var cell:saved.cells()) {
            String member=memberCodes.get(cell.code());
            if(member==null || !"CALCULATED".equals(cell.status()) || cell.value()==null)
                throw new IllegalStateException("FIN02_UNQUALIFIED_RESULT_CELL");
            values.put(member,cell.value().toPlainString());
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("kind","FINANCIAL_VERTICAL_REVENUE_STRUCTURE");
        result.put("methodCode",CODE);
        result.put("methodVersion",AutoIndicatorEngine.METHOD_VERSION);
        result.put("qualification","METHOD_FAMILY_NOT_YET_APPROVED");
        result.put("sourceRevisionId",revision.factSetRevisionId().toString());
        result.put("reportRunId",receipt.reportRunId().toString());
        result.put("inputFingerprint",receipt.inputFingerprint());
        result.put("period",pop.period().toString());
        result.put("currency",pop.currency());
        result.put("denominator","REV_NET_APPROVED_UNDIMENSIONED_TOTAL");
        result.put("denominatorValue",pop.total().toPlainString());
        result.put("unit","PCT");
        result.put("status","CALCULATED");
        result.put("categoryShares",values);
        return json.writeValueAsString(result);
    }

    private Optional<Population> verifiedPopulation(TrustedRevision revision) {
        var publication=jdbc.query("""
            SELECT reporting_basis,data_space,snapshot_hash
            FROM fin_fact.fact_set_publication
            WHERE organization_id=? AND fact_set_revision_id=?
              AND economic_entity_id=? AND business_scope_id=?
            """,(rs,i)->new Publication(rs.getString(1),rs.getString(2),rs.getString(3)),
            revision.organizationId(),revision.factSetRevisionId(),
            revision.economicEntityId(),revision.businessScopeId());
        if(publication.size()!=1 ||
                !revision.snapshotHash().equals(publication.getFirst().hash()) ||
                !revision.dataSpace().equals(publication.getFirst().space()))
            return Optional.empty();

        var facts=jdbc.query("""
            SELECT a.start_inclusive,a.end_exclusive,a.metric_version,a.currency_code,
                   a.dimensions::text,a.value_state,a.numeric_value
            FROM fin_fact.fact_selection s
            JOIN fin_fact.fact_assertion a ON a.id=s.fact_assertion_id
                AND a.organization_id=s.organization_id
            JOIN fin_calc.metric_contract m ON m.metric_code=a.metric_code
                AND m.metric_version=a.metric_version
            WHERE s.organization_id=? AND s.fact_set_revision_id=?
              AND a.economic_entity_id=? AND a.business_scope_id=?
              AND a.reporting_basis=? AND a.metric_code='REV_NET'
              AND a.time_kind='INTERVAL' AND a.unit_code='MONEY'
              AND m.measure_kind='FLOW' AND m.unit_code='MONEY'
              AND m.qualification='QUALIFIED'
            ORDER BY a.start_inclusive DESC,a.dimensions::text
            """,(rs,i)->new Fact(rs.getObject(1,LocalDate.class),
                rs.getObject(2,LocalDate.class),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getString(6),rs.getBigDecimal(7)),
            revision.organizationId(),revision.factSetRevisionId(),
            revision.economicEntityId(),revision.businessScopeId(),
            publication.getFirst().basis());
        if(facts.isEmpty()) return Optional.empty();
        LocalDate latest=facts.getFirst().from();
        YearMonth period=YearMonth.from(latest);
        if(!latest.equals(period.atDay(1))) return Optional.empty();
        var relevant=facts.stream().filter(f->f.from().equals(latest)).toList();

        Fact total=null;
        var members=new TreeMap<String,BigDecimal>();
        for(var f:relevant) {
            if(!f.to().equals(period.plusMonths(1).atDay(1)) ||
                    f.currency()==null || !f.currency().matches("[A-Z]{3}") ||
                    f.value()==null || !Set.of("PRESENT","EXPLICIT_ZERO").contains(f.state()))
                return Optional.empty();
            if("{}".equals(f.dimensions())) {
                if(total!=null) return Optional.empty();
                total=f;
            } else {
                // Only a single canonical category dimension is accepted. No
                // summing of stores, SKU rows, tax adjustments or overlapping totals.
                try {
                    var dimension=json.readTree(f.dimensions());
                    if(dimension.size()!=1 || !dimension.hasNonNull("category") ||
                            !dimension.get("category").isTextual())
                        return Optional.empty();
                    String member=dimension.get("category").asText();
                    if(member.isBlank() || member.length()>128 ||
                            members.putIfAbsent(member,f.value())!=null)
                        return Optional.empty();
                } catch(Exception e) {
                    return Optional.empty();
                }
            }
        }
        if(total==null || members.size()<2 || members.size()>MAX_CATEGORIES ||
                total.value().signum()<=0) return Optional.empty();
        BigDecimal sum=BigDecimal.ZERO;
        for(var f:relevant) {
            if(!f.version().equals(total.version()) ||
                    !f.currency().equals(total.currency()) || f.value().signum()<0)
                return Optional.empty();
        }
        for(BigDecimal amount:members.values())sum=sum.add(amount);
        if(sum.compareTo(total.value())!=0) return Optional.empty();
        return Optional.of(new Population(period,total.version(),total.currency(),
            total.value(),Collections.unmodifiableSortedSet(new TreeSet<>(members.keySet())),
            EconomicKernel.ReportingBasis.valueOf(publication.getFirst().basis())));
    }

    private record Publication(String basis,String space,String hash) {}
    private record Fact(LocalDate from,LocalDate to,String version,String currency,
                        String dimensions,String state,BigDecimal value) {}
    private record Population(YearMonth period,String metricVersion,String currency,
                              BigDecimal total,SortedSet<String> members,
                              EconomicKernel.ReportingBasis basis) {}
}
