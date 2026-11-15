package kz.fin.backend.compass;

import kz.fin.backend.diagnostics.BusinessDiagnosticEngine.RuleCode;

import java.time.YearMonth;
import java.util.*;

/**
 * FIN Diagnostic Compass R1: selects the minimum evidence needed to answer a
 * specific financial question. Recommendations do not assert causal findings,
 * authorize a source, calculate KPI values or expose an HTTP endpoint.
 *
 * Request input MUST eventually be assembled by a Keycloak/ABAC-authorized
 * application service from a trusted tenant-scoped SourceRevision registry;
 * user-submitted evidence states and rule codes are not a trust boundary.
 */
public final class DiagnosticCompass {
    public enum Depth { ESSENTIAL, DEEP }
    public enum EvidenceState { APPROVED, PENDING_APPROVAL, INVALID, STALE, NOT_APPLICABLE }
    public enum Tier { CORE, DIAGNOSTIC_EVIDENCE, CONDITIONAL, EVIDENCE_UPGRADE }
    public enum Action { PROVIDE_FORM, APPROVE_EXISTING, CORRECT_SOURCE, REFRESH_SOURCE, EXTEND_HISTORY }
    public enum Readiness {
        NEEDS_INPUTS, NEEDS_APPROVAL, INVALID_INPUT, NEEDS_COMPARABLE_HISTORY,
        NEEDS_BUSINESS_MODEL, CORE_DATA_AVAILABLE_NOT_QUALIFIED
    }

    public record FormEvidence(String formCode, EvidenceState state, String sourceRevision,
                               Set<Integer> fiscalYears, Set<YearMonth> months) {
        public FormEvidence {
            Objects.requireNonNull(formCode);
            Objects.requireNonNull(state);
            fiscalYears = Set.copyOf(Objects.requireNonNull(fiscalYears));
            months = Set.copyOf(Objects.requireNonNull(months));
            if(state == EvidenceState.APPROVED &&
                    (sourceRevision == null || sourceRevision.isBlank()))
                throw new IllegalArgumentException("APPROVED_SOURCE_REVISION_REQUIRED");
            for(Integer year : fiscalYears)
                if(year==null || year<1900 || year>2200)
                    throw new IllegalArgumentException("INVALID_FISCAL_YEAR");
        }
        public static FormEvidence approved(String code,String revision,
                                            Set<Integer> years,Set<YearMonth> months) {
            return new FormEvidence(code,EvidenceState.APPROVED,revision,years,months);
        }
    }

    public record Request(String scenarioCode, Set<String> businessModels,
                          Set<String> confirmedTraits, Set<String> deniedTraits,
                          Map<String, FormEvidence> existingForms, Set<RuleCode> findings,
                          Depth depth) {
        public Request {
            Objects.requireNonNull(scenarioCode);
            businessModels = Set.copyOf(Objects.requireNonNull(businessModels));
            confirmedTraits = Set.copyOf(Objects.requireNonNull(confirmedTraits));
            deniedTraits = Set.copyOf(Objects.requireNonNull(deniedTraits));
            existingForms = Map.copyOf(Objects.requireNonNull(existingForms));
            findings = Set.copyOf(Objects.requireNonNull(findings));
            Objects.requireNonNull(depth);
            if(!Collections.disjoint(confirmedTraits,deniedTraits))
                throw new IllegalArgumentException("CONFLICTING_PROFILE_TRAITS");
            for(var entry:existingForms.entrySet())
                if(!entry.getKey().equals(entry.getValue().formCode()))
                    throw new IllegalArgumentException("EVIDENCE_KEY_MISMATCH");
        }
    }

