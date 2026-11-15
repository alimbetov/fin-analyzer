package kz.fin.backend.diagnostics;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.diagnostics.ScenarioEconomicsEngine.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioEconomicsEngineTest {
    private static final Scope SCOPE=new Scope(UUID.randomUUID(),UUID.randomUUID(),
            UUID.randomUUID(),UUID.randomUUID(),AnalysisContext.DataSpace.SYNTHETIC,
            "MANAGEMENT","CY-V1","KZT");
    private static final YearMonth P=YearMonth.of(2026,10);

    private static Map<String,BigDecimal> n(String... pairs) {
        if(pairs.length%2!=0)throw new IllegalArgumentException();
        Map<String,BigDecimal> map=new TreeMap<>();
        for(int i=0;i<pairs.length;i+=2)map.put(pairs[i],new BigDecimal(pairs[i+1]));
        return map;
    }
    private static InputRow row(String form,String extension,String item,String id,
                                YearMonth when,Map<String,BigDecimal> values) {
        return new InputRow(form,extension,item,id,when,null,"KZT",
                extension.equals("EX-CST-06")?"calc:synthetic-evm-v1":"approved-source-"+id,
                EvidenceState.APPROVED,values);
    }
    private static InputRow r(String form,String ext,String item,Map<String,BigDecimal> values){
        return row(form,ext,item,item,P,values);
    }
    private static InputRow dated(String form,String ext,String item,String id,
                                  LocalDate when,Map<String,BigDecimal> values){
        return new InputRow(form,ext,item,id,YearMonth.from(when),when,"KZT",
                "approved-source-"+id,EvidenceState.APPROVED,values);
    }
    private static Report assess(String scenario,InputRow... rows){
        return ScenarioEconomicsEngine.evaluate(new Input(SCOPE,scenario,List.of(rows)));
    }
    private static Finding f(Report r,Method kind){
        return r.findings().stream().filter(x->x.method()==kind).findFirst()
                .orElseThrow(()->new AssertionError("Not found: "+kind));
    }
    private static void val(Finding f,String field,String value){
        assertEquals(0,new BigDecimal(value).compareTo(
                new BigDecimal(f.computedValues().get(field))),f.toString());
    }

    @Test void olderFinancialFormsReconcileBalanceAndCashBridge(){
        var a=assess("SC-LLC-FINANCE",
                r("F01","","entity",n("assets","1000","liabilities","300","equity","700")),
                r("F03","","entity",n("openingCash","200","cfo","90","cfi","-20",
                        "cff","-10","closingCash","260")));
        assertEquals(2,a.findings().size());
        assertEquals(Verdict.REVIEW,f(a,Method.BALANCE_RECONCILIATION).verdict());
        val(f(a,Method.BALANCE_RECONCILIATION),"balanceDifference","0");
        assertEquals(Verdict.REVIEW,f(a,Method.CASH_FLOW_RECONCILIATION).verdict());
        val(f(a,Method.CASH_FLOW_RECONCILIATION),"cashDifference","0");
        assertEquals("SYNTHETIC_PILOT_UNQUALIFIED",a.qualification());
    }

    @Test void oldBalanceAndCashStatementDiscrepanciesAreObservationsNotGuesses(){
        var result=assess("SC-LLC-FINANCE",
                r("F01","","entity",n("assets","1000","liabilities","300","equity","690")),
                r("F03","","entity",n("openingCash","200","cfo","90","cfi","-20",
                        "cff","-10","closingCash","250")));
        assertEquals(Verdict.OBSERVATION,f(result,Method.BALANCE_RECONCILIATION).verdict());
        assertEquals(Verdict.OBSERVATION,f(result,Method.CASH_FLOW_RECONCILIATION).verdict());
        val(f(result,Method.BALANCE_RECONCILIATION),"balanceDifference","10");
        val(f(result,Method.CASH_FLOW_RECONCILIATION),"cashDifference","-10");
        assertEquals(1,f(result,Method.CASH_FLOW_RECONCILIATION).sources().size());
    }

    @Test void incompleteOldFormCannotProduceReconciliation(){
        var a=assess("SC-LLC-FINANCE",
                r("F01","","entity",n("assets","1000","equity","800")));
        assertEquals(Verdict.MISSING_EVIDENCE,f(a,Method.BALANCE_RECONCILIATION).verdict());
        assertEquals(Verdict.MISSING_EVIDENCE,f(a,Method.CASH_FLOW_RECONCILIATION).verdict());
    }

    @Test void soleTraderCashEvidenceNeverRepresentsProfit(){
        var a=assess("SC-IP-HEALTH",
                r("DF-CSH-01","","cash-in",n("amount","900")),
                r("DF-CSH-02","","cash-out",n("amount","600")));
        assertEquals("CASH_MOVEMENT_NOT_NET_PROFIT",f(a,Method.OWNER_CASH_MOVEMENT).reason());
        val(f(a,Method.OWNER_CASH_MOVEMENT),"cashMovement","300");
        assertFalse(f(a,Method.OWNER_CASH_MOVEMENT).computedValues().containsKey("netProfit"));
    }

    @Test void annualHistoryWorksFromThreeOldF02StatementsWithoutMonthlyData(){
        var a=assess("SC-HISTORY-3Y5Y",
                row("F02","","ANNUAL_REVENUE","f23",YearMonth.of(2023,12),n("netRevenue","100")),
                row("F02","","ANNUAL_REVENUE","f24",YearMonth.of(2024,12),n("netRevenue","110")),
                row("F02","","ANNUAL_REVENUE","f25",YearMonth.of(2025,12),n("netRevenue","99")));
        Finding finding=f(a,Method.ANNUAL_REVENUE_TREND);
        assertEquals(Verdict.REVIEW,finding.verdict());
        assertEquals("YOY_DECLINE",finding.reason());
        val(finding,"yoyAbsolute","-11");
        val(finding,"yoyFraction","-0.1");
        assertEquals(3,finding.sources().size());
    }

    @Test void missingAnnualYearOrNonpositiveBaselineStopsGrowthRate(){
        var missing=assess("SC-HISTORY-3Y5Y",
                row("F02","","ANNUAL_REVENUE","f22",YearMonth.of(2022,12),n("netRevenue","100")),
                row("F02","","ANNUAL_REVENUE","f23",YearMonth.of(2023,12),n("netRevenue","110")),
                row("F02","","ANNUAL_REVENUE","f25",YearMonth.of(2025,12),n("netRevenue","120")));
        assertEquals(Verdict.MISSING_EVIDENCE,
                f(missing,Method.ANNUAL_REVENUE_TREND).verdict());
        var zero=assess("SC-HISTORY-3Y5Y",
                row("F02","","ANNUAL_REVENUE","f23",YearMonth.of(2023,12),n("netRevenue","100")),
                row("F02","","ANNUAL_REVENUE","f24",YearMonth.of(2024,12),n("netRevenue","0")),
                row("F02","","ANNUAL_REVENUE","f25",YearMonth.of(2025,12),n("netRevenue","120")));
        assertEquals(Verdict.NOT_COMPUTABLE,
                f(zero,Method.ANNUAL_REVENUE_TREND).verdict());
    }

    @Test void approvedBudgetExtensionReconcilesOldExpenseFormAtSameGrainAndPeriod(){
        var a=assess("SC-BUDGET-FPA",
                r("F07","EX-BUD-02","COST_CENTER_A:LABOR",n("plannedAmount","100")),
                r("C09","","COST_CENTER_A:LABOR",n("amount","120")));
        Finding f=f(a,Method.BUDGET_ACTUAL_VARIANCE);
        assertEquals(Verdict.REVIEW,f.verdict());
        val(f,"absoluteVariance","20");
        val(f,"relativeVariance","0.2");
        assertEquals(2,f.sources().size());
        assertEquals("EX-BUD-02",f.sources().getFirst().sheetCode());
    }

    @Test void zeroBudgetStillYieldsAbsoluteVarianceButNeverPercent(){
        var a=assess("SC-BUDGET-FPA",
                r("F07","EX-BUD-02","COST_CENTER_A",n("plannedAmount","0")),
                r("C09","","COST_CENTER_A",n("amount","20")));
        Finding f=f(a,Method.BUDGET_ACTUAL_VARIANCE);
        assertEquals(Verdict.NOT_COMPUTABLE,f.verdict());
        val(f,"absoluteVariance","20");
        assertFalse(f.computedValues().containsKey("relativeVariance"));
    }

    @Test void mismatchingBudgetPeriodOrAccountCannotBeSilentZero(){
        var a=assess("SC-BUDGET-FPA",
                row("F07","EX-BUD-02","CENTER_A","p",P,n("plannedAmount","100")),
                row("C09","","CENTER_B","a",P,n("amount","120")));
        assertEquals(Verdict.MISSING_EVIDENCE,
                f(a,Method.BUDGET_ACTUAL_VARIANCE).verdict());
    }

    @Test void manufacturingWipAndOldProductionCostsYieldSeparateEvidence(){
        var a=assess("SC-MFG-ECONOMY",
                r("SF-C02","EX-MFG-02","ORDER-A",n("openingWip","100",
                        "addedCosts","80","transferredOut","130","closingWip","50")),
                r("SF-C02","","ORDER-A",n("materialUsed","110","directLabor","30",
                        "overhead","10","goodUnits","5")),
                r("SF-C02","EX-MFG-04","ORDER-A",n("standardQty","10","actualQty","12",
                        "standardRate","5","actualRate","6")));
        assertEquals(Verdict.REVIEW,f(a,Method.WIP_ROLLFORWARD).verdict());
        val(f(a,Method.WIP_ROLLFORWARD),"wipDifference","0");
        val(f(a,Method.UNIT_PRODUCTION_COST),"unitCost","30");
        val(f(a,Method.STANDARD_ACTUAL_VARIANCE),"priceVariance","12");
        val(f(a,Method.STANDARD_ACTUAL_VARIANCE),"usageVariance","10");
        val(f(a,Method.STANDARD_ACTUAL_VARIANCE),"totalVariance","22");
    }

    @Test void manufacturingWipMismatchMustBeVisible(){
        var a=assess("SC-MFG-ECONOMY",
                r("SF-C02","EX-MFG-02","ORDER-A",n("openingWip","100",
                        "addedCosts","80","transferredOut","130","closingWip","60")));
        assertEquals(Verdict.OBSERVATION,f(a,Method.WIP_ROLLFORWARD).verdict());
        val(f(a,Method.WIP_ROLLFORWARD),"wipDifference","10");
    }

    @Test void zeroFinishedUnitsAndNegativeResourceInputAreBlocked(){
        var a=assess("SC-MFG-ECONOMY",
                r("SF-C02","","ORDER-A",n("materialUsed","110","directLabor","30",
                        "overhead","10","goodUnits","0")));
        assertEquals(Verdict.NOT_COMPUTABLE,
                f(a,Method.UNIT_PRODUCTION_COST).verdict());
        assertThrows(IllegalArgumentException.class,()->assess("SC-MFG-ECONOMY",
                r("SF-C02","EX-MFG-04","ORDER-A",n("standardQty","10",
                        "actualQty","12","standardRate","5","actualRate","-6"))));
    }

    @Test void boqOneItemWithTwoResourceRatesDoesNotDoubleCountWorkQuantity(){
        var a=assess("SC-CONSTRUCTION-ESTIMATE",
                r("SF-F01","EX-CST-01","WBS-01",n("measuredQuantity","10")),
                row("SF-F02","EX-CST-02","WBS-01","materials",P,
                        n("quantityNorm","3","unitRate","50")),
                row("SF-F02","EX-CST-02","WBS-01","labor",P,
                        n("quantityNorm","2","unitRate","20")));
        Finding f=f(a,Method.BOQ_RESOURCE_COST);
        assertEquals(Verdict.REVIEW,f.verdict());
        val(f,"directCost","1900");
        assertEquals(3,f.sources().size());
    }

    @Test void boqRejectsMissingRateOrDifferentPeriod(){
        var a=assess("SC-CONSTRUCTION-ESTIMATE",
                r("SF-F01","EX-CST-01","WBS-01",n("measuredQuantity","10")),
                row("SF-F02","EX-CST-02","WBS-01","materials",P.minusMonths(1),
                        n("quantityNorm","3","unitRate","50")));
        assertEquals(Verdict.MISSING_EVIDENCE,
                f(a,Method.BOQ_RESOURCE_COST).verdict());
        assertThrows(IllegalArgumentException.class,()->assess("SC-CONSTRUCTION-ESTIMATE",
                r("SF-F01","EX-CST-01","WBS-01",n("measuredQuantity","-10")),
                r("SF-F02","EX-CST-02","WBS-01",n("quantityNorm","3","unitRate","50"))));
    }

    @Test void constructionEvUsesExplicitAcPlusEtcPolicyAndReportsOverrun(){
        var a=assess("SC-CONSTRUCTION-ESTIMATE",
                r("SF-F01","EX-CST-06","WBS-01",n("pv","100","ev","80","ac","100",
                        "bac","150","etc","70","eac","170")));
        Finding f=f(a,Method.CONSTRUCTION_EVM);
        assertEquals(Verdict.OBSERVATION,f.verdict());
        assertEquals("PROJECT_COST_OVERRUN",f.reason());
        val(f,"cpi","0.8");
        val(f,"spi","0.8");
        val(f,"vac","-20");
    }

    @Test void constructionEvmRejectsUnknownEacPolicyOrZeroDenominator(){
        var wrong=assess("SC-CONSTRUCTION-ESTIMATE",
                r("SF-F01","EX-CST-06","WBS-01",n("pv","100","ev","80","ac","100",
                        "bac","150","etc","70","eac","175")));
        assertEquals("EAC_MUST_MATCH_SELECTED_AC_PLUS_ETC_POLICY",
                f(wrong,Method.CONSTRUCTION_EVM).reason());
        var zero=assess("SC-CONSTRUCTION-ESTIMATE",
                r("SF-F01","EX-CST-06","WBS-01",n("pv","0","ev","80","ac","100",
                        "bac","150","etc","70","eac","170")));
        assertEquals(Verdict.NOT_COMPUTABLE,f(zero,Method.CONSTRUCTION_EVM).verdict());
        assertFalse(f(zero,Method.CONSTRUCTION_EVM).computedValues().containsKey("spi"));
    }

    @Test void irregularDatedFcffNpvIsLabeledApproximateAndHasExactSourceRefs(){
        var a=assess("SC-INVESTMENT-APPRAISAL",
                dated("P07","EX-INV-02","WACC","policy",
                        LocalDate.of(2026,1,1),n("wacc","0.10")),
                dated("P07","EX-INV-01","PROJECT-A","investment",
                        LocalDate.of(2026,1,1),n("fcff","-1000")),
                dated("P07","EX-INV-01","PROJECT-A","receipt",
                        LocalDate.of(2027,1,1),n("fcff","1100")));
        Finding f=f(a,Method.DATED_PROJECT_NPV);
        assertEquals(Verdict.REVIEW,f.verdict());
        assertEquals("ACT_365_APPROXIMATE_NO_TERMINAL_OR_FCFE",f.reason());
        assertTrue(new BigDecimal(f.computedValues().get("datedNpvApproximate"))
                .abs().compareTo(new BigDecimal("0.000001"))<0);
        assertEquals(3,f.sources().size());
    }

    @Test void cannotConfuseCostOfEquityWithWaccOrUseMissingDatedCashflows(){
        var noRate=assess("SC-INVESTMENT-APPRAISAL",
                dated("P07","EX-INV-02","COE","policy",LocalDate.of(2026,1,1),
                        n("costOfEquity","0.15")),
                dated("P07","EX-INV-01","PROJECT-A","investment",
                        LocalDate.of(2026,1,1),n("fcff","-1000")));
        assertEquals(Verdict.MISSING_EVIDENCE,
                f(noRate,Method.DATED_PROJECT_NPV).verdict());
        assertThrows(IllegalArgumentException.class,()->assess("SC-INVESTMENT-APPRAISAL",
                dated("P07","EX-INV-02","WACC","policy",
                        LocalDate.of(2026,1,1),n("wacc","-1")),
                dated("P07","EX-INV-01","PROJECT-A","receipt",
                        LocalDate.of(2026,1,1),n("fcff","100"))));
    }

    @Test void rejectsWrongExtensionParentAndUnregisteredMetricNames(){
        assertEquals("INVALID_EXTENSION_PARENT",assertThrows(IllegalArgumentException.class,
                ()->assess("SC-BUDGET-FPA",r("F01","EX-BUD-02","A",
                        n("plannedAmount","100")))).getMessage());
        assertEquals("UNREGISTERED_EXTENSION_FIELD",assertThrows(IllegalArgumentException.class,
                ()->assess("SC-BUDGET-FPA",r("F07","EX-BUD-02","A",
                        n("fakeProfit","100")))).getMessage());
        assertEquals("UNKNOWN_DIAGNOSTIC_FORM",assertThrows(IllegalArgumentException.class,
                ()->assess("SC-BUDGET-FPA",r("UNREGISTERED","","A",
                        n("amount","100")))).getMessage());
    }

    @Test void mixedCurrencyAndActualInputsFailClosed(){
        InputRow different=new InputRow("C09","","CENTRE","id",P,null,"USD",
                "approved-1",EvidenceState.APPROVED,n("amount","100"));
        assertEquals("DIAGNOSTIC_CROSS_CURRENCY",assertThrows(IllegalArgumentException.class,
                ()->assess("SC-BUDGET-FPA",different)).getMessage());
        var actual=new Scope(SCOPE.organizationId(),SCOPE.entityId(),
                SCOPE.businessScopeId(),SCOPE.factSetRevisionId(),
                AnalysisContext.DataSpace.ACTUAL,SCOPE.reportingBasis(),
                SCOPE.fiscalCalendarVersion(),"KZT");
        assertEquals("UNQUALIFIED_SCENARIO_DIAGNOSTICS_FOR_ACTUAL",
                assertThrows(IllegalArgumentException.class,()->
                        ScenarioEconomicsEngine.evaluate(new Input(actual,
                                "SC-BUDGET-FPA",List.of()))).getMessage());
    }

    @Test void pendingEstimatedAndDuplicateEvidenceCannotBypassApproval(){
        assertThrows(IllegalArgumentException.class,()->new InputRow("F01","","A","id",
                P,null,"KZT","",EvidenceState.APPROVED,n("assets","100")));
        assertThrows(IllegalArgumentException.class,()->new InputRow("F01","","A","id",
                P,null,"KZT","forecast",EvidenceState.ESTIMATED,n("assets","100")));
        InputRow row=r("F01","","entity",n("assets","100","liabilities","50","equity","50"));
        assertEquals("DUPLICATE_DIAGNOSTIC_GRAIN",
                assertThrows(IllegalArgumentException.class,()->
                        assess("SC-LLC-FINANCE",row,row)).getMessage());
    }

    @Test void repeatEvaluationIsDeterministicAndOutputIsImmutable(){
        InputRow plan=r("F07","EX-BUD-02","A",n("plannedAmount","100"));
        InputRow actual=r("C09","","A",n("amount","90"));
        var one=assess("SC-BUDGET-FPA",plan,actual);
        var two=assess("SC-BUDGET-FPA",plan,actual);
        assertEquals(one,two);
        assertThrows(UnsupportedOperationException.class,()->one.findings().clear());
        assertThrows(UnsupportedOperationException.class,()->
                one.findings().getFirst().computedValues().clear());
    }

    @Test void oldRetailFormsProduceGrossMarginAndStockTurnover(){
        var a=assess("SC-RETAIL-ECONOMY",
                r("SF-G01","","SKU-A",n("netRevenue","1000","cogs","750")),
                r("SF-G02","","SKU-A",n("avgStockValue","300","periodCogs","750")));
        assertEquals(Verdict.REVIEW,f(a,Method.TRADE_GROSS_MARGIN).verdict());
        val(f(a,Method.TRADE_GROSS_MARGIN),"grossProfit","250");
        val(f(a,Method.TRADE_GROSS_MARGIN),"grossMarginFraction","0.25");
        val(f(a,Method.STOCK_TURNOVER),"turnoverForPeriod","2.5");
    }

    @Test void unprofitableSkuTriggersObservationWithoutInferringCause(){
        var a=assess("SC-RETAIL-ECONOMY",
                r("SF-G01","","SKU-A",n("netRevenue","1000","cogs","1200")));
        assertEquals(Verdict.OBSERVATION,f(a,Method.TRADE_GROSS_MARGIN).verdict());
        val(f(a,Method.TRADE_GROSS_MARGIN),"grossProfit","-200");
        assertEquals(Verdict.MISSING_EVIDENCE,f(a,Method.STOCK_TURNOVER).verdict());
    }

    @Test void zeroStockAndZeroRevenueCannotProduceMisleadingRatios(){
        var a=assess("SC-RETAIL-ECONOMY",
                r("SF-G01","","SKU-A",n("netRevenue","0","cogs","0")),
                r("SF-G02","","SKU-A",n("avgStockValue","0","periodCogs","0")));
        assertEquals(Verdict.NOT_COMPUTABLE,f(a,Method.TRADE_GROSS_MARGIN).verdict());
        assertEquals(Verdict.NOT_COMPUTABLE,f(a,Method.STOCK_TURNOVER).verdict());
    }

    @Test void legacyDebtServiceFormProducesOnlyUnqualifiedDscr(){
        var a=assess("SC-CREDIT-DOSSIER",
                r("P09","","BORROWER",n("cfads","140","debtService","100")));
        assertEquals(Verdict.REVIEW,f(a,Method.DSCR_PILOT).verdict());
        val(f(a,Method.DSCR_PILOT),"dscr","1.4");
        assertEquals("DSCR_DEFINITION_REQUIRES_LENDER_POLICY",
                f(a,Method.DSCR_PILOT).reason());
    }

    @Test void noDebtServiceDenominatorBlocksDscrInsteadOfAutoZero(){
        var a=assess("SC-CREDIT-DOSSIER",
                r("P09","","BORROWER",n("cfads","50","debtService","0")));
        assertEquals(Verdict.NOT_COMPUTABLE,f(a,Method.DSCR_PILOT).verdict());
    }

    @Test void industryRoutingWithoutQualifiedRulesDoesNotInventSectorHealth(){
        var a=assess("SC-SECTOR-ROUTED");
        assertEquals(1,a.findings().size());
        assertEquals(Verdict.NOT_COMPUTABLE,
                f(a,Method.SECTOR_METHOD_QUALIFICATION).verdict());
        assertEquals("SECTOR_APPLICABILITY_NEEDS_INDEPENDENT_METHODOLOGY",
                f(a,Method.SECTOR_METHOD_QUALIFICATION).reason());
    }

    @Test void derivedEvmSheetCannotBePassedOffAsManualSource(){
        assertEquals("DERIVED_SHEET_REQUIRES_CALCULATION_LINEAGE",
                assertThrows(IllegalArgumentException.class,()->
                        assess("SC-CONSTRUCTION-ESTIMATE",
                            new InputRow("SF-F01","EX-CST-06","WBS-01","manual",P,null,
                                "KZT","manual-entry",EvidenceState.APPROVED,
                                n("pv","10","ev","10","ac","10","bac","20",
                                  "etc","5","eac","15")))).getMessage());
    }

    @Test void testOnlyNewExtensionFieldsCannotMasqueradeAsQualifiedForms(){
        var r=assess("SC-MFG-ECONOMY",
                row("SF-C02","EX-MFG-04","BATCH","resource",P,n("standardQty","1",
                        "actualQty","2","standardRate","10","actualRate","12")));
        assertEquals("SYNTHETIC_PILOT_UNQUALIFIED",r.qualification());
        assertEquals(METHOD_VERSION,r.methodVersion());
    }
}
