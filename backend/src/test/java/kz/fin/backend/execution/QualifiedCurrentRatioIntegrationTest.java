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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"fin.methods.fin04.enabled=true","fin.execution.enabled=false"})
@Testcontainers
class QualifiedCurrentRatioIntegrationTest {
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
    @Autowired FactAssertionJpaRepository assertions;
    @Autowired FactSetPublicationService publication;
    @Autowired SingleExecutorScheduler scheduler;
    @Autowired CompositeCoreReportPublisher coreReports;
    @Autowired ObjectMapper json;

    private static final LocalDate AS_OF=LocalDate.of(2026,2,28);
    private static final EconomicKernel.ReportingBasis BASIS=
        EconomicKernel.ReportingBasis.MANAGEMENT;
    private static final String DOC_HASH="b".repeat(64);
    private record Fixture(UUID org,UUID entity,UUID scope,UUID revision){}

    private Fixture seed(String assets,
                         String liabilities,boolean origins,boolean sameDate,
                         boolean sameCurrency,boolean useCurrentFields) {
        return seed(assets,liabilities,origins,sameDate,sameCurrency,useCurrentFields,false);
    }

    private Fixture seed(String assets,
                         String liabilities,boolean origins,boolean sameDate,
                         boolean sameCurrency,boolean useCurrentFields,
                         boolean additionalUnqualifiedFact) {
        for(String metric:List.of("CURRENT_ASSETS","CURRENT_LIABILITIES")) {
            jdbc.update("""
                INSERT INTO fin_calc.metric_contract
                 (metric_code,metric_version,measure_kind,unit_code,qualification,method_ref)
                VALUES (?,'1.0.0','STOCK','MONEY','QUALIFIED','FIN04_TEST')
                ON CONFLICT DO NOTHING
                """,metric);
        }
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),
             scope=UUID.randomUUID(),revision=UUID.randomUUID(),source=UUID.randomUUID();
        String formVersion=useCurrentFields?"FIN-LIQUIDITY-V1":"FIN-PILOT-V1";
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision
             (organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?,?,'F01',?,now())
            """,org,source,formVersion);
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_intake
             (organization_id,source_revision_id,normalized_cell_fingerprint,
              source_document_hash,input_currency,amount_scale,
              expected_cell_count,reviewed_by,reviewed_at)
            VALUES (?,?,?,?,'KZT',1,?,'FIN04_TEST_REVIEWER',now())
            """,org,source,"a".repeat(64),DOC_HASH,
                additionalUnqualifiedFact ? 3 : 2);
        jdbc.update("""
            INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at)
            VALUES (?,?,'APPROVED',now())
            """,revision,org);
        insertBalance(org,entity,scope,revision,source,"1.0.0",
            useCurrentFields?"CURRENT_ASSETS":"ASSETS",
            "currentAssets","assets",assets,AS_OF,"KZT",origins);
        insertBalance(org,entity,scope,revision,source,contractVersion,
            useCurrentFields?"CURRENT_LIABILITIES":"LIABILITIES",
            "currentLiabilities","liabilities",liabilities,
            sameDate?AS_OF:AS_OF.minusDays(1),sameCurrency?"KZT":"USD",origins);
        if(additionalUnqualifiedFact)
            insertBalance(org,entity,scope,revision,source,"1.0.0",
                "UNRELATED_F01_TOTAL","notMapped","notMapped",
                "999",AS_OF,"KZT",false);
        publication.publish(new FactSetPublicationService.Request(
            org,entity,scope,BASIS,AnalysisContext.DataSpace.ACTUAL,revision,null));
        return new Fixture(org,entity,scope,revision);
    }

    private void insertBalance(UUID org,UUID entity,UUID scope,UUID revision,
                               UUID source,String version,String metric,
                               String currentField,String totalField,String number,
                               LocalDate date,String currency,boolean origins) {
        // This fixture can explicitly exercise forbidden F01 TOTAL fields.
        var key=new EconomicKernel.FactKey(org,entity,scope,metric,version,
            EconomicKernel.TemporalReference.asOf(date),
            BASIS,"MONEY",currency,new TreeMap<>());
        UUID fact=UUID.randomUUID();
        assertions.saveAndFlush(FactAssertionJpaEntity.from(new EconomicKernel.FactAssertion(
            fact,key,EconomicKernel.EconomicValue.present(number),source,
            Instant.parse("2026-11-16T00:00:00Z"))));
        String fingerprint=jdbc.queryForObject(
            "SELECT fin_fact.fact_identity_hash(?)",String.class,fact);
        jdbc.update("""
            INSERT INTO fin_fact.fact_selection
             (organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
            VALUES (?,?,?,?)
            """,org,revision,fingerprint,fact);
        if(origins) {
            jdbc.update("""
                INSERT INTO fin_fact.fact_origin
                 (fact_assertion_id,organization_id,source_revision_id,source_row_id,source_field)
                VALUES (?,?,?,?,?)
                """,fact,org,source,"F01-LINE-"+metric,
                metric.startsWith("CURRENT_")?currentField:totalField);
            jdbc.update("""
                INSERT INTO fin_fact.source_population_control
                 (organization_id,source_revision_id,metric_code,time_start,time_end,
                  expected_rows,expected_total,source_document_hash)
                VALUES (?,?,?,?,?,1,?,?)
                """,org,source,metric,date,date.plusDays(1),new BigDecimal(number),DOC_HASH);
        }
    }

    private void executeToTerminal(Fixture f) {
        for(int n=0;n<50;n++) {
            scheduler.tick();
            Long ended=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.analysis_run
                WHERE organization_id=? AND fact_set_revision_id=?
                  AND run_status IN ('COMPLETED','PARTIAL','FAILED')
                """,Long.class,f.org(),f.revision());
            if(ended==1L)return;
        }
        fail("FIN04_SCHEDULER_NOT_TERMINAL");
    }

    private String result(Fixture f) {
        var rows=jdbc.query("""
            SELECT t.result_json::text
            FROM fin_exec.analysis_task t JOIN fin_exec.analysis_run r ON r.id=t.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
              AND t.method_code='FIN-04/CURRENT_RATIO'
              AND t.task_status='COMPUTED'
            """,(rs,i)->rs.getString(1),f.org(),f.revision());
        return rows.isEmpty()?null:rows.getFirst();
    }

    private long scheduled(Fixture f) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_task t
            JOIN fin_exec.analysis_run r ON r.id=t.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
              AND t.method_code='FIN-04/CURRENT_RATIO'
            """,Long.class,f.org(),f.revision());
    }

    @Test void approvedCurrentBalancesProduceTwoPointZeroAndImmutableCore() throws Exception {
        var f=seed("400","200",true,true,true,true);
        executeToTerminal(f);
        assertEquals(1L,scheduled(f));
        var r=json.readTree(result(f));
        assertEquals("CALCULATED",r.path("status").asText());
        assertEquals("RATIO",r.path("unit").asText());
        assertEquals("2026-02-28",r.path("asOfDate").asText());
        assertEquals(0,new BigDecimal("2.0000").compareTo(
            new BigDecimal(r.path("value").asText())));
        assertEquals("METHOD_FAMILY_NOT_YET_APPROVED",
            r.path("qualification").asText());

        for(int i=0;i<30;i++) {
            coreReports.publishReady();
            Long n=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.core_report_snapshot
                WHERE organization_id=? AND fact_set_revision_id=?
                """,Long.class,f.org(),f.revision());
            if(n==1L)break;
        }
        UUID reportId=jdbc.queryForObject("""
            SELECT id FROM fin_exec.core_report_snapshot
            WHERE organization_id=? AND fact_set_revision_id=?
            """,UUID.class,f.org(),f.revision());
        var report=coreReports.load(f.org(),reportId);
        assertEquals("CORE",report.path("reportMode").asText());
        assertEquals("PARTIAL",report.path("coreReadiness").asText());
        assertEquals("NOT_REQUESTED",report.path("mlReadiness").asText());
        assertTrue(report.path("methods").toString().contains("FIN-04/CURRENT_RATIO"));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            DELETE FROM fin_exec.core_report_snapshot WHERE id=?
            """,reportId));
        assertThrows(IllegalArgumentException.class,()->
            coreReports.load(UUID.randomUUID(),reportId));
    }

    @Test void unrelatedUnqualifiedMetricDoesNotPoisonTheQualifiedRatio() throws Exception {
        var f=seed("400","200",true,true,true,true,true);
        executeToTerminal(f);
        assertEquals(1L,scheduled(f));
        var result=json.readTree(result(f));
        assertEquals("CALCULATED",result.path("status").asText());
        assertEquals(0,new BigDecimal("2").compareTo(
            new BigDecimal(result.path("value").asText())));
    }

    @Test void zeroLiabilitiesProducesExplicitNotComputableNotInfinity() throws Exception {
        var f=seed("400","0",true,true,true,true);
        executeToTerminal(f);
        assertEquals(1L,scheduled(f));
        var result=json.readTree(result(f));
        assertEquals("NOT_COMPUTABLE",result.path("status").asText());
        assertEquals("ZERO_DENOMINATOR",result.path("reason").asText());
        assertFalse(result.has("value"));
    }

    @Test void missingOriginOrWrongDateOrCurrencyNeverSchedulesRatio() {
        var noOrigin=seed("400","200",false,true,true,true);
        executeToTerminal(noOrigin);
        assertEquals(0L,scheduled(noOrigin));
        var wrongDate=seed("400","200",true,false,true,true);
        executeToTerminal(wrongDate);
        assertEquals(0L,scheduled(wrongDate));
        var wrongCurrency=seed("400","200",true,true,false,true);
        executeToTerminal(wrongCurrency);
        assertEquals(0L,scheduled(wrongCurrency));
    }

    @Test void totalAssetsAreNotAcceptedAsCurrentAssets() {
        var total=seed("400","200",true,true,true,false);
        executeToTerminal(total);
        assertEquals(0L,scheduled(total));
        // Qualified metric contracts alone are insufficient: F01 TOTAL
        // columns cannot be interpreted as CURRENT columns.
    }
}
