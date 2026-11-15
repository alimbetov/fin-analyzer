package kz.fin.backend.diagnostics;

import kz.fin.backend.analytics.AnalysisContext;
import java.math.*;
import java.time.LocalDate;
import java.util.*;

/**
 * R4.3 arithmetic profit bridge + reviewable hypotheses + scoped materiality.
 * Decomposition is an identity, NOT causal attribution or approved financial advice.
 */
public final class DiagnosticProofEngine {
    private DiagnosticProofEngine() {}
    public static final String VERSION="FIN_PROOF_R4_1";
    private static final int SCALE=12;
    public enum ExplanationStatus { ACCOUNTING_DECOMPOSITION_ONLY }
    public enum RiskState { NOT_ASSESSED, MATERIAL_LOSS, BELOW_APPROVED_THRESHOLD, NO_LOSS }
    public enum HypothesisState { NOT_TESTED, CONSISTENT_WITH_EVIDENCE, CONTRADICTED, INCONCLUSIVE }

    public record Sku(String sku,BigDecimal quantity,BigDecimal unitPrice,BigDecimal unitCost,
                      String sourceRevision) {
        public Sku {
            if(sku==null||sku.isBlank()||sourceRevision==null||sourceRevision.isBlank())
                throw new IllegalArgumentException("PROOF_SOURCE_REQUIRED");
            Objects.requireNonNull(quantity);Objects.requireNonNull(unitPrice);Objects.requireNonNull(unitCost);
            if(quantity.signum()<0||unitPrice.signum()<0||unitCost.signum()<0)
                throw new IllegalArgumentException("NEGATIVE_BRIDGE_DRIVER");
            for(BigDecimal value:List.of(quantity,unitPrice,unitCost))
                if(value.precision()>38||value.scale()>12)
                    throw new IllegalArgumentException("BRIDGE_DECIMAL_POLICY");
        }
        BigDecimal contribution(){return quantity.multiply(unitPrice.subtract(unitCost));}
    }
    public record Context(UUID organizationId,UUID businessScopeId,UUID sourceFactSetRevisionId,
                          AnalysisContext.DataSpace dataSpace,String currency) {
        public Context {
            Objects.requireNonNull(organizationId);Objects.requireNonNull(businessScopeId);
            Objects.requireNonNull(sourceFactSetRevisionId);Objects.requireNonNull(dataSpace);
            if(currency==null||!currency.matches("[A-Z]{3}"))
                throw new IllegalArgumentException("INVALID_BRIDGE_CURRENCY");
        }
    }
    public record Bridge(Context context,BigDecimal grossProfitPrior,BigDecimal grossProfitCurrent,
                         BigDecimal deltaGrossProfit,BigDecimal volumeEffect,BigDecimal mixEffect,
                         BigDecimal priceEffect,BigDecimal costEffect,BigDecimal residual,
                         Map<String,List<String>> evidence,ExplanationStatus explanationStatus,
                         String methodVersion) {
        public Bridge {
            Map<String,List<String>> immutable=new TreeMap<>();
            evidence.forEach((key,value)->immutable.put(key,List.copyOf(value)));
            evidence=Collections.unmodifiableMap(immutable);
        }
    }
    public static Bridge decompose(Context context,List<Sku> prior,List<Sku> current){
        Objects.requireNonNull(context);
        if(context.dataSpace()!=AnalysisContext.DataSpace.SYNTHETIC)
            throw new IllegalArgumentException("PROOF_ACTUAL_METHOD_NOT_QUALIFIED");
        Map<String,Sku> old=unique(prior),now=unique(current);
        if(old.isEmpty()||!old.keySet().equals(now.keySet()))
            throw new IllegalArgumentException("BRIDGE_SKU_COVERAGE_OR_MIX_MISMATCH");
        BigDecimal q0=old.values().stream().map(Sku::quantity).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal q1=now.values().stream().map(Sku::quantity).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(q0.signum()<=0)throw new IllegalArgumentException("BRIDGE_BASE_QUANTITY_NONPOSITIVE");
        BigDecimal gp0=old.values().stream().map(Sku::contribution).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal gp1=now.values().stream().map(Sku::contribution).reduce(BigDecimal.ZERO,BigDecimal::add);
        // A precisely documented sequential allocation convention; not a causal conclusion.
        BigDecimal baseContributionPerUnit=gp0.divide(q0,SCALE,RoundingMode.HALF_EVEN);
        BigDecimal volume=q1.subtract(q0).multiply(baseContributionPerUnit);
        BigDecimal atNewMixOldRates=BigDecimal.ZERO;
        BigDecimal price=BigDecimal.ZERO,cost=BigDecimal.ZERO;
        Map<String,List<String>> sources=new TreeMap<>();
        for(String sku:old.keySet()){
            Sku a=old.get(sku),b=now.get(sku);
            atNewMixOldRates=atNewMixOldRates.add(b.quantity().multiply(a.unitPrice().subtract(a.unitCost())));
            price=price.add(b.quantity().multiply(b.unitPrice().subtract(a.unitPrice())));
            cost=cost.subtract(b.quantity().multiply(b.unitCost().subtract(a.unitCost())));
            sources.put(sku,List.of(a.sourceRevision(),b.sourceRevision()));
        }
        BigDecimal mix=atNewMixOldRates.subtract(gp0).subtract(volume);
        BigDecimal delta=gp1.subtract(gp0);
        BigDecimal residual=delta.subtract(volume).subtract(mix).subtract(price).subtract(cost);
        if(residual.signum()!=0)throw new IllegalStateException("BRIDGE_NOT_RECONCILED");
        return new Bridge(context,gp0,gp1,delta,volume,mix,price,cost,residual,
                sources,ExplanationStatus.ACCOUNTING_DECOMPOSITION_ONLY,VERSION);
    }
    private static Map<String,Sku> unique(List<Sku> input){
        Objects.requireNonNull(input);
        Map<String,Sku> result=new TreeMap<>();
        for(Sku s:input){
            Objects.requireNonNull(s);
            if(result.putIfAbsent(s.sku(),s)!=null)
                throw new IllegalArgumentException("DUPLICATE_BRIDGE_SKU");
        }
        return result;
    }

