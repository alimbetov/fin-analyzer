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
