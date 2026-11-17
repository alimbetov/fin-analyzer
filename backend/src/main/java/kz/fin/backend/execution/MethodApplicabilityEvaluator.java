package kz.fin.backend.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * R2.A2, fail-closed advisory evaluation of an EXACT published FactSet.
 * Read only: no executor, permit issuance, plan, report, or DB side effects.
 * Callers at the API boundary MUST enforce tenant/ABAC before invocation.
 */
@Service
public class MethodApplicabilityEvaluator {
    public enum Status { ELIGIBLE, NOT_APPLICABLE, BLOCKED_MISSING,
                         BLOCKED_CONFLICT, DISABLED_POLICY, UNQUALIFIED }

    public record SourceKey(UUID organizationId, UUID economicEntityId,
                            UUID businessScopeId, UUID factSetRevisionId,
                            String snapshotHash, String dataSpace) {
        public SourceKey {
            Objects.requireNonNull(organizationId);
            Objects.requireNonNull(economicEntityId);
            Objects.requireNonNull(businessScopeId);
            Objects.requireNonNull(factSetRevisionId);
            if (snapshotHash == null || !snapshotHash.matches("[0-9a-f]{64}")
                    || dataSpace == null || !Set.of("SYNTHETIC", "ACTUAL").contains(dataSpace))
                throw new IllegalArgumentException("R2_APPLICABILITY_SOURCE_KEY_INVALID");
        }
    }
    public record Decision(String methodCode, String variantVersion, Status status,
                           List<String> reasonCodes, List<String> matchedMetrics,
                           List<String> missingMetrics, String sourceFingerprint,
                           String registryFingerprint) {
        public Decision {
            reasonCodes = List.copyOf(reasonCodes);
            matchedMetrics = List.copyOf(matchedMetrics);
            missingMetrics = List.copyOf(missingMetrics);
        }
        public boolean executionPermitted() { return false; }
    }
    private record Publication(String hash,String dataSpace,String basis) {}
    private record Fact(String metric,String version,String unit,String timeKind,
                        String valueState,String measure,String declaredUnit,
                        String qualification,String basis,String currency,
                        String startDate,String endDate,String dimensions) {}