    public record Recommendation(String formCode, Tier tier, Action action, int priority,
                                 String catalogStatus, EvidenceState existingState,
                                 List<String> reasonKeys,List<String> unlocksCodes) {
        public Recommendation {
            reasonKeys=List.copyOf(reasonKeys);
            unlocksCodes=List.copyOf(unlocksCodes);
        }
    }
    public record ContextQuestion(String traitCode, String relatedFormCode,
                                  String promptKey) {}
    public record Result(String scenarioCode,Readiness dataReadiness,
                         String methodQualification, List<Recommendation> nextForms,
                         List<ContextQuestion> questions, List<String> reusedFormCodes,
                         List<String> blockingReasons) {
        public Result {
            nextForms=List.copyOf(nextForms);
            questions=List.copyOf(questions);
            reusedFormCodes=List.copyOf(reusedFormCodes);
            blockingReasons=List.copyOf(blockingReasons);
        }
    }

    private final CompassCatalog catalog;
    public DiagnosticCompass(CompassCatalog catalog) {
        this.catalog=Objects.requireNonNull(catalog);
    }
    public static DiagnosticCompass bundled() {
        return new DiagnosticCompass(CompassCatalog.bundled());
    }

    public Result recommend(Request request) {
        Objects.requireNonNull(request);
        CompassCatalog.Scenario scenario=catalog.scenarios().get(request.scenarioCode());
        if(scenario==null)throw new IllegalArgumentException("UNKNOWN_COMPASS_SCENARIO");
        for(String model:request.businessModels())
            if(!catalog.modelToSection().containsKey(model))
                throw new IllegalArgumentException("UNKNOWN_COMPASS_BUSINESS_MODEL: "+model);
        for(String trait:request.confirmedTraits())
            if(!catalog.traits().contains(trait))
                throw new IllegalArgumentException("UNKNOWN_COMPASS_TRAIT: "+trait);
        for(String trait:request.deniedTraits())
            if(!catalog.traits().contains(trait))
                throw new IllegalArgumentException("UNKNOWN_COMPASS_TRAIT: "+trait);
        for(String form:request.existingForms().keySet())
            if(!catalog.forms().containsKey(form))
                throw new IllegalArgumentException("UNKNOWN_COMPASS_FORM: "+form);

        Set<String> sections=new TreeSet<>();
        request.businessModels().forEach(model ->
                sections.add(catalog.modelToSection().get(model)));

        Map<String, MutableRecommendation> proposed=new TreeMap<>();
        SortedSet<String> reused=new TreeSet<>();
        SortedSet<String> blockers=new TreeSet<>();
        List<ContextQuestion> questions=new ArrayList<>();

        boolean missing=false,pending=false,invalid=false;
        for(String code:scenario.required()) {
            FormEvidence observed=request.existingForms().get(code);
            if(isApproved(observed)) {
                reused.add(code);
                continue;
            }
            if(observed==null)missing=true;
            else switch(observed.state()) {
                case PENDING_APPROVAL -> pending=true;
                case INVALID, NOT_APPLICABLE -> invalid=true;
                case STALE -> missing=true;
                case APPROVED -> throw new IllegalStateException("APPROVED_STATE_ALREADY_HANDLED");
            }
            propose(proposed,request,code,Tier.CORE,300,
                    "compass.coreRequired",scenario.code(),false);
        }

        boolean historyGap=false;
        if(scenario.code().equals("SC-HISTORY-3Y5Y")) {
            var history=historicalReadiness(request,proposed,scenario);
            historyGap=history;
            if(historyGap) {
                blockers.add("HISTORY_PERIODS_NOT_COMPARABLE");
                reused.removeAll(List.of("F01","F02","F03"));
            }
        }

        if(scenario.code().equals("SC-SECTOR-ROUTED")) {
            if(sections.isEmpty()) {
                blockers.add("BUSINESS_MODEL_UNSPECIFIED");
                questions.add(new ContextQuestion("BUSINESS_MODEL","DF-CTX-01",
                        "compass.chooseBusinessModel"));
            }else{
                for(String section:sections) {
                    var sector=catalog.sectors().get(section);
                    if(sector==null)throw new IllegalStateException("MISSING_SECTOR_CATALOG");
                    List<String> forms=sector.candidateForms();
                    propose(proposed,request,forms.getFirst(),Tier.CONDITIONAL,150,
                            "compass.sectorCoreEvidence",section+"OperationalKpis",false);
                    if(request.depth()==Depth.DEEP)
                        for(String extra:forms.subList(1,forms.size()))
                            propose(proposed,request,extra,Tier.EVIDENCE_UPGRADE,50,
                                    "compass.sectorDeepEvidence",section+"OperationalKpis",false);
                }
            }
        }

        for(String code:scenario.conditional()) {
            if(scenario.code().equals("SC-SECTOR-ROUTED"))continue;
            var policy=catalog.traitPolicy(scenario.code(),code);
            if(policy==null) {
                if(request.depth()==Depth.DEEP)
                    propose(proposed,request,code,Tier.EVIDENCE_UPGRADE,40,
                            "compass.optionalEvidence",scenario.code(),false);
                continue;
            }
            if(request.confirmedTraits().contains(policy.trait()))
                propose(proposed,request,code,Tier.CONDITIONAL,130,
                        policy.reasonKey(),policy.unlocksCode(),false);
            else if(!request.deniedTraits().contains(policy.trait())
                    && request.depth()==Depth.DEEP)
                questions.add(new ContextQuestion(policy.trait(),code,
                        "compass.ask."+policy.trait()));
        }

        // Only already-confirmed rule codes from a trusted persisted diagnosis
        // may be supplied by the future application layer; this engine does
        // not infer causal truth from the presence of a rule.
        for(RuleCode rule:RuleCode.values()) {
            if(!request.findings().contains(rule))continue;
            for(var policy:catalog.evidencePolicies(rule)) {
                if(!policy.sectorSection().equals("ALL")
                        && !sections.contains(policy.sectorSection()))continue;
                propose(proposed,request,policy.formCode(),Tier.DIAGNOSTIC_EVIDENCE,
                        200+policy.priority(),policy.reasonKey(),policy.unlocksCode(),false);
            }
        }

        List<Recommendation> recommendations=proposed.values().stream()
                .map(x->x.toImmutable(catalog.forms().get(x.form).status()))
                .sorted(Comparator.comparingInt(Recommendation::priority).reversed()
                        .thenComparing(Recommendation::formCode))
                .toList();

        // The same business trait can govern several forms (for example
        // P04 and C05 both depend on debt). Ask one question, not two.
        Map<String,ContextQuestion> uniqueQuestions=new TreeMap<>();
        questions.stream()
                .sorted(Comparator.comparing(ContextQuestion::relatedFormCode))
                .forEach(q->uniqueQuestions.putIfAbsent(q.traitCode(),q));
        questions=List.copyOf(uniqueQuestions.values());
        Readiness readiness=invalid?Readiness.INVALID_INPUT:
                missing?Readiness.NEEDS_INPUTS:
                pending?Readiness.NEEDS_APPROVAL:
                historyGap?Readiness.NEEDS_COMPARABLE_HISTORY:
                scenario.code().equals("SC-SECTOR-ROUTED") && sections.isEmpty()
                        ? Readiness.NEEDS_BUSINESS_MODEL:
                          Readiness.CORE_DATA_AVAILABLE_NOT_QUALIFIED;
        if(invalid)blockers.add("INVALID_OR_INAPPLICABLE_CORE_FORM");
        if(missing)blockers.add("CORE_FORMS_MISSING_OR_STALE");
        if(pending)blockers.add("CORE_FORMS_WAITING_FOR_APPROVAL");
        return new Result(scenario.code(),readiness,"DESIGN_ONLY",
                recommendations,questions,List.copyOf(reused),List.copyOf(blockers));
    }

