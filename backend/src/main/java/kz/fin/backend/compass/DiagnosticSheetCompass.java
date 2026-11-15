package kz.fin.backend.compass;

import kz.fin.backend.analytics.AnalysisContext;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Secondary compass for the 30 EXPERIMENTAL extension-sheet contracts.
 * It can identify missing source/detail dependencies but cannot interpret
 * persisted form values or assert that a financial method is qualified.
 */
public final class DiagnosticSheetCompass {
    public enum Depth { ESSENTIAL, DEEP }
    public enum State { APPROVED, PENDING_APPROVAL, INVALID, STALE, NOT_APPLICABLE }
    public enum PriorityClass { CORE, ENRICHMENT, DERIVED }
    public enum Action {
        PROVIDE_PARENT_FORM, FILL_DETAIL_SHEET, APPROVE_DRAFT,
        CORRECT_SHEET, REFRESH_SHEET, DERIVE_FROM_SOURCES
    }

    public record Request(String scenarioCode, Set<String> approvedBaseForms,
                          Map<String,State> sheetStates, Set<String> confirmedTraits,
                          Set<String> deniedTraits,Depth depth,AnalysisContext.DataSpace dataSpace) {
        public Request {
            Objects.requireNonNull(scenarioCode);
            approvedBaseForms=Set.copyOf(Objects.requireNonNull(approvedBaseForms));
            sheetStates=Map.copyOf(Objects.requireNonNull(sheetStates));
            confirmedTraits=Set.copyOf(Objects.requireNonNull(confirmedTraits));
            deniedTraits=Set.copyOf(Objects.requireNonNull(deniedTraits));
            Objects.requireNonNull(depth);
            Objects.requireNonNull(dataSpace);
            if(!Collections.disjoint(confirmedTraits,deniedTraits))
                throw new IllegalArgumentException("CONFLICTING_SHEET_TRAITS");
        }
    }
    public record Guidance(String sheetCode,String parentFormCode,PriorityClass priorityClass,
                           Action action,String reasonCode,String qualificationStatus) {}
    public record Question(String traitCode,String reasonCode) {}
    public record Result(String scenarioCode,List<Guidance> nextInputs,
                         List<Guidance> derivedCalculations,
                         List<String> reusedSheets,List<Question> questions,
                         String qualificationStatus) {
        public Result {
            nextInputs=List.copyOf(nextInputs);
            derivedCalculations=List.copyOf(derivedCalculations);
            reusedSheets=List.copyOf(reusedSheets);
            questions=List.copyOf(questions);
        }
    }
    private record Sheet(String code,String parent,String mode,String status) {}
    private record Policy(String sheetCode,String trait,PriorityClass priority,String status) {}
    private final CompassCatalog catalog;
    private final Map<String,Sheet> sheets;
    private final Map<String,Policy> policy;

    public DiagnosticSheetCompass(){
        this.catalog=CompassCatalog.bundled();
        this.sheets=loadSheets();
        this.policy=loadPolicies();
        if(sheets.size()!=30 || policy.size()!=30 || !policy.keySet().equals(sheets.keySet()))
            throw new IllegalStateException("SHEET_POLICY_COVERAGE_MISMATCH");
        for(var p:policy.values()){
            if(!catalog.forms().containsKey(sheets.get(p.sheetCode()).parent()))
                throw new IllegalStateException("UNREGISTERED_PARENT_FORM");
            if(!p.trait().equals("ALWAYS") && !catalog.traits().contains(p.trait()))
                throw new IllegalStateException("UNKNOWN_SHEET_TRAIT");
            if(!p.status().equals("DESIGN_ONLY"))
                throw new IllegalStateException("UNQUALIFIED_POLICY_STATUS");
            boolean derived=sheets.get(p.sheetCode()).mode().equals("DERIVED");
            if(derived!=(p.priority()==PriorityClass.DERIVED))
                throw new IllegalStateException("DERIVED_SHEET_MUST_NOT_REQUIRE_MANUAL_INPUT");
        }
    }

