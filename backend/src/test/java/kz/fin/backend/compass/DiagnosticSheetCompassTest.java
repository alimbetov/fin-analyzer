package kz.fin.backend.compass;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.compass.DiagnosticSheetCompass.*;
import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticSheetCompassTest {
    private static final DiagnosticSheetCompass ENGINE=new DiagnosticSheetCompass();
    private static Request req(String scenario,Set<String> approved,Map<String,State> sheets,
                               Set<String> yes,Set<String> no,Depth depth){
        return new Request(scenario,approved,sheets,yes,no,depth,
                AnalysisContext.DataSpace.SYNTHETIC);
    }
    private static Request basic(String scenario,Set<String> approved){
        return req(scenario,approved,Map.of(),Set.of(),Set.of(),Depth.ESSENTIAL);
    }
    private static Guidance next(Result result,String code){
        return result.nextInputs().stream().filter(x->x.sheetCode().equals(code))
                .findFirst().orElseThrow();
    }
    private static boolean contains(Result result,String sheet){
        return result.nextInputs().stream().anyMatch(x->x.sheetCode().equals(sheet));
    }

    @Test void proposedSheetsAreNotTopLevelFormsAndDerivedIsNotManualEntry(){
        var history=ENGINE.recommend(basic("SC-HISTORY-3Y5Y",
                Set.of("F01","F02","F03","DF-CTX-03")));
        assertEquals(Action.FILL_DETAIL_SHEET,next(history,"EX-HIS-01").action());
        assertFalse(contains(history,"EX-HIS-06"));
        assertTrue(history.derivedCalculations().stream().anyMatch(g->
                g.sheetCode().equals("EX-HIS-06") &&
                g.action()==Action.DERIVE_FROM_SOURCES));
        assertEquals("DESIGN_ONLY",history.qualificationStatus());
    }

    @Test void missingBaseFormMustBeRequestedBeforeSheetEntry(){
        var result=ENGINE.recommend(basic("SC-BUDGET-FPA",Set.of("P02","P03")));
        assertEquals(Action.FILL_DETAIL_SHEET,next(result,"EX-BUD-01").action());
        assertEquals(Action.PROVIDE_PARENT_FORM,next(result,"EX-BUD-02").action());
        assertEquals("F07",next(result,"EX-BUD-02").parentFormCode());
        assertEquals(3,result.nextInputs().size());
    }

    @Test void approvedDetailIsReusedAndDraftsAreApprovedNotReuploaded(){
        var result=ENGINE.recommend(req("SC-BUDGET-FPA",Set.of("P02","P03","F07"),
                Map.of("EX-BUD-01",State.APPROVED,"EX-BUD-02",State.PENDING_APPROVAL),
                Set.of(),Set.of(),Depth.ESSENTIAL));
        assertTrue(result.reusedSheets().contains("EX-BUD-01"));
        assertFalse(contains(result,"EX-BUD-01"));
        assertEquals(Action.APPROVE_DRAFT,next(result,"EX-BUD-02").action());
    }

    @Test void manufacturingWithoutBomDoesNotAskForBOMByDefault(){
        var a=ENGINE.recommend(basic("SC-MFG-ECONOMY",Set.of("SF-C02")));
        assertEquals(Action.FILL_DETAIL_SHEET,next(a,"EX-MFG-02").action());
        assertFalse(contains(a,"EX-MFG-01"));
        assertFalse(contains(a,"EX-MFG-03"));
        var b=ENGINE.recommend(req("SC-MFG-ECONOMY",Set.of("SF-C02","DF-OPS-02"),
                Map.of(),Set.of("HAS_BOM"),Set.of(),Depth.ESSENTIAL));
        assertEquals(Action.FILL_DETAIL_SHEET,next(b,"EX-MFG-01").action());
    }

    @Test void originalConstructionEstimateDoesNotRequireActOrSubcontract(){
        var a=ENGINE.recommend(basic("SC-CONSTRUCTION-ESTIMATE",Set.of("SF-F01","SF-F02")));
        assertTrue(contains(a,"EX-CST-01"));
        assertTrue(contains(a,"EX-CST-02"));
        assertTrue(contains(a,"EX-CST-03"));
        assertFalse(contains(a,"EX-CST-05"));
        assertFalse(contains(a,"EX-CST-04"));
        assertTrue(a.derivedCalculations().stream()
                .anyMatch(x->x.sheetCode().equals("EX-CST-06")));
    }

    @Test void constructionWithCertifiedWorkRequestsTheRelevantSheetOnly(){
        var a=ENGINE.recommend(req("SC-CONSTRUCTION-ESTIMATE",
                Set.of("SF-F01","SF-F02","SF-F03"),Map.of(),
                Set.of("HAS_COMPLETED_WORK"),Set.of("HAS_SUBCONTRACTS"),Depth.ESSENTIAL));
        assertEquals(Action.FILL_DETAIL_SHEET,next(a,"EX-CST-05").action());
        assertFalse(contains(a,"EX-CST-04"));
    }

    @Test void unleveredInvestmentDoesNotRequestDebtSchedule(){
        var a=ENGINE.recommend(req("SC-INVESTMENT-APPRAISAL",Set.of("P07"),
                Map.of(),Set.of(),Set.of("HAS_DEBT"),Depth.ESSENTIAL));
        assertTrue(contains(a,"EX-INV-01"));
        assertTrue(contains(a,"EX-INV-02"));
        assertFalse(contains(a,"EX-INV-03"));
        assertEquals(Action.PROVIDE_PARENT_FORM,
                next(a,"EX-INV-06").action());
    }

    @Test void leveragedInvestmentRequestsSpecificDebtSchedule(){
        var a=ENGINE.recommend(req("SC-INVESTMENT-APPRAISAL",Set.of("P07","P04"),
                Map.of(),Set.of("HAS_DEBT"),Set.of(),Depth.ESSENTIAL));
        assertEquals(Action.FILL_DETAIL_SHEET,next(a,"EX-INV-03").action());
        assertEquals("P04",next(a,"EX-INV-03").parentFormCode());
    }

    @Test void deepModeAsksUnresolvedTraitsInsteadOfAssumingTheyApply(){
        var a=ENGINE.recommend(req("SC-MFG-ECONOMY",Set.of("SF-C02"),
                Map.of(),Set.of(),Set.of(),Depth.DEEP));
        assertTrue(a.questions().stream().anyMatch(q->q.traitCode().equals("HAS_BOM")));
        assertTrue(a.questions().stream().anyMatch(q->q.traitCode().equals("HAS_EQUIPMENT")));
        assertFalse(contains(a,"EX-MFG-01"));
    }

    @Test void staleAndInvalidSheetsHaveDedicatedActions(){
        var a=ENGINE.recommend(req("SC-BUDGET-FPA",Set.of("P02","P03","F07"),
                Map.of("EX-BUD-01",State.STALE,"EX-BUD-02",State.INVALID),
                Set.of(),Set.of(),Depth.ESSENTIAL));
        assertEquals(Action.REFRESH_SHEET,next(a,"EX-BUD-01").action());
        assertEquals(Action.CORRECT_SHEET,next(a,"EX-BUD-02").action());
    }

    @Test void actualAndOrphanApprovedSheetCannotPassAsTrustedEvidence(){
        assertEquals("SHEET_COMPASS_REQUIRES_SYNTHETIC",
                assertThrows(IllegalArgumentException.class,()->
                        ENGINE.recommend(new Request("SC-BUDGET-FPA",Set.of(),
                                Map.of(),Set.of(),Set.of(),Depth.ESSENTIAL,
                                AnalysisContext.DataSpace.ACTUAL))).getMessage());
        assertEquals("APPROVED_SHEET_WITHOUT_APPROVED_PARENT",
                assertThrows(IllegalArgumentException.class,()->
                        ENGINE.recommend(req("SC-BUDGET-FPA",Set.of("P02","P03"),
                                Map.of("EX-BUD-02",State.APPROVED),
                                Set.of(),Set.of(),Depth.ESSENTIAL))).getMessage());
    }

    @Test void repeatedResultsAreDeterministicAndUnknownCodesRejected(){
        var request=basic("SC-INVESTMENT-APPRAISAL",Set.of("P07"));
        Result a=ENGINE.recommend(request),b=ENGINE.recommend(request);
        assertEquals(a,b);
        assertThrows(UnsupportedOperationException.class,()->a.nextInputs().clear());
        assertThrows(IllegalArgumentException.class,()->
                ENGINE.recommend(basic("NO_SCENARIO",Set.of())));
        assertThrows(IllegalArgumentException.class,()->
                ENGINE.recommend(req("SC-BUDGET-FPA",Set.of(),
                        Map.of("NOT_A_SHEET",State.APPROVED),Set.of(),Set.of(),Depth.ESSENTIAL)));
    }
}
