package kz.fin.backend.analytics;

import java.util.*;

/**
 * Small pure readiness and freshness evaluator for a versioned calculation DAG.
 * Not an authorization layer; caller must supply tenant-scoped, approved evidence
 * and a trustworthy input-manifest fingerprint. Does not mutate saved snapshots.
 */
public final class IndicatorReadiness {
    private IndicatorReadiness() {}

    public enum InputState { APPROVED, MISSING, PENDING_APPROVAL, INVALID, NOT_APPLICABLE }
    public enum Readiness { READY, WAITING_FOR_DATA, WAITING_FOR_APPROVAL, INVALID_INPUT, NOT_APPLICABLE }
    public enum Freshness { CURRENT, STALE, UNKNOWN }

    public record SourceEvidence(String sourceForm, String period, InputState state) {
        public SourceEvidence {
            if (sourceForm == null || sourceForm.isBlank() || period == null || period.isBlank())
                throw new IllegalArgumentException("SOURCE_REFERENCE_REQUIRED");
            Objects.requireNonNull(state);
        }
    }
    public record DependencyIssue(String inputCode, String sourceForm, String period, InputState state) {}
    public record Diagnostic(String metricCode, Readiness readiness, Freshness freshness,
                             List<DependencyIssue> issues) {
        public Diagnostic { issues = List.copyOf(issues); }
    }

    /**
     * Nodes map derived metrics to required metric/input codes. Leaf input codes
     * must have evidence or are MISSING. An empty dependency list is never a valid
     * derived metric. Versioning belongs to the owning FormulaRegistry.
     */
    public static Diagnostic evaluate(
            String metricCode, Map<String,List<String>> graph,
            Map<String,SourceEvidence> leaves, String currentInputFingerprint,
            String lastCalculatedFingerprint) {
        Objects.requireNonNull(metricCode);
        Objects.requireNonNull(graph);
        Objects.requireNonNull(leaves);
        if (currentInputFingerprint == null || currentInputFingerprint.isBlank())
            throw new IllegalArgumentException("CURRENT_FINGERPRINT_REQUIRED");
        List<DependencyIssue> issues = new ArrayList<>();
        walk(metricCode,graph,leaves,new HashSet<>(),new HashSet<>(),issues);
        InputState worst = null;
        for (DependencyIssue issue : issues) {
            if (issue.state() == InputState.INVALID) { worst = InputState.INVALID; break; }
            if (issue.state() == InputState.MISSING) worst = InputState.MISSING;
            else if (issue.state() == InputState.PENDING_APPROVAL && worst != InputState.MISSING)
                worst = InputState.PENDING_APPROVAL;
            else if (issue.state() == InputState.NOT_APPLICABLE && worst == null)
                worst = InputState.NOT_APPLICABLE;
        }
        Readiness readiness = worst == null ? Readiness.READY
                : switch (worst) {
                    case INVALID -> Readiness.INVALID_INPUT;
                    case MISSING -> Readiness.WAITING_FOR_DATA;
                    case PENDING_APPROVAL -> Readiness.WAITING_FOR_APPROVAL;
                    case NOT_APPLICABLE -> Readiness.NOT_APPLICABLE;
                    case APPROVED -> Readiness.READY;
                };
        Freshness freshness = lastCalculatedFingerprint == null ? Freshness.UNKNOWN
                : lastCalculatedFingerprint.equals(currentInputFingerprint)
                    ? Freshness.CURRENT : Freshness.STALE;
        return new Diagnostic(metricCode,readiness,freshness,issues);
    }

    private static void walk(
            String code, Map<String,List<String>> graph, Map<String,SourceEvidence> leaves,
            Set<String> active, Set<String> visited, List<DependencyIssue> issues) {
        if (!active.add(code)) throw new IllegalArgumentException("DEPENDENCY_CYCLE: " + code);
        if (visited.add(code)) {
            List<String> inputs = graph.get(code);
            if (inputs != null) {
                if (inputs.isEmpty()) throw new IllegalArgumentException("EMPTY_FORMULA_DEPENDENCIES");
                for (String child : inputs) {
                    if (child == null || child.isBlank()) throw new IllegalArgumentException("INVALID_DEPENDENCY");
                    walk(child,graph,leaves,active,visited,issues);
                }
            } else {
                SourceEvidence evidence = leaves.get(code);
                if (evidence == null) {
                    issues.add(new DependencyIssue(code,null,null,InputState.MISSING));
                } else if (evidence.state() != InputState.APPROVED) {
                    issues.add(new DependencyIssue(code,evidence.sourceForm(),evidence.period(),evidence.state()));
                }
            }
        }
        active.remove(code);
    }
}
