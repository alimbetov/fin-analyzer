package kz.fin.backend.diagnostics;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.diagnostics.ScenarioEconomicsEngine.*;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * All 30 extension sheets receive executable schema/scope rejection tests.
 * This is NOT formula qualification for sheets without a scenario method.
 */
class ExtensionSheetContractSmokeTest {
    private static final Scope SCOPE=new Scope(UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),UUID.randomUUID(),AnalysisContext.DataSpace.SYNTHETIC,
            "MANAGEMENT","CY-V1","KZT");
    private static final YearMonth PERIOD=YearMonth.of(2026,10);

    private static List<String[]> tsv(String path,String firstColumn,int width){
        var source=ExtensionSheetContractSmokeTest.class.getResourceAsStream(path);
        assertNotNull(source,"Resource missing: "+path);
        try(var reader=new BufferedReader(new InputStreamReader(source,StandardCharsets.UTF_8))){
            String header=reader.readLine();
            assertNotNull(header);
            assertEquals(firstColumn,header.split("\\t",-1)[0]);
            List<String[]> rows=new ArrayList<>();
            String line;
            while((line=reader.readLine())!=null){
                if(line.isBlank())continue;
                String[] parts=line.split("\\t",-1);
                assertEquals(width,parts.length,line);
                rows.add(parts);
            }
            return rows;
        }catch(Exception e){throw new AssertionError("Failed to read "+path,e);}
    }
    private static InputRow row(String form,String sheet,Map<String,BigDecimal> fields){
        return new InputRow(form,sheet,"item","row",PERIOD,null,"KZT",
                sheet.equals("EX-CST-06")?"calc:synthetic-only":"synthetic-source-1",
                EvidenceState.APPROVED,fields);
    }
    private static Report assess(InputRow r){
        return ScenarioEconomicsEngine.evaluate(new Input(SCOPE,"SC-SECTOR-ROUTED",List.of(r)));
    }
    @Test void everyExtensionIsRegisteredAndRejectsWrongParentAndUnknownFields(){
        List<String[]> sources=tsv("/fin/form-extension-sheets-v2.tsv","sheetCode",11);
        List<String[]> forms=tsv("/fin/form-compatibility.tsv","formCode",8);
        assertEquals(30,sources.size());
        assertEquals(166,forms.size());
        Set<String> knownForms=forms.stream().map(row->row[0]).collect(Collectors.toSet());
        Set<String> knownSheets=new HashSet<>();
        for(String[] entry:sources){
            String sheet=entry[0],parent=entry[2],mode=entry[3],qualification=entry[10];
            assertTrue(knownSheets.add(sheet),"duplicate sheet "+sheet);
            assertTrue(knownForms.contains(parent),sheet+" parent absent");
            assertEquals("DESIGN_ONLY",qualification,sheet);
            assertTrue(Set.of("STRUCTURED","DERIVED","COMPOSITE").contains(mode),
                    "unsupported sheet mode "+sheet);
            Report accepted=assertDoesNotThrow(()->assess(row(parent,sheet,Map.of())),sheet);
            assertEquals("SYNTHETIC_PILOT_UNQUALIFIED",accepted.qualification(),sheet);
            assertEquals(Verdict.NOT_COMPUTABLE,accepted.findings().getFirst().verdict(),sheet);
            String wrongParent=parent.equals("F01")?"F02":"F01";
            IllegalArgumentException wrong=assertThrows(IllegalArgumentException.class,
                    ()->assess(row(wrongParent,sheet,Map.of())),sheet);
            assertEquals("INVALID_EXTENSION_PARENT",wrong.getMessage(),sheet);
            IllegalArgumentException unknown=assertThrows(IllegalArgumentException.class,
                    ()->assess(row(parent,sheet,Map.of("notARegisteredField",
                            BigDecimal.ONE))),sheet);
            assertEquals("UNREGISTERED_EXTENSION_FIELD",unknown.getMessage(),sheet);
        }
        assertEquals(30,knownSheets.size());
    }
    @Test void anyClientClaimOfApprovalWithoutRevisionOrForActualMustFailClosed(){
        assertThrows(IllegalArgumentException.class,()->new InputRow("F07","EX-BUD-02",
                "cost-centre","row",PERIOD,null,"KZT",null,EvidenceState.APPROVED,
                Map.of("plannedAmount",BigDecimal.ONE)));
        assertThrows(IllegalArgumentException.class,()->new InputRow("F07","EX-BUD-02",
                "cost-centre","row",PERIOD,null,"KZT",null,EvidenceState.PENDING,
                Map.of("plannedAmount",BigDecimal.ONE)));
        Scope actual=new Scope(SCOPE.organizationId(),SCOPE.entityId(),SCOPE.businessScopeId(),
                SCOPE.factSetRevisionId(),AnalysisContext.DataSpace.ACTUAL,"MANAGEMENT",
                "CY-V1","KZT");
        assertEquals("UNQUALIFIED_SCENARIO_DIAGNOSTICS_FOR_ACTUAL",
                assertThrows(IllegalArgumentException.class,()->ScenarioEconomicsEngine.evaluate(
                        new Input(actual,"SC-BUDGET-FPA",List.of()))).getMessage());
    }
}
