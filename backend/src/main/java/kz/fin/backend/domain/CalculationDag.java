package kz.fin.backend.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

public final class CalculationDag {
    public record Dimension(Map<String, Integer> powers) {
        public Dimension {
            TreeMap<String, Integer> normalized = new TreeMap<>();
            Objects.requireNonNull(powers).forEach((k, v) -> {
                if (k == null || k.isBlank() || v == null) throw new IllegalArgumentException("invalid dimension");
                if (v != 0) normalized.put(k, v);
            });
            powers = Collections.unmodifiableMap(normalized);
        }
        public static Dimension scalar() { return new Dimension(Map.of()); }
        public static Dimension money() { return new Dimension(Map.of("MONEY", 1)); }
        public static Dimension units() { return new Dimension(Map.of("UNIT", 1)); }
        public Dimension multiply(Dimension other) { return combine(other, 1); }
        public Dimension divide(Dimension other) { return combine(other, -1); }
        private Dimension combine(Dimension other, int sign) {
            Map<String, Integer> next = new HashMap<>(powers);
            other.powers.forEach((k,v) -> next.merge(k, sign * v, Integer::sum));
            return new Dimension(next);
        }
    }

    public sealed interface Expr permits Ref, Constant, Add, Subtract, Multiply, Divide {}
    public record Ref(String code) implements Expr {}
    public record Constant(BigDecimal value, Dimension dimension) implements Expr {}
    public record Add(Expr left, Expr right) implements Expr {}
    public record Subtract(Expr left, Expr right) implements Expr {}
    public record Multiply(Expr left, Expr right) implements Expr {}
    public record Divide(Expr numerator, Expr denominator) implements Expr {}
    public record Formula(String code, String version, Dimension dimension, Expr expression) {}

    public enum Status { CALCULATED, ESTIMATED, NOT_COMPUTABLE }
    public record Result(Status status, BigDecimal value, Dimension dimension, String reason) {
        public static Result missing(Dimension dimension, String reason) {
            return new Result(Status.NOT_COMPUTABLE, null, dimension, reason);
        }
        public static Result calculated(BigDecimal value, Dimension dimension) {
            return new Result(Status.CALCULATED, value, dimension, null);
        }
        public static Result estimated(BigDecimal value, Dimension dimension) {
            return new Result(Status.ESTIMATED, value, dimension, "ESTIMATED_INPUT");
        }
    }

    private final Map<String, Dimension> inputDefinitions;
    private final Map<String, Formula> formulas;
    private final MathContext mathContext;

    public CalculationDag(Map<String, Dimension> inputDefinitions,
                          Collection<Formula> definedFormulas, MathContext mathContext) {
        this.inputDefinitions = Map.copyOf(inputDefinitions);
        this.mathContext = Objects.requireNonNull(mathContext);
        Map<String, Formula> mapped = new HashMap<>();
        for (Formula formula : definedFormulas) {
            Objects.requireNonNull(formula);
            if (formula.code() == null || formula.version() == null || formula.version().isBlank()
                    || formula.dimension() == null || formula.expression() == null
                    || mapped.putIfAbsent(formula.code(), formula) != null
                    || inputDefinitions.containsKey(formula.code())) {
                throw new IllegalArgumentException("duplicate or incomplete formula");
            }
        }
        this.formulas = Map.copyOf(mapped);
        Map<String, Dimension> checked = new HashMap<>();
        for (Formula formula : mapped.values()) {
            Dimension inferred = inferMetric(formula.code(), checked, new HashSet<>());
            if (!formula.dimension().equals(inferred)) {
                throw new IllegalArgumentException("declared formula dimension does not match: " + formula.code());
            }
        }
    }

    private Dimension inferMetric(String code, Map<String, Dimension> memo, Set<String> visiting) {
        if (inputDefinitions.containsKey(code)) return inputDefinitions.get(code);
        if (memo.containsKey(code)) return memo.get(code);
        Formula f = formulas.get(code);
        if (f == null) throw new IllegalArgumentException("unknown metric: " + code);
        if (!visiting.add(code)) throw new IllegalArgumentException("calculation DAG contains cycle: " + code);
        Dimension value = inferExpr(f.expression(), memo, visiting);
        visiting.remove(code);
        if (!f.dimension().equals(value)) throw new IllegalArgumentException("dimension mismatch: " + code);
        memo.put(code, value);
        return value;
    }