    public record MaterialityPolicy(BigDecimal lossLimit,String version,String approvedBy,LocalDate approvedAt) {
        public MaterialityPolicy {
            Objects.requireNonNull(lossLimit);
            if(lossLimit.signum()<=0||version==null||version.isBlank()
                    ||approvedBy==null||approvedBy.isBlank()||approvedAt==null)
                throw new IllegalArgumentException("UNAPPROVED_MATERIALITY_POLICY");
        }
    }
    public record Risk(RiskState state,BigDecimal measuredLoss,BigDecimal reviewedThreshold,
                       String policyVersion,String reason) {}
    public static Risk assessMateriality(Bridge bridge,MaterialityPolicy policy){
        Objects.requireNonNull(bridge);
        BigDecimal loss=bridge.deltaGrossProfit().signum()<0?bridge.deltaGrossProfit().negate():BigDecimal.ZERO;
        if(policy==null)return new Risk(RiskState.NOT_ASSESSED,loss,null,null,
                "NO_REVIEWED_MATERIALITY_THRESHOLD");
        if(loss.signum()==0)return new Risk(RiskState.NO_LOSS,loss,policy.lossLimit(),
                policy.version(),"GROSS_PROFIT_DID_NOT_DECREASE");
        RiskState state=loss.compareTo(policy.lossLimit())>=0
                ?RiskState.MATERIAL_LOSS:RiskState.BELOW_APPROVED_THRESHOLD;
        return new Risk(state,loss,policy.lossLimit(),policy.version(),
                "APPROVED_THRESHOLD_COMPARISON_NOT_CAUSAL_RISK_SCORE");
    }

    public record Hypothesis(String code,Set<String> requiredEvidenceCodes,String question) {
        public Hypothesis {
            if(code==null||code.isBlank()||question==null||question.isBlank())
                throw new IllegalArgumentException("INVALID_HYPOTHESIS");
            requiredEvidenceCodes=Set.copyOf(Objects.requireNonNull(requiredEvidenceCodes));
            if(requiredEvidenceCodes.isEmpty())throw new IllegalArgumentException("NO_HYPOTHESIS_TEST");
        }
    }
    public record HypothesisReview(Hypothesis hypothesis,HypothesisState state,
                                   Set<String> reviewedEvidence,String limitation) {
        public HypothesisReview {reviewedEvidence=Set.copyOf(reviewedEvidence);}
    }
    public static HypothesisReview review(Hypothesis hypothesis,Set<String> verifiedEvidence,
                                          Boolean supportsHypothesis) {
        Objects.requireNonNull(hypothesis);Objects.requireNonNull(verifiedEvidence);
        Set<String> accepted=Set.copyOf(verifiedEvidence);
        if(!accepted.containsAll(hypothesis.requiredEvidenceCodes()))
            return new HypothesisReview(hypothesis,HypothesisState.NOT_TESTED,accepted,
                    "INDEPENDENT_DRIVER_EVIDENCE_MISSING");
        if(supportsHypothesis==null)
            return new HypothesisReview(hypothesis,HypothesisState.INCONCLUSIVE,accepted,
                    "EVIDENCE_PRESENT_BUT_NOT_ADJUDICATED");
        return new HypothesisReview(hypothesis,
                supportsHypothesis?HypothesisState.CONSISTENT_WITH_EVIDENCE:HypothesisState.CONTRADICTED,
                accepted,"CONSISTENCY_IS_NOT_PROOF_OF_CAUSATION");
    }
}
