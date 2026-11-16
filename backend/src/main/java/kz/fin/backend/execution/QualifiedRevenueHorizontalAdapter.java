package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.analytics.AutoIndicatorEngine;
import kz.fin.backend.analytics.AutoReportProjection;
import kz.fin.backend.domain.EconomicKernel;
import kz.fin.backend.persistence.TrustedAutoReportService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * FIN-01 (limited scope): horizontal comparison of two consecutive monthly
 * net-revenue totals. Reuses TrustedAutoReportService/AutoIndicatorEngine;
 * automatic execution requires a QUALIFIED REV_NET contract and two valid
 * approved month-level observations. Not full FIN-01 family qualification.
 */
@Component
public final class QualifiedRevenueHorizontalAdapter implements MethodTaskAdapter {
    public static final String CODE="FIN-01/REV_NET_MOM";
    private final JdbcTemplate jdbc;
    private final TrustedAutoReportService reports;
    private final ObjectMapper json;

    public QualifiedRevenueHorizontalAdapter(JdbcTemplate jdbc,
                                              TrustedAutoReportService reports,
                                              ObjectMapper json) {
        this.jdbc=jdbc; this.reports=reports; this.json=json;
    }

    @Override public String methodCode() { return CODE; }
    @Override public List<String> dependsOn() {
        return List.of("SYS/VERIFY_PUBLISHED_FACT_SET");
    }
    @Override public boolean autoEligible() { return true; }

    @Override public boolean applicable(TrustedRevision revision) {
        return selectQualifiedMonths(revision).isPresent();
    }

    @Override public String execute(TrustedRevision revision) throws Exception {
        var input=selectQualifiedMonths(revision)
            .orElseThrow(()->new IllegalStateException("FIN01_QUALIFIED_INPUTS_UNAVAILABLE"));
        var series=new AutoIndicatorEngine.SeriesKey(
            revision.organizationId(),revision.economicEntityId(),revision.businessScopeId(),
            revision.factSetRevisionId(),"REV_NET",input.metricVersion(),
            AutoIndicatorEngine.MeasureKind.FLOW,"MONEY",input.currency(),
            input.basis(),AnalysisContext.DataSpace.valueOf(revision.dataSpace()),new TreeMap<>());
        var first=EconomicKernel.TemporalReference.interval(
            input.previous().atDay(1),input.previous().plusMonths(1).atDay(1));
        var second=EconomicKernel.TemporalReference.interval(
            input.current().atDay(1),input.current().plusMonths(1).atDay(1));
        var definition=new AutoReportProjection.ReportDefinition(
            "FIN_REVENUE_HORIZONTAL","v1",List.of(
                new AutoReportProjection.Column("REV_MOM_PCT",100,
                    "finance.revenue.month_over_month",
                    new AutoReportProjection.Compare(series,first,second,
                        AutoIndicatorEngine.Aggregation.FLOW_SUM,
                        AutoReportProjection.ComparisonMode.PERCENT))));
        var receipt=reports.calculateAndPublish(
            revision.organizationId(),revision.factSetRevisionId(),definition);
        var published=reports.loadPublished(revision.organizationId(),receipt.reportRunId());
        if(published.cells().size()!=1)
            throw new IllegalStateException("FIN01_RESULT_SCHEMA_MISMATCH");
        var cell=published.cells().getFirst();
        var output=new LinkedHashMap<String,Object>();
        output.put("kind","FINANCIAL_HORIZONTAL_ANALYSIS");
        output.put("methodCode",CODE);
        output.put("methodVersion",cell.methodVersion());
        output.put("qualification","METHOD_FAMILY_NOT_YET_APPROVED");
        output.put("reportRunId",receipt.reportRunId().toString());
        output.put("sourceRevisionId",revision.factSetRevisionId().toString());
        output.put("inputFingerprint",receipt.inputFingerprint());
        output.put("previousPeriod",input.previous().toString());
        output.put("currentPeriod",input.current().toString());
        output.put("currency",input.currency());
        output.put("metric","REV_NET");
        output.put("unit",cell.unit());
        output.put("status",cell.status());
        if(cell.value()!=null) output.put("value",cell.value().toPlainString());
        if(cell.reason()!=null) output.put("reason",cell.reason());
        return json.writeValueAsString(output);
    }

    private Optional<Input> selectQualifiedMonths(TrustedRevision revision) {
        var publications=jdbc.query("""
            SELECT reporting_basis,data_space,snapshot_hash
            FROM fin_fact.fact_set_publication
            WHERE organization_id=? AND fact_set_revision_id=?
              AND economic_entity_id=? AND business_scope_id=?
            """,(rs,i)->new Publication(rs.getString(1),rs.getString(2),rs.getString(3)),
                revision.organizationId(),revision.factSetRevisionId(),
                revision.economicEntityId(),revision.businessScopeId());
        if(publications.size()!=1 ||
                !publications.getFirst().hash().equals(revision.snapshotHash()) ||
                !publications.getFirst().space().equals(revision.dataSpace()))
            return Optional.empty();
        var rows=jdbc.query("""
            SELECT a.start_inclusive,a.end_exclusive,a.metric_version,a.currency_code
            FROM fin_fact.fact_selection s
            JOIN fin_fact.fact_assertion a ON a.id=s.fact_assertion_id
                 AND a.organization_id=s.organization_id
            JOIN fin_calc.metric_contract m ON m.metric_code=a.metric_code
                 AND m.metric_version=a.metric_version
            WHERE s.organization_id=? AND s.fact_set_revision_id=?
              AND a.economic_entity_id=? AND a.business_scope_id=?
              AND a.metric_code='REV_NET' AND a.time_kind='INTERVAL'
              AND a.reporting_basis=? AND a.unit_code='MONEY'
              AND a.value_state IN ('PRESENT','EXPLICIT_ZERO')
              AND a.dimensions='{}'::jsonb
              AND m.measure_kind='FLOW' AND m.unit_code='MONEY'
              AND m.qualification='QUALIFIED'
            ORDER BY a.start_inclusive DESC
            LIMIT 2
            """,(rs,i)->new MonthRow(rs.getObject(1,LocalDate.class),
                rs.getObject(2,LocalDate.class),rs.getString(3),rs.getString(4)),
                revision.organizationId(),revision.factSetRevisionId(),
                revision.economicEntityId(),revision.businessScopeId(),
                publications.getFirst().basis());
        if(rows.size()!=2) return Optional.empty();
        var current=rows.get(0);
        var previous=rows.get(1);
        if(current.currency()==null || !current.currency().equals(previous.currency()) ||
                !current.version().equals(previous.version()) ||
                current.start().getDayOfMonth()!=1 || previous.start().getDayOfMonth()!=1 ||
                !current.end().equals(current.start().plusMonths(1)) ||
                !previous.end().equals(previous.start().plusMonths(1)) ||
                !previous.start().plusMonths(1).equals(current.start()))
            return Optional.empty();
        return Optional.of(new Input(YearMonth.from(previous.start()),
            YearMonth.from(current.start()),current.version(),current.currency(),
            EconomicKernel.ReportingBasis.valueOf(publications.getFirst().basis())));
    }

    private record Publication(String basis,String space,String hash){}
    private record MonthRow(LocalDate start,LocalDate end,String version,String currency){}
    private record Input(YearMonth previous,YearMonth current,String metricVersion,
                         String currency,EconomicKernel.ReportingBasis basis){}
}
