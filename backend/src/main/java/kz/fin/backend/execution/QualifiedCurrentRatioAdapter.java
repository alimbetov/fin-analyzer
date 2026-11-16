package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.analytics.AutoIndicatorEngine;
import kz.fin.backend.analytics.AutoReportProjection;
import kz.fin.backend.domain.EconomicKernel;
import kz.fin.backend.persistence.TrustedAutoReportService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * FIN-04 pilot: current assets / current liabilities at exactly the same F01
 * as-of date. TOTAL ASSETS and TOTAL LIABILITIES are NOT interchangeable with
 * current balances. No automatic promotion of the FIN-04 methodology family.
 */
@Component
public final class QualifiedCurrentRatioAdapter implements MethodTaskAdapter {
    public static final String CODE="FIN-04/CURRENT_RATIO";
    private static final String CURRENT_ASSETS="CURRENT_ASSETS";
    private static final String CURRENT_LIABILITIES="CURRENT_LIABILITIES";
    private final JdbcTemplate jdbc;
    private final TrustedAutoReportService reports;
    private final ObjectMapper json;
    private final boolean explicitlyEnabled;

    public QualifiedCurrentRatioAdapter(JdbcTemplate jdbc,
                                        TrustedAutoReportService reports,
                                        ObjectMapper json,
                                        @Value("${fin.methods.fin04.enabled:false}") boolean explicitlyEnabled) {
        this.jdbc=jdbc; this.reports=reports; this.json=json;
        this.explicitlyEnabled=explicitlyEnabled;
    }

    @Override public String methodCode(){return CODE;}
    @Override public boolean autoEligible(){return explicitlyEnabled;}
    @Override public List<String> dependsOn(){
        return List.of("SYS/VERIFY_PUBLISHED_FACT_SET");
    }

    @Override public boolean applicable(TrustedRevision revision) {
        return verifiedBalances(revision).isPresent();
    }

    @Override public String execute(TrustedRevision revision) throws Exception {
        var input=verifiedBalances(revision)
                .orElseThrow(()->new IllegalStateException("FIN04_VERIFIED_F01_CURRENT_BALANCES_REQUIRED"));
        var period=EconomicKernel.TemporalReference.interval(
                input.asOf(),input.asOf().plusDays(1));
        var numerator=series(revision,input,CURRENT_ASSETS);
        var denominator=series(revision,input,CURRENT_LIABILITIES);
        var definition=new AutoReportProjection.ReportDefinition(
                "FIN_CURRENT_RATIO","v1",List.of(
                new AutoReportProjection.Column("CURRENT_RATIO",100,
                        "finance.liquidity.current_ratio",
                        new AutoReportProjection.RatioOf(numerator,denominator,period,
                                AutoIndicatorEngine.Aggregation.STOCK_CLOSING))));
        var receipt=reports.calculateAndPublish(
                revision.organizationId(),revision.factSetRevisionId(),definition);
        var published=reports.loadPublished(
                revision.organizationId(),receipt.reportRunId());
        if(published.cells().size()!=1)
            throw new IllegalStateException("FIN04_REPORT_RESULT_SHAPE_MISMATCH");
        var cell=published.cells().getFirst();
        if(!"RATIO".equals(cell.unit()) || cell.currency()!=null)
            throw new IllegalStateException("FIN04_INVALID_RESULT_UNIT");
        var output=new LinkedHashMap<String,Object>();
        output.put("kind","FINANCIAL_LIQUIDITY_RATIO");
        output.put("methodCode",CODE);
        output.put("methodVersion",cell.methodVersion());
        output.put("qualification","METHOD_FAMILY_NOT_YET_APPROVED");
        output.put("sourceRevisionId",revision.factSetRevisionId().toString());
        output.put("f01SourceRevisionId",input.sourceRevisionId().toString());
        output.put("sourceDocumentHash",input.documentHash());
        output.put("reportRunId",receipt.reportRunId().toString());
        output.put("inputFingerprint",receipt.inputFingerprint());
        output.put("asOfDate",input.asOf().toString());
        output.put("currency",input.currency());
        output.put("unit","RATIO");
        output.put("status",cell.status());
        if(cell.value()!=null) output.put("value",cell.value().toPlainString());
        if(cell.reason()!=null) output.put("reason",cell.reason());
        return json.writeValueAsString(output);
    }

    private static AutoIndicatorEngine.SeriesKey series(
            TrustedRevision r,Input input,String metric) {
        return new AutoIndicatorEngine.SeriesKey(
                r.organizationId(),r.economicEntityId(),r.businessScopeId(),
                r.factSetRevisionId(),metric,input.metricVersion(),
                AutoIndicatorEngine.MeasureKind.STOCK,"MONEY",input.currency(),
                input.basis(),AnalysisContext.DataSpace.valueOf(r.dataSpace()),
                new TreeMap<>());
    }

