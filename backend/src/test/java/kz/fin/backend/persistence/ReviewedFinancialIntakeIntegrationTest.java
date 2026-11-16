package kz.fin.backend.persistence;

import kz.fin.backend.domain.EconomicKernel;
import kz.fin.backend.persistence.TrustedFinancialFormMapper.Cell;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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

@SpringBootTest
@Testcontainers
class ReviewedFinancialIntakeIntegrationTest {
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
    @Autowired TrustedFinancialPopulationService population;
    @Autowired TrustedCrossFormDiagnosticService diagnostics;
    @Autowired JdbcTemplate jdbc;

    private static final YearMonth OCT=YearMonth.of(2026,10);
    private static final YearMonth SEP=YearMonth.of(2026,9);
    private record Context(UUID org,UUID entity,UUID scope) {}
    private static Context ctx(){return new Context(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());}
    private static Cell c(YearMonth month,String row,String field,String value){
        return new Cell(row,field,month,new BigDecimal(value));
    }
    private static List<Cell> f01(YearMonth month,String assets,String debt,String equity,
                                  String cash,String ar){
        return List.of(c(month,"balance","assets",assets),c(month,"balance","liabilities",debt),
                c(month,"balance","equity",equity),c(month,"balance","cash",cash),
                c(month,"balance","receivables",ar));
    }
    private static List<Cell> f02(){
        return List.of(c(OCT,"pnl","netRevenue","400"),c(OCT,"pnl","cogs","260"),
                c(OCT,"pnl","operatingExpenses","90"));
    }
    private ReviewedFinancialIntakeService.Submitted submit(Context x,String form,List<Cell> cells){
        return intake.submitManual("author",new ReviewedFinancialIntakeService.IntakeRequest(
                x.org(),x.entity(),x.scope(),form,"MANAGEMENT","KZT",1,cells));
    }
    private UUID approve(Context x,String form,List<Cell> cells){
        var s=submit(x,form,cells);
        var review=intake.review("reviewer",x.org(),s.id(),true,"Reviewed source lines");
        assertEquals("APPROVED",review.decision());
        assertEquals(cells.size(),review.mappedFacts());
        return s.id();
    }
    private static ReviewedFinancialIntakeService.PublishRequest publishRequest(
            Context x,List<UUID> sources,UUID parent){
        return new ReviewedFinancialIntakeService.PublishRequest(x.org(),x.entity(),
                x.scope(),EconomicKernel.ReportingBasis.MANAGEMENT,sources,parent);
    }

    private static List<Cell> liquidityF01(YearMonth period,String assets,
                                             String debt,String equity,
                                             String currentAssets,String currentLiabilities){
        return List.of(
            c(period,"balance","assets",assets),
            c(period,"balance","liabilities",debt),
            c(period,"balance","equity",equity),
            c(period,"balance","cash","100"),
            c(period,"balance","receivables","80"),
            c(period,"balance","currentAssets",currentAssets),
            c(period,"balance","currentLiabilities",currentLiabilities)
        );
    }

    private ReviewedFinancialIntakeService.Submitted liquiditySubmission(
            Context x,List<Cell> cells){
        return intake.submitManual("author",
            new ReviewedFinancialIntakeService.IntakeRequest(
                x.org(),x.entity(),x.scope(),"F01","MANAGEMENT","KZT",1,
                cells,"FIN-LIQUIDITY-V1"));
    }