    public Result recommend(Request request){
        Objects.requireNonNull(request);
        if(request.dataSpace()!=AnalysisContext.DataSpace.SYNTHETIC)
            throw new IllegalArgumentException("SHEET_COMPASS_REQUIRES_SYNTHETIC");
        var scenario=catalog.scenarios().get(request.scenarioCode());
        if(scenario==null)throw new IllegalArgumentException("UNKNOWN_SHEET_SCENARIO");
        for(String form:request.approvedBaseForms())
            if(!catalog.forms().containsKey(form))
                throw new IllegalArgumentException("UNKNOWN_APPROVED_BASE_FORM");
        for(String sheet:request.sheetStates().keySet())
            if(!sheets.containsKey(sheet))throw new IllegalArgumentException("UNKNOWN_SHEET_CODE");
        for(String trait:request.confirmedTraits())
            if(!catalog.traits().contains(trait))throw new IllegalArgumentException("UNKNOWN_SHEET_TRAIT");
        for(String trait:request.deniedTraits())
            if(!catalog.traits().contains(trait))throw new IllegalArgumentException("UNKNOWN_SHEET_TRAIT");

        List<Guidance> next=new ArrayList<>(),derived=new ArrayList<>();
        List<String> reused=new ArrayList<>();
        SortedMap<String,Question> questions=new TreeMap<>();
        Set<String> selected=new LinkedHashSet<>(Arrays.asList(scenario.code().equals("SC-SECTOR-ROUTED")
                ? new String[0]:scenarioSheets(scenario.code()).toArray(String[]::new)));
        for(String code:selected){
            Sheet sheet=sheets.get(code);
            Policy rule=policy.get(code);
            if(rule==null)throw new IllegalStateException("MISSING_SHEET_POLICY");
            if(!rule.trait().equals("ALWAYS")){
                if(request.deniedTraits().contains(rule.trait()))continue;
                if(!request.confirmedTraits().contains(rule.trait())){
                    if(request.depth()==Depth.DEEP)
                        questions.putIfAbsent(rule.trait(),new Question(rule.trait(),
                                "compass.sheet.ask."+rule.trait()));
                    continue;
                }
            }else if(rule.priority()==PriorityClass.ENRICHMENT && request.depth()==Depth.ESSENTIAL){
                continue;
            }
            boolean parentApproved=request.approvedBaseForms().contains(sheet.parent());
            State state=request.sheetStates().get(code);
            if(state==State.APPROVED){
                if(!parentApproved)throw new IllegalArgumentException(
                        "APPROVED_SHEET_WITHOUT_APPROVED_PARENT");
                reused.add(code);continue;
            }
            if(rule.priority()==PriorityClass.DERIVED){
                derived.add(new Guidance(code,sheet.parent(),rule.priority(),
                        Action.DERIVE_FROM_SOURCES,"DERIVED_NOT_A_USER_FORM","DESIGN_ONLY"));
                continue;
            }
            Action action= !parentApproved?Action.PROVIDE_PARENT_FORM:
                    state==null?Action.FILL_DETAIL_SHEET:
                    switch(state){
                        case APPROVED -> throw new IllegalStateException("ALREADY_REUSED");
                        case PENDING_APPROVAL -> Action.APPROVE_DRAFT;
                        case INVALID, NOT_APPLICABLE -> Action.CORRECT_SHEET;
                        case STALE -> Action.REFRESH_SHEET;
                    };
            next.add(new Guidance(code,sheet.parent(),rule.priority(),action,
                    "SHEET_DATA_FOR_SCENARIO","DESIGN_ONLY"));
        }
        next.sort(Comparator.comparing((Guidance g)->g.priorityClass()==PriorityClass.CORE?0:1)
                .thenComparing(Guidance::sheetCode));
        derived.sort(Comparator.comparing(Guidance::sheetCode));
        Collections.sort(reused);
        return new Result(scenario.code(),next,derived,reused,List.copyOf(questions.values()),
                "DESIGN_ONLY");
    }

    private List<String> scenarioSheets(String code){
        // Scenario ownership comes from the existing versioned user journey
        // registry, never from the diagnostic engine's subjective estimates.
        String line=SCENARIO_SHEET_CODES.get(code);
        if(line==null)throw new IllegalStateException("SCENARIO_SHEET_ROUTES_NOT_REGISTERED");
        return List.of(line.split(";"));
    }

    private static final Map<String,String> SCENARIO_SHEET_CODES=loadScenarioSheets();
    private static Map<String,String> loadScenarioSheets(){
        Map<String,String> mapping=new TreeMap<>();
        for(String[] row:tsv("scenario-foundation-v2.tsv",11))
            if(mapping.putIfAbsent(row[0],row[5])!=null)
                throw new IllegalStateException("DUPLICATE_SCENARIO_SHEET_LINK");
        return Map.copyOf(mapping);
    }
    private static Map<String,Sheet> loadSheets(){
        Map<String,Sheet> result=new TreeMap<>();
        for(String[] row:tsv("form-extension-sheets-v2.tsv",11)){
            Sheet sheet=new Sheet(row[0],row[2],row[3],row[10]);
            if(result.putIfAbsent(row[0],sheet)!=null)
                throw new IllegalStateException("DUPLICATE_SHEET_DEFINITION");
        }
        return Map.copyOf(result);
    }
    private static Map<String,Policy> loadPolicies(){
        Map<String,Policy> result=new TreeMap<>();
        for(String[] row:tsv("diagnostic-sheet-policy-v1.tsv",4)){
            Policy p=new Policy(row[0],row[1],PriorityClass.valueOf(row[2]),row[3]);
            if(result.putIfAbsent(row[0],p)!=null)
                throw new IllegalStateException("DUPLICATE_SHEET_APPLICABILITY");
        }
        return Map.copyOf(result);
    }
    private static List<String[]> tsv(String file,int expectedFields){
        try(var input=DiagnosticSheetCompass.class.getResourceAsStream("/fin/"+file)){
            if(input==null)throw new IllegalStateException("MISSING_SHEET_CATALOG: "+file);
            try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){
                String header=reader.readLine();
                if(header==null || header.split("\\t").length!=expectedFields)
                    throw new IllegalStateException("SHEET_HEADER_DRIFT: "+file);
                List<String[]> rows=new ArrayList<>();String line;
                while((line=reader.readLine())!=null){
                    if(line.isBlank())continue;
                    String[] fields=line.split("\\t",-1);
                    if(fields.length!=expectedFields || Arrays.stream(fields).anyMatch(String::isBlank))
                        throw new IllegalStateException("INVALID_SHEET_REGISTRY_ROW: "+file);
                    rows.add(fields);
                }
                return List.copyOf(rows);
            }
        }catch(IOException ex){throw new IllegalStateException("SHEET_CATALOG_IO_ERROR",ex);}
    }
}