    private final JdbcTemplate jdbc;
    private final MethodVariantRegistry registry;
    private final boolean syntheticLabEnabled;
    public MethodApplicabilityEvaluator(JdbcTemplate jdbc, MethodVariantRegistry registry,
              @Value("${fin.r2.synthetic-lab.enabled:false}") boolean syntheticLabEnabled) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.registry = Objects.requireNonNull(registry);
        this.syntheticLabEnabled = syntheticLabEnabled;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Decision> evaluate(SourceKey source) {
        Objects.requireNonNull(source);
        var published=jdbc.query("""
            SELECT p.snapshot_hash,p.data_space,p.reporting_basis
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
            WHERE p.organization_id=? AND p.economic_entity_id=?
              AND p.business_scope_id=? AND p.fact_set_revision_id=?
            """,(rs,i)->new Publication(rs.getString(1),rs.getString(2),rs.getString(3)),
            source.organizationId(),source.economicEntityId(),source.businessScopeId(),
            source.factSetRevisionId());
        if(published.size()!=1)
            throw new IllegalArgumentException("R2_SOURCE_NOT_PUBLISHED_OR_FORBIDDEN");
        var publication=published.getFirst();
        if(!source.snapshotHash().equals(publication.hash()))
            throw new IllegalArgumentException("R2_SOURCE_FINGERPRINT_CONFLICT");
        if(!source.dataSpace().equals(publication.dataSpace())
                || !Set.of("MANAGEMENT","SEPARATE","CONSOLIDATED").contains(publication.basis()))
            throw new IllegalArgumentException("R2_SOURCE_CONTEXT_CONFLICT");

        // Pin facts in one repeatable-read transaction. LEFT JOIN exposes
        // missing/unqualified metric contracts as conflicts, not verified inputs.
        var facts=jdbc.query("""
            SELECT a.metric_code,a.metric_version,a.unit_code,a.time_kind,
                   a.value_state,m.measure_kind,m.unit_code,m.qualification,
                   a.reporting_basis,a.currency_code,a.start_inclusive::text,
                   a.end_exclusive::text,a.dimensions::text
            FROM fin_fact.fact_selection s
            JOIN fin_fact.fact_assertion a
              ON a.id=s.fact_assertion_id AND a.organization_id=s.organization_id
            JOIN fin_fact.approved_source_revision sr
              ON sr.organization_id=a.organization_id
             AND sr.source_revision_id=a.source_revision_id
            LEFT JOIN fin_calc.metric_contract m
              ON m.metric_code=a.metric_code AND m.metric_version=a.metric_version
            WHERE s.organization_id=? AND s.fact_set_revision_id=?
              AND a.economic_entity_id=? AND a.business_scope_id=?
            """,(rs,i)->new Fact(rs.getString(1),rs.getString(2),rs.getString(3),
               rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),
               rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),
               rs.getString(12),rs.getString(13)),
            source.organizationId(),source.factSetRevisionId(),
            source.economicEntityId(),source.businessScopeId());

        var output=new ArrayList<Decision>();
        for(var d:registry.all()) output.add(assess(d,source,publication,facts));
        return List.copyOf(output);
    }

    private Decision assess(MethodVariantDescriptor d,SourceKey source,
                            Publication publication,List<Fact> facts) {
        var matched=new TreeSet<String>();
        var missing=new TreeSet<String>();
        var conflicts=new TreeSet<String>();
        boolean unresolvedPopulation=false;
        var matchedFacts=new ArrayList<Fact>();
        if(!d.supportedDataSpaces().contains(source.dataSpace()))
            return decision(d,source,Status.NOT_APPLICABLE,
                    List.of("R2_DATA_SPACE_NOT_SUPPORTED"),matched,missing);
        for(var input:d.inputs()){
            if("POPULATION".equals(input.measureKind())){
                // A metric row is never a reviewed SKU or revenue category
                // population. The specialized resolver is not yet connected.
                unresolvedPopulation=true;
                continue;
            }
            var candidates=facts.stream().filter(f->input.metricCode().equals(f.metric()))
                    .toList();
            if(candidates.isEmpty()) { missing.add(input.metricCode()); continue; }
            var compatible=new ArrayList<Fact>();
            for(var f:candidates){
                boolean valid=input.unitCode().equals(f.unit())
                    && input.unitCode().equals(f.declaredUnit())
                    && input.measureKind().equals(f.measure())
                    && ("FLOW".equals(input.measureKind())
                         ? "INTERVAL".equals(f.timeKind()) : "AS_OF".equals(f.timeKind()))
                    && Set.of("PRESENT","EXPLICIT_ZERO").contains(f.valueState())
                    && "QUALIFIED".equals(f.qualification())
                    && publication.basis().equals(f.basis())
                    && f.currency()!=null && f.currency().matches("[A-Z]{3}")
                    && f.startDate()!=null && f.endDate()!=null;
                if(!valid) conflicts.add("R2_INPUT_SEMANTICS_CONFLICT");
                else compatible.add(f);
            }
            if(!compatible.isEmpty()) matched.add(input.metricCode());
            matchedFacts.addAll(compatible);
            if(compatible.isEmpty()) conflicts.add("R2_INPUT_UNQUALIFIED_OR_INCOMPATIBLE");
            if(compatible.stream().map(Fact::currency).distinct().count()>1)
                conflicts.add("R2_CURRENCY_CONFLICT");
            if(compatible.stream().map(Fact::version).distinct().count()>1)
                conflicts.add("R2_METRIC_VERSION_CONFLICT");
            if("FIN-01/REV_NET_MOM".equals(d.methodCode()) && !compatible.isEmpty()){
                var months=new HashSet<YearMonth>();
                for(var f:compatible){
                    try {
                        LocalDate from=LocalDate.parse(f.startDate());
                        LocalDate to=LocalDate.parse(f.endDate());
                        YearMonth ym=YearMonth.from(from);
                        if(!from.equals(ym.atDay(1))
                            || !to.equals(ym.plusMonths(1).atDay(1))
                            || !"{}".equals(f.dimensions()))
                            conflicts.add("R2_PERIOD_OR_POPULATION_CONFLICT");
                        else months.add(ym);
                    }catch(RuntimeException ex){
                        conflicts.add("R2_PERIOD_OR_POPULATION_CONFLICT");
                    }
                }
                if(months.size()<2) missing.add("REV_NET_PREVIOUS_COMPARABLE_MONTH");
                if(months.size()>2) conflicts.add("R2_AMBIGUOUS_REVENUE_HISTORY");
                if(compatible.size()!=months.size())
                    conflicts.add("R2_DUPLICATE_REVENUE_PERIOD");
                if(months.size()==2) {
                    var ordered=months.stream().sorted().toList();
                    if(!ordered.getFirst().plusMonths(1).equals(ordered.getLast()))
                        conflicts.add("R2_NONCONSECUTIVE_REVENUE_MONTHS");
                }
            }
        }
        if("FIN-04/CURRENT_RATIO".equals(d.methodCode())
                && missing.isEmpty() && conflicts.isEmpty()) {
            if(matchedFacts.size()!=2
                    || matchedFacts.stream().map(Fact::currency).distinct().count()!=1
                    || matchedFacts.stream().map(Fact::version).distinct().count()!=1
                    || matchedFacts.stream().map(Fact::startDate).distinct().count()!=1
                    || matchedFacts.stream().map(Fact::endDate).distinct().count()!=1)
                conflicts.add("R2_CURRENT_RATIO_PAIR_INCOMPATIBLE");
        }
        if(!conflicts.isEmpty())
            return decision(d,source,Status.BLOCKED_CONFLICT,
                    List.copyOf(conflicts),matched,missing);
        if(!missing.isEmpty())
            return decision(d,source,Status.BLOCKED_MISSING,
                    List.of("R2_REQUIRED_INPUT_MISSING"),matched,missing);
        if(unresolvedPopulation)
            return decision(d,source,Status.UNQUALIFIED,
                    List.of("R2_REVIEWED_POPULATION_RESOLVER_PENDING"),matched,missing);
        // No registry descriptor or approved metric is an ACTUAL permit.
        if("ACTUAL".equals(source.dataSpace()))
            return decision(d,source,Status.UNQUALIFIED,
                    List.of("R2_ACTUAL_EXECUTION_PERMIT_HOLD"),matched,missing);
        if(!syntheticLabEnabled)
            return decision(d,source,Status.DISABLED_POLICY,
                    List.of("R2_SYNTHETIC_LAB_DISABLED"),matched,missing);
        if("SYS/VERIFY_PUBLISHED_FACT_SET".equals(d.methodCode()))
            return decision(d,source,Status.ELIGIBLE,List.of(),matched,missing);
        return decision(d,source,Status.UNQUALIFIED,
                List.of("R2_METHOD_INPUT_PASSPORT_PENDING"),matched,missing);
    }
    private Decision decision(MethodVariantDescriptor d,SourceKey source,Status status,
                              List<String> reasons,Set<String> matched,Set<String> missing){
        return new Decision(d.methodCode(),d.variantVersion(),status,
                reasons,List.copyOf(matched),List.copyOf(missing),
                source.snapshotHash(),registry.fingerprint());
    }
}
