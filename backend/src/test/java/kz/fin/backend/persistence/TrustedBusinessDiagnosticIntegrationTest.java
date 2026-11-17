package kz.fin.backend.persistence;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.domain.EconomicKernel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import kz.fin.backend.execution.LegacyExecutorGoldenFixturePermit;
import org.springframework.dao.DataAccessException;
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

@Import(LegacyExecutorGoldenFixturePermit.class)
@SpringBootTest
@Testcontainers
class TrustedBusinessDiagnosticIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FactAssertionJpaRepository facts;
    @Autowired FactSetPublicationService publicationService;
    @Autowired TrustedBusinessDiagnosticService diagnostics;
    @Autowired kz.fin.backend.execution.SingleExecutorScheduler scheduler;
    @Autowired kz.fin.backend.execution.CompositeCoreReportPublisher coreReports;

    private static final YearMonth CURRENT=YearMonth.of(2026,10);
    private static final EconomicKernel.ReportingBasis BASIS=EconomicKernel.ReportingBasis.MANAGEMENT;
    record Fixture(UUID org,UUID entity,UUID scope,UUID revision,
                   AnalysisContext.DataSpace dataSpace) {}

    private void metricContract(String code,boolean stock){
        jdbc.update("""
            INSERT INTO fin_calc.metric_contract(
              metric_code,metric_version,measure_kind,unit_code,qualification,method_ref)
            VALUES(?,'v1',?,'MONEY','PILOT','DIAGNOSTIC_EXPERIMENT_V1')
            ON CONFLICT DO NOTHING
            """,code,stock?"STOCK":"FLOW");
    }

    private Fixture seed(UUID org,UUID entity,UUID scope,UUID parent,
                         AnalysisContext.DataSpace space, String latestRevenue,
                         boolean withLatestCogs, boolean fullHistory, String revenueCurrency,
                         boolean dimensioned) {
        for(String metric:List.of("REV_NET","COGS","CASH_END","AR_END"))
            metricContract(metric,metric.equals("CASH_END")||metric.equals("AR_END"));
        UUID revision=UUID.randomUUID(),source=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision(
                organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES(?,?,'DF-SAL-01','v1',now())
            """,org,source);
        if(parent==null){
            jdbc.update("""
                INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at)
                VALUES(?,?,'APPROVED',now())
                """,revision,org);
        }else{
            jdbc.update("""
                INSERT INTO fin_fact.fact_set_revision(
                    id,organization_id,parent_revision_id,status,approved_at)
                VALUES(?,?,?,'APPROVED',now())
                """,revision,org,parent);
        }
        for(int back=(fullHistory?12:1);back>=0;back--) {
            YearMonth month=CURRENT.minusMonths(back);
            String rev=month.equals(CURRENT)?latestRevenue:
                    month.equals(CURRENT.minusYears(1))?"120":"100";
            insertFact(org,entity,scope,source,revision,"REV_NET",month,rev,
                    revenueCurrency,dimensioned);
            if(!month.equals(CURRENT)||withLatestCogs)
                insertFact(org,entity,scope,source,revision,"COGS",month,
                        month.equals(CURRENT)?"70":
                        month.equals(CURRENT.minusYears(1))?"50":"60","KZT",false);
            insertFact(org,entity,scope,source,revision,"CASH_END",month,
                    month.equals(CURRENT)?"160":"200","KZT",false);
            insertFact(org,entity,scope,source,revision,"AR_END",month,
                    month.equals(CURRENT)?"120":"80","KZT",false);
        }
        return new Fixture(org,entity,scope,revision,space);
    }

    private void insertFact(UUID org,UUID entity,UUID scope,UUID source,UUID revision,
                            String metric,YearMonth month,String amount,String currency,
                            boolean dimensioned) {
        boolean stock=metric.equals("CASH_END") || metric.equals("AR_END");
        var time=stock?EconomicKernel.TemporalReference.asOf(month.atEndOfMonth()):
                EconomicKernel.TemporalReference.interval(
                        month.atDay(1),month.plusMonths(1).atDay(1));
        var key=new EconomicKernel.FactKey(org,entity,scope,metric,"v1",time,BASIS,
                "MONEY",currency,dimensioned?
                new TreeMap<>(Map.of("store","A")):new TreeMap<>());
        UUID id=UUID.randomUUID();
        facts.saveAndFlush(FactAssertionJpaEntity.from(new EconomicKernel.FactAssertion(
                id,key,EconomicKernel.EconomicValue.present(amount),source,
                Instant.parse("2026-11-15T00:00:00Z"))));
        String hash=jdbc.queryForObject("SELECT fin_fact.fact_identity_hash(?)",String.class,id);
        jdbc.update("""
            INSERT INTO fin_fact.fact_selection(
                 organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
            VALUES(?,?,?,?)
            """,org,revision,hash,id);
    }

    private void publish(Fixture f,UUID current){
        publicationService.publish(new FactSetPublicationService.Request(f.org(),f.entity(),f.scope(),
                BASIS,f.dataSpace(),f.revision(),current));
    }


    @Test void orchestratedSyntheticDiagnosticsBuildImmutablePartialCoreReport() {
        Fixture fixture=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"150",true,true,"KZT",false);
        publish(fixture,null);

        for(int i=0;i<30;i++) {
            scheduler.tick();
            var done=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.analysis_run
                WHERE organization_id=? AND fact_set_revision_id=? AND run_status='COMPLETED'
                """,Long.class,fixture.org(),fixture.revision());
            if(done==1L) break;
        }

        assertEquals(2L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_task t
            JOIN fin_exec.analysis_run r ON r.id=t.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
              AND t.task_status='COMPUTED'
            """,Long.class,fixture.org(),fixture.revision()));
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_task t
            JOIN fin_exec.analysis_run r ON r.id=t.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
              AND t.method_code='SYS/DIAGNOSTIC_PILOT' AND t.task_status='COMPUTED'
            """,Long.class,fixture.org(),fixture.revision()));
        for(int i=0;i<30;i++) {
            coreReports.publishReady();
            var ready=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.core_report_snapshot s
                WHERE s.organization_id=? AND s.fact_set_revision_id=?
                """,Long.class,fixture.org(),fixture.revision());
            if(ready==1L) break;
        }
        UUID reportId=jdbc.queryForObject("""
            SELECT id FROM fin_exec.core_report_snapshot
            WHERE organization_id=? AND fact_set_revision_id=?
            """,UUID.class,fixture.org(),fixture.revision());
        assertNotNull(reportId);
        var snapshot=coreReports.load(fixture.org(),reportId);
        assertEquals("CORE",snapshot.path("reportMode").asText());
        assertEquals("PARTIAL",snapshot.path("coreReadiness").asText());
        assertEquals("NOT_REQUESTED",snapshot.path("mlReadiness").asText());
        assertEquals("SYNTHETIC",snapshot.path("sourceClassification").asText());
        assertEquals(2,snapshot.path("methods").size());
        assertEquals(3,snapshot.path("diagnosticFindings").size());
        assertEquals("GROSS_MARGIN_YOY_COMPRESSION",
                snapshot.path("diagnosticFindings").get(0).path("ruleCode").asText());
        assertEquals("CORE_REPORT_NOT_FOUND_OR_FORBIDDEN",
                assertThrows(IllegalArgumentException.class,
                    ()->coreReports.load(UUID.randomUUID(),reportId)).getMessage());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("""
            UPDATE fin_exec.core_report_snapshot SET snapshot='{}'::jsonb WHERE id=?
            """,reportId));
        coreReports.publishReady();
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.core_report_snapshot
            WHERE organization_id=? AND fact_set_revision_id=?
            """,Long.class,fixture.org(),fixture.revision()));
        assertEquals(reportId,jdbc.queryForObject("""
            SELECT id FROM fin_exec.core_report_snapshot
            WHERE organization_id=? AND fact_set_revision_id=?
            """,UUID.class,fixture.org(),fixture.revision()));
    }

    @Test void actualFinancialRevisionNeverExecutesSyntheticDiagnosticPilot() {
        Fixture fixture=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.ACTUAL,"150",true,false,"KZT",false);
        publish(fixture,null);
        for(int i=0;i<30;i++) {
            scheduler.tick();
            var done=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.analysis_run
                WHERE organization_id=? AND fact_set_revision_id=? AND run_status='COMPLETED'
                """,Long.class,fixture.org(),fixture.revision());
            if(done==1L) break;
        }
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_task t
            JOIN fin_exec.analysis_run r ON r.id=t.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
              AND t.method_code='SYS/DIAGNOSTIC_PILOT'
            """,Long.class,fixture.org(),fixture.revision()));
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_task t
            JOIN fin_exec.analysis_run r ON r.id=t.run_id
            WHERE r.organization_id=? AND r.fact_set_revision_id=?
              AND t.task_status='COMPUTED'
            """,Long.class,fixture.org(),fixture.revision()));
    }


    @Test void failedExecutionCannotMasqueradeAsReadyCoreReport() {
        Fixture f=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"150",true,true,"KZT",false);
        publish(f,null);
        UUID eventId=jdbc.queryForObject("""
            SELECT event_id FROM fin_exec.fact_publication_outbox
            WHERE organization_id=? AND fact_set_revision_id=?
            """,UUID.class,f.org(),f.revision());
        String hash=jdbc.queryForObject("""
            SELECT snapshot_hash FROM fin_fact.fact_set_publication
            WHERE organization_id=? AND fact_set_revision_id=?
            """,String.class,f.org(),f.revision());
        UUID run=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_exec.analysis_run
              (id,event_id,organization_id,fact_set_revision_id,snapshot_hash,
               profile_code,run_status)
            VALUES (?,?,?,?,?,'CORE_FAILURE_FIXTURE','FAILED')
            """,run,eventId,f.org(),f.revision(),hash);
        for(int i=0;i<100;i++) {
            coreReports.publishReady();
            Long found=jdbc.queryForObject("""
                SELECT count(*) FROM fin_exec.core_report_snapshot WHERE run_id=?
                """,Long.class,run);
            if(found==1L)break;
        }
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.core_report_snapshot
            WHERE run_id=? AND core_readiness='BLOCKED'
            """,Long.class,run));
        UUID reportId=jdbc.queryForObject("""
            SELECT id FROM fin_exec.core_report_snapshot WHERE run_id=?
            """,UUID.class,run);
        var report=coreReports.load(f.org(),reportId);
        assertEquals("FAILED",report.path("executionStatus").asText());
        assertEquals("BLOCKED",report.path("coreReadiness").asText());
        assertEquals(0,report.path("computedTaskCount").asInt(-1));
        assertEquals("NOT_REQUESTED",report.path("mlReadiness").asText());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("""
            UPDATE fin_exec.core_report_snapshot SET core_readiness='PARTIAL'
            WHERE id=?
            """,reportId));
    }

    @Test void evaluatesVerifiedThirteenMonthHistoryAndPersistsExplanations(){
        Fixture f=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"150",true,true,"KZT",false);
        publish(f,null);
        var receipt=diagnostics.assessAndPublish(f.org(),f.revision(),CURRENT);
        assertFalse(receipt.reused());
        assertEquals(64,receipt.inputFingerprint().length());
        assertEquals(64,receipt.snapshotHash().length());
        var loaded=diagnostics.loadPublished(f.org(),receipt.id());
        assertEquals(TrustedBusinessDiagnosticService.SourceFreshness.CURRENT_SOURCE,
                loaded.sourceFreshness());
        var json=loaded.snapshot();
        assertEquals("1250",json.path("trends").path("REV_NET").path("ttm").path("value").asText());
        assertEquals("50",json.path("trends").path("REV_NET").path("momPercent").path("value").asText());
        assertEquals("25",json.path("trends").path("REV_NET").path("yoyPercent").path("value").asText());
        assertEquals("MONEY",json.path("trends").path("CASH_END").path("unit").asText());
        assertEquals("NOT_COMPUTABLE",
                json.path("trends").path("CASH_END").path("ttm").path("status").asText());
        assertEquals(3,json.path("findings").size());
        assertEquals("GROSS_MARGIN_YOY_COMPRESSION",
                json.path("findings").get(0).path("ruleCode").asText());
        assertEquals("OBSERVATION",json.path("findings").get(1).path("kind").asText());
        assertEquals("HYPOTHESIS",json.path("findings").get(2).path("kind").asText());
        assertEquals("WORKING_CAPITAL_REVIEW",
                json.path("findings").get(2).path("playbookCode").asText());
        assertEquals(2,json.path("proposedDecisionPlaybooks").size());
        assertEquals("PROPOSED_NOT_EXECUTED",
                json.path("proposedDecisionPlaybooks").get(0).path("status").asText());
        assertTrue(json.path("proposedDecisionPlaybooks").get(1).path("actions").get(0)
                .path("requiresHumanApproval").asBoolean());
        assertEquals("150",json.path("findings").get(1).path("evidence").get(0)
                .path("value").asText());
    }

    @Test void retryIsIdempotentAndPostgresRejectsSnapshotMutation(){
        Fixture f=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"150",true,true,"KZT",false);
        publish(f,null);
        var a=diagnostics.assessAndPublish(f.org(),f.revision(),CURRENT);
        var b=diagnostics.assessAndPublish(f.org(),f.revision(),CURRENT);
        assertEquals(a.id(),b.id());
        assertTrue(b.reused());
        assertEquals(a.snapshotHash(),b.snapshotHash());
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE fin_diag.assessment_run SET snapshot='{}'::jsonb WHERE id=?
            """,a.id()));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            DELETE FROM fin_diag.assessment_run WHERE id=?
            """,a.id()));
    }

    @Test void newCorrectionLeavesOldDiagnosisReproducibleAndStale(){
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),scope=UUID.randomUUID();
        var first=seed(org,entity,scope,null,AnalysisContext.DataSpace.SYNTHETIC,
                "150",true,true,"KZT",false);
        publish(first,null);
        var previous=diagnostics.assessAndPublish(org,first.revision(),CURRENT);
        var revised=seed(org,entity,scope,first.revision(),
                AnalysisContext.DataSpace.SYNTHETIC,"130",true,true,"KZT",false);
        publish(revised,first.revision());
        var current=diagnostics.assessAndPublish(org,revised.revision(),CURRENT);
        assertNotEquals(previous.id(),current.id());
        assertNotEquals(previous.inputFingerprint(),current.inputFingerprint());
        assertEquals(TrustedBusinessDiagnosticService.SourceFreshness.STALE_SOURCE,
                diagnostics.loadPublished(org,previous.id()).sourceFreshness());
        assertEquals(TrustedBusinessDiagnosticService.SourceFreshness.CURRENT_SOURCE,
                diagnostics.loadPublished(org,current.id()).sourceFreshness());
        assertEquals("150",diagnostics.loadPublished(org,previous.id()).snapshot()
                .path("trends").path("REV_NET").path("current").path("value").asText());
        assertEquals("130",diagnostics.loadPublished(org,current.id()).snapshot()
                .path("trends").path("REV_NET").path("current").path("value").asText());
    }

    @Test void missingCostIsFindingNotZeroOrVerifiedMargin(){
        Fixture f=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"150",false,true,"KZT",false);
        publish(f,null);
        var receipt=diagnostics.assessAndPublish(f.org(),f.revision(),CURRENT);
        var findings=diagnostics.loadPublished(f.org(),receipt.id()).snapshot().path("findings");
        assertEquals(3,findings.size());
        assertEquals("MISSING_EVIDENCE",findings.get(0).path("kind").asText());
        assertEquals("COGS:CURRENT",findings.get(0).path("missingMetrics").get(0).asText());
        assertEquals("INCOMPLETE",diagnostics.loadPublished(f.org(),receipt.id())
                .snapshot().path("trends").path("COGS").path("current").path("status").asText());
    }

    @Test void partialHistoryDoesNotInventYearOverYearOrTtm(){
        Fixture f=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"150",true,false,"KZT",false);
        publish(f,null);
        var id=diagnostics.assessAndPublish(f.org(),f.revision(),CURRENT).id();
        var j=diagnostics.loadPublished(f.org(),id).snapshot();
        assertEquals("INCOMPLETE",j.path("trends").path("REV_NET")
                .path("yearAgo").path("status").asText());
        assertEquals("INCOMPLETE",j.path("trends").path("REV_NET")
                .path("ttm").path("status").asText());
        assertEquals("MISSING_EVIDENCE",j.path("findings").get(0)
                .path("kind").asText());
    }

    @Test void crossTenantAndUnpublishedFactsCannotBeDiagnosed(){
        Fixture f=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"150",true,false,"KZT",false);
        UUID outsider=UUID.randomUUID();
        assertEquals("DIAGNOSTIC_FACT_SET_NOT_PUBLISHED_OR_FOREIGN",
                assertThrows(IllegalArgumentException.class,()->
                    diagnostics.assessAndPublish(f.org(),f.revision(),CURRENT)).getMessage());
        publish(f,null);
        var result=diagnostics.assessAndPublish(f.org(),f.revision(),CURRENT);
        assertEquals("DIAGNOSTIC_FACT_SET_NOT_PUBLISHED_OR_FOREIGN",
                assertThrows(IllegalArgumentException.class,()->
                    diagnostics.assessAndPublish(outsider,f.revision(),CURRENT)).getMessage());
        assertEquals("DIAGNOSTIC_NOT_FOUND_OR_FORBIDDEN",
                assertThrows(IllegalArgumentException.class,()->
                    diagnostics.loadPublished(outsider,result.id())).getMessage());
    }

    @Test void actualBusinessCannotUseUnqualifiedSyntheticDiagnosticRules(){
        Fixture f=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.ACTUAL,"150",true,false,"KZT",false);
        publish(f,null);
        assertEquals("UNQUALIFIED_DIAGNOSTIC_RULES_FOR_ACTUAL",
                assertThrows(IllegalArgumentException.class,()->
                    diagnostics.assessAndPublish(f.org(),f.revision(),CURRENT)).getMessage());
    }

    @Test void mixedCurrencyAndDimensionedTotalsAreRejectedRatherThanCombined(){
        Fixture differentCurrency=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"150",true,false,"USD",false);
        publish(differentCurrency,null);
        assertEquals("DIAGNOSTIC_CURRENCY_MISMATCH",
                assertThrows(IllegalArgumentException.class,()->
                    diagnostics.assessAndPublish(differentCurrency.org(),
                            differentCurrency.revision(),CURRENT)).getMessage());
        Fixture dimensioned=seed(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),null,
                AnalysisContext.DataSpace.SYNTHETIC,"150",true,false,"KZT",true);
        publish(dimensioned,null);
        assertEquals("DIAGNOSTIC_REQUIRES_UNDIMENSIONED_TOTAL",
                assertThrows(IllegalArgumentException.class,()->
                    diagnostics.assessAndPublish(dimensioned.org(),dimensioned.revision(),CURRENT)).getMessage());
    }
}
