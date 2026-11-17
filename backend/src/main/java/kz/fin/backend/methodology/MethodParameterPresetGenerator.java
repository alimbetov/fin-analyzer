package kz.fin.backend.methodology;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic editorial suggestions, NOT approved financial methodologies.
 * The caller must explicitly select base points and the percentage-point step;
 * neither 80/95 nor 5pp is an implicit business default.
 *
 * Only the two R2.1 typed profiles are eligible. The 259 unreviewed parameter
 * candidates are not a basis for manufacturing 60 synthetic algorithms.
 */
public final class MethodParameterPresetGenerator {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final String BOUNDARY = "CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED";
    private MethodParameterPresetGenerator() {}

    public record Preset(int optionNo, Map<String,Object> parameters) {
        public Preset {
            if (optionNo < 1 || optionNo > 10) throw new IllegalArgumentException("FIN_PRESET_OPTION_LIMIT");
            parameters = Map.copyOf(parameters);
        }
    }
    private record Candidate(int deltaA, int deltaB, BigDecimal a, BigDecimal b) {
        int distance() { return Math.abs(deltaA) + Math.abs(deltaB); }
    }

    /** CON-01 has precisely one implemented, non-configurable policy. */
    public static List<Preset> fixedPareto() {
        return List.of(new Preset(1, Map.of(
                "tieSortPolicy","AMOUNT_DESC_SKU_CODE_ASC",
                "populationMetric","NET_SALES",
                "returnsPolicy","REVIEWED_NET_RECONCILED")));
    }

    /**
     * Explore an explicitly specified grid around a reviewed candidate base.
     * The center is always option 1; nearest valid distinct combinations follow.
     * The caller must freeze and moderate the resulting edition separately.
     */
    public static List<Preset> abcNear(BigDecimal baseA, BigDecimal baseB,
                                      BigDecimal stepPoints, int maxOptions) {
        Objects.requireNonNull(baseA);
        Objects.requireNonNull(baseB);
        Objects.requireNonNull(stepPoints);
        if (maxOptions < 1 || maxOptions > 10)
            throw new IllegalArgumentException("FIN_PRESET_OPTION_LIMIT");
        if (stepPoints.signum() <= 0 || stepPoints.compareTo(BigDecimal.TEN) > 0)
            throw new IllegalArgumentException("FIN_PRESET_STEP_INVALID");
        if (!valid(baseA,baseB))
            throw new IllegalArgumentException("FIN_PRESET_BASE_BOUNDARIES_INVALID");

        var candidates = new ArrayList<Candidate>();
        for (int da=-3;da<=3;da++) {
            for (int db=-3;db<=3;db++) {
                var a = baseA.add(stepPoints.multiply(BigDecimal.valueOf(da)));
                var b = baseB.add(stepPoints.multiply(BigDecimal.valueOf(db)));
                if (valid(a,b)) candidates.add(new Candidate(da,db,a,b));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::distance)
                .thenComparingInt(c -> Math.abs(c.deltaA()))
                .thenComparingInt(c -> Math.abs(c.deltaB()))
                .thenComparingInt(Candidate::deltaA)
                .thenComparingInt(Candidate::deltaB));
        var choices = new ArrayList<Preset>();
        for (var c : candidates) {
            if (choices.size() >= maxOptions) break;
            choices.add(new Preset(choices.size()+1, Map.of(
                    "aLimitPercent", c.a().stripTrailingZeros(),
                    "bLimitPercent", c.b().stripTrailingZeros(),
                    "boundaryCrossingPolicy", BOUNDARY)));
        }
        return List.copyOf(choices);
    }
    private static boolean valid(BigDecimal a,BigDecimal b) {
        return a.signum()>0 && a.compareTo(b)<0 && b.compareTo(HUNDRED)<0;
    }
}
