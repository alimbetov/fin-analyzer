package kz.fin.backend.analytics;

import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract and referential-integrity tests for *DESIGN_ONLY* finance
 * requirements. These do not certify actual calculation or form ingestion.
 */
class ScenarioFoundationCatalogTest {
    private static List<String[]> rows(String filename, int columnCount, String expectedHeader)
            throws Exception {
        try (var input=Objects.requireNonNull(ScenarioFoundationCatalogTest.class
                        .getResourceAsStream("/fin/"+filename),filename);
             var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
            assertEquals(expectedHeader,reader.readLine(),"Header drift in "+filename);
            List<String[]> result=new ArrayList<>();
            String line;
            while ((line=reader.readLine())!=null) {
                if (line.isBlank()) continue;
                var fields=line.split("\\t",-1);
                assertEquals(columnCount,fields.length,filename+": "+line);
                for (String v:fields) assertFalse(v.isBlank(),filename+": "+line);
                result.add(fields);
            }
            return List.copyOf(result);
        }
    }

    private static Set<String> codes(List<String[]> rows,int index) {
        return rows.stream().map(x->x[index]).collect(Collectors.toSet());
    }

    private static Set<String> members(String separated) {
        return Arrays.stream(separated.split(";")).collect(Collectors.toSet());
    }

    private static final String FORM_HEADER =
            "formCode\tcatalog\tformKind\taggregateRoot\tgrain\tfieldContract\tfieldEvidence\tqualificationStatus";
    private static final String MODEL_HEADER =
            "modelCode\tpack\ttitle\tinputFormCodes\toutputMetricCodes\tqualificationStatus";
    private static final String EXT_HEADER =
            "sheetCode\tdomain\tparentFormCode\tsheetMode\tsourceGrain\tnaturalKey\tfieldContract\teconomicSemantics\tvalidationContract\tsourcePolicy\tqualificationStatus";
    private static final String SCENARIO_HEADER =
            "scenarioCode\taudience\tbusinessQuestion\trequiredFormCodes\tconditionalFormCodes\textensionSheetCodes\tanalyticalModelCodes\tperiodHorizon\texpectedOutputs\treadinessGate\tqualificationStatus";
    private static final String SECTOR_HEADER =
            "section\tbusinessModelVariants\tsectorFormCodes\tbaseDataFormCodes\toperationalGrain\tanalysisFamilies\trequiredExtensionSheets\tmethodologyRisk\tqualificationStatus";
    private static final String ACCEPTANCE_HEADER =
            "caseCode\tscenarioCode\tfixtureProfile\tcondition\texpectedStatus\texpectedInvariant\timplementationStatus";
    private static final String TITLE_HEADER =
            "code\ttitleRu\ttitleKk\ttitleEn\tqualificationStatus";

    @Test void topLevelCatalogRemains166AndModelsRemain42() throws Exception {
        var forms=rows("form-compatibility.tsv",8,FORM_HEADER);
        var models=rows("analytics-model-catalog.tsv",6,MODEL_HEADER);
        assertEquals(166,forms.size());
        assertEquals(166,codes(forms,0).size());
        assertEquals(42,models.size());
        assertEquals(42,codes(models,0).size());
        for (var f:forms) assertTrue(Set.of("DESIGN_ONLY","PILOT_UNQUALIFIED")
                .contains(f[7]),"Form cannot be counted as fully qualified: "+f[0]);
    }

    @Test void thirtySheetsReferenceRealFormsAndAreNotNewTopLevelForms() throws Exception {
        Set<String> forms=codes(rows("form-compatibility.tsv",8,FORM_HEADER),0);
        var sheets=rows("form-extension-sheets-v2.tsv",11,EXT_HEADER);
        assertEquals(30,sheets.size());
        assertEquals(30,codes(sheets,0).size());
        Map<String,Long> byDomain=sheets.stream().collect(Collectors.groupingBy(
                s->s[1],Collectors.counting()));
        assertEquals(Map.of("HISTORY",6L,"BUDGET",6L,"MANUFACTURING",6L,
                "CONSTRUCTION",6L,"INVESTMENT",6L),byDomain);
        for(var row:sheets) {
            assertTrue(row[0].matches("EX-(HIS|BUD|MFG|CST|INV)-0[1-6]"),row[0]);
            assertFalse(forms.contains(row[0]),"Extension code collides with a top-level form");
            assertTrue(forms.contains(row[2]),"Parent does not exist: "+row[2]);
            assertTrue(Set.of("STRUCTURED","COMPOSITE","DERIVED").contains(row[3]));
            assertTrue(Set.of("APPROVED_ONLY","SCENARIO_ONLY").contains(row[9]));
            assertEquals("DESIGN_ONLY",row[10]);
            for(String field:row[6].split(";"))
                assertTrue(field.matches("[A-Za-z][A-Za-z0-9]*:[A-Z]+"),
                        "Malformed field definition in "+row[0]+": "+field);
            Set<String> defined=Arrays.stream(row[6].split(";"))
                    .map(f->f.split(":")[0]).collect(Collectors.toSet());
            assertEquals(row[6].split(";").length,defined.size(),
                    "Duplicate field code in "+row[0]);
        }
    }

