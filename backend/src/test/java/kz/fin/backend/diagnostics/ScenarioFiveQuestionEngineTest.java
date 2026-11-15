package kz.fin.backend.diagnostics;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.diagnostics.ScenarioEconomicsEngine.*;
import kz.fin.backend.diagnostics.ScenarioFiveQuestionEngine.GateState;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Each case verifies exact source lineage + what/why/severity/action/effect. */
class ScenarioFiveQuestionEngineTest {
    private static final Scope SCOPE=new Scope(
            UUID.fromString("00000000-0000-4000-8000-000000000101"),
            UUID.fromString("00000000-0000-4000-8000-000000000102"),
            UUID.fromString("00000000-0000-4000-8000-000000000103"),
            UUID.fromString("00000000-0000-4000-8000-000000000104"),
            AnalysisContext.DataSpace.SYNTHETIC,"MANAGEMENT","CY-V1","KZT");
    private static final YearMonth OCT=YearMonth.of(2026,10);

    private record Smoke(String name,String scenario,Method method,String metric,
                         String initial,String altered,
                         Function<Boolean,List<InputRow>> fixture) {}

    private static Map<String,BigDecimal> values(String... items) {
        if(items.length%2!=0)throw new IllegalArgumentException("ODD_FIELDS");
        Map<String,BigDecimal> map=new TreeMap<>();
        for(int i=0;i<items.length;i+=2)map.put(items[i],new BigDecimal(items[i+1]));
        return map;
    }
    private static InputRow row(String form,String sheet,String key,String rowId,
                                YearMonth month,String... pairs) {
        return new InputRow(form,sheet,key,rowId,month,null,"KZT",
                sheet.equals("EX-CST-06")?"calc:smoke-evm-v1":"synthetic-source-"+rowId,
                EvidenceState.APPROVED,values(pairs));
    }
    private static InputRow dated(String form,String sheet,String key,String rowId,
                                  LocalDate date,String... pairs) {
        return new InputRow(form,sheet,key,rowId,YearMonth.from(date),date,"KZT",
                "synthetic-source-"+rowId,EvidenceState.APPROVED,values(pairs));
    }
    private static List<Smoke> cases(){
        return List.of(
            new Smoke("IP cash movement","SC-IP-HEALTH",Method.OWNER_CASH_MOVEMENT,
                    "cashMovement","300","350",changed->List.of(
                    row("DF-CSH-01","","BANK","in",OCT,"amount","900"),
                    row("DF-CSH-02","","BANK","out",OCT,"amount",changed?"550":"600"))),
            new Smoke("F01 balance equation","SC-LLC-FINANCE",Method.BALANCE_RECONCILIATION,
                    "balanceDifference","10","0",changed->List.of(
                    row("F01","","ENTITY","balance",OCT,
                            "assets","1000","liabilities","300","equity",changed?"700":"690"))),
            new Smoke("F03 cash flow bridge","SC-LLC-FINANCE",Method.CASH_FLOW_RECONCILIATION,
                    "cashDifference","-10","0",changed->List.of(
                    row("F03","","ENTITY","cashflow",OCT,
                            "openingCash","200","cfo","90","cfi","-20","cff","-10",
                            "closingCash",changed?"260":"250"))),
            new Smoke("F02 annual 3-year growth","SC-HISTORY-3Y5Y",Method.ANNUAL_REVENUE_TREND,
                    "yoyAbsolute","-11","11",changed->List.of(
                    row("F02","","ANNUAL_REVENUE","f23",YearMonth.of(2023,12),"netRevenue","100"),
                    row("F02","","ANNUAL_REVENUE","f24",YearMonth.of(2024,12),"netRevenue","110"),
                    row("F02","","ANNUAL_REVENUE","f25",YearMonth.of(2025,12),
                            "netRevenue",changed?"121":"99"))),
            new Smoke("budget plan/actual","SC-BUDGET-FPA",Method.BUDGET_ACTUAL_VARIANCE,
                    "absoluteVariance","20","10",changed->List.of(
                    row("F07","EX-BUD-02","COST_CENTER:A","budget",OCT,"plannedAmount","100"),
                    row("C09","","COST_CENTER:A","actual",OCT,"amount",changed?"110":"120"))),
            new Smoke("manufacturing WIP","SC-MFG-ECONOMY",Method.WIP_ROLLFORWARD,
                    "wipDifference","10","0",changed->List.of(
                    row("SF-C02","EX-MFG-02","ORDER-A","wip",OCT,
                        "openingWip","100","addedCosts","80","transferredOut","130",
                        "closingWip",changed?"50":"60"))),
            new Smoke("manufacturing unit cost","SC-MFG-ECONOMY",Method.UNIT_PRODUCTION_COST,
                    "unitCost","30","28",changed->List.of(
                    row("SF-C02","","ORDER-A","cost",OCT,
                        "materialUsed","110","directLabor","30","overhead",changed?"0":"10",
                        "goodUnits","5"))),
            new Smoke("manufacturing standard/actual","SC-MFG-ECONOMY",Method.STANDARD_ACTUAL_VARIANCE,
                    "totalVariance","22","10",changed->List.of(
                    row("SF-C02","EX-MFG-04","BATCH-A","std",OCT,
                        "standardQty","10","actualQty","12","standardRate","5",
                        "actualRate",changed?"5":"6"))),
            new Smoke("construction BoQ resources","SC-CONSTRUCTION-ESTIMATE",Method.BOQ_RESOURCE_COST,
                    "directCost","100","120",changed->List.of(
                    row("SF-F01","EX-CST-01","WBS-A","qty",OCT,
                        "measuredQuantity",changed?"12":"10"),
                    row("SF-F02","EX-CST-02","WBS-A","rate",OCT,
                        "quantityNorm","2","unitRate","5"))),
            new Smoke("construction earned value","SC-CONSTRUCTION-ESTIMATE",Method.CONSTRUCTION_EVM,
                    "vac","-20","10",changed->List.of(
                    row("SF-F01","EX-CST-06","WBS-A","evm",OCT,
                        "pv","100","ev","80","ac","100","bac","150",
                        "etc",changed?"40":"70","eac",changed?"140":"170"))),
            new Smoke("investment dated NPV","SC-INVESTMENT-APPRAISAL",Method.DATED_PROJECT_NPV,
                    "datedNpvApproximate","0","100",changed->List.of(
                    dated("P07","EX-INV-02","WACC","discount",
                        LocalDate.of(2026,1,1),"wacc","0.10"),
                    dated("P07","EX-INV-01","PROJECT","investment",
                        LocalDate.of(2026,1,1),"fcff","-1000"),
                    dated("P07","EX-INV-01","PROJECT","receipt",
                        LocalDate.of(2027,1,1),"fcff",changed?"1210":"1100"))),
            new Smoke("retail gross profit","SC-RETAIL-ECONOMY",Method.TRADE_GROSS_MARGIN,
                    "grossProfit","-20","10",changed->List.of(
                    row("SF-G01","","SKU-A","sale",OCT,
                        "netRevenue","100","cogs",changed?"90":"120"))),
            new Smoke("retail inventory turnover","SC-RETAIL-ECONOMY",Method.STOCK_TURNOVER,
                    "turnoverForPeriod","6","4",changed->List.of(
                    row("SF-G02","","SKU-A","stock",OCT,
                        "periodCogs","600","avgStockValue",changed?"150":"100"))),
            new Smoke("borrower DSCR pilot","SC-CREDIT-DOSSIER",Method.DSCR_PILOT,
                    "dscr","1.5","1.7",changed->List.of(
                    row("P09","","BORROWER","debt",OCT,
                        "cfads",changed?"170":"150","debtService","100")))
        );
    }
    private static Input input(Smoke smoke,boolean altered){
        return new Input(SCOPE,smoke.scenario(),smoke.fixture().apply(altered));
    }

