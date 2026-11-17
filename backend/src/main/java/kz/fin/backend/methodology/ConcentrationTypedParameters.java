package kz.fin.backend.methodology;

import com.fasterxml.jackson.databind.JsonNode;
import kz.fin.backend.analytics.ConcentrationAnalysisEngine;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * Read-only FIN-MR-R2.1 decoder for the existing Pareto and ABC engines.
 * These values are explicit and never defaulted. This utility does NOT
 * approve contracts, authorize execution or modify any runtime adapter.
 */
public final class ConcentrationTypedParameters {
    private ConcentrationTypedParameters() {}

    public record ParetoParameters(String tieSortPolicy, String populationMetric,
                                   String returnsPolicy) {}

    public static ParetoParameters pareto(JsonNode params) {
        requireKeys(params, Set.of("tieSortPolicy","populationMetric","returnsPolicy"));
        String sort=text(params,"tieSortPolicy");
        String returns=text(params,"returnsPolicy");
        String metric=text(params,"populationMetric");
        if (!"AMOUNT_DESC_SKU_CODE_ASC".equals(sort)
                || !"REVIEWED_NET_RECONCILED".equals(returns)
                || !"NET_SALES".equals(metric))
            throw new IllegalArgumentException("FIN_METHOD_PARETO_UNSUPPORTED_POLICY");
        return new ParetoParameters(sort,metric,returns);
    }

    /**
     * sourceParetoVersion must come from the verified upstream result receipt,
     * not from JSON configuration or a client-provided default.
     */
    public static ConcentrationAnalysisEngine.AbcPolicy abc(
            JsonNode params, String sourceParetoVersion) {
        if (!ConcentrationAnalysisEngine.PARETO_VERSION.equals(sourceParetoVersion))
            throw new IllegalArgumentException("FIN_METHOD_PARETO_INPUT_VERSION_CONFLICT");
        requireKeys(params, Set.of("aLimitPercent","bLimitPercent","boundaryCrossingPolicy"));
        if (!"CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED".equals(
                text(params,"boundaryCrossingPolicy")))
            throw new IllegalArgumentException("FIN_METHOD_ABC_BOUNDARY_POLICY_UNSUPPORTED");
        BigDecimal a=decimal(params,"aLimitPercent");
        BigDecimal b=decimal(params,"bLimitPercent");
        if (a.signum()<=0 || a.compareTo(b)>=0
                || b.compareTo(new BigDecimal("100"))>=0)
            throw new IllegalArgumentException("FIN_METHOD_ABC_THRESHOLDS_OUT_OF_RANGE");
        return new ConcentrationAnalysisEngine.AbcPolicy(
                ConcentrationAnalysisEngine.ABC_VERSION,
                a.stripTrailingZeros(),b.stripTrailingZeros());
    }

    private static void requireKeys(JsonNode params, Set<String> expected) {
        if (params==null || !params.isObject() || params.size()!=expected.size())
            throw new IllegalArgumentException("FIN_METHOD_TYPED_CONFIG_SHAPE_INVALID");
        var keys=new HashSet<String>();
        params.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(expected))
            throw new IllegalArgumentException("FIN_METHOD_TYPED_CONFIG_SHAPE_INVALID");
    }

    private static String text(JsonNode params,String key) {
        JsonNode value=params.get(key);
        if(value==null || !value.isTextual())
            throw new IllegalArgumentException("FIN_METHOD_TYPED_STRING_REQUIRED");
        return value.textValue();
    }

    private static BigDecimal decimal(JsonNode params,String key) {
        JsonNode value=params.get(key);
        if(value==null || !value.isNumber())
            throw new IllegalArgumentException("FIN_METHOD_TYPED_DECIMAL_REQUIRED");
        return value.decimalValue();
    }
}
