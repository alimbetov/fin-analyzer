package kz.fin.backend.compass;

import kz.fin.backend.diagnostics.BusinessDiagnosticEngine.RuleCode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Immutable, server-bundled design catalog for form-selection decisions.
 * All source TSV records are checked for referential integrity at load time.
 * This is not a dynamically editable or financially qualified FormRegistry.
 */
public final class CompassCatalog {
    public record Form(String code, String status) {}
    public record Scenario(String code, List<String> required, List<String> conditional,
                           String horizon, String outputs) {
        public Scenario {
            required = List.copyOf(required);
            conditional = List.copyOf(conditional);
        }
    }
    public record Sector(String section, Set<String> businessModels,
                         List<String> candidateForms) {
        public Sector {
            businessModels = Set.copyOf(businessModels);
            candidateForms = List.copyOf(candidateForms);
        }
    }
    public record TraitPolicy(String scenarioCode, String formCode, String trait,
                              String reasonKey, String unlocksCode) {}
    public record FindingPolicy(RuleCode ruleCode, String formCode, String sectorSection,
                                String reasonKey, String unlocksCode, int priority) {}

    private final Map<String, Form> forms;
    private final Map<String, Scenario> scenarios;
    private final Map<String, Sector> sectors;
    private final Map<String, String> modelToSection;
    private final Map<String, TraitPolicy> traitPolicies;
    private final Map<RuleCode, List<FindingPolicy>> evidencePolicies;
    private final Set<String> traits;

    private CompassCatalog(Map<String, Form> forms, Map<String, Scenario> scenarios,
                           Map<String, Sector> sectors, Map<String, String> models,
                           Map<String, TraitPolicy> traitPolicies,
                           Map<RuleCode, List<FindingPolicy>> evidencePolicies,
                           Set<String> traits) {
        this.forms = Map.copyOf(forms);
        this.scenarios = Map.copyOf(scenarios);
        this.sectors = Map.copyOf(sectors);
        this.modelToSection = Map.copyOf(models);
        this.traitPolicies = Map.copyOf(traitPolicies);
        Map<RuleCode, List<FindingPolicy>> frozen = new EnumMap<>(RuleCode.class);
        evidencePolicies.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        this.evidencePolicies = Collections.unmodifiableMap(frozen);
        this.traits = Set.copyOf(traits);
    }

    public Map<String, Form> forms() { return forms; }
    public Map<String, Scenario> scenarios() { return scenarios; }
    public Map<String, Sector> sectors() { return sectors; }
    public Set<String> traits() { return traits; }
    public Map<String, String> modelToSection() { return modelToSection; }
    public TraitPolicy traitPolicy(String scenario, String form) {
        return traitPolicies.get(scenario + "/" + form);
    }
    public List<FindingPolicy> evidencePolicies(RuleCode code) {
        return evidencePolicies.getOrDefault(code, List.of());
    }
    public int traitPolicyCount() { return traitPolicies.size(); }
    public int findingPolicyCount() {
        return evidencePolicies.values().stream().mapToInt(List::size).sum();
    }

