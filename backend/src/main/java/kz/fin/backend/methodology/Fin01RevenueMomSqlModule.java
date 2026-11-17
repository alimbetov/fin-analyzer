package kz.fin.backend.methodology;

import kz.fin.backend.execution.MethodTaskAdapter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * R2.M2 FIN-01 SQL PILOT. Read-only calculation on an exact, published,
 * SYNTHETIC FactSet only. Not exposed to SingleExecutorScheduler or REST.
 * No ACTUAL permit, result publication or independent S2 approval is implied.
 */
@Component
public final class Fin01RevenueMomSqlModule implements MethodModule {
    public static final String CODE = "FIN-01/REV_NET_MOM";
    public static final String VERSION = "R2_SQL_PILOT_1";
    private final JdbcTemplate jdbc;
    private static final String ZERO_HASH = "0".repeat(64);

    public Fin01RevenueMomSqlModule(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override public Definition definition() {
        return new Definition(CODE, VERSION, "R2_M2_SQL_1",
                "FIN01_SQL_R2_1", "FIN01_MOM_RESULT_V1", CalculationEngine.SQL,
                List.of("SYS/VERIFY_PUBLISHED_FACT_SET"), false, false);
    }

    private record Row(UUID assertionId, UUID sourceRevisionId, LocalDate start, LocalDate end,
                       String metricVersion, String currency, BigDecimal value,
                       String valueState, String timeKind, String unitCode, String dimensions,
                       String basis, String measureKind, String contractUnit,
                       String qualification, String sourceForm, String sourceFormVersion,
                       int totalCount, BigDecimal previousAmount, LocalDate previousStart,
                       BigDecimal delta, BigDecimal percent) {}

    /**
     * Query is server-owned; parameter bindings include the exact source
     * publication and committed outbox event. No client-selected SQL/table.
     * Native SQL calculates LAG, delta and HALF_EVEN percent to 12 decimals.
     */
    private static final String QUERY = """
        WITH source_rows AS (
            SELECT a.id, a.source_revision_id, a.start_inclusive, a.end_exclusive,
                   a.metric_version, a.currency_code, a.numeric_value, a.value_state,
                   a.time_kind, a.unit_code, a.dimensions::text AS dimensions,
                   a.reporting_basis, m.measure_kind, m.unit_code AS contract_unit,
                   m.qualification, sr.form_code, sr.form_version
            FROM fin_fact.fact_set_publication p
            JOIN fin_exec.fact_publication_outbox o
              ON o.organization_id=p.organization_id
             AND o.economic_entity_id=p.economic_entity_id
             AND o.business_scope_id=p.business_scope_id
             AND o.fact_set_revision_id=p.fact_set_revision_id
             AND o.snapshot_hash=p.snapshot_hash
             AND o.reporting_basis=p.reporting_basis
             AND o.data_space=p.data_space
             AND o.event_type='FACT_SET_PUBLISHED'
            JOIN fin_fact.fact_selection s
              ON s.organization_id=p.organization_id
             AND s.fact_set_revision_id=p.fact_set_revision_id
            JOIN fin_fact.fact_assertion a
              ON a.id=s.fact_assertion_id AND a.organization_id=s.organization_id
             AND a.economic_entity_id=p.economic_entity_id
             AND a.business_scope_id=p.business_scope_id
             AND a.reporting_basis=p.reporting_basis
            LEFT JOIN fin_calc.metric_contract m
              ON m.metric_code=a.metric_code AND m.metric_version=a.metric_version
            LEFT JOIN fin_fact.approved_source_revision sr
              ON sr.organization_id=a.organization_id
             AND sr.source_revision_id=a.source_revision_id
            WHERE p.organization_id=? AND p.economic_entity_id=?
              AND p.business_scope_id=? AND p.fact_set_revision_id=?
              AND p.snapshot_hash=? AND p.data_space='SYNTHETIC'
              AND a.metric_code='REV_NET'
        ), ranked AS (
            SELECT *, count(*) OVER () AS population_count,
                   lag(numeric_value) OVER (ORDER BY start_inclusive,id) AS previous_amount,
                   lag(start_inclusive) OVER (ORDER BY start_inclusive,id) AS previous_start
            FROM source_rows
        ), computed AS (
            SELECT *, numeric_value - previous_amount AS delta,
                   (numeric_value - previous_amount)*100/
                       NULLIF(previous_amount,0) AS raw_percent
            FROM ranked
        )
        SELECT id,source_revision_id,start_inclusive,end_exclusive,
               metric_version,currency_code,numeric_value,value_state,
               time_kind,unit_code,dimensions,reporting_basis,measure_kind,
               contract_unit,qualification,form_code,form_version,
               population_count,previous_amount,previous_start,delta,
               CASE WHEN previous_amount<=0 THEN NULL
                    WHEN abs(raw_percent*1000000000000 -
                             trunc(raw_percent*1000000000000))=0.5
                    THEN (trunc(raw_percent*1000000000000) +
                          CASE WHEN mod(abs(trunc(raw_percent*1000000000000)),2)=0
                               THEN 0 ELSE sign(raw_percent) END)
                         /1000000000000
                    ELSE round(raw_percent,12) END AS percentage
        FROM computed
        ORDER BY start_inclusive,id
        """;

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public MethodResultEnvelope calculate(MethodTaskAdapter.TrustedRevision source) {
        Objects.requireNonNull(source);
        if (!"SYNTHETIC".equals(source.dataSpace()))
            throw new IllegalStateException("R2_FIN01_SQL_ACTUAL_HOLD");
        if (source.organizationId()==null || source.economicEntityId()==null
                || source.businessScopeId()==null || source.factSetRevisionId()==null
                || source.snapshotHash()==null || !source.snapshotHash().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("R2_FIN01_SQL_SOURCE_INVALID");

        List<Row> rows = jdbc.query(QUERY, (rs,i)->new Row(
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,LocalDate.class),rs.getObject(4,LocalDate.class),
                rs.getString(5),rs.getString(6),rs.getBigDecimal(7),rs.getString(8),
                rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),
                rs.getString(13),rs.getString(14),rs.getString(15),rs.getString(16),
                rs.getString(17),rs.getInt(18),rs.getBigDecimal(19),
                rs.getObject(20,LocalDate.class),rs.getBigDecimal(21),
                rs.getBigDecimal(22)),
                source.organizationId(),source.economicEntityId(),
                source.businessScopeId(),source.factSetRevisionId(),
                source.snapshotHash());

        if (rows.size()!=2 || rows.stream().anyMatch(r->r.totalCount()!=2))
            throw new IllegalStateException("R2_FIN01_SQL_EXACT_TWO_MONTHS_REQUIRED");
        var previous=rows.get(0);
        var current=rows.get(1);
        for (var r: rows) {
            if (r.value()==null || r.metricVersion()==null || r.metricVersion().isBlank()
                    || r.currency()==null || !r.currency().matches("[A-Z]{3}")
                    || !"INTERVAL".equals(r.timeKind()) || !"MONEY".equals(r.unitCode())
                    || !"MONEY".equals(r.contractUnit()) || !"FLOW".equals(r.measureKind())
                    || !"QUALIFIED".equals(r.qualification())
                    || !Set.of("PRESENT","EXPLICIT_ZERO").contains(r.valueState())
                    || ("EXPLICIT_ZERO".equals(r.valueState()) && r.value().signum()!=0)
                    || !"{}".equals(r.dimensions())
                    || r.start()==null || r.end()==null
                    || r.start().getDayOfMonth()!=1
                    || !r.end().equals(r.start().plusMonths(1))
                    || r.sourceForm()==null || r.sourceFormVersion()==null)
                throw new IllegalStateException("R2_FIN01_SQL_UNQUALIFIED_INPUT");
        }
        if (!previous.start().plusMonths(1).equals(current.start())
                || !Objects.equals(previous.metricVersion(),current.metricVersion())
                || !Objects.equals(previous.currency(),current.currency())
                || !Objects.equals(previous.basis(),current.basis())
                || !Objects.equals(previous.sourceRevisionId(),current.sourceRevisionId())
                || !Objects.equals(previous.sourceForm(),current.sourceForm())
                || !Objects.equals(previous.sourceFormVersion(),current.sourceFormVersion())
                || !Objects.equals(current.previousStart(),previous.start())
                || current.previousAmount()==null
                || current.previousAmount().compareTo(previous.value())!=0
                || current.delta()==null
                || current.delta().compareTo(current.value().subtract(previous.value()))!=0)
            throw new IllegalStateException("R2_FIN01_SQL_PERIOD_OR_ORIGIN_CONFLICT");
        var facts = rows.stream().map(r->new MethodInput.SourceFact(
                r.assertionId(),r.sourceRevisionId(),"REV_NET",r.metricVersion(),
                r.start(),r.end(),r.currency(),r.valueState(),r.value())).toList();
        var fingerprint=hash(source.snapshotHash()+"|"+facts.stream().map(f->
                f.assertionId()+"|"+f.sourceRevisionId()+"|"+f.startInclusive()+
                "|"+f.endExclusive()+"|"+f.metricVersion()+"|"+f.currency()+
                "|"+f.valueState()+"|"+f.amount().stripTrailingZeros().toPlainString())
                .reduce("",(a,b)->a+"|"+b));
        var input=new MethodInput(source,CODE,VERSION,fingerprint,ZERO_HASH,facts);
        String status,reason;
        BigDecimal percent;
        if (previous.value().signum()<=0) {
            status="NOT_COMPUTABLE";
            reason=previous.value().signum()==0
                    ? "ZERO_BASELINE" : "NEGATIVE_BASELINE_PERCENT_UNDEFINED";
            percent=null;
        } else {
            if (current.percent()==null)
                throw new IllegalStateException("R2_FIN01_SQL_RESULT_MISSING");
            status="CALCULATED";
            reason=null;
            percent=current.percent().stripTrailingZeros();
        }
        return new MethodResultEnvelope(input, CalculationEngine.SQL,
                definition().implementationVersion(),definition().resultSchemaVersion(),
                status,percent,"PCT",reason,facts);
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("R2_FIN01_SHA256_UNAVAILABLE",e);
        }
    }
}
