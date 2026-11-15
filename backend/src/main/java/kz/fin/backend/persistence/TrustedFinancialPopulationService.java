package kz.fin.backend.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * R4.1/R4.2 source-to-canonical population guard.
 * Requirements are server-owned (never accept them as authorization from the browser).
 * The control is only as complete as the independently reviewed source-file row manifest;
 * it does not attest that the original upload contained every business transaction.
 * Pilot form aliases FIN-PILOT-V1 are NOT statutory field mappings.
 *
 * This method deliberately has no HTTP endpoint. Caller-supplied authorizedOrg is
 * NOT a substitute for OIDC/ABAC and ACTUAL must remain blocked until an authorized
 * orchestration layer, production source intake and financial S2 review exist.
 */
@Service
public class TrustedFinancialPopulationService {
    private final JdbcTemplate jdbc;
    public TrustedFinancialPopulationService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record RequiredGroup(UUID sourceRevisionId,String metricCode,LocalDate start,LocalDate end) {
        public RequiredGroup {
            Objects.requireNonNull(sourceRevisionId);
            if(metricCode==null || !metricCode.matches("[A-Z][A-Z0-9_]{1,127}"))
                throw new IllegalArgumentException("INVALID_REQUIRED_METRIC");
            Objects.requireNonNull(start);Objects.requireNonNull(end);
            if(!end.isAfter(start))throw new IllegalArgumentException("INVALID_REQUIRED_PERIOD");
        }
    }
    public record MappedFact(UUID factId,UUID sourceRevisionId,String formCode,String formVersion,
                             String sourceRowId,String sourceField,String metricCode,
                             LocalDate start,LocalDate end,BigDecimal value,String currency) {}
    public record VerifiedPopulation(UUID factSetRevisionId,List<MappedFact> rows) {
        public VerifiedPopulation { rows=List.copyOf(rows); }
    }
    private record Control(int count,BigDecimal total) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public VerifiedPopulation requireComplete(UUID authorizedOrg,UUID publishedFactSet,
                                               List<RequiredGroup> requirements) {
        Objects.requireNonNull(authorizedOrg);Objects.requireNonNull(publishedFactSet);
        requirements=List.copyOf(Objects.requireNonNull(requirements));
        if(requirements.isEmpty() || new HashSet<>(requirements).size()!=requirements.size())
            throw new IllegalArgumentException("MISSING_OR_DUPLICATE_POPULATION_REQUIREMENTS");
        List<String> spaces=jdbc.query("""
            SELECT data_space FROM fin_fact.fact_set_publication
             WHERE organization_id=? AND fact_set_revision_id=?
            """,(rs,i)->rs.getString(1),authorizedOrg,publishedFactSet);
        if(spaces.size()!=1) throw new IllegalArgumentException("POPULATION_FACTSET_NOT_PUBLISHED_OR_FOREIGN");
        if(!spaces.getFirst().equals("SYNTHETIC"))
            throw new IllegalArgumentException("ACTUAL_FINANCIAL_MAPPER_NOT_AUTHORIZED");
        List<MappedFact> all=new ArrayList<>();
        for(RequiredGroup required:requirements){
            List<Control> controls=jdbc.query("""
                SELECT expected_rows,expected_total FROM fin_fact.source_population_control
                WHERE organization_id=? AND source_revision_id=? AND metric_code=?
                  AND time_start=? AND time_end=?
                """,(rs,i)->new Control(rs.getInt(1),rs.getBigDecimal(2)),
                authorizedOrg,required.sourceRevisionId(),required.metricCode(),
                required.start(),required.end());
            if(controls.size()!=1)throw new IllegalArgumentException("SOURCE_POPULATION_CONTROL_MISSING");
            Control expected=controls.getFirst();
            // Every row must have trusted mapping and stable source field lineage.
            List<MappedFact> selected=jdbc.query("""
                SELECT a.id,o.source_revision_id,sr.form_code,sr.form_version,
                       o.source_row_id,o.source_field,a.metric_code,a.start_inclusive,
                       a.end_exclusive,a.numeric_value,a.currency_code
                  FROM fin_fact.fact_selection sel
                  JOIN fin_fact.fact_assertion a
                    ON a.id=sel.fact_assertion_id AND a.organization_id=sel.organization_id
                  JOIN fin_fact.fact_origin o
                    ON o.fact_assertion_id=a.id AND o.organization_id=a.organization_id
                  JOIN fin_fact.approved_source_revision sr
                    ON sr.organization_id=o.organization_id
                   AND sr.source_revision_id=o.source_revision_id
                  JOIN fin_fact.pilot_field_mapping mapping
                    ON mapping.form_code=sr.form_code AND mapping.form_version=sr.form_version
                   AND mapping.source_field=o.source_field AND mapping.metric_code=a.metric_code
                   AND mapping.metric_version=a.metric_version
                 WHERE sel.organization_id=? AND sel.fact_set_revision_id=?
                   AND a.source_revision_id=? AND a.metric_code=?
                   AND a.start_inclusive=? AND a.end_exclusive=?
                   AND a.value_state IN ('PRESENT','EXPLICIT_ZERO')
                   AND a.unit_code='MONEY'
                 ORDER BY o.source_row_id,o.source_field
                """,(rs,i)->new MappedFact(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                    rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),
                    rs.getString(7),rs.getObject(8,LocalDate.class),rs.getObject(9,LocalDate.class),
                    rs.getBigDecimal(10),rs.getString(11)),
                authorizedOrg,publishedFactSet,required.sourceRevisionId(),required.metricCode(),
                required.start(),required.end());

            Long anySelected=jdbc.queryForObject("""
                SELECT count(*) FROM fin_fact.fact_selection s
                JOIN fin_fact.fact_assertion a
                  ON a.id=s.fact_assertion_id AND a.organization_id=s.organization_id
                WHERE s.organization_id=? AND s.fact_set_revision_id=?
                  AND a.source_revision_id=? AND a.metric_code=?
                  AND a.start_inclusive=? AND a.end_exclusive=?
                """,Long.class,authorizedOrg,publishedFactSet,required.sourceRevisionId(),
                required.metricCode(),required.start(),required.end());
            if(anySelected==null || anySelected!=selected.size() || selected.size()!=expected.count())
                throw new IllegalArgumentException("SOURCE_POPULATION_INCOMPLETE_OR_UNMAPPED");
            BigDecimal selectedTotal=selected.stream().map(MappedFact::value)
                    .reduce(BigDecimal.ZERO,BigDecimal::add);
            if(selectedTotal.compareTo(expected.total())!=0)
                throw new IllegalArgumentException("SOURCE_POPULATION_TOTAL_MISMATCH");

            // Count registered source rows, and require the exact row IDs in selected set.
            List<UUID> registered=jdbc.query("""
                SELECT o.fact_assertion_id FROM fin_fact.fact_origin o
                JOIN fin_fact.fact_assertion a ON a.id=o.fact_assertion_id
                 AND a.organization_id=o.organization_id
                WHERE o.organization_id=? AND o.source_revision_id=?
                  AND a.metric_code=? AND a.start_inclusive=? AND a.end_exclusive=?
                """,(rs,i)->rs.getObject(1,UUID.class),authorizedOrg,required.sourceRevisionId(),
                required.metricCode(),required.start(),required.end());
            Set<UUID> selectedIds=new HashSet<>();
            selected.forEach(row->selectedIds.add(row.factId()));
            if(registered.size()!=expected.count() ||
                    !selectedIds.equals(new HashSet<>(registered)))
                throw new IllegalArgumentException("SOURCE_POPULATION_MISSING_REGISTERED_ROWS");
            all.addAll(selected);
        }
        Set<String> currencies=new HashSet<>();
        for(MappedFact row:all) {
            if(row.currency()==null || !row.currency().matches("[A-Z]{3}"))
                throw new IllegalArgumentException("SOURCE_POPULATION_CURRENCY_MISSING");
            currencies.add(row.currency());
        }
        if(currencies.size()!=1)
            throw new IllegalArgumentException("SOURCE_POPULATION_MIXED_CURRENCIES");
        return new VerifiedPopulation(publishedFactSet,all);
    }
}
