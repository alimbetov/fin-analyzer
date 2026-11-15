package kz.fin.backend.diagnostics;

import kz.fin.backend.analytics.AnalysisContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.*;

/**
 * FIN R4.5: deterministic relationship graph over independently verified,
 * published cross-form metric populations. A relationship is not a statistical
 * correlation or proof of causality. The production-facing adapter MUST authorize
 * the organization and resolve source revisions and all fact values server-side.
 */
public final class CrossFormDiagnosticEngine {
    private CrossFormDiagnosticEngine() {}
    public static final String METHOD_VERSION = "CROSS_FORM_R4_5_SYNTHETIC_V1";

    public enum TimeRole { CURRENT_FLOW, CURRENT_STOCK, PRIOR_STOCK }
    public enum RelationType { ACCOUNTING_IDENTITY, CROSS_REPORT_RECONCILIATION,
        ECONOMIC_BRIDGE, QUALITATIVE_PATTERN }
    public enum Verdict { RECONCILED, DEVIATION, OBSERVATION, REVIEW,
        MISSING_EVIDENCE, NOT_COMPUTABLE }

    public record MetricKey(String code, TimeRole role) {
        public MetricKey {
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,127}"))
                throw new IllegalArgumentException("INVALID_CROSS_FORM_METRIC");
            Objects.requireNonNull(role);
        }
    }
    public record Source(String formCode, UUID sourceRevision, String rowId, String sourceField,
                         UUID factId) {
        public Source {
            if(formCode == null || formCode.isBlank() || rowId == null || rowId.isBlank()
                    || sourceField == null || sourceField.isBlank())
                throw new IllegalArgumentException("CROSS_FORM_SOURCE_REQUIRED");
            Objects.requireNonNull(sourceRevision);
            Objects.requireNonNull(factId);
        }
    }
    public record Evidence(MetricKey key, YearMonth period, BigDecimal amount,
                           String currency, List<Source> sources) {
        public Evidence {
            Objects.requireNonNull(key);
            Objects.requireNonNull(period);
            Objects.requireNonNull(amount);
            if(currency == null || !currency.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("CROSS_FORM_CURRENCY_REQUIRED");
            sources = List.copyOf(Objects.requireNonNull(sources));
            if(sources.isEmpty())throw new IllegalArgumentException("CROSS_FORM_PROVENANCE_REQUIRED");
            if(sources.stream().map(Source::factId).distinct().count() != sources.size())
                throw new IllegalArgumentException("CROSS_FORM_DUPLICATE_FACT");
        }
    }
    public record Scope(UUID organizationId, UUID entityId, UUID businessScopeId,
                        UUID factSetRevisionId, AnalysisContext.DataSpace dataSpace,
                        String currency, String reportingBasis, YearMonth month) {
        public Scope {
            Objects.requireNonNull(organizationId);
            Objects.requireNonNull(entityId);
            Objects.requireNonNull(businessScopeId);
            Objects.requireNonNull(factSetRevisionId);
            Objects.requireNonNull(dataSpace);
            Objects.requireNonNull(month);
            if(currency == null || !currency.matches("[A-Z]{3}")
                    || !Set.of("MANAGEMENT","SEPARATE","CONSOLIDATED").contains(reportingBasis))
                throw new IllegalArgumentException("CROSS_FORM_INVALID_CONTEXT");
        }
    }
    public record Rule(String code, RelationType type, List<MetricKey> required) {
        public Rule {
            Objects.requireNonNull(type);
            if(code == null || !code.matches("[A-Z][A-Z0-9_]{1,127}"))
                throw new IllegalArgumentException("INVALID_CROSS_FORM_RULE");
            required = List.copyOf(required);
            if(required.isEmpty() || new HashSet<>(required).size()!=required.size())
                throw new IllegalArgumentException("INVALID_CROSS_FORM_DEPENDENCIES");
        }
    }
    private static MetricKey flow(String code) {return new MetricKey(code, TimeRole.CURRENT_FLOW);}
    private static MetricKey stock(String code) {return new MetricKey(code, TimeRole.CURRENT_STOCK);}
    private static MetricKey prior(String code) {return new MetricKey(code, TimeRole.PRIOR_STOCK);}

    // Explicitly versioned semantic relations: not inferred from similar names.
    public static final List<Rule> RULES = List.of(
        new Rule("BALANCE_IDENTITY",RelationType.ACCOUNTING_IDENTITY,
                List.of(stock("ASSETS"),stock("LIABILITIES"),stock("EQUITY"))),
        new Rule("CASH_ROLLFORWARD",RelationType.CROSS_REPORT_RECONCILIATION,
                List.of(prior("CASH_END"),stock("CASH_END"),
                        flow("CFO"),flow("CFI"),flow("CFF"))),
        new Rule("CASH_LEDGER_CASHFLOW_BRIDGE",RelationType.ECONOMIC_BRIDGE,
                List.of(flow("CASH_IN"),flow("CASH_OUT"),flow("CFO"),flow("CFI"),flow("CFF"))),
        new Rule("OPERATING_RESULT_TO_CFO",RelationType.ECONOMIC_BRIDGE,
                List.of(flow("REV_NET"),flow("COGS"),flow("OPEX"),flow("CFO"))),
        new Rule("RECEIVABLES_CASH_STRESS",RelationType.QUALITATIVE_PATTERN,
                List.of(prior("AR_END"),stock("AR_END"),flow("REV_NET"),flow("CFO"))),
        new Rule("GROSS_MARGIN",RelationType.ECONOMIC_BRIDGE,
                List.of(flow("REV_NET"),flow("COGS")))
    );
    public static final Set<MetricKey> REQUIRED_KEYS;
    static {
        Set<MetricKey> keys=new TreeSet<>(Comparator.comparing(MetricKey::code)
                .thenComparing(x->x.role().name()));
        RULES.forEach(r->keys.addAll(r.required()));
        REQUIRED_KEYS=Set.copyOf(keys);
    }

    public record Finding(String ruleCode, RelationType type, Verdict verdict, String reasonCode,
                          Map<String,BigDecimal> computed, List<MetricKey> missing,
                          List<Source> sources, String qualification) {
        public Finding {
            computed=Collections.unmodifiableMap(new TreeMap<>(computed));
            missing=List.copyOf(missing);
            sources=List.copyOf(sources);
            if(verdict==Verdict.MISSING_EVIDENCE && missing.isEmpty())
                throw new IllegalArgumentException("MISSING_REQUIRES_EXPLICIT_METRICS");
            if(verdict!=Verdict.MISSING_EVIDENCE && !missing.isEmpty())
                throw new IllegalArgumentException("NONMISSING_CANNOT_HAVE_MISSING_METRICS");
        }
    }
    public record Report(Scope scope,String methodVersion,Map<MetricKey,Evidence> evidence,
                         List<Finding> findings,String qualification) {
        public Report{
            evidence=Map.copyOf(evidence);findings=List.copyOf(findings);
        }
    }

    public static Report evaluate(Scope scope,Collection<Evidence> evidences) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(evidences);
        if(scope.dataSpace()!=AnalysisContext.DataSpace.SYNTHETIC)
            throw new IllegalArgumentException("UNQUALIFIED_CROSS_FORM_ACTUAL");
        Map<MetricKey,Evidence> values=new HashMap<>();
        for(Evidence evidence:evidences){
            Objects.requireNonNull(evidence);
            if(!scope.currency().equals(evidence.currency()))
                throw new IllegalArgumentException("CROSS_FORM_MIXED_CURRENCY");
            if(!REQUIRED_KEYS.contains(evidence.key()))
                throw new IllegalArgumentException("CROSS_FORM_UNRECOGNIZED_METRIC");
            YearMonth expected=evidence.key().role()==TimeRole.PRIOR_STOCK
                    ?scope.month().minusMonths(1):scope.month();
            if(!expected.equals(evidence.period()))
                throw new IllegalArgumentException("CROSS_FORM_PERIOD_MISMATCH");
            if(values.putIfAbsent(evidence.key(),evidence)!=null)
                throw new IllegalArgumentException("CROSS_FORM_DUPLICATE_METRIC_SLOT");
        }

        List<Finding> findings=new ArrayList<>();
        for(Rule rule:RULES){
            List<MetricKey> missing=rule.required().stream()
                    .filter(k->!values.containsKey(k)).toList();
            if(!missing.isEmpty()){
                findings.add(new Finding(rule.code(),rule.type(),Verdict.MISSING_EVIDENCE,
                        "CROSS_FORM_REQUIRES_ADDITIONAL_APPROVED_EVIDENCE",
                        Map.of(),missing,List.of(),"SYNTHETIC_PILOT_NOT_CAUSAL"));
                continue;
            }
            Map<String,BigDecimal> computed=new TreeMap<>();
            Verdict verdict;
            String reason;
            switch(rule.code()){
                case "BALANCE_IDENTITY" -> {
                    BigDecimal difference=amount(values,stock("ASSETS"))
                            .subtract(amount(values,stock("LIABILITIES")))
                            .subtract(amount(values,stock("EQUITY")));
                    computed.put("balanceDifference",difference);
                    verdict=difference.signum()==0?Verdict.RECONCILED:Verdict.DEVIATION;
                    reason=difference.signum()==0?"BALANCE_RECONCILED":"BALANCE_DIFFERENCE_REVIEW";
                }
                case "CASH_ROLLFORWARD" -> {
                    BigDecimal expectedCash=amount(values,prior("CASH_END"))
                            .add(amount(values,flow("CFO")))
                            .add(amount(values,flow("CFI")))
                            .add(amount(values,flow("CFF")));
                    BigDecimal difference=amount(values,stock("CASH_END")).subtract(expectedCash);
                    computed.put("expectedClosingCash",expectedCash);
                    computed.put("cashRollforwardDifference",difference);
                    verdict=difference.signum()==0?Verdict.RECONCILED:Verdict.DEVIATION;
                    reason=difference.signum()==0?"CASH_ROLLFORWARD_RECONCILED":
                            "CASH_BRIDGE_REVIEW_FX_AND_RECLASSIFICATION_NOT_MODELED";
                }
                case "CASH_LEDGER_CASHFLOW_BRIDGE" -> {
                    BigDecimal netLedger=amount(values,flow("CASH_IN"))
                            .subtract(amount(values,flow("CASH_OUT")));
                    BigDecimal netReported=amount(values,flow("CFO"))
                            .add(amount(values,flow("CFI")))
                            .add(amount(values,flow("CFF")));
                    computed.put("netLedgerMovement",netLedger);
                    computed.put("netReportedCashFlows",netReported);
                    computed.put("ledgerCashflowDifference",netLedger.subtract(netReported));
                    // Cash ledger could include transfers, different cash-equivalent scope, VAT etc.
                    verdict=Verdict.REVIEW;
                    reason="CASH_LEDGER_SCOPE_AND_CASH_EQUIVALENTS_REVIEW_REQUIRED";
                }
                case "OPERATING_RESULT_TO_CFO" -> {
                    BigDecimal operatingProxy=amount(values,flow("REV_NET"))
                            .subtract(amount(values,flow("COGS")))
                            .subtract(amount(values,flow("OPEX")));
                    computed.put("simplifiedOperatingResult",operatingProxy);
                    computed.put("cfoDifferenceToOperatingResult",
                            amount(values,flow("CFO")).subtract(operatingProxy));
                    verdict=Verdict.OBSERVATION;
                    reason="CFO_AND_OPERATING_RESULT_DIFFERENCE_NOT_CAUSAL";
                }
                case "RECEIVABLES_CASH_STRESS" -> {
                    BigDecimal change=amount(values,stock("AR_END"))
                            .subtract(amount(values,prior("AR_END")));
                    computed.put("receivablesChange",change);
                    computed.put("operatingCash",amount(values,flow("CFO")));
                    boolean signal=change.signum()>0
                            && amount(values,flow("CFO")).signum()<0
                            && amount(values,flow("REV_NET")).signum()>0;
                    verdict=signal?Verdict.REVIEW:Verdict.OBSERVATION;
                    reason=signal?"AR_GROWTH_WITH_NEGATIVE_CFO_HYPOTHESIS_ONLY":
                            "NO_COMBINED_AR_CFO_STRESS_PATTERN";
                }
                case "GROSS_MARGIN" -> {
                    BigDecimal revenue=amount(values,flow("REV_NET"));
                    if(revenue.signum()<=0){
                        verdict=Verdict.NOT_COMPUTABLE;
                        reason="GROSS_MARGIN_POSITIVE_REVENUE_REQUIRED";
                    }else{
                        BigDecimal gross=revenue.subtract(amount(values,flow("COGS")));
                        computed.put("grossProfit",gross);
                        computed.put("grossMarginRatio",
                                gross.divide(revenue,12,RoundingMode.HALF_EVEN));
                        verdict=Verdict.OBSERVATION;
                        reason="GROSS_MARGIN_CALCULATED_NOT_QUALIFIED";
                    }
                }
                default -> throw new IllegalStateException("UNKNOWN_CROSS_FORM_RULE");
            }
            // Provenance is the union of exact source rows that contributed.
            Map<UUID,Source> provenance=new LinkedHashMap<>();
            for(MetricKey key:rule.required())
                values.get(key).sources().forEach(source->provenance.putIfAbsent(source.factId(),source));
            findings.add(new Finding(rule.code(),rule.type(),verdict,reason,
                    computed,List.of(),List.copyOf(provenance.values()),
                    "SYNTHETIC_PILOT_NOT_CAUSAL"));
        }
        return new Report(scope,METHOD_VERSION,values,findings,"SYNTHETIC_ONLY_UNQUALIFIED");
    }
    private static BigDecimal amount(Map<MetricKey,Evidence> evidence,MetricKey key){
        return evidence.get(key).amount();
    }
}