    @TestFactory Stream<DynamicTest> numericalScenarioAndFiveQuestionGates(){
        return cases().stream().map(test->DynamicTest.dynamicTest(test.name(),()->{
            Input baseline=input(test,false), alternative=input(test,true);
            var result=ScenarioFiveQuestionEngine.evaluate(baseline,alternative,
                    test.method(),test.metric());
            assertEquals(ScenarioFiveQuestionEngine.VERSION,result.contractVersion());
            assertEquals(ScenarioEconomicsEngine.METHOD_VERSION,result.methodVersion());
            assertEquals(GateState.CALCULATED_SYNTHETIC,result.what().state());
            assertEquals(GateState.HYPOTHESIS_ONLY,result.why().state());
            assertEquals(GateState.NOT_ASSESSED,result.seriousness().state());
            assertEquals(GateState.REVIEW_REQUIRED,result.action().state());
            assertEquals(GateState.SIMULATED_ONLY,result.effect().state());
            assertTrue(result.humanApprovalRequired());
            assertFalse(result.reviewAction().isBlank());
            assertEquals(0,new BigDecimal(test.initial()).compareTo(
                    new BigDecimal(result.values().get("baseline"))),test.name());
            assertEquals(0,new BigDecimal(test.altered()).compareTo(
                    new BigDecimal(result.alternatives().get("after"))),test.name());
            BigDecimal goldenDelta=new BigDecimal(test.altered()).subtract(
                    new BigDecimal(test.initial()));
            assertEquals(0,goldenDelta.compareTo(
                    new BigDecimal(result.alternatives().get("delta"))),test.name());
            Set<String> expectedIds=new HashSet<>();
            for(InputRow source:baseline.rows())expectedIds.add(source.rowId());
            assertEquals(expectedIds,new HashSet<>(result.evidence().stream()
                    .map(ScenarioFiveQuestionEngine.Evidence::rowId).toList()),test.name());
            assertTrue(result.evidence().stream().allMatch(source->
                    source.sourceRevision().startsWith("synthetic-source-")
                    ||source.sourceRevision().startsWith("calc:smoke-evm-v1")));
            assertTrue(result.why().reason().contains("NOT_CAUSAL_EVIDENCE"));
            assertTrue(result.seriousness().reason().contains("NO_APPROVED"));
            assertTrue(result.effect().reason().contains("NO_COST_OR_FORECAST"));
        }));
    }