    @Test void tenScenariosHaveValidInputsModelsReadinessAndNoRequiredSectorSpam()
            throws Exception {
        Set<String> forms=codes(rows("form-compatibility.tsv",8,FORM_HEADER),0);
        Set<String> models=codes(rows("analytics-model-catalog.tsv",6,MODEL_HEADER),0);
        Set<String> sheets=codes(rows("form-extension-sheets-v2.tsv",11,EXT_HEADER),0);
        var scenarios=rows("scenario-foundation-v2.tsv",11,SCENARIO_HEADER);
        assertEquals(10,scenarios.size());
        assertEquals(10,codes(scenarios,0).size());
        for(var s:scenarios) {
            assertTrue(s[0].matches("SC-[A-Z0-9-]+"));
            Set<String> required=members(s[3]),conditional=members(s[4]);
            assertTrue(Collections.disjoint(required,conditional),
                    "Required and conditional source conflict "+s[0]);
            for(String form:required) assertTrue(forms.contains(form),"Unknown form "+form);
            for(String form:conditional) assertTrue(forms.contains(form),"Unknown form "+form);
            for(String sheet:members(s[5]))assertTrue(sheets.contains(sheet),
                    "Unknown extension in "+s[0]+": "+sheet);
            for(String model:members(s[6]))assertTrue(models.contains(model),
                    "Unknown model in "+s[0]+": "+model);
            assertEquals("DESIGN_ONLY",s[10]);
        }
        var routed=scenarios.stream().filter(s->s[0].equals("SC-SECTOR-ROUTED"))
                .findFirst().orElseThrow();
        assertEquals(0,members(routed[3]).stream().filter(x->x.startsWith("SF-")).count(),
                "All sector form families must not be mandatory for each enterprise");
        assertEquals(21,members(routed[4]).stream().filter(x->x.startsWith("SF-")).count());
    }

    @Test void twentyOneSectorsHaveRealFormsAndDistinctActivityVariants() throws Exception {
        Set<String> forms=codes(rows("form-compatibility.tsv",8,FORM_HEADER),0);
        Set<String> sheets=codes(rows("form-extension-sheets-v2.tsv",11,EXT_HEADER),0);
        var sectors=rows("sector-business-model-packs-v2.tsv",9,SECTOR_HEADER);
        assertEquals(21,sectors.size());
        assertEquals("ABCDEFGHIJKLMNOPQRSTU",
                sectors.stream().map(s->s[0]).collect(Collectors.joining()));
        Set<String> variants=new HashSet<>();
        for(var s:sectors) {
            var sectorForms=members(s[2]);
            assertEquals(3,sectorForms.size(),"Existing sector has exactly 3 base forms: "+s[0]);
            for(String f:sectorForms) {
                assertTrue(f.matches("SF-"+s[0]+"0[1-3]"),"Wrong industry "+s[0]+"/"+f);
                assertTrue(forms.contains(f),"Sector form not registered: "+f);
            }
            for(String f:members(s[3]))
                assertTrue(forms.contains(f),"Industry base input not registered "+f);
            for(String extension:members(s[6]))
                assertTrue(sheets.contains(extension),"Unknown industry extension "+extension);
            var variantsForSection=members(s[1]);
            assertTrue(variantsForSection.size()>=2,"Only one business model for "+s[0]);
            for(String variant:variantsForSection) {
                assertTrue(variant.startsWith(s[0]+"_"),variant);
                assertTrue(variants.add(variant),"Duplicate activity code "+variant);
            }
            assertEquals("DESIGN_ONLY",s[8]);
        }
        assertEquals(64,variants.size());
        assertTrue(variants.contains("C_PROCESS"));
        assertTrue(variants.contains("F_INFRASTRUCTURE"));
        assertTrue(variants.contains("K_INSURANCE"));
    }

    @Test void fiftyTwoGoldenRequirementsAreNotMisrepresentedAsPassingEngineTests()
            throws Exception {
        Set<String> scenarios=codes(rows("scenario-foundation-v2.tsv",11,SCENARIO_HEADER),0);
        var cases=rows("scenario-acceptance-contract-v2.tsv",7,ACCEPTANCE_HEADER);
        assertEquals(52,cases.size());
        assertEquals(52,codes(cases,0).size());
        var supported=Set.of("CALCULATED","RECONCILED","INCOMPLETE","STALE",
                "BLOCKED","NOT_COMPUTABLE","NOT_APPLICABLE");
        for(var c:cases) {
            assertTrue(scenarios.contains(c[1]),"Unknown scenario "+c[1]);
            assertTrue(supported.contains(c[4]),c[4]);
            assertEquals("SPEC_ONLY",c[6]);
        }
        Map<String,Long> count=cases.stream().collect(Collectors.groupingBy(
                c->c[1],Collectors.counting()));
        for(String s:scenarios) assertTrue(count.getOrDefault(s,0L)>=2,
                "Acceptance missing for "+s);
        for(String s:List.of("SC-HISTORY-3Y5Y","SC-BUDGET-FPA",
                "SC-MFG-ECONOMY","SC-CONSTRUCTION-ESTIMATE",
                "SC-INVESTMENT-APPRAISAL"))
            assertTrue(count.get(s)>=7,"Key business scenario lacks negative tests "+s);
    }

    @Test void fortyLocalizedTitlesCoverExactlyTheNewScenariosAndSheets()
            throws Exception {
        Set<String> expected=new HashSet<>(
                codes(rows("form-extension-sheets-v2.tsv",11,EXT_HEADER),0));
        expected.addAll(codes(rows("scenario-foundation-v2.tsv",11,SCENARIO_HEADER),0));
        var labels=rows("foundation-titles-ru-kk-en-v2.tsv",5,TITLE_HEADER);
        assertEquals(40,labels.size());
        assertEquals(expected,codes(labels,0));
        for(var l:labels) {
            for(int i=1;i<=3;i++)assertTrue(l[i].length()>=8,
                    "Missing locale label for "+l[0]);
            assertEquals("DESIGN_ONLY",l[4]);
        }
    }
}