    @Test void independentlyReviewedF01LiquidityPublishesExactlyMappedCurrentBalances(){
        var x=ctx();
        var submitted=liquiditySubmission(
            x,liquidityF01(OCT,"1000","600","400","400","200"));
        var pending=intake.read(x.org(),submitted.id());
        assertEquals("FIN-LIQUIDITY-V1",pending.formVersion());
        assertEquals("PENDING",pending.reviewStatus());
        assertThrows(IllegalArgumentException.class,
            ()->intake.review("author",x.org(),submitted.id(),true,"self approval forbidden"));
        var approved=intake.review("finance-reviewer",x.org(),submitted.id(),true,
                "Reconciled current balances to approved F01 statement");
        assertEquals("APPROVED",approved.decision());
        assertEquals(7,approved.mappedFacts());
        var published=intake.publishReviewedActual(
            publishRequest(x,List.of(submitted.id()),null));
        assertEquals(1,published.currentVersion());
        var facts=jdbc.queryForList("""
            SELECT a.metric_code,a.numeric_value,a.start_inclusive,
                   a.end_exclusive,sr.form_version,o.source_field
            FROM fin_fact.fact_selection sel
            JOIN fin_fact.fact_assertion a ON a.id=sel.fact_assertion_id
            JOIN fin_fact.fact_origin o ON o.fact_assertion_id=a.id
            JOIN fin_fact.approved_source_revision sr
                ON sr.organization_id=a.organization_id
               AND sr.source_revision_id=a.source_revision_id
            WHERE sel.organization_id=? AND sel.fact_set_revision_id=?
              AND a.metric_code IN ('CURRENT_ASSETS','CURRENT_LIABILITIES')
            ORDER BY a.metric_code
            """,x.org(),published.revisionId());
        assertEquals(2,facts.size());
        assertEquals("CURRENT_ASSETS",facts.get(0).get("metric_code"));
        assertEquals(0,new BigDecimal("400").compareTo(
            (BigDecimal)facts.get(0).get("numeric_value")));
        assertEquals("currentAssets",facts.get(0).get("source_field"));
        assertEquals("CURRENT_LIABILITIES",facts.get(1).get("metric_code"));
        assertEquals(0,new BigDecimal("200").compareTo(
            (BigDecimal)facts.get(1).get("numeric_value")));
        assertEquals("currentLiabilities",facts.get(1).get("source_field"));
        assertEquals("FIN-LIQUIDITY-V1",facts.get(1).get("form_version"));
        assertEquals(OCT.atEndOfMonth().toString(),
            facts.get(0).get("start_inclusive").toString());
        assertThrows(IllegalArgumentException.class,
            ()->intake.read(UUID.randomUUID(),submitted.id()));
    }

    @Test void f01LiquidityIntakeRejectsMissingOrMisclassifiedCurrentBalances(){
        var x=ctx();
        var missing=liquidityF01(OCT,"1000","600","400","400","200")
                .stream().filter(f->!f.sourceField().equals("currentLiabilities")).toList();
        assertThrows(IllegalArgumentException.class,()->liquiditySubmission(x,missing));
        assertThrows(IllegalArgumentException.class,()->liquiditySubmission(x,
            liquidityF01(OCT,"1000","600","400","1001","200")));
        assertThrows(IllegalArgumentException.class,()->liquiditySubmission(x,
            liquidityF01(OCT,"1000","600","400","400","601")));
        assertThrows(IllegalArgumentException.class,()->liquiditySubmission(x,
            liquidityF01(OCT,"1000","600","400","-1","200")));
        assertThrows(IllegalArgumentException.class,()->intake.submitManual("author",
            new ReviewedFinancialIntakeService.IntakeRequest(
                x.org(),x.entity(),x.scope(),"F02","MANAGEMENT","KZT",1,
                f02(),"FIN-LIQUIDITY-V1")));
    }

    @Test void independentReviewerPublishesActualFactsButUnqualifiedDiagnosticStaysBlocked(){
        var x=ctx();
        var sources=List.of(
                approve(x,"F01",f01(SEP,"900","320","580","100","50")),
                approve(x,"F01",f01(OCT,"1000","350","650","120","80")),
                approve(x,"F02",f02()),
                approve(x,"F03",List.of(c(OCT,"cf","operatingCash","35"),
                        c(OCT,"cf","investingCash","-10"),c(OCT,"cf","financingCash","-5"))),
                approve(x,"DF-CSH-01",List.of(c(OCT,"receipt1","receipt","160"),
                        c(OCT,"receipt2","receipt","140"))),
                approve(x,"DF-CSH-02",List.of(c(OCT,"payment1","payment","280")))
        );
        var published=intake.publishReviewedActual(publishRequest(x,sources,null));
        assertEquals(1,published.currentVersion());
        assertEquals(64,published.snapshotHash().length());
        assertEquals("ACTUAL",jdbc.queryForObject(
                "SELECT data_space FROM fin_fact.fact_set_publication WHERE organization_id=? AND fact_set_revision_id=?",
                String.class,x.org(),published.revisionId()));
        assertEquals(19L,jdbc.queryForObject(
                "SELECT count(*) FROM fin_fact.fact_selection WHERE organization_id=? AND fact_set_revision_id=?",
                Long.class,x.org(),published.revisionId()));
        assertThrows(IllegalArgumentException.class,
                ()->diagnostics.evaluate(x.org(),published.revisionId(),OCT));
        assertThrows(IllegalArgumentException.class,
                ()->population.requireComplete(x.org(),published.revisionId(),
                List.of(new TrustedFinancialPopulationService.RequiredGroup(sources.get(2),
                    "REV_NET",OCT.atDay(1),OCT.plusMonths(1).atDay(1)))));
    }