    private Dimension inferExpr(Expr e, Map<String, Dimension> memo, Set<String> visiting) {
        if (e instanceof Ref x) return inferMetric(x.code(), memo, visiting);
        if (e instanceof Constant x) return Objects.requireNonNull(x.dimension());
        if (e instanceof Add x) return equal(inferExpr(x.left(), memo, visiting), inferExpr(x.right(), memo, visiting));
        if (e instanceof Subtract x) return equal(inferExpr(x.left(), memo, visiting), inferExpr(x.right(), memo, visiting));
        if (e instanceof Multiply x) return inferExpr(x.left(), memo, visiting).multiply(inferExpr(x.right(), memo, visiting));
        if (e instanceof Divide x) return inferExpr(x.numerator(), memo, visiting).divide(inferExpr(x.denominator(), memo, visiting));
        throw new IllegalArgumentException("unsupported expression");
    }

    private static Dimension equal(Dimension a, Dimension b) {
        if (!a.equals(b)) throw new IllegalArgumentException("cannot add/subtract incompatible units");
        return a;
    }

    public Result calculate(String metricCode, Map<String, EconomicKernel.EconomicValue> inputs) {
        return evaluateMetric(metricCode, inputs, new HashMap<>());
    }

    private Result evaluateMetric(String code, Map<String, EconomicKernel.EconomicValue> inputs, Map<String, Result> memo) {
        if (memo.containsKey(code)) return memo.get(code);
        if (inputDefinitions.containsKey(code)) {
            EconomicKernel.EconomicValue v = inputs.get(code);
            Result r = (v == null || !v.numeric())
                    ? Result.missing(inputDefinitions.get(code),
                            v != null && v.state() == EconomicKernel.ValueState.NOT_APPLICABLE
                                    ? "NOT_APPLICABLE" : "MISSING_INPUT")
                    : v.state() == EconomicKernel.ValueState.ESTIMATED
                        ? Result.estimated(v.decimal(), inputDefinitions.get(code))
                        : Result.calculated(v.decimal(), inputDefinitions.get(code));
            memo.put(code, r);
            return r;
        }
        Formula formula = formulas.get(code);
        if (formula == null) throw new IllegalArgumentException("unknown metric: " + code);
        Result r = evaluateExpr(formula.expression(), inputs, memo);
        memo.put(code, r);
        return r;
    }

    private Result evaluateExpr(Expr e, Map<String, EconomicKernel.EconomicValue> inputs, Map<String, Result> memo) {
        if (e instanceof Ref x) return evaluateMetric(x.code(), inputs, memo);
        if (e instanceof Constant x) return Result.calculated(x.value(), x.dimension());
        Expr left, right;
        if (e instanceof Add x) { left=x.left(); right=x.right(); }
        else if (e instanceof Subtract x) { left=x.left(); right=x.right(); }
        else if (e instanceof Multiply x) { left=x.left(); right=x.right(); }
        else if (e instanceof Divide x) { left=x.numerator(); right=x.denominator(); }
        else throw new IllegalArgumentException("unsupported expression");
        Result a=evaluateExpr(left, inputs, memo), b=evaluateExpr(right, inputs, memo);
        Dimension dim = e instanceof Multiply ? a.dimension().multiply(b.dimension())
                : e instanceof Divide ? a.dimension().divide(b.dimension()) : equal(a.dimension(), b.dimension());
        if (a.status() == Status.NOT_COMPUTABLE) {
            return Result.missing(dim, a.reason() == null ? "MISSING_DEPENDENCY" : a.reason());
        }
        if (b.status() == Status.NOT_COMPUTABLE) {
            return Result.missing(dim, b.reason() == null ? "MISSING_DEPENDENCY" : b.reason());
        }
        if (e instanceof Divide && b.value().signum()==0) return Result.missing(dim, "ZERO_DENOMINATOR");
        BigDecimal v;
        if (e instanceof Add) v=a.value().add(b.value(), mathContext);
        else if (e instanceof Subtract) v=a.value().subtract(b.value(), mathContext);
        else if (e instanceof Multiply) v=a.value().multiply(b.value(), mathContext);
        else v=a.value().divide(b.value(), mathContext);
        return (a.status() == Status.ESTIMATED || b.status() == Status.ESTIMATED)
                ? Result.estimated(v, dim) : Result.calculated(v, dim);
    }
}
