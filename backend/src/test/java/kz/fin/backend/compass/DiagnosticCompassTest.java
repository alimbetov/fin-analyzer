package kz.fin.backend.compass;

import kz.fin.backend.diagnostics.BusinessDiagnosticEngine.RuleCode;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.*;

import static kz.fin.backend.compass.DiagnosticCompass.*;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticCompassTest {
    private static final CompassCatalog catalog=CompassCatalog.bundled();
    private static final DiagnosticCompass engine=new DiagnosticCompass(catalog);

    private static Request request(String scenario,Set<String> models,Set<String> has,
                                   Set<String> not,Map<String,FormEvidence> data,
                                   Set<RuleCode> rules,Depth depth){
        return new Request(scenario,models,has,not,data,rules,depth);
    }
    private static Request empty(String scenario) {
        return request(scenario,Set.of(),Set.of(),Set.of(),Map.of(),Set.of(),Depth.ESSENTIAL);
    }
    private static FormEvidence approved(String code) {
        return FormEvidence.approved(code,"sealed-revision-"+code,Set.of(),Set.of());
    }
    private static FormEvidence annual(String code,Integer... years) {
        return FormEvidence.approved(code,"sealed-"+code,Set.of(years),Set.of());
    }
    private static FormEvidence monthly(String code,Set<YearMonth> months) {
        return FormEvidence.approved(code,"sealed-"+code,Set.of(),months);
    }
    private static Set<YearMonth> consecutiveMonths(int n){
        Set<YearMonth> result=new HashSet<>();
        YearMonth end=YearMonth.of(2026,10);
        for(int i=0;i<n;i++)result.add(end.minusMonths(i));
        return result;
    }
    private static Map<String,FormEvidence> approvedCore(String scenario){
        var result=new TreeMap<String,FormEvidence>();
        catalog.scenarios().get(scenario).required().forEach(c->result.put(c,approved(c)));
        return result;
    }
    private static Recommendation form(Result result,String code){
        return result.nextForms().stream().filter(x->x.formCode().equals(code))
                .findFirst().orElseThrow(()->new AssertionError("Missing candidate "+code));
    }
    private static boolean contains(Result result,String code){
        return result.nextForms().stream().anyMatch(x->x.formCode().equals(code));
    }

    @Test void catalogIsInternallyConnectedAndNoSourcePretendsToBeProductionReady() {
        assertEquals(166,catalog.forms().size());
        assertEquals(10,catalog.scenarios().size());
        assertEquals(21,catalog.sectors().size());
        assertEquals(64,catalog.modelToSection().size());
        assertEquals(40,catalog.traitPolicyCount());
        assertEquals(15,catalog.findingPolicyCount());
        assertTrue(catalog.forms().values().stream().noneMatch(
                f->f.status().equals("QUALIFIED")));
    }

    @Test void newSoleTraderGetsOnlySixCoreFormsNotTheWholeCatalog(){
        var result=engine.recommend(empty("SC-IP-HEALTH"));
        assertEquals(Readiness.NEEDS_INPUTS,result.dataReadiness());
        assertEquals("DESIGN_ONLY",result.methodQualification());
        assertEquals(6,result.nextForms().size());
        assertTrue(result.nextForms().stream().allMatch(x->x.tier()==Tier.CORE));
        assertEquals(Action.PROVIDE_FORM,form(result,"DF-SAL-01").action());
        assertFalse(contains(result,"F02"));
        assertFalse(contains(result,"SF-G01"));
    }

    @Test void approvedFormsAreReusedAndPendingApprovalIsNotReuploaded(){
        var current=Map.of(
                "DF-CTX-01",approved("DF-CTX-01"),
                "DF-CTX-03",new FormEvidence("DF-CTX-03",EvidenceState.PENDING_APPROVAL,
                        "draft-2",Set.of(),Set.of())
        );
        Result result=engine.recommend(request("SC-IP-HEALTH",Set.of(),Set.of(),Set.of(),
                current,Set.of(),Depth.ESSENTIAL));
        assertTrue(result.reusedFormCodes().contains("DF-CTX-01"));
        assertFalse(contains(result,"DF-CTX-01"));
        assertEquals(Action.APPROVE_EXISTING,form(result,"DF-CTX-03").action());
        assertEquals(Readiness.NEEDS_INPUTS,result.dataReadiness());
    }

    @Test void invalidAndStaleSourceRequireCorrectActionAndDoNotBecomeReady(){
        Map<String,FormEvidence> evidence=new TreeMap<>(approvedCore("SC-IP-HEALTH"));
        evidence.put("DF-SAL-01",new FormEvidence("DF-SAL-01",EvidenceState.INVALID,
                "bad-1",Set.of(),Set.of()));
        evidence.put("DF-CSH-01",new FormEvidence("DF-CSH-01",EvidenceState.STALE,
                "old-1",Set.of(),Set.of()));
        var response=engine.recommend(request("SC-IP-HEALTH",Set.of(),Set.of(),
                Set.of(),evidence,Set.of(),Depth.ESSENTIAL));
        assertEquals(Readiness.INVALID_INPUT,response.dataReadiness());
        assertEquals(Action.CORRECT_SOURCE,form(response,"DF-SAL-01").action());
        assertEquals(Action.REFRESH_SOURCE,form(response,"DF-CSH-01").action());
    }

    @Test void annualReportsForThreeConsecutiveYearsQualifyForHistoricalDataPreflight(){
        var data=new TreeMap<>(approvedCore("SC-HISTORY-3Y5Y"));
        for(String form:List.of("F01","F02","F03"))
            data.put(form,annual(form,2023,2024,2025));
        var result=engine.recommend(request("SC-HISTORY-3Y5Y",Set.of(),Set.of(),
                Set.of(),data,Set.of(),Depth.ESSENTIAL));
        assertEquals(Readiness.CORE_DATA_AVAILABLE_NOT_QUALIFIED,result.dataReadiness());
        assertTrue(result.nextForms().isEmpty());
        assertEquals(4,result.reusedFormCodes().size());
    }

    @Test void onlyTwoAnnualYearsTriggersHistoryExtensionEvenWithApprovedForms(){
        var data=new TreeMap<>(approvedCore("SC-HISTORY-3Y5Y"));
        for(String form:List.of("F01","F02","F03"))
            data.put(form,annual(form,2024,2025));
        var result=engine.recommend(request("SC-HISTORY-3Y5Y",Set.of(),Set.of(),
                Set.of(),data,Set.of(),Depth.ESSENTIAL));
        assertEquals(Readiness.NEEDS_COMPARABLE_HISTORY,result.dataReadiness());
        for(String code:List.of("F01","F02","F03")){
            assertEquals(Action.EXTEND_HISTORY,form(result,code).action());
            assertEquals(Tier.CORE,form(result,code).tier());
        }
        assertEquals(3,result.nextForms().size());
        assertTrue(result.blockingReasons().contains("HISTORY_PERIODS_NOT_COMPARABLE"));
    }

    @Test void threeYearsThatDoNotOverlapMustNotBePresentedAsComparable(){
        var data=new TreeMap<>(approvedCore("SC-HISTORY-3Y5Y"));
        data.put("F01",annual("F01",2020,2021,2022));
        data.put("F02",annual("F02",2023,2024,2025));
        data.put("F03",annual("F03",2023,2024,2025));
        var result=engine.recommend(request("SC-HISTORY-3Y5Y",Set.of(),Set.of(),
                Set.of(),data,Set.of(),Depth.ESSENTIAL));
        assertEquals(Readiness.NEEDS_COMPARABLE_HISTORY,result.dataReadiness());
        assertEquals(3,result.nextForms().size());
    }

    @Test void thirtySixCompleteMonthlyPeriodsAcceptedButOneGapRejects(){
        var complete=new TreeMap<>(approvedCore("SC-HISTORY-3Y5Y"));
        for(String form:List.of("F01","F02","F03"))
            complete.put(form,monthly(form,consecutiveMonths(36)));
        assertEquals(Readiness.CORE_DATA_AVAILABLE_NOT_QUALIFIED,
                engine.recommend(request("SC-HISTORY-3Y5Y",Set.of(),Set.of(),
                        Set.of(),complete,Set.of(),Depth.ESSENTIAL)).dataReadiness());
        Set<YearMonth> missing=new HashSet<>(consecutiveMonths(36));
        missing.remove(YearMonth.of(2025,5));
        complete.put("F03",monthly("F03",missing));
        assertEquals(Readiness.NEEDS_COMPARABLE_HISTORY,
                engine.recommend(request("SC-HISTORY-3Y5Y",Set.of(),Set.of(),
                        Set.of(),complete,Set.of(),Depth.ESSENTIAL)).dataReadiness());
    }

    @Test void fullAnnualButNoMonthlyHistoryDoesNotClaimMonthlySeasonalAnalytics(){
        var data=new TreeMap<>(approvedCore("SC-HISTORY-3Y5Y"));
        for(String form:List.of("F01","F02","F03"))
            data.put(form,annual(form,2023,2024,2025,2026));
        var result=engine.recommend(request("SC-HISTORY-3Y5Y",Set.of(),Set.of(),
                Set.of(),data,Set.of(),Depth.ESSENTIAL));
        assertEquals(Readiness.CORE_DATA_AVAILABLE_NOT_QUALIFIED,result.dataReadiness());
        assertFalse(result.methodQualification().equals("QUALIFIED"));
    }

    @Test void investmentWithoutDebtDoesNotDemandDebtSchedule(){
        var result=engine.recommend(request("SC-INVESTMENT-APPRAISAL",Set.of(),
                Set.of(),Set.of("HAS_DEBT","HAS_PROJECT_FINANCING"),Map.of(),Set.of(),
                Depth.ESSENTIAL));
        assertEquals(5,result.nextForms().size());
        assertFalse(contains(result,"P04"));
        assertFalse(contains(result,"C05"));
        assertTrue(contains(result,"P07"));
    }

    @Test void investmentWithDebtAddsScheduleAsConditional(){
        var result=engine.recommend(request("SC-INVESTMENT-APPRAISAL",Set.of(),
                Set.of("HAS_DEBT"),Set.of(),Map.of(),Set.of(),Depth.ESSENTIAL));
        assertEquals(Tier.CONDITIONAL,form(result,"P04").tier());
        assertTrue(contains(result,"C05"));
    }

    @Test void constructionEstimateWithoutActsDoesNotDemandCertification(){
        var result=engine.recommend(request("SC-CONSTRUCTION-ESTIMATE",Set.of(),
                Set.of(),Set.of("HAS_COMPLETED_WORK"),Map.of(),Set.of(),Depth.ESSENTIAL));
        assertEquals(3,result.nextForms().size());
        assertFalse(contains(result,"SF-F03"));
    }

    @Test void productionWithoutEquipmentDoesNotDemandAssetUtilizationForm(){
        var none=engine.recommend(empty("SC-MFG-ECONOMY"));
        assertEquals(3,none.nextForms().size());
        assertFalse(contains(none,"SF-C03"));
        var equipment=engine.recommend(request("SC-MFG-ECONOMY",Set.of(),
                Set.of("HAS_EQUIPMENT"),Set.of(),Map.of(),Set.of(),Depth.ESSENTIAL));
        assertEquals(Tier.CONDITIONAL,form(equipment,"SF-C03").tier());
        assertEquals("PILOT_UNQUALIFIED",form(equipment,"SF-C03").catalogStatus());
    }

    @Test void sectorSelectionNeverRecommendsAllTwentyOneIndustryForms(){
        var missing=engine.recommend(empty("SC-SECTOR-ROUTED"));
        assertEquals(Readiness.NEEDS_INPUTS,missing.dataReadiness());
        assertTrue(missing.questions().stream().anyMatch(
                q->q.traitCode().equals("BUSINESS_MODEL")));
        assertTrue(missing.nextForms().stream().noneMatch(
                f->f.formCode().startsWith("SF-")));

        var selected=engine.recommend(request("SC-SECTOR-ROUTED",
                Set.of("G_RETAIL","M_CONSULTING"),Set.of(),Set.of(),
                approvedCore("SC-SECTOR-ROUTED"),Set.of(),Depth.ESSENTIAL));
        assertEquals(Readiness.CORE_DATA_AVAILABLE_NOT_QUALIFIED,selected.dataReadiness());
        assertEquals(Set.of("SF-G01","SF-M01"),selected.nextForms().stream()
                .map(Recommendation::formCode).collect(java.util.stream.Collectors.toSet()));
        assertTrue(selected.nextForms().stream().noneMatch(f->f.formCode().startsWith("SF-K")));
    }

    @Test void detailedIndustryModeProposesOnlyThreeFormsPerSelectedSection(){
        var result=engine.recommend(request("SC-SECTOR-ROUTED",Set.of("C_PROCESS"),
                Set.of(),Set.of(),approvedCore("SC-SECTOR-ROUTED"),Set.of(),Depth.DEEP));
        assertEquals(Set.of("SF-C01","SF-C02","SF-C03"),result.nextForms().stream()
                .map(Recommendation::formCode).collect(java.util.stream.Collectors.toSet()));
    }

    @Test void triggeredCashDivergenceProposesAgingAndReceiptsButNoFakeCausalVerdict(){
        var existing=approvedCore("SC-IP-HEALTH");
        var result=engine.recommend(request("SC-IP-HEALTH",Set.of("G_RETAIL"),Set.of(),
                Set.of(),existing,Set.of(RuleCode.REVENUE_CASH_AR_DIVERGENCE),
                Depth.ESSENTIAL));
        assertTrue(contains(result,"C01"));
        assertTrue(contains(result,"DF-CSH-04"));
        assertTrue(contains(result,"DF-CSH-03"));
        assertTrue(contains(result,"SF-G02"));
        assertFalse(contains(result,"SF-C02"));
        assertFalse(contains(result,"DF-CSH-01")); // already approved
        assertTrue(result.reusedFormCodes().contains("DF-CSH-01"));
        assertEquals(Tier.DIAGNOSTIC_EVIDENCE,form(result,"C01").tier());
        assertTrue(form(result,"C01").reasonKeys().contains("compass.receivables"));
    }

    @Test void fallingRevenueRequestsSalesAndReturnsEvidenceNotUnrelatedSectorForms(){
        var result=engine.recommend(request("SC-LLC-FINANCE",Set.of("M_CONSULTING"),
                Set.of(),Set.of(),approvedCore("SC-LLC-FINANCE"),
                Set.of(RuleCode.REVENUE_YOY_DECLINE),Depth.ESSENTIAL));
        assertTrue(contains(result,"DF-SAL-01"));
        assertTrue(contains(result,"C07"));
        assertTrue(contains(result,"DF-SAL-03"));
        assertFalse(contains(result,"SF-G01"));
    }

    @Test void marginDivergenceRequestsManufacturingCostEvidenceOnlyForManufacturing(){
        var manufacturing=engine.recommend(request("SC-MFG-ECONOMY",Set.of("C_PROCESS"),
                Set.of(),Set.of(),Map.of(),Set.of(RuleCode.GROSS_MARGIN_YOY_COMPRESSION),
                Depth.ESSENTIAL));
        assertTrue(contains(manufacturing,"SF-C02"));
        assertFalse(contains(manufacturing,"SF-G01"));
        assertTrue(form(manufacturing,"SF-C02").reasonKeys()
                .contains("compass.productionCost"));
    }

    @Test void deepModeAsksContextBeforeDemandingConditionalForms(){
        var result=engine.recommend(request("SC-INVESTMENT-APPRAISAL",Set.of(),
                Set.of(),Set.of(),approvedCore("SC-INVESTMENT-APPRAISAL"),
                Set.of(),Depth.DEEP));
        assertFalse(contains(result,"P04"));
        assertTrue(result.questions().stream().anyMatch(
                q->q.traitCode().equals("HAS_DEBT")));
        assertEquals(Readiness.CORE_DATA_AVAILABLE_NOT_QUALIFIED,result.dataReadiness());
    }

    @Test void incompatibleProfileOrFormEvidenceIsRejected(){
        assertThrows(IllegalArgumentException.class,()->
                request("SC-IP-HEALTH",Set.of(),Set.of("HAS_DEBT"),Set.of("HAS_DEBT"),
                        Map.of(),Set.of(),Depth.ESSENTIAL));
        assertThrows(IllegalArgumentException.class,()->
                engine.recommend(request("SC-IP-HEALTH",Set.of("G_FAKE"),Set.of(),
                        Set.of(),Map.of(),Set.of(),Depth.ESSENTIAL)));
        assertThrows(IllegalArgumentException.class,()->
                engine.recommend(request("SC-IP-HEALTH",Set.of(),Set.of(),
                        Set.of(),Map.of("UNKNOWN",approved("UNKNOWN")),Set.of(),
                        Depth.ESSENTIAL)));
        assertThrows(IllegalArgumentException.class,()->
                FormEvidence.approved("F01","",Set.of(2025),Set.of()));
    }

    @Test void outputIsImmutableSortedAndSafeForRepeatedEvaluation(){
        var request=empty("SC-IP-HEALTH");
        Result one=engine.recommend(request),two=engine.recommend(request);
        assertEquals(one,two);
        assertEquals(one.nextForms().stream().map(Recommendation::formCode).sorted().toList(),
                one.nextForms().stream().map(Recommendation::formCode).toList());
        assertThrows(UnsupportedOperationException.class,()->one.nextForms().clear());
        assertThrows(UnsupportedOperationException.class,()->one.reusedFormCodes().add("F01"));
    }
}