    @Test void ownSubmissionCannotBeApprovedAndUnreviewedFactsCannotPublish(){
        var x=ctx();var s=submit(x,"F02",f02());
        assertEquals("PENDING",intake.read(x.org(),s.id()).reviewStatus());
        assertThrows(IllegalArgumentException.class,
                ()->intake.review("author",x.org(),s.id(),true,"Self approved"));
        assertThrows(IllegalArgumentException.class,
                ()->intake.publishReviewedActual(publishRequest(x,List.of(s.id()),null)));
        assertEquals(0L,jdbc.queryForObject(
                "SELECT count(*) FROM fin_fact.fact_set_publication WHERE organization_id=?",
                Long.class,x.org()));
    }

    @Test void rejectedSourcesAndForeignTenantAreNotTrusted(){
        var x=ctx();var s=submit(x,"F02",f02());
        assertEquals("REJECTED",intake.review(
                "reviewer",x.org(),s.id(),false,"Source unsupported").decision());
        assertThrows(IllegalArgumentException.class,
                ()->intake.publishReviewedActual(publishRequest(x,List.of(s.id()),null)));
        assertThrows(IllegalArgumentException.class,
                ()->intake.read(UUID.randomUUID(),s.id()));
        assertThrows(IllegalArgumentException.class,
                ()->intake.review("reviewer",UUID.randomUUID(),s.id(),true,"Wrong tenant"));
    }

    @Test void approvedDecisionIsImmutableAndCannotBeReassigned(){
        var x=ctx();var id=approve(x,"F02",f02());
        assertThrows(IllegalArgumentException.class,
                ()->intake.review("other-reviewer",x.org(),id,false,"Change"));
        assertThrows(DataAccessException.class,()->jdbc.update(
                "UPDATE fin_intake.review SET decision='REJECTED' WHERE submission_id=?",
                id));
        assertEquals("APPROVED",intake.read(x.org(),id).reviewStatus());
    }

    @Test void unbalancedOrIncompleteStatementCannotBeSubmitted(){
        var x=ctx();
        assertThrows(IllegalArgumentException.class,
                ()->submit(x,"F01",List.of(c(OCT,"balance","assets","100"))));
        assertThrows(IllegalArgumentException.class,
                ()->submit(x,"F01",f01(OCT,"101","40","60","10","5")));
        assertThrows(IllegalArgumentException.class,
                ()->submit(x,"F03",List.of(c(OCT,"cf","operatingCash","10"))));
        assertThrows(IllegalArgumentException.class,
                ()->submit(x,"D01",f01(OCT,"100","40","60","10","5")));
    }

    @Test void aReplacementCannotSilentlyLosePreviouslyApprovedMonthsOrCells(){
        var x=ctx();
        UUID older=approve(x,"F01",f01(SEP,"900","320","580","100","50"));
        UUID newer=approve(x,"F02",f02());
        var first=intake.publishReviewedActual(publishRequest(x,List.of(older,newer),null));
        assertThrows(IllegalArgumentException.class,()->intake.publishReviewedActual(
                publishRequest(x,List.of(newer),first.revisionId())));
        assertEquals(first.revisionId(),jdbc.queryForObject("""
            SELECT fact_set_revision_id FROM fin_fact.current_fact_set
            WHERE organization_id=? AND economic_entity_id=? AND business_scope_id=?
            AND data_space='ACTUAL'
            """,UUID.class,x.org(),x.entity(),x.scope()));
        var complete=intake.publishReviewedActual(publishRequest(
                x,List.of(older,newer),first.revisionId()));
        assertEquals(2,complete.currentVersion());
        assertEquals(8L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_fact.fact_selection
            WHERE organization_id=? AND fact_set_revision_id=?
            """,Long.class,x.org(),complete.revisionId()));
    }

    @Test void sourceFingerprintIsManualNotFabricatedRawXlsxHash(){
        var x=ctx();var submitted=submit(x,"F02",f02());
        var info=intake.read(x.org(),submitted.id());
        assertEquals("MANUAL",info.sourceKind());
        assertEquals(3,info.cells().size());
        assertEquals(submitted.sourceDocumentHash(),info.sourceDocumentHash());
        assertEquals(64,submitted.fingerprint().length());
    }

    @Test void optimisticVersionAndCrossTenantPublicationAreEnforced(){
        var x=ctx();UUID id=approve(x,"F02",f02());
        var published=intake.publishReviewedActual(publishRequest(x,List.of(id),null));
        assertThrows(RuntimeException.class,
                ()->intake.publishReviewedActual(publishRequest(x,List.of(id),null)));
        var foreign=new Context(UUID.randomUUID(),x.entity(),x.scope());
        assertThrows(IllegalArgumentException.class,
                ()->intake.publishReviewedActual(publishRequest(foreign,List.of(id),null)));
        assertNotNull(published.revisionId());
    }
}
