package kz.fin.backend.diagnostics;

import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.*;

import java.util.*;

/**
 * Minimal one-hop evidence plan from an observed metric to its registered
 * cross-form relationships. No form is recommended because it merely has a
 * similar name or an unverified statistical relationship.
 */
public final class CrossFormEvidencePlanner {
    private CrossFormEvidencePlanner() {}
    private static final Map<String,String> PILOT_ORIGIN_FORM=Map.ofEntries(
            Map.entry("ASSETS","F01"),Map.entry("LIABILITIES","F01"),
            Map.entry("EQUITY","F01"),Map.entry("CASH_END","F01"),
            Map.entry("AR_END","F01"),Map.entry("REV_NET","F02"),
            Map.entry("COGS","F02"),Map.entry("OPEX","F02"),
            Map.entry("CFO","F03"),Map.entry("CFI","F03"),Map.entry("CFF","F03"),
            Map.entry("CASH_IN","DF-CSH-01"),Map.entry("CASH_OUT","DF-CSH-02"));
    public enum Action { PROVIDE_FORM, COMPLETE_OR_CORRECT_FORM }
    public record Needed(String formCode, Action action,List<MetricKey> requiredSlots,
                         List<String> relatedRules) {
        public Needed {
            requiredSlots=List.copyOf(requiredSlots);
            relatedRules=List.copyOf(relatedRules);
        }
    }
    public record Plan(String status,List<Needed> nextForms,List<String> checkedRuleCodes) {
        public Plan {
            nextForms=List.copyOf(nextForms);
            checkedRuleCodes=List.copyOf(checkedRuleCodes);
        }
    }
    private static final class Mutable {
        final String formCode;
        final Set<MetricKey> slots=new HashSet<>();
        final Set<String> rules=new TreeSet<>();
        Mutable(String formCode){this.formCode=formCode;}
    }
    public static Plan plan(Report report){
        Objects.requireNonNull(report);
        Set<MetricKey> available=report.evidence().keySet();
        if(available.isEmpty())return new Plan("NO_VERIFIED_ANCHOR",List.of(),List.of());
        Map<String,Mutable> missingByForm=new TreeMap<>();
        List<String> checked=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(Rule rule:CrossFormDiagnosticEngine.RULES){
            boolean connected=rule.required().stream().anyMatch(available::contains);
            if(!connected)continue;
            checked.add(rule.code());
            seen.add(rule.code());
            for(MetricKey key:rule.required()){
                if(available.contains(key))continue;
                String form=PILOT_ORIGIN_FORM.get(key.code());
                if(form==null)throw new IllegalStateException("UNREGISTERED_CROSS_FORM_SOURCE");
                Mutable m=missingByForm.computeIfAbsent(form,Mutable::new);
                m.slots.add(key);
                m.rules.add(rule.code());
            }
        }
        List<Needed> next=new ArrayList<>();
        Set<String> presentForms=new HashSet<>();
        for(Evidence evidence:report.evidence().values())
            evidence.sources().forEach(source->presentForms.add(source.formCode()));
        for(Mutable m:missingByForm.values()){
            List<MetricKey> slots=m.slots.stream().sorted(
                    Comparator.comparing(MetricKey::code).thenComparing(k->k.role().name())).toList();
            next.add(new Needed(m.formCode,
                    presentForms.contains(m.formCode)?Action.COMPLETE_OR_CORRECT_FORM:Action.PROVIDE_FORM,
                    slots,List.copyOf(m.rules)));
        }
        // Number of verified linked methods unlocked, not a financial severity score.
        next.sort(Comparator.comparingInt((Needed n)->n.relatedRules().size()).reversed()
                .thenComparing(Needed::formCode));
        return new Plan("ONE_HOP_VERIFIED_RELATIONSHIPS",next,checked);
    }
}