    @Test void everyPublishedSmokeCoverageRowMatchesExecutedGoldenInputs() throws Exception {
        var resource=ScenarioFiveQuestionEngineTest.class.getResourceAsStream(
                "/simulation/scenario-five-question-coverage.tsv");
        assertNotNull(resource);
        Map<Method,Smoke> implemented=new EnumMap<>(Method.class);
        cases().forEach(x->assertNull(implemented.put(x.method(),x)));
        Set<Method> advertised=EnumSet.noneOf(Method.class);
        try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(resource,
                java.nio.charset.StandardCharsets.UTF_8))) {
            assertEquals("method\tscenarioCode\tmetricCode\tsourceForms\textensionSheets\tstatus",
                    reader.readLine());
            String line;
            while((line=reader.readLine())!=null) {
                if(line.isBlank())continue;
                String[] cells=line.split("\\t",-1);
                assertEquals(6,cells.length,line);
                Method method=Method.valueOf(cells[0]);
                assertTrue(advertised.add(method),"duplicated "+method);
                Smoke smoke=implemented.get(method);
                assertNotNull(smoke,"coverage without golden case: "+method);
                assertEquals(smoke.scenario(),cells[1]);
                assertEquals(smoke.metric(),cells[2]);
                assertEquals("SYNTHETIC_TEST_DEFINED",cells[5]);
                List<InputRow> rows=smoke.fixture().apply(false);
                Set<String> forms=new TreeSet<>(), extensions=new TreeSet<>();
                rows.forEach(x->{
                    forms.add(x.formCode());
                    if(!x.sheetCode().isBlank())extensions.add(x.sheetCode());
                });
                assertEquals(forms,new TreeSet<>(Arrays.asList(cells[3].split(";"))),method.toString());
                assertEquals(extensions,cells[4].equals("-")?Set.of():
                    new TreeSet<>(Arrays.asList(cells[4].split(";"))),method.toString());
            }
        }
        assertEquals(implemented.keySet(),advertised);
    }

    @Test void allImplementedJavaMethodsHaveARealGoldenAndCounterfactualSmoke(){
        EnumSet<Method> expected=EnumSet.allOf(Method.class);
        expected.remove(Method.SECTOR_METHOD_QUALIFICATION);
        assertEquals(expected,new HashSet<>(cases().stream().map(Smoke::method).toList()));
        assertEquals(14,cases().size());
        assertEquals(14,new HashSet<>(cases().stream().map(Smoke::name).toList()).size());
    }
    @Test void missingEvidenceCannotBeReplacedWithAZeroOrAPositiveRiskClaim(){
        var unavailable=new Input(SCOPE,"SC-LLC-FINANCE",List.of());
        var result=ScenarioFiveQuestionEngine.evaluate(unavailable,null,
                Method.BALANCE_RECONCILIATION,"balanceDifference");
        assertEquals(GateState.NOT_COMPUTABLE,result.what().state());
        assertEquals(GateState.NOT_ASSESSED,result.seriousness().state());
        assertEquals(GateState.NOT_COMPUTABLE,result.effect().state());
        assertTrue(result.values().isEmpty());
        assertTrue(result.evidence().isEmpty());
    }
    @Test void alternativeMustStayOnExactlyTheSameScopeSourcesAndSchema(){
        Smoke s=cases().get(0);
        Input input=input(s,false), alternate=input(s,true);
        var wrongScope=new Scope(UUID.randomUUID(),SCOPE.entityId(),
                SCOPE.businessScopeId(),SCOPE.factSetRevisionId(),
                AnalysisContext.DataSpace.SYNTHETIC,"MANAGEMENT","CY-V1","KZT");
        assertThrows(IllegalArgumentException.class,()->ScenarioFiveQuestionEngine.evaluate(
                input,new Input(wrongScope,s.scenario(),alternate.rows()),s.method(),s.metric()));
        assertThrows(IllegalArgumentException.class,()->ScenarioFiveQuestionEngine.evaluate(
                input,input,s.method(),s.metric()));
        InputRow row=alternate.rows().getFirst();
        InputRow forged=new InputRow(row.formCode(),row.sheetCode(),row.itemKey(),
                "tampered-row",row.period(),row.eventDate(),row.currency(),
                row.sourceRevision(),row.state(),row.values());
        assertThrows(IllegalArgumentException.class,()->ScenarioFiveQuestionEngine.evaluate(
                input,new Input(SCOPE,s.scenario(),List.of(alternate.rows().getFirst(),forged)),
                s.method(),s.metric()));
        assertThrows(IllegalArgumentException.class,()->ScenarioFiveQuestionEngine.evaluate(
                input,new Input(SCOPE,s.scenario(),List.of(
                        new InputRow(row.formCode(),row.sheetCode(),row.itemKey(),
                            row.rowId(),row.period(),row.eventDate(),row.currency(),
                            row.sourceRevision(),row.state(),Map.of("amount",new BigDecimal("1"),
                                "extra",new BigDecimal("2"))),
                        alternate.rows().get(1))),
                s.method(),s.metric()));
    }
}
