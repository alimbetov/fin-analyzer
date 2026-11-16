package kz.fin.backend.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Deterministic CON-01 Pareto / CON-02 ABC mathematics, without authorization,
 * financial-source verification, data inference or external report publication.
 * A signed, reconciled SKU population must be proven by a separate trusted
 * data adapter before these operations may run inside SingleExecutor.
 */
public final class ConcentrationAnalysisEngine {
    private ConcentrationAnalysisEngine() {}

    public static final String PARETO_VERSION="CON01_PARETO_V1";
    public static final String ABC_VERSION="CON02_ABC_BOUNDARY_V1";
    public static final int SCALE=12;
    private static final int MAX_ITEMS=10_000;
    private static final BigDecimal HUNDRED=new BigDecimal("100");

    public record SkuAmount(String skuCode,BigDecimal amount) {
        public SkuAmount {
            if(skuCode==null || !skuCode.matches("[A-Za-z0-9_-]{1,128}"))
                throw new IllegalArgumentException("CON_SKU_CODE_INVALID");
            Objects.requireNonNull(amount,"amount");
            if(amount.scale()>12 || amount.precision()>38 || amount.signum()<0)
                throw new IllegalArgumentException("CON_AMOUNT_UNQUALIFIED");
        }
    }
    public record ParetoRow(String skuCode,BigDecimal amount,BigDecimal sharePercent,
                            BigDecimal cumulativePercent,BigDecimal precedingPercent) {}
    public record Pareto(BigDecimal reconciledTotal,List<ParetoRow> rows,String methodVersion) {
        public Pareto { rows=List.copyOf(rows); }
    }
    public record AbcPolicy(String policyVersion,BigDecimal aLimitPercent,BigDecimal bLimitPercent) {
        public AbcPolicy {
            if(policyVersion==null || policyVersion.isBlank() ||
                    aLimitPercent==null || bLimitPercent==null ||
                    aLimitPercent.signum()<=0 ||
                    aLimitPercent.compareTo(bLimitPercent)>=0 ||
                    bLimitPercent.compareTo(HUNDRED)>=0)
                throw new IllegalArgumentException("CON_ABC_INVALID_POLICY");
        }
    }
    public record AbcRow(String skuCode,String classification,BigDecimal contributionPercent,
                         BigDecimal cumulativePercent) {}
    public record AbcResult(String policyVersion,List<AbcRow> rows) {
        public AbcResult { rows=List.copyOf(rows); }
    }

    public static final AbcPolicy DEFAULT_ABC_POLICY =
            new AbcPolicy(ABC_VERSION,new BigDecimal("80"),new BigDecimal("95"));

    public static Pareto pareto(List<SkuAmount> source,BigDecimal trustedPopulationTotal) {
        Objects.requireNonNull(source,"source");
        Objects.requireNonNull(trustedPopulationTotal,"trustedPopulationTotal");
        if(source.isEmpty() || source.size()>MAX_ITEMS)
            throw new IllegalArgumentException("CON_POPULATION_SIZE_INVALID");
        if(trustedPopulationTotal.signum()<=0)
            throw new IllegalArgumentException("CON_TOTAL_MUST_BE_POSITIVE");
        var ordered=new ArrayList<>(source);
        var codes=new HashSet<String>();
        BigDecimal sum=BigDecimal.ZERO;
        for(var entry:ordered) {
            Objects.requireNonNull(entry,"sku");
            if(!codes.add(entry.skuCode()))
                throw new IllegalArgumentException("CON_DUPLICATE_SKU");
            sum=sum.add(entry.amount());
        }
        if(sum.compareTo(trustedPopulationTotal)!=0)
            throw new IllegalArgumentException("CON_POPULATION_NOT_RECONCILED");
        ordered.sort(Comparator.comparing(SkuAmount::amount).reversed()
                .thenComparing(SkuAmount::skuCode));
        var rows=new ArrayList<ParetoRow>();
        BigDecimal running=BigDecimal.ZERO;
        for(var item:ordered) {
            var before=percent(running,trustedPopulationTotal);
            running=running.add(item.amount());
            rows.add(new ParetoRow(item.skuCode(),item.amount(),
                    percent(item.amount(),trustedPopulationTotal),
                    percent(running,trustedPopulationTotal),before));
        }
        return new Pareto(trustedPopulationTotal,rows,PARETO_VERSION);
    }

    /** Boundary-crossing SKU stays in its preceding band, by explicit policy.
     *  An SKU bringing cumulative contribution over 80% belongs to class A.
     *  The NEXT SKU starts class B. Same rule at 95% for B/C.
     */
    public static AbcResult abc(Pareto sortedPareto,AbcPolicy policy) {
        Objects.requireNonNull(sortedPareto,"sortedPareto");
        Objects.requireNonNull(policy,"policy");
        if(!PARETO_VERSION.equals(sortedPareto.methodVersion()) ||
                sortedPareto.rows().isEmpty())
            throw new IllegalArgumentException("CON_PARETO_VERSION_UNQUALIFIED");
        var classified=new ArrayList<AbcRow>();
        for(var row:sortedPareto.rows()) {
            String bucket=row.precedingPercent().compareTo(policy.aLimitPercent())<0?"A":
                    row.precedingPercent().compareTo(policy.bLimitPercent())<0?"B":"C";
            classified.add(new AbcRow(row.skuCode(),bucket,
                    row.sharePercent(),row.cumulativePercent()));
        }
        return new AbcResult(policy.policyVersion(),classified);
    }

    private static BigDecimal percent(BigDecimal amount,BigDecimal total) {
        return amount.multiply(HUNDRED).divide(total,SCALE,RoundingMode.HALF_EVEN);
    }
}