    /**
     * Approved F01 provenance is mandatory. An arbitrary pair of named facts,
     * or total-balance fields from FIN-PILOT-V1, cannot qualify this adapter.
     */
    private Optional<Input> verifiedBalances(TrustedRevision revision) {
        var publication=jdbc.query("""
            SELECT reporting_basis,data_space,snapshot_hash
              FROM fin_fact.fact_set_publication
             WHERE organization_id=? AND fact_set_revision_id=?
               AND economic_entity_id=? AND business_scope_id=?
            """,(rs,n)->new Publication(rs.getString(1),rs.getString(2),rs.getString(3)),
            revision.organizationId(),revision.factSetRevisionId(),
            revision.economicEntityId(),revision.businessScopeId());
        if(publication.size()!=1 ||
           !revision.snapshotHash().equals(publication.getFirst().hash()) ||
           !revision.dataSpace().equals(publication.getFirst().space()))
            return Optional.empty();

        var rows=jdbc.query("""
            SELECT a.metric_code,a.metric_version,a.start_inclusive,a.end_exclusive,
                   a.currency_code,a.unit_code,a.value_state,a.source_revision_id,
                   s.form_version,i.source_document_hash,
                   o.source_field
            FROM fin_fact.fact_selection selection
            JOIN fin_fact.fact_assertion a ON a.id=selection.fact_assertion_id
                 AND a.organization_id=selection.organization_id
            JOIN fin_fact.fact_origin o ON o.fact_assertion_id=a.id
                 AND o.organization_id=a.organization_id
                 AND o.source_revision_id=a.source_revision_id
            JOIN fin_fact.approved_source_revision s
                 ON s.organization_id=a.organization_id
                 AND s.source_revision_id=a.source_revision_id
            JOIN fin_fact.approved_source_intake i
                 ON i.organization_id=s.organization_id
                 AND i.source_revision_id=s.source_revision_id
            JOIN fin_fact.source_population_control pop
                 ON pop.organization_id=a.organization_id
                 AND pop.source_revision_id=a.source_revision_id
                 AND pop.metric_code=a.metric_code
                 AND pop.time_start=a.start_inclusive
                 AND pop.time_end=a.end_exclusive
                 AND pop.expected_rows=1
                 AND pop.expected_total=a.numeric_value
                 AND pop.source_document_hash=i.source_document_hash
            JOIN fin_fact.pilot_field_mapping mapping
                 ON mapping.form_code=s.form_code
                 AND mapping.form_version=s.form_version
                 AND mapping.source_field=o.source_field
                 AND mapping.metric_code=a.metric_code
                 AND mapping.metric_version=a.metric_version
                 AND mapping.required_time_kind=a.time_kind
            JOIN fin_calc.metric_contract m
                 ON m.metric_code=a.metric_code
                 AND m.metric_version=a.metric_version
            WHERE selection.organization_id=?
              AND selection.fact_set_revision_id=?
              AND a.economic_entity_id=? AND a.business_scope_id=?
              AND a.reporting_basis=?
              AND s.form_code='F01' AND s.form_version='FIN-LIQUIDITY-V1'
              AND a.metric_code IN ('CURRENT_ASSETS','CURRENT_LIABILITIES')
              AND a.time_kind='AS_OF' AND a.unit_code='MONEY'
              AND a.currency_code=i.input_currency
              AND a.dimensions='{}'::jsonb
              AND m.measure_kind='STOCK' AND m.unit_code='MONEY'
              AND m.qualification='QUALIFIED'
              AND a.value_state IN ('PRESENT','EXPLICIT_ZERO')
            ORDER BY a.start_inclusive DESC,a.metric_code
            """,(rs,n)->new Balance(rs.getString(1),rs.getString(2),
                    rs.getObject(3,LocalDate.class),rs.getObject(4,LocalDate.class),
                    rs.getString(5),rs.getString(6),rs.getString(7),
                    rs.getObject(8,UUID.class),rs.getString(9),rs.getString(10),
                    rs.getString(11)),
            revision.organizationId(),revision.factSetRevisionId(),
            revision.economicEntityId(),revision.businessScopeId(),
            publication.getFirst().basis());
        // Exactly one pair for this prototype revision; other as-of periods
        // require versioned report-period selection, not an implicit latest-date guess.
        if(rows.size()!=2) return Optional.empty();
        var assets=rows.stream().filter(r->CURRENT_ASSETS.equals(r.metricCode())).toList();
        var liabilities=rows.stream().filter(r->CURRENT_LIABILITIES.equals(r.metricCode())).toList();
        if(assets.size()!=1||liabilities.size()!=1)return Optional.empty();
        var a=assets.getFirst();
        var b=liabilities.getFirst();
        if(!a.date().equals(b.date()) ||
           !a.endExclusive().equals(a.date().plusDays(1)) ||
           !b.endExclusive().equals(b.date().plusDays(1)) ||
           !a.version().equals(b.version()) ||
           !Objects.equals(a.currency(),b.currency()) ||
           a.currency()==null ||
           !a.sourceRevisionId().equals(b.sourceRevisionId()) ||
           !a.documentHash().equals(b.documentHash()) ||
           !a.formVersion().equals(b.formVersion()) ||
           !"currentAssets".equals(a.sourceField()) ||
           !"currentLiabilities".equals(b.sourceField()))
            return Optional.empty();
        return Optional.of(new Input(a.date(),a.version(),a.currency(),
                a.sourceRevisionId(),a.documentHash(),
                EconomicKernel.ReportingBasis.valueOf(publication.getFirst().basis())));
    }

    private record Publication(String basis,String dataSpace,String hash){}
    private record Balance(String metricCode,String version,LocalDate date,
                           LocalDate endExclusive,String currency,String unit,
                           String valueState,UUID sourceRevisionId,String formVersion,
                           String documentHash,String sourceField){}
    private record Input(LocalDate asOf,String metricVersion,String currency,
                         UUID sourceRevisionId,String documentHash,
                         EconomicKernel.ReportingBasis basis){}
}