    private boolean historicalReadiness(Request req,Map<String,MutableRecommendation> proposed,
                                        CompassCatalog.Scenario scenario) {
        List<String> anchors=List.of("F01","F02","F03");
        if(anchors.stream().anyMatch(c->!isApproved(req.existingForms().get(c))))return false;
        Set<Integer> yearIntersection=null;
        Set<YearMonth> monthIntersection=null;
        for(String code:anchors) {
            FormEvidence evidence=req.existingForms().get(code);
            if(yearIntersection==null)yearIntersection=new HashSet<>(evidence.fiscalYears());
            else yearIntersection.retainAll(evidence.fiscalYears());
            if(monthIntersection==null)monthIntersection=new HashSet<>(evidence.months());
            else monthIntersection.retainAll(evidence.months());
        }
        boolean threeAnnual=longestConsecutiveYears(yearIntersection)>=3;
        boolean thirtySixMonths=longestConsecutiveMonths(monthIntersection)>=36;
        if(threeAnnual || thirtySixMonths)return false;
        // A finished upload can still be insufficient for the requested
        // analysis, and must never be silently counted as 3–5 year evidence.
        for(String code:anchors)
            propose(proposed,req,code,Tier.CORE,310,
                    "compass.alignHistoricalPeriods","comparableHistory",true);
        return true;
    }