    public static CompassCatalog bundled() {
        Map<String, Form> forms = new TreeMap<>();
        for(String[] r : tsv("form-compatibility.tsv",
                "formCode\tcatalog\tformKind\taggregateRoot\tgrain\tfieldContract\tfieldEvidence\tqualificationStatus",8)) {
            if(!Set.of("DESIGN_ONLY","PILOT_UNQUALIFIED").contains(r[7]))
                throw new IllegalStateException("UNKNOWN_FORM_QUALIFICATION: "+r[0]);
            unique(forms,r[0],new Form(r[0],r[7]));
        }
        Map<String, Scenario> scenarios = new TreeMap<>();
        for(String[] r : tsv("scenario-foundation-v2.tsv",
                "scenarioCode\taudience\tbusinessQuestion\trequiredFormCodes\tconditionalFormCodes\textensionSheetCodes\tanalyticalModelCodes\tperiodHorizon\texpectedOutputs\treadinessGate\tqualificationStatus",11)) {
            Set<String> req = splitSet(r[3]), cond = splitSet(r[4]);
            if(!Collections.disjoint(req,cond))
                throw new IllegalStateException("DUPLICATE_SCENARIO_FORM: "+r[0]);
            for(String form: req) knownForm(forms,form);
            for(String form: cond) knownForm(forms,form);
            if(!r[10].equals("DESIGN_ONLY"))
                throw new IllegalStateException("UNEXPECTED_SCENARIO_STATUS");
            unique(scenarios,r[0],new Scenario(r[0],List.copyOf(req),List.copyOf(cond),r[7],r[8]));
        }

        Map<String, Sector> sectors = new TreeMap<>();
        Map<String, String> modelToSection = new TreeMap<>();
        for(String[] r : tsv("sector-business-model-packs-v2.tsv",
                "section\tbusinessModelVariants\tsectorFormCodes\tbaseDataFormCodes\toperationalGrain\tanalysisFamilies\trequiredExtensionSheets\tmethodologyRisk\tqualificationStatus",9)) {
            if(!r[0].matches("[A-U]")) throw new IllegalStateException("INVALID_SECTOR");
            Set<String> models = splitSet(r[1]);
            Set<String> sectorForms = splitSet(r[2]);
            if(models.size()<2 || sectorForms.size()!=3)
                throw new IllegalStateException("INVALID_SECTOR_COVERAGE: "+r[0]);
            for(String form : sectorForms) {
                knownForm(forms,form);
                if(!form.matches("SF-"+r[0]+"0[1-3]"))
                    throw new IllegalStateException("CROSS_SECTOR_FORM: "+form);
            }
            for(String model : models) {
                if(!model.startsWith(r[0]+"_") || modelToSection.putIfAbsent(model,r[0])!=null)
                    throw new IllegalStateException("INVALID_BUSINESS_MODEL: "+model);
            }
            unique(sectors,r[0],new Sector(r[0],models,List.copyOf(sectorForms)));
        }

        Map<String,TraitPolicy> traits = new TreeMap<>();
        Set<String> traitNames = new TreeSet<>();
        for(String[] r : tsv("compass-trait-policy-v1.tsv",
                "scenarioCode\tformCode\ttraitGate\treasonKey\tunlocksCode\tqualificationStatus",6)) {
            Scenario scenario = requireScenario(scenarios,r[0]);
            knownForm(forms,r[1]);
            if(!scenario.conditional().contains(r[1]) || !r[2].matches("HAS_[A-Z0-9_]+")
                    || !r[5].equals("DESIGN_ONLY"))
                throw new IllegalStateException("INVALID_TRAIT_POLICY: "+r[0]+"/"+r[1]);
            traitNames.add(r[2]);
            unique(traits,r[0]+"/"+r[1],
                    new TraitPolicy(r[0],r[1],r[2],r[3],r[4]));
        }

        Map<RuleCode,List<FindingPolicy>> findingPolicies = new EnumMap<>(RuleCode.class);
        Set<String> seenLinks = new HashSet<>();
        for(String[] r : tsv("compass-evidence-policy-v1.tsv",
                "ruleCode\tformCode\tsectorSection\treasonKey\tunlocksCode\tpriority\tqualificationStatus",7)) {
            RuleCode rule=RuleCode.valueOf(r[0]);
            knownForm(forms,r[1]);
            if(!r[2].equals("ALL") && !sectors.containsKey(r[2]))
                throw new IllegalStateException("UNKNOWN_SECTOR_FILTER: "+r[2]);
            if(r[1].startsWith("SF-") && !r[2].equals(r[1].substring(3,4)))
                throw new IllegalStateException("INDUSTRY_EVIDENCE_WITHOUT_SECTOR: "+r[1]);
            int priority=Integer.parseInt(r[5]);
            if(priority<1 || priority>100 || !r[6].equals("DESIGN_ONLY")
                    || !seenLinks.add(rule+"/"+r[1]))
                throw new IllegalStateException("INVALID_EVIDENCE_POLICY: "+r[0]+"/"+r[1]);
            findingPolicies.computeIfAbsent(rule,ignored->new ArrayList<>()).add(
                    new FindingPolicy(rule,r[1],r[2],r[3],r[4],priority));
        }
        return new CompassCatalog(forms,scenarios,sectors,modelToSection,
                traits,findingPolicies,traitNames);
    }

    private static Scenario requireScenario(Map<String,Scenario> scenarios,String code) {
        Scenario s=scenarios.get(code);
        if(s==null)throw new IllegalStateException("UNKNOWN_SCENARIO: "+code);
        return s;
    }
    private static void knownForm(Map<String,Form> forms,String code) {
        if(!forms.containsKey(code))throw new IllegalStateException("UNKNOWN_FORM: "+code);
    }
    private static <T> void unique(Map<String,T> map,String code,T record) {
        if(map.putIfAbsent(code,record)!=null)
            throw new IllegalStateException("DUPLICATE_REGISTRY_CODE: "+code);
    }
    private static Set<String> splitSet(String value) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        for(String member : value.split(";",-1)) {
            if(member.isBlank() || !items.add(member))
                throw new IllegalStateException("INVALID_REGISTRY_MEMBERS: "+value);
        }
        return Collections.unmodifiableSet(items);
    }
    private static List<String[]> tsv(String name,String expectedHeader,int count) {
        try(var input=CompassCatalog.class.getResourceAsStream("/fin/"+name)) {
            if(input==null)throw new IllegalStateException("MISSING_COMPASS_REGISTRY: "+name);
            try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
                String header=reader.readLine();
                if(!expectedHeader.equals(header))
                    throw new IllegalStateException("REGISTRY_HEADER_DRIFT: "+name);
                List<String[]> rows=new ArrayList<>();
                String line;
                while((line=reader.readLine())!=null) {
                    if(line.isBlank())continue;
                    String[] fields=line.split("\\t",-1);
                    if(fields.length!=count || Arrays.stream(fields).anyMatch(String::isBlank))
                        throw new IllegalStateException("INVALID_REGISTRY_ROW: "+name);
                    rows.add(fields);
                }
                return List.copyOf(rows);
            }
        }catch(IOException e) {
            throw new IllegalStateException("REGISTRY_READ_FAILURE: "+name,e);
        }
    }
}
