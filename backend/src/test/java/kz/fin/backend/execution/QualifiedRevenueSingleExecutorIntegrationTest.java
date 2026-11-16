package kz.fin.backend.execution;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.domain.EconomicKernel;
import kz.fin.backend.persistence.FactAssertionJpaEntity;
import kz.fin.backend.persistence.FactAssertionJpaRepository;
import kz.fin.backend.persistence.FactSetPublicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class QualifiedRevenueSingleExecutorIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",db::getJdbcUrl);
        registry.add("spring.datasource.username",db::getUsername);
        registry.add("spring.datasource.password",db::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FactAssertionJpaRepository facts;
    @Autowired FactSetPublicationService publication;
    @Autowired SingleExecutorScheduler scheduler;
    @Autowired CompositeCoreReportPublisher reports;

    private static final EconomicKernel.ReportingBasis BASIS=
            EconomicKernel.ReportingBasis.MANAGEMENT;

    private record Fixture(UUID org,UUID entity,UUID scope,UUID revision) {}

    private Fixture seed(String methodVersion,String qualification,boolean twoMonths) {
        jdbc.update("""
            INSERT INTO fin_calc.metric_contract
              (metric_code,metric_version,measure_kind,unit_code,qualification,method_ref)
            VALUES ('REV_NET',?,'FLOW','MONEY',?,'REVENUE_HORIZONTAL_TEST')
            ON CONFLICT DO NOTHING
            """,methodVersion,qualification);
        var org=UUID.randomUUID();
        var entity=UUID.randomUUID();
        var scope=UUID.randomUUID();
        var revision=UUID.randomUUID();
        var source=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision
                (organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?,?,'DF-SAL-01','v1',now())
            """,org,source);
        jdbc.update("""
            INSERT INTO fin_fact.fact_set_revision
                (id,organization_id,status,approved_at)
            VALUES (?,?,'APPROVED',now())
            """,revision,org);
        if(twoMonths) put(org,entity,scope,revision,source,methodVersion,
                YearMonth.of(2026,1),"100");
        put(org,entity,scope,revision,source,methodVersion,
                YearMonth.of(2026,2),"150");
        publication.publish(new FactSetPublicationService.Request(
                org,entity,scope,BASIS,AnalysisContext.DataSpace.ACTUAL,revision,null));
        return new Fixture(org,entity,scope,revision);
    }

    private void put(UUID org,UUID entity,UUID scope,UUID revision,UUID source,
                     String version,YearMonth period,String amount) {
        var time=EconomicKernel.TemporalReference.interval(
            period.atDay(1),period.plusMonths(1).atDay(1));
        var key=new EconomicKernel.FactKey(org,entity,scope,"REV_NET",version,time,
                BASIS,"MONEY","KZT",new TreeMap<>());
        var assertion=UUID.randomUUID();
        facts.saveAndFlush(FactAssertionJpaEntity.from(
                new EconomicKernel.FactAssertion(assertion,key,
                    EconomicKernel.EconomicValue.present(amount),source,
                    Instant.parse("2026-11-16T00:00:00Z"))));
        String keyHash=jdbc.queryForObject(
                "SELECT fin_fact.fact_identity_hash(?)",String.class,assertion);
        jdbc.update("""
            INSERT INTO fin_fact.fact_selection
                (organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
            VALUES (?,?,?,?)
            """,org,revision,keyHash,assertion);
    }

    private void tickUntilTerminal(Fixture f) {
        for(int i=0;i<100;i++) {
            scheduler.tick();
            Long done=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.analysis_run
                WHERE organization_id=? AND fact_set_revision_id=?
                  AND run_status IN ('COMPLETED','PARTIAL','FAILED')
                """,Long.class,f.org(),f.revision());
            if(done==1L)return;
        }
        fail("TEST_ORCHESTRATION_RUN_DID_NOT_TERMINATE");
    }

    @Test void qualifiedActualRevenueRunsThroughSingleExecutorAndCoreReport() {
        var f=seed("vFIN01Qualified","QUALIFIED",true);
        tickUntilTerminal(f);
        var methods=jdbc.queryForList("""
            SELECT t.method_code,t.task_status,t.result_json::text AS output
            FROM fin_exec.analysis_task t
            JOIN fin_exec.analysis_run r ON r.id=t.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
            ORDER BY t.method_code
            """,f.org(),f.revision());
        assertEquals(2,methods.size());
        var horizontal=methods.stream().filter(m->
                QualifiedRevenueHorizontalAdapter.CODE.equals(m.get("method_code")))
                .findFirst().orElseThrow();
        assertEquals("COMPUTED",horizontal.get("task_status"));
        assertTrue(((String)horizontal.get("output")).contains("50"));
        for(int i=0;i<100;i++) {
            reports.publishReady();
            Long n=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.core_report_snapshot
                WHERE organization_id=? AND fact_set_revision_id=?
                """,Long.class,f.org(),f.revision());
            if(n==1L)break;
        }
        UUID id=jdbc.queryForObject("""
            SELECT id FROM fin_exec.core_report_snapshot
            WHERE organization_id=? AND fact_set_revision_id=?
            """,UUID.class,f.org(),f.revision());
        var result=reports.load(f.org(),id);
        assertEquals("CORE",result.path("reportMode").asText());
        assertEquals("PARTIAL",result.path("coreReadiness").asText());
        assertEquals("ACTUAL",result.path("sourceClassification").asText());
        assertEquals(2,result.path("methods").size());
        assertEquals(0,result.path("diagnosticFindings").size());
        assertTrue(result.path("methods").toString().contains("REV_MOM_PCT")==false);
        assertTrue(result.path("methods").toString().contains("FIN-01/REV_NET_MOM"));
        assertTrue(result.path("methods").toString().contains("50"));
    }

    @Test void pilotOrIncompleteActualMetricIsNotAutoQualified() {
        var pilot=seed("vFIN01Unqualified","PILOT",true);
        tickUntilTerminal(pilot);
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_task t
            JOIN fin_exec.analysis_run r ON r.id=t.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
              AND t.method_code='FIN-01/REV_NET_MOM'
            """,Long.class,pilot.org(),pilot.revision()));
        var incomplete=seed("vFIN01Qualified","QUALIFIED",false);
        tickUntilTerminal(incomplete);
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_task t
            JOIN fin_exec.analysis_run r ON r.id=t.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
              AND t.method_code='FIN-01/REV_NET_MOM'
            """,Long.class,incomplete.org(),incomplete.revision()));
    }
}
