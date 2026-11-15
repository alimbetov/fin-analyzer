package kz.fin.backend.diagnostics;

import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.MetricKey;
import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.Rule;
import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.TimeRole;

import java.util.*;

/**
 * R4.6 safe, bounded graph traversal from a business problem or source form.
 * Plans missing methodological evidence; never calculates invented financial data.
 * Edges are sourced exclusively from the versioned relationship registry.
 */
public final class DynamicDiagnosticPlanner {
    private DynamicDiagnosticPlanner(){}
    private static final IndicatorRelationshipRegistry REGISTRY =
            CrossFormDiagnosticEngine.RELATIONSHIPS;

    public enum Status { RELATED_METHODS_FOUND, NO_REVIEWED_RELATIONSHIP, NO_EVIDENCE_ANCHOR }
    public enum IntakeAction { PROVIDE_FORM, COMPLETE_OR_CORRECT_FORM }
    public record Demand(MetricKey metric,String originForm,IntakeAction action,
                         List<String> unlocksRules) {
        public Demand { unlocksRules=List.copyOf(unlocksRules); }
    }
    public record GraphPlan(Status status,String registryVersion,String registryHash,
                            List<String> relatedRuleCodes,List<Demand> missingEvidence,
                            List<String> formsWithoutQualifiedCalculations,int depth){
        public GraphPlan {
            relatedRuleCodes=List.copyOf(relatedRuleCodes);
            missingEvidence=List.copyOf(missingEvidence);
            formsWithoutQualifiedCalculations=List.copyOf(formsWithoutQualifiedCalculations);
        }
    }
    public static final Map<String,List<MetricKey>> PROBLEMS=Map.of(
        "CASH_SHORTFALL",List.of(new MetricKey("CASH_END",TimeRole.CURRENT_STOCK),
                                 new MetricKey("CFO",TimeRole.CURRENT_FLOW)),
        "PROFITABILITY",List.of(new MetricKey("REV_NET",TimeRole.CURRENT_FLOW),
                                new MetricKey("COGS",TimeRole.CURRENT_FLOW)),
        "BALANCE_RECONCILIATION",List.of(new MetricKey("ASSETS",TimeRole.CURRENT_STOCK),
                                        new MetricKey("EQUITY",TimeRole.CURRENT_STOCK)),
        "RECEIVABLES",List.of(new MetricKey("AR_END",TimeRole.CURRENT_STOCK),
                              new MetricKey("REV_NET",TimeRole.CURRENT_FLOW))
    );
    public static GraphPlan fromProblem(String code,Set<MetricKey> verifiedAvailable,int depth) {
        List<MetricKey> anchor=PROBLEMS.get(code);
        if(anchor==null)return empty(Status.NO_REVIEWED_RELATIONSHIP,depth);
        return traverse(Set.copyOf(anchor),verifiedAvailable,depth);
    }
    public static GraphPlan fromForm(String formCode,Set<MetricKey> verifiedAvailable,int depth) {
        IndicatorRelationshipRegistry.Form form=REGISTRY.forms().get(formCode);
        if(form==null)throw new IllegalArgumentException("UNKNOWN_RELATION_FORM");
        if(form.disposition()!=IndicatorRelationshipRegistry.FormDisposition.PILOT_NUMERIC_SOURCE)
            return empty(Status.NO_REVIEWED_RELATIONSHIP,depth);
        Set<MetricKey> anchor=new HashSet<>();
        REGISTRY.relationships().forEach(r->r.inputs().stream()
                .filter(b->b.formCode().equals(formCode))
                .forEach(b->anchor.add(b.key())));
        return traverse(anchor,verifiedAvailable,depth);
    }
    public static GraphPlan fromVerifiedFacts(Set<MetricKey> verifiedAvailable,int depth){
        Objects.requireNonNull(verifiedAvailable);
        if(verifiedAvailable.isEmpty())return empty(Status.NO_EVIDENCE_ANCHOR,depth);
        return traverse(verifiedAvailable,verifiedAvailable,depth);
    }
    private static GraphPlan empty(Status status,int depth) {
        checkDepth(depth);
        return new GraphPlan(status,REGISTRY.VERSION,REGISTRY.definitionHash(),
                List.of(),List.of(),List.of(),depth);
    }
    private static void checkDepth(int depth){
        if(depth<1||depth>3)throw new IllegalArgumentException("RELATION_GRAPH_DEPTH_OUT_OF_RANGE");
    }
    private static GraphPlan traverse(Set<MetricKey> anchor,Set<MetricKey> verified,int depth){
        checkDepth(depth);
        Objects.requireNonNull(anchor); Objects.requireNonNull(verified);
        Set<MetricKey> available=Set.copyOf(verified);
        if(anchor.isEmpty())return empty(Status.NO_REVIEWED_RELATIONSHIP,depth);
        // Treat "form chosen" as the discovery anchor, NEVER as proof that its
        // cells are present or approved. Only verified inputs count as available.
        Set<MetricKey> frontier=new HashSet<>(anchor);
        Set<MetricKey> discovered=new HashSet<>(anchor);
        Map<String,Rule> reachableRules=new LinkedHashMap<>();
        for(int hop=0;hop<depth && !frontier.isEmpty();hop++){
            Set<MetricKey> newFrontier=new HashSet<>();
            for(Rule rule:CrossFormDiagnosticEngine.RULES){
                if(reachableRules.containsKey(rule.code()))continue;
                if(rule.required().stream().noneMatch(frontier::contains))continue;
                reachableRules.put(rule.code(),rule);
                for(MetricKey input:rule.required())
                    if(discovered.add(input))newFrontier.add(input);
            }
            frontier=newFrontier;
        }
        if(reachableRules.isEmpty())return empty(Status.NO_REVIEWED_RELATIONSHIP,depth);
        Map<MetricKey,SortedSet<String>> needed=new HashMap<>();
        for(Rule r:reachableRules.values()){
            for(MetricKey key:r.required())
                if(!available.contains(key))
                    needed.computeIfAbsent(key,k->new TreeSet<>()).add(r.code());
        }
        Set<String> availableForms=new HashSet<>();
        for(MetricKey key:available){
            String form=REGISTRY.originByMetric().get(key.code());
            if(form!=null)availableForms.add(form);
        }
        List<Demand> demands=new ArrayList<>();
        for(var item:needed.entrySet()){
            String origin=REGISTRY.originByMetric().get(item.getKey().code());
            if(origin==null)throw new IllegalStateException("UNKNOWN_REQUIRED_METRIC_SOURCE");
            demands.add(new Demand(item.getKey(),origin,
                    availableForms.contains(origin)?IntakeAction.COMPLETE_OR_CORRECT_FORM:
                        IntakeAction.PROVIDE_FORM,List.copyOf(item.getValue())));
        }
        demands.sort(Comparator.comparingInt((Demand d)->d.unlocksRules().size()).reversed()
                .thenComparing(Demand::originForm).thenComparing(d->d.metric().code())
                .thenComparing(d->d.metric().role().name()));
        return new GraphPlan(Status.RELATED_METHODS_FOUND,REGISTRY.VERSION,REGISTRY.definitionHash(),
                List.copyOf(reachableRules.keySet()),demands,List.of(),depth);
    }
}
