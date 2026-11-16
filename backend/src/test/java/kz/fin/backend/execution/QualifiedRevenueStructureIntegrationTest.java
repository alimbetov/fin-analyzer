package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent economic goldens: 200 + 800 = 1000 -> 20% / 80%.
 * Mismatch, missing total, and unqualified revenue must be fail-closed.
 * No hypothetical denominator is constructed by the production adapter.
 */
@SpringBootTest
@Testcontainers
class QualifiedRevenueStructureIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FactAssertionJpaRepository facts;
    @Autowired FactSetPublicationService publication;
    @Autowired SingleExecutorScheduler scheduler;
    @Autowired CompositeCoreReportPublisher coreReports;
    @Autowired ObjectMapper mapper;

    private static final EconomicKernel.ReportingBasis BASIS=
        EconomicKernel.ReportingBasis.MANAGEMENT;
    private static final YearMonth FEB=YearMonth.of(2026,2);
    private record Fixture(UUID org,UUID entity,UUID scope,UUID revision){}

    private Fixture seed(String version,String qualification,
                         boolean hasTotal,String totalValue,String secondCategory) {
        jdbc.update("""
            INSERT INTO fin_calc.metric_contract
                (metric_code,metric_version,measure_kind,unit_code,qualification,method_ref)
            VALUES ('REV_NET',?,'FLOW','MONEY',?,'FIN02_TEST_ONLY')
            ON CONFLICT DO NOTHING
            """,version,qualification);
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),
             scope=UUID.randomUUID(),revision=UUID.randomUUID(),source=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision
                (organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?,?,'DF-SAL-01','v1',now())
            """,org,source);
        jdbc.update("""
            INSERT INTO fin_fact.fact_set_revision
                (id,organization_id,status,approved_at)
            VALUES (?,?, 'APPROVED',now())
            """,revision,org);
        if(hasTotal) add(org,entity,scope,revision,source,version,
            new TreeMap<>(),totalValue);
        add(org,entity,scope,revision,source,version,
            new TreeMap<>(Map.of("category","A")),"200");
        add(org,entity,scope,revision,source,version,
            new TreeMap<>(Map.of("category","B")),secondCategory);
        publication.publish(new FactSetPublicationService.Request(
            org,entity,scope,BASIS,AnalysisContext.DataSpace.ACTUAL,revision,null));
        return new Fixture(org,entity,scope,revision);
    }

    private void add(UUID org,UUID entity,UUID scope,UUID revision,UUID source,
                     String version,SortedMap<String,String> dimension,String number) {
        var time=EconomicKernel.TemporalReference.interval(
            FEB.atDay(1),FEB.plusMonths(1).atDay(1));
        var key=new EconomicKernel.FactKey(org,entity,scope,"REV_NET",version,
            time,BASIS,"MONEY","KZT",dimension);
        UUID id=UUID.randomUUID();
        facts.saveAndFlush(FactAssertionJpaEntity.from(new EconomicKernel.FactAssertion(
            id,key,EconomicKernel.EconomicValue.present(number),source,
            Instant.parse("2026-11-16T00:00:00Z"))));
        String fingerprint=jdbc.queryForObject(
            "SELECT fin_fact.fact_identity_hash(?)",String.class,id);
        jdbc.update("""
            INSERT INTO fin_fact.fact_selection
                (organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
            VALUES (?,?,?,?)
            """,org,revision,fingerprint,id);
    }

    private void run(Fixture f) {
        for(int i=0;i<30;i++) {
            scheduler.tick();
            Long completed=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.analysis_run
                WHERE organization_id=? AND fact_set_revision_id=?
                  AND run_status IN ('COMPLETED','PARTIAL','FAILED')
                """,Long.class,f.org(),f.revision());
            if(completed==1L) return;
        }
        fail("SINGLE_EXECUTOR_NOT_TERMINAL");
    }

    private long scheduledCount(Fixture f) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_task t
            JOIN fin_exec.analysis_run r ON r.id=t.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
              AND t.method_code='FIN-02/REV_CATEGORY_SHARE'
            """,Long.class,f.org(),f.revision());
    }

    @Test void qualifiedPopulationCalculatesExactSharesAndPublishesCore() throws Exception {
        var f=seed("vFin02Good","QUALIFIED",true,"1000","800");
        run(f);
        assertEquals(1L,scheduledCount(f));
        String result=jdbc.queryForObject("""
            SELECT t.result_json::text FROM fin_exec.analysis_task t
            JOIN fin_exec.analysis_run r ON r.id=t.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
              AND t.method_code='FIN-02/REV_CATEGORY_SHARE' AND t.task_status='COMPUTED'
            """,String.class,f.org(),f.revision());
        var j=mapper.readTree(result);
        assertEquals("CALCULATED",j.path("status").asText());
        assertEquals("REV_NET_APPROVED_UNDIMENSIONED_TOTAL",j.path("denominator").asText());
        assertEquals(0,new BigDecimal("1000").compareTo(
            new BigDecimal(j.path("denominatorValue").asText())));
        assertEquals(0,new BigDecimal("20").compareTo(
            new BigDecimal(j.path("categoryShares").path("A").asText())));
        assertEquals(0,new BigDecimal("80").compareTo(
            new BigDecimal(j.path("categoryShares").path("B").asText())));

        for(int i=0;i<30;i++) {
            coreReports.publishReady();
            Long n=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.core_report_snapshot
                WHERE organization_id=? AND fact_set_revision_id=?
                """,Long.class,f.org(),f.revision());
            if(n==1L) break;
        }
        UUID report=jdbc.queryForObject("""
            SELECT id FROM fin_exec.core_report_snapshot
            WHERE organization_id=? AND fact_set_revision_id=?
            """,UUID.class,f.org(),f.revision());
        var core=coreReports.load(f.org(),report);
        assertEquals("PARTIAL",core.path("coreReadiness").asText());
        assertEquals("NOT_REQUESTED",core.path("mlReadiness").asText());
        assertTrue(core.path("methods").toString().contains("FIN-02/REV_CATEGORY_SHARE"));
        assertTrue(core.path("methods").toString().contains("20"));
        assertEquals("CORE_REPORT_NOT_FOUND_OR_FORBIDDEN",
            assertThrows(IllegalArgumentException.class,()->
                coreReports.load(UUID.randomUUID(),report)).getMessage());
        assertThrows(org.springframework.dao.DataAccessException.class,()->
            jdbc.update("""
                UPDATE fin_exec.core_report_snapshot
                SET snapshot='{}'::jsonb WHERE id=?
                """,report));
        for(int i=0;i<3;i++) coreReports.publishReady();
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.core_report_snapshot
            WHERE organization_id=? AND fact_set_revision_id=?
            """,Long.class,f.org(),f.revision()));
    }

    @Test void missingTotalAndReconciliationMismatchAreNotAutoScheduled() {
        var missing=seed("vFin02Missing","QUALIFIED",false,"1000","800");
        run(missing);
        assertEquals(0L,scheduledCount(missing));
        var mismatch=seed("vFin02Mismatch","QUALIFIED",true,"990","800");
        run(mismatch);
        assertEquals(0L,scheduledCount(mismatch));
    }

    @Test void unqualifiedOrNegativeSourceCannotRunForActualCompany() {
        var pilot=seed("vFin02Pilot","PILOT",true,"1000","800");
        run(pilot);
        assertEquals(0L,scheduledCount(pilot));
        var negative=seed("vFin02Negative","QUALIFIED",true,"1000","-800");
        run(negative);
        assertEquals(0L,scheduledCount(negative));
    }
}
