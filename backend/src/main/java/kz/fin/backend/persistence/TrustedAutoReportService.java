package kz.fin.backend.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.analytics.*;
import kz.fin.backend.domain.EconomicKernel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import static kz.fin.backend.analytics.AutoIndicatorEngine.*;

/**
 * Backend-owned calculation: only database-published and scoped assertions
 * reach AutoReportProjection, with units and measure kind from MetricContract.
 *
 * No REST endpoint exists. The application caller MUST perform Keycloak/ABAC
 * authorization and use server-owned ReportDefinitions. Do not expose the
 * ReportDefinition parameter to untrusted client input until a validated
 * FormulaRegistry/ReportRegistry is implemented.
 */
@Service
public class TrustedAutoReportService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TrustedAutoReportService(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        this.mapper=new ObjectMapper();
    }

    public enum SourceFreshness { CURRENT_SOURCE, STALE_SOURCE, UNKNOWN_POINTER }

    public record StoredCell(String code, int order, String translationKey, String status,
                             BigDecimal value, String reason, String unit, String currency,
                             String methodVersion) {}

    public record ReportView(UUID id, UUID organizationId, UUID factSetRevisionId,
                             String reportCode, String reportVersion,
                             String inputFingerprint, String cellsHash,
                             SourceFreshness sourceFreshness, List<StoredCell> cells) {
        public ReportView { cells=List.copyOf(cells); }
    }

    public record ReportReceipt(UUID reportRunId, boolean reused,
                                String inputFingerprint, String cellsHash) {}

    private record Publication(UUID revision,UUID organization,UUID entity,UUID scope,
                               EconomicKernel.ReportingBasis basis,
                               AnalysisContext.DataSpace dataSpace) {}

    private record MetricId(String code,String version) {}
    private record ExistingRun(UUID id,String definitionHash,int expectedCellCount) {}
    private record Policy(MeasureKind kind,String unit,String qualification) {}

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ReportReceipt calculateAndPublish(
            UUID authorizedOrganizationId, UUID publishedRevisionId,
            AutoReportProjection.ReportDefinition definition) {
        Objects.requireNonNull(authorizedOrganizationId);
        Objects.requireNonNull(publishedRevisionId);
        Objects.requireNonNull(definition);

        Publication pub=publication(authorizedOrganizationId,publishedRevisionId);
        if(pub==null) throw new IllegalArgumentException("UNPUBLISHED_OR_FOREIGN_FACT_SET");

        Map<MetricId,Policy> policies=loadPolicies();
        for(var col:definition.columns()) {
            for(SeriesKey key:referencedSeries(col.operation())) {
                if(!matches(pub,key)) throw new IllegalArgumentException("REPORT_SCOPE_MISMATCH");
                validatedPolicy(pub,policies,key.metricCode(),key.metricVersion(),
                        key.measureKind(),key.unitCode());
            }
        }
        Map<SeriesKey,List<Observation>> observations=loadAuthorizedObservations(pub,policies);
        AutoReportProjection.Projection projection=
                AutoReportProjection.project(definition,observations);

        String definitionHash=definitionHash(definition);
        UUID proposedRunId=UUID.randomUUID();
        int inserted=jdbc.update("""
            INSERT INTO fin_calc.report_run(
                id,organization_id,fact_set_revision_id,report_code,report_version,
                method_version,definition_hash,input_fingerprint,expected_cell_count)
            VALUES (?,?,?,?,?,?,?,?,?)
            ON CONFLICT (organization_id,fact_set_revision_id,
                         report_code,report_version,method_version) DO NOTHING
            """,proposedRunId,pub.organization(),pub.revision(),projection.reportCode(),
                projection.reportVersion(),AutoIndicatorEngine.METHOD_VERSION,
                definitionHash,"0".repeat(64),projection.cells().size());

        UUID runId=proposedRunId;
        if(inserted==0) {
            ExistingRun existingRun=jdbc.queryForObject("""
                SELECT id,definition_hash,expected_cell_count FROM fin_calc.report_run
                WHERE organization_id=? AND fact_set_revision_id=?
                  AND report_code=? AND report_version=? AND method_version=?
                """,(rs,i)->new ExistingRun(rs.getObject("id",UUID.class),
                       rs.getString("definition_hash"),rs.getInt("expected_cell_count")),
                pub.organization(),pub.revision(),projection.reportCode(),
                projection.reportVersion(),AutoIndicatorEngine.METHOD_VERSION);
            if(existingRun==null || !definitionHash.equals(existingRun.definitionHash())
                    || existingRun.expectedCellCount()!=projection.cells().size())
                throw new IllegalArgumentException("REPORT_VERSION_DEFINITION_COLLISION");
            runId=existingRun.id();
            Long finalized=jdbc.queryForObject("""
                SELECT count(*) FROM fin_calc.report_publication
                WHERE report_run_id=? AND organization_id=?
                """,Long.class,runId,pub.organization());
            if(finalized==null || finalized!=1L)
                throw new IllegalStateException("REPORT_RUN_NOT_FINALIZED");
            var existing=fingerprintAndHash(pub.organization(),runId);
            return new ReportReceipt(runId,true,existing[0],existing[1]);
        }

        for(var cell:projection.cells()){
            Calculation v=cell.calculation();
            jdbc.update("""
                INSERT INTO fin_calc.report_cell(
                    report_run_id,organization_id,column_code,display_order,translation_key,
                    status,numeric_value,reason,unit_code,currency_code,method_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,runId,pub.organization(),cell.columnCode(),cell.displayOrder(),
                    cell.translationKey(),v.status().name(),v.value(),v.reason(),
                    cell.unitCode(),cell.currencyCode(),v.methodVersion());
        }
        jdbc.update("""
            INSERT INTO fin_calc.report_publication(report_run_id,organization_id,cells_hash)
            VALUES (?,?,?)
            """,runId,pub.organization(),"0".repeat(64));
        var hashes=fingerprintAndHash(pub.organization(),runId);
        return new ReportReceipt(runId,false,hashes[0],hashes[1]);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ReportView loadPublished(UUID authorizedOrganizationId,UUID reportRunId) {
        Objects.requireNonNull(authorizedOrganizationId);
        Objects.requireNonNull(reportRunId);
        List<ReportView> reports=jdbc.query("""
            SELECT r.id,r.organization_id,r.fact_set_revision_id,r.report_code,r.report_version,
                   r.input_fingerprint,fp.cells_hash,cf.fact_set_revision_id AS current_revision
            FROM fin_calc.report_run r
            JOIN fin_calc.report_publication fp
              ON fp.report_run_id=r.id AND fp.organization_id=r.organization_id
            JOIN fin_fact.fact_set_publication p
              ON p.fact_set_revision_id=r.fact_set_revision_id
             AND p.organization_id=r.organization_id
            LEFT JOIN fin_fact.current_fact_set cf
              ON cf.organization_id=p.organization_id
             AND cf.economic_entity_id=p.economic_entity_id
             AND cf.business_scope_id=p.business_scope_id
             AND cf.reporting_basis=p.reporting_basis
             AND cf.data_space=p.data_space
            WHERE r.id=? AND r.organization_id=?
            """,(rs,i)->{
                UUID current=rs.getObject("current_revision",UUID.class);
                UUID factset=rs.getObject("fact_set_revision_id",UUID.class);
                SourceFreshness freshness=current==null?SourceFreshness.UNKNOWN_POINTER:
                        current.equals(factset)?SourceFreshness.CURRENT_SOURCE:
                                SourceFreshness.STALE_SOURCE;
                return new ReportView(rs.getObject("id",UUID.class),
                        rs.getObject("organization_id",UUID.class),
                        factset,rs.getString("report_code"),rs.getString("report_version"),
                        rs.getString("input_fingerprint"),rs.getString("cells_hash"),
                        freshness,List.of());
            },reportRunId,authorizedOrganizationId);
        if(reports.size()!=1) throw new IllegalArgumentException("REPORT_NOT_FOUND_OR_FORBIDDEN");
        ReportView r=reports.getFirst();
        List<StoredCell> cells=jdbc.query("""
            SELECT column_code,display_order,translation_key,status,numeric_value,
                   reason,unit_code,currency_code,method_version
            FROM fin_calc.report_cell
            WHERE organization_id=? AND report_run_id=? ORDER BY display_order
            """,(rs,i)->new StoredCell(rs.getString("column_code"),rs.getInt("display_order"),
                        rs.getString("translation_key"),rs.getString("status"),
                        rs.getBigDecimal("numeric_value"),rs.getString("reason"),
                        rs.getString("unit_code"),rs.getString("currency_code"),
                        rs.getString("method_version")),
                authorizedOrganizationId,reportRunId);
        return new ReportView(r.id(),r.organizationId(),r.factSetRevisionId(),
                r.reportCode(),r.reportVersion(),r.inputFingerprint(),r.cellsHash(),
                r.sourceFreshness(),cells);
    }

    private static String definitionHash(AutoReportProjection.ReportDefinition definition){
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            feed(hash,definition.reportCode());
            feed(hash,definition.version());
            for(var c:definition.columns()){
                feed(hash,c.columnCode());
                feed(hash,Integer.toString(c.displayOrder()));
                feed(hash,c.translationKey());
                hashOperation(hash,c.operation());
            }
            return HexFormat.of().formatHex(hash.digest());
        }catch(NoSuchAlgorithmException e){
            throw new IllegalStateException("SHA_256_UNAVAILABLE",e);
        }
    }

    private static void feed(MessageDigest hash,String value){
        byte[] data=value==null?null:value.getBytes(StandardCharsets.UTF_8);
        hash.update(ByteBuffer.allocate(4).putInt(data==null?-1:data.length).array());
        if(data!=null) hash.update(data);
    }

    private static void feedKey(MessageDigest hash,SeriesKey key){
        feed(hash,key.organizationId().toString());
        feed(hash,key.economicEntityId().toString());
        feed(hash,key.businessScopeId().toString());
        feed(hash,key.factSetRevisionId().toString());
        feed(hash,key.metricCode());
        feed(hash,key.metricVersion());
        feed(hash,key.measureKind().name());
        feed(hash,key.unitCode());
        feed(hash,key.currencyCode());
        feed(hash,key.reportingBasis().name());
        feed(hash,key.dataSpace().name());
        for(var entry:key.dimensions().entrySet()){
            feed(hash,entry.getKey());feed(hash,entry.getValue());
        }
    }

    private static void feedPeriod(MessageDigest hash,EconomicKernel.TemporalReference p){
        feed(hash,p.kind().name());
        feed(hash,p.startInclusive().toString());
        feed(hash,p.endExclusive().toString());
    }

    private static void hashOperation(MessageDigest hash,AutoReportProjection.Operation op){
        if(op instanceof AutoReportProjection.Aggregate a){
            feed(hash,"AGGREGATE");
            feedKey(hash,a.key());feedPeriod(hash,a.period());feed(hash,a.method().name());
        }else if(op instanceof AutoReportProjection.Compare a){
            feed(hash,"COMPARE");
            feedKey(hash,a.key());feedPeriod(hash,a.previous());
            feedPeriod(hash,a.current());feed(hash,a.method().name());feed(hash,a.mode().name());
        }else if(op instanceof AutoReportProjection.MovingAverage a){
            feed(hash,"MOVING_AVERAGE");
            feedKey(hash,a.key());feed(hash,a.endingMonth().toString());
            feed(hash,Integer.toString(a.windowMonths()));feed(hash,a.perMonth().name());
        }else if(op instanceof AutoReportProjection.PercentOf a){
            feed(hash,"PERCENT_OF");
            feedKey(hash,a.numerator());feedKey(hash,a.denominator());
            feedPeriod(hash,a.period());feed(hash,a.method().name());
        }else if(op instanceof AutoReportProjection.RatioOf a){
            feed(hash,"RATIO_OF");
            feedKey(hash,a.numerator());feedKey(hash,a.denominator());
            feedPeriod(hash,a.period());feed(hash,a.method().name());
        }else if(op instanceof AutoReportProjection.ShareOfTotal a){
            feed(hash,"SHARE_OF_TOTAL");
            feedKey(hash,a.member());feed(hash,a.dimension());
            for(String member:new TreeSet<>(a.allMembers())) feed(hash,member);
            feedPeriod(hash,a.period());feed(hash,a.method().name());
        }else throw new IllegalArgumentException("UNKNOWN_REPORT_OPERATION");
    }

    private String[] fingerprintAndHash(UUID org,UUID runId){
        return jdbc.queryForObject("""
            SELECT r.input_fingerprint,p.cells_hash
            FROM fin_calc.report_run r
            JOIN fin_calc.report_publication p
              ON p.report_run_id=r.id AND p.organization_id=r.organization_id
            WHERE r.organization_id=? AND r.id=?
            """,(rs,i)->new String[]{rs.getString(1),rs.getString(2)},org,runId);
    }

    private Publication publication(UUID org,UUID revision){
        var list=jdbc.query("""
            SELECT organization_id,fact_set_revision_id,economic_entity_id,
                   business_scope_id,reporting_basis,data_space
            FROM fin_fact.fact_set_publication
            WHERE organization_id=? AND fact_set_revision_id=?
            """,(rs,i)->new Publication(rs.getObject("fact_set_revision_id",UUID.class),
                    rs.getObject("organization_id",UUID.class),
                    rs.getObject("economic_entity_id",UUID.class),
                    rs.getObject("business_scope_id",UUID.class),
                    EconomicKernel.ReportingBasis.valueOf(rs.getString("reporting_basis")),
                    AnalysisContext.DataSpace.valueOf(rs.getString("data_space"))),
                org,revision);
        return list.isEmpty()?null:list.getFirst();
    }

    private Map<MetricId,Policy> loadPolicies() {
        var rows=jdbc.query("""
            SELECT metric_code,metric_version,measure_kind,unit_code,qualification
            FROM fin_calc.metric_contract
            """,(rs,i)->Map.entry(
                    new MetricId(rs.getString(1),rs.getString(2)),
                    new Policy(MeasureKind.valueOf(rs.getString(3)),
                            rs.getString(4),rs.getString(5))));
        Map<MetricId,Policy> result=new HashMap<>();
        for(var entry:rows) result.put(entry.getKey(),entry.getValue());
        return Map.copyOf(result);
    }

    private Policy validatedPolicy(Publication pub,Map<MetricId,Policy> policies,
                                   String metric,String version,MeasureKind kind,String unit) {
        Policy p=policies.get(new MetricId(metric,version));
        if(p==null) throw new IllegalArgumentException("UNKNOWN_METRIC_METHOD");
        if(p.kind()!=kind || !p.unit().equals(unit))
            throw new IllegalArgumentException("METRIC_CONTRACT_MISMATCH");
        if(!p.qualification().equals("QUALIFIED") &&
                pub.dataSpace()!=AnalysisContext.DataSpace.SYNTHETIC)
            throw new IllegalArgumentException("UNQUALIFIED_METRIC_FOR_ACTUAL");
        return p;
    }

    private List<SeriesKey> referencedSeries(AutoReportProjection.Operation op) {
        if(op instanceof AutoReportProjection.Aggregate a) return List.of(a.key());
        if(op instanceof AutoReportProjection.Compare a) return List.of(a.key());
        if(op instanceof AutoReportProjection.MovingAverage a) return List.of(a.key());
        if(op instanceof AutoReportProjection.PercentOf a)
            return List.of(a.numerator(),a.denominator());
        if(op instanceof AutoReportProjection.RatioOf a)
            return List.of(a.numerator(),a.denominator());
        if(op instanceof AutoReportProjection.ShareOfTotal a) return List.of(a.member());
        throw new IllegalArgumentException("UNKNOWN_REPORT_OPERATION");
    }

    private boolean matches(Publication p,SeriesKey s) {
        return p.organization().equals(s.organizationId())
                && p.entity().equals(s.economicEntityId())
                && p.scope().equals(s.businessScopeId())
                && p.revision().equals(s.factSetRevisionId())
                && p.basis()==s.reportingBasis()
                && p.dataSpace()==s.dataSpace();
    }

    private Map<SeriesKey,List<Observation>> loadAuthorizedObservations(
            Publication pub,Map<MetricId,Policy> policies) {
        List<Observation> rows=jdbc.query("""
            SELECT a.metric_code,a.metric_version,a.unit_code,a.currency_code,
                   a.dimensions::text AS dimension_json,a.time_kind,
                   a.start_inclusive,a.end_exclusive,a.value_state,
                   a.numeric_value,a.estimate_method_ref
            FROM fin_fact.fact_selection s
            JOIN fin_fact.fact_assertion a
              ON a.id=s.fact_assertion_id AND a.organization_id=s.organization_id
            WHERE s.organization_id=? AND s.fact_set_revision_id=?
            ORDER BY a.metric_code,a.start_inclusive
            """,(rs,i)->toObservation(pub,policies,rs),pub.organization(),pub.revision());
        Map<SeriesKey,List<Observation>> grouped=new HashMap<>();
        for(Observation row:rows){
            grouped.computeIfAbsent(row.series(),ignored->new ArrayList<>()).add(row);
        }
        Map<SeriesKey,List<Observation>> frozen=new HashMap<>();
        for(var entry:grouped.entrySet()) frozen.put(entry.getKey(),List.copyOf(entry.getValue()));
        return Map.copyOf(frozen);
    }

    private Observation toObservation(Publication p,Map<MetricId,Policy> policies,ResultSet rs)
            throws SQLException {
        String metric=rs.getString("metric_code");
        String version=rs.getString("metric_version");
        String unit=rs.getString("unit_code");
        Policy contract=policies.get(new MetricId(metric,version));
        if(contract==null)throw new IllegalArgumentException("UNKNOWN_METRIC_METHOD");
        validatedPolicy(p,policies,metric,version,contract.kind(),unit);
        SortedMap<String,String> dimensions;
        try {
            dimensions=mapper.readValue(rs.getString("dimension_json"),
                    new TypeReference<TreeMap<String,String>>(){});
        }catch(IOException e){
            throw new IllegalStateException("INVALID_STORED_DIMENSIONS",e);
        }
        SeriesKey key=new SeriesKey(p.organization(),p.entity(),p.scope(),p.revision(),
                metric,version,contract.kind(),unit,rs.getString("currency_code"),
                p.basis(),p.dataSpace(),dimensions);
        EconomicKernel.TemporalReference time=new EconomicKernel.TemporalReference(
                EconomicKernel.TimeKind.valueOf(rs.getString("time_kind")),
                rs.getObject("start_inclusive",LocalDate.class),
                rs.getObject("end_exclusive",LocalDate.class));
        EconomicKernel.EconomicValue value=new EconomicKernel.EconomicValue(
                EconomicKernel.ValueState.valueOf(rs.getString("value_state")),
                rs.getBigDecimal("numeric_value"),rs.getString("estimate_method_ref"));
        return new Observation(key,time,value);
    }
}
