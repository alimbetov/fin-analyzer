package kz.fin.backend.diagnostics;

import kz.fin.backend.analytics.AnalysisContext;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.*;

class CrossFormDiagnosticEngineTest {
    private static final YearMonth MONTH=YearMonth.of(2026,10);
    private static Scope scope(){
        return new Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),AnalysisContext.DataSpace.SYNTHETIC,
                "KZT","MANAGEMENT",MONTH);
    }
    private static Evidence evidence(String metric,TimeRole role,String amount,String form) {
        YearMonth period=role==TimeRole.PRIOR_STOCK?MONTH.minusMonths(1):MONTH;
        return new Evidence(new MetricKey(metric,role),period,new BigDecimal(amount),"KZT",
                List.of(new Source(form,UUID.randomUUID(),"row","field",UUID.randomUUID())));
    }
    private static Evidence stock(String metric,String amount){return evidence(metric,TimeRole.CURRENT_STOCK,amount,"F01");}
    private static Evidence oldStock(String metric,String amount){return evidence(metric,TimeRole.PRIOR_STOCK,amount,"F01");}
    private static Evidence flow(String metric,String amount,String form){return evidence(metric,TimeRole.CURRENT_FLOW,amount,form);}
    private static List<Evidence> fullyReconciled(){
        return List.of(stock("ASSETS","1000"),stock("LIABILITIES","350"),stock("EQUITY","650"),
                oldStock("CASH_END","100"),stock("CASH_END","120"),
                oldStock("AR_END","50"),stock("AR_END","80"),
                flow("REV_NET","400","F02"),flow("COGS","260","F02"),flow("OPEX","90","F02"),
                flow("CFO","35","F03"),flow("CFI","-10","F03"),flow("CFF","-5","F03"),
                flow("CASH_IN","300","DF-CSH-01"),flow("CASH_OUT","280","DF-CSH-02"));
    }
    private static Finding byCode(Report report,String code){
        return report.findings().stream().filter(f->f.ruleCode().equals(code)).findFirst().orElseThrow();
    }
    @Test void financialEvidenceFromFiveFormsMustProduceSixLinkedDiagnosticFindings(){
        Report result=evaluate(scope(),fullyReconciled());
        assertEquals(6,result.findings().size());
        assertEquals(Verdict.RECONCILED,byCode(result,"BALANCE_IDENTITY").verdict());
        assertEquals(0,new BigDecimal("0").compareTo(
                byCode(result,"BALANCE_IDENTITY").computed().get("balanceDifference")));
        assertEquals(Verdict.RECONCILED,byCode(result,"CASH_ROLLFORWARD").verdict());
        assertEquals(0,new BigDecimal("120").compareTo(
                byCode(result,"CASH_ROLLFORWARD").computed().get("expectedClosingCash")));
        assertEquals(0,new BigDecimal("0").compareTo(
                byCode(result,"CASH_LEDGER_CASHFLOW_BRIDGE").computed().get("ledgerCashflowDifference")));
        // Scope of all cash receipts vs F03 cash-equivalents is not independently reconciled.
        assertEquals(Verdict.REVIEW,byCode(result,"CASH_LEDGER_CASHFLOW_BRIDGE").verdict());
        assertEquals(Verdict.OBSERVATION,byCode(result,"OPERATING_RESULT_TO_CFO").verdict());
        assertEquals(0,new BigDecimal("-15").compareTo(
                byCode(result,"OPERATING_RESULT_TO_CFO").computed().get("cfoDifferenceToOperatingResult")));
        assertEquals(Verdict.OBSERVATION,byCode(result,"RECEIVABLES_CASH_STRESS").verdict());
        assertEquals(0,new BigDecimal("140").compareTo(
                byCode(result,"GROSS_MARGIN").computed().get("grossProfit")));
        assertEquals(0,new BigDecimal("0.35").compareTo(
                byCode(result,"GROSS_MARGIN").computed().get("grossMarginRatio")));
        assertEquals(3,byCode(result,"BALANCE_IDENTITY").sources().size());
    }
    @Test void missingRelatedFormReturnsMissingEvidenceRatherThanFalseZero(){
        Report report=evaluate(scope(),List.of(flow("REV_NET","400","F02"),flow("COGS","260","F02")));
        Finding margin=byCode(report,"GROSS_MARGIN");
        assertEquals(Verdict.OBSERVATION,margin.verdict());
        Finding cash=byCode(report,"CASH_ROLLFORWARD");
        assertEquals(Verdict.MISSING_EVIDENCE,cash.verdict());
        assertTrue(cash.missing().contains(new MetricKey("CFO",TimeRole.CURRENT_FLOW)));
        assertTrue(cash.computed().isEmpty());
        assertEquals(Verdict.MISSING_EVIDENCE,byCode(report,"BALANCE_IDENTITY").verdict());
    }
    @Test void accountingMismatchIsFlaggedAndNeverSilentlyAdjusted(){
        List<Evidence> e=new ArrayList<>(fullyReconciled());
        e.removeIf(x->x.key().equals(new MetricKey("CASH_END",TimeRole.CURRENT_STOCK)));
        e.add(stock("CASH_END","125"));
        Report report=evaluate(scope(),e);
        assertEquals(Verdict.DEVIATION,byCode(report,"CASH_ROLLFORWARD").verdict());
        assertEquals(0,new BigDecimal("5").compareTo(
                byCode(report,"CASH_ROLLFORWARD").computed().get("cashRollforwardDifference")));
    }
    @Test void currencyAndPeriodAndDuplicateSlotsRejectCrossFormContamination(){
        List<Evidence> e=new ArrayList<>(fullyReconciled());
        e.add(e.getFirst());
        assertThrows(IllegalArgumentException.class,()->evaluate(scope(),e));
        Evidence wrong=new Evidence(new MetricKey("REV_NET",TimeRole.CURRENT_FLOW),MONTH.minusMonths(1),
                new BigDecimal("400"),"KZT",List.of(new Source("F02",UUID.randomUUID(),"r","f",UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class,()->evaluate(scope(),List.of(wrong)));
        Evidence usd=new Evidence(new MetricKey("REV_NET",TimeRole.CURRENT_FLOW),MONTH,
                new BigDecimal("400"),"USD",List.of(new Source("F02",UUID.randomUUID(),"r","f",UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class,()->evaluate(scope(),List.of(usd)));
        Scope actual=new Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                AnalysisContext.DataSpace.ACTUAL,"KZT","MANAGEMENT",MONTH);
        assertThrows(IllegalArgumentException.class,()->evaluate(actual,List.of()));
    }
    @Test void receivablesGrowthWithNegativeCfoIsOnlyHypothesisSignal(){
        List<Evidence> data=new ArrayList<>(fullyReconciled());
        data.removeIf(x->x.key().equals(new MetricKey("CFO",TimeRole.CURRENT_FLOW)));
        data.add(flow("CFO","-25","F03"));
        var finding=byCode(evaluate(scope(),data),"RECEIVABLES_CASH_STRESS");
        assertEquals(Verdict.REVIEW,finding.verdict());
        assertEquals("AR_GROWTH_WITH_NEGATIVE_CFO_HYPOTHESIS_ONLY",finding.reasonCode());
    }
}