    private static int longestConsecutiveYears(Set<Integer> years) {
        List<Integer> sorted=years.stream().sorted().toList();
        int longest=0,run=0,previous=Integer.MIN_VALUE;
        for(int year:sorted){
            run=year==previous+1?run+1:1;
            longest=Math.max(longest,run);
            previous=year;
        }
        return longest;
    }
    private static int longestConsecutiveMonths(Set<YearMonth> months) {
        List<YearMonth> sorted=months.stream().sorted().toList();
        int longest=0,run=0;
        YearMonth previous=null;
        for(YearMonth month:sorted){
            run=previous!=null && previous.plusMonths(1).equals(month)?run+1:1;
            longest=Math.max(longest,run);
            previous=month;
        }
        return longest;
    }
    private static boolean isApproved(FormEvidence evidence) {
        return evidence!=null && evidence.state()==EvidenceState.APPROVED;
    }

    private static void propose(Map<String,MutableRecommendation> result, Request request,
                                String form,Tier tier,int rank,String why,String unlock,
                                boolean extendHistory) {
        FormEvidence evidence=request.existingForms().get(form);
        if(isApproved(evidence) && !extendHistory)return;
        Action action=extendHistory?Action.EXTEND_HISTORY:
                evidence==null?Action.PROVIDE_FORM:
                switch(evidence.state()) {
                    case APPROVED -> Action.EXTEND_HISTORY;
                    case PENDING_APPROVAL -> Action.APPROVE_EXISTING;
                    case INVALID, NOT_APPLICABLE -> Action.CORRECT_SOURCE;
                    case STALE -> Action.REFRESH_SOURCE;
                };
        MutableRecommendation current=result.computeIfAbsent(form,
                ignored->new MutableRecommendation(form,tier,action,rank,
                        evidence==null?null:evidence.state()));
        if(rank>current.rank){
            current.tier=tier;current.action=action;current.rank=rank;
        }
        current.reasons.add(why);
        current.unlocks.add(unlock);
    }

    private static final class MutableRecommendation {
        private final String form;
        private Tier tier;
        private Action action;
        private int rank;
        private final EvidenceState existing;
        private final SortedSet<String> reasons=new TreeSet<>();
        private final SortedSet<String> unlocks=new TreeSet<>();
        private MutableRecommendation(String form,Tier tier,Action action,int rank,
                                      EvidenceState existing){
            this.form=form;this.tier=tier;this.action=action;
            this.rank=rank;this.existing=existing;
        }
        private Recommendation toImmutable(String qualification) {
            return new Recommendation(form,tier,action,rank,qualification,
                    existing,List.copyOf(reasons),List.copyOf(unlocks));
        }
    }
}
