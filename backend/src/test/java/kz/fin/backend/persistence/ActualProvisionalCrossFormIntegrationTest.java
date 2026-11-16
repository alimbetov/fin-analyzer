package kz.fin.backend.persistence;

import kz.fin.backend.domain.EconomicKernel;
import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine;
import kz.fin.backend.persistence.TrustedFinancialFormMapper.Cell;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="fin.diagnostics.actual-preview-enabled=true")
@Testcontainers
class ActualProvisionalCrossFormIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired ReviewedFinancialIntakeService intake;
    @Autowired TrustedCrossFormDiagnosticService diagnostic;
    @Autowired JdbcTemplate jdbc;
    private static final YearMonth OCT=YearMonth.of(2026,10),SEP=YearMonth.of(2026,9);
    private record C(UUID org,UUID entity,UUID scope) {}
    private static C c(){return new C(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());}
    private static Cell f(YearMonth month,String row,String field,String value) {
        return new Cell(row,field,month,new BigDecimal(value));
    }
    private UUID reviewed(C ctx,String form,List<Cell> cells) {
        UUID id=intake.submitManual("separate-author",
                new ReviewedFinancialIntakeService.IntakeRequest(ctx.org(),ctx.entity(),ctx.scope(),
                        form,"MANAGEMENT","KZT",1,cells)).id();
        intake.review("independent-reviewer",ctx.org(),id,true,"Reconciled source evidence");
        return id;
    }
    private static List<Cell> balance(YearMonth month,String assets,String liabilities,
                                    String equity,String cash,String receivables){
        return List.of(f(month,"balance","assets",assets),
                f(month,"balance","liabilities",liabilities),
                f(month,"balance","equity",equity),f(month,"balance","cash",cash),
                f(month,"balance","receivables",receivables));
    }
    private UUID publication(C ctx,boolean includeCashDetails){
        List<UUID> reviewed=new ArrayList<>();
        reviewed.add(reviewed(ctx,"F01",balance(SEP,"900","320","580","100","50")));
        reviewed.add(reviewed(ctx,"F01",balance(OCT,"1000","350","650","120","80")));
        reviewed.add(reviewed(ctx,"F02",List.of(f(OCT,"pnl","netRevenue","400"),
                f(OCT,"pnl","cogs","260"),f(OCT,"pnl","operatingExpenses","90"))));
        reviewed.add(reviewed(ctx,"F03",List.of(f(OCT,"cf","operatingCash","35"),
                f(OCT,"cf","investingCash","-10"),f(OCT,"cf","financingCash","-5"))));
        if(includeCashDetails){
            reviewed.add(reviewed(ctx,"DF-CSH-01",List.of(
                    f(OCT,"receipt1","receipt","160"),f(OCT,"receipt2","receipt","140"))));
            reviewed.add(reviewed(ctx,"DF-CSH-02",List.of(f(OCT,"payment1","payment","280"))));
        }
        return intake.publishReviewedActual(new ReviewedFinancialIntakeService.PublishRequest(
                ctx.org(),ctx.entity(),ctx.scope(),EconomicKernel.ReportingBasis.MANAGEMENT,
                reviewed,null)).revisionId();
    }
    @Test void completeManualActualStatementsProduceSixExplanatoryButUnqualifiedFindings(){
        var ctx=c();UUID revision=publication(ctx,true);
        var report=diagnostic.evaluateProvisionalActual(ctx.org(),revision,OCT);
        assertEquals("ACTUAL_PREVIEW_UNQUALIFIED_NO_CAUSAL_CLAIMS",report.qualification());
        assertEquals(6,report.findings().size());
        var balance=report.findings().stream().filter(x->x.ruleCode().equals("BALANCE_IDENTITY"))
                .findFirst().orElseThrow();
        assertEquals(CrossFormDiagnosticEngine.Verdict.RECONCILED,balance.verdict());
        assertEquals(0,BigDecimal.ZERO.compareTo(balance.computed().get("balanceDifference")));
        assertEquals(3,balance.sources().size());
        var cash=report.findings().stream().filter(x->x.ruleCode().equals("CASH_ROLLFORWARD"))
                .findFirst().orElseThrow();
        assertEquals(CrossFormDiagnosticEngine.Verdict.RECONCILED,cash.verdict());
        assertEquals(0,new BigDecimal("120").compareTo(cash.computed().get("expectedClosingCash")));
        assertTrue(report.findings().stream().allMatch(x->
                x.qualification().equals("ACTUAL_PROVISIONAL_METHOD_S2_HOLD")));
        var first=diagnostic.assessAndPublishProvisionalActual(ctx.org(),revision,OCT);
        var second=diagnostic.assessAndPublishProvisionalActual(ctx.org(),revision,OCT);
        assertFalse(first.reused());assertTrue(second.reused());
        assertEquals(first.id(),second.id());
        var persisted=diagnostic.loadProvisionalActual(ctx.org(),first.id());
        assertEquals("CROSS_FORM_ACTUAL_PREVIEW",persisted.snapshot().path("ruleSetCode").asText());
        assertEquals("ACTUAL_PREVIEW_UNQUALIFIED_NO_CAUSAL_CLAIMS",
                persisted.snapshot().path("qualification").asText());
        assertEquals(6,persisted.snapshot().path("findings").size());
        assertEquals(64,persisted.snapshotHash().length());
        assertThrows(IllegalArgumentException.class,
                ()->diagnostic.loadProvisionalActual(UUID.randomUUID(),first.id()));
        assertThrows(IllegalArgumentException.class,
                ()->diagnostic.evaluate(ctx.org(),revision,OCT));
    }
    @Test void aMissingApprovedFormIsExplicitlyMarkedAndNeverInferredAsZero(){
        var ctx=c();UUID revision=publication(ctx,false);
        var report=diagnostic.evaluateProvisionalActual(ctx.org(),revision,OCT);
        var missing=report.findings().stream()
                .filter(x->x.ruleCode().equals("CASH_LEDGER_CASHFLOW_BRIDGE")).findFirst().orElseThrow();
        assertEquals(CrossFormDiagnosticEngine.Verdict.MISSING_EVIDENCE,missing.verdict());
        assertTrue(missing.computed().isEmpty());
        assertTrue(missing.missing().stream().anyMatch(x->x.code().equals("CASH_IN")));
    }
}
