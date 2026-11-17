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
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual PostgreSQL negative proof using the *production* deny-all guard.
 * No test-only legacy golden fixture permit is imported into this context.
 */
@SpringBootTest(properties = "fin.execution.enabled=false")
@Testcontainers
class LegacyExecutorSafetyPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FactAssertionJpaRepository facts;
    @Autowired FactSetPublicationService publication;
    @Autowired SingleExecutorScheduler scheduler;
    @Autowired LegacyExecutionPermitGuard permitGuard;

    private record Published(UUID organization, UUID revision) {}

    private Published published(AnalysisContext.DataSpace space) {
        var organization=UUID.randomUUID();
        var entity=UUID.randomUUID();
        var scope=UUID.randomUUID();
        var revision=UUID.randomUUID();
        var source=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.metric_contract
                (metric_code, metric_version, measure_kind, unit_code, qualification, method_ref)
            VALUES ('REV_NET','1.0.0','FLOW','MONEY','QUALIFIED','LEGACY_DENY_GUARD')
            ON CONFLICT DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision
                (organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?,?,'DF-SAL-01','v1',now())
            """,organization,source);
        jdbc.update("""
            INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at)
            VALUES (?,?,'APPROVED',now())
            """,revision,organization);
        for (var period : new YearMonth[]{YearMonth.of(2026,1),YearMonth.of(2026,2)}) {
            var interval=EconomicKernel.TemporalReference.interval(
                    period.atDay(1),period.plusMonths(1).atDay(1));
            var key=new EconomicKernel.FactKey(
                    organization,entity,scope,"REV_NET","1.0.0",interval,
                    EconomicKernel.ReportingBasis.MANAGEMENT,"MONEY","KZT",new TreeMap<>());
            var assertion=UUID.randomUUID();
            facts.saveAndFlush(FactAssertionJpaEntity.from(
                    new EconomicKernel.FactAssertion(assertion,key,
                            EconomicKernel.EconomicValue.present(
                                    period.getMonthValue()==1?"100":"150"),
                            source,Instant.parse("2026-11-16T00:00:00Z"))));
            var hash=jdbc.queryForObject("SELECT fin_fact.fact_identity_hash(?)",
                    String.class,assertion);
            jdbc.update("""
                INSERT INTO fin_fact.fact_selection
                    (organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
                VALUES (?,?,?,?)
                """,organization,revision,hash,assertion);
        }
        publication.publish(new FactSetPublicationService.Request(
                organization,entity,scope,EconomicKernel.ReportingBasis.MANAGEMENT,
                space,revision,null));
        return new Published(organization,revision);
    }

    private long count(String sql,UUID organization) {
        return jdbc.queryForObject(sql,Long.class,organization);
    }

    private void assertNotConsumed(Published p) {
        assertEquals(1L,count("""
            SELECT count(*) FROM fin_exec.fact_publication_outbox
             WHERE organization_id=?
            """,p.organization()));
        assertEquals(0L,count("""
            SELECT count(*) FROM fin_exec.analysis_run WHERE organization_id=?
            """,p.organization()));
        assertEquals(0L,count("""
            SELECT count(*) FROM fin_exec.analysis_task t
              JOIN fin_exec.analysis_run r ON r.id=t.run_id
             WHERE r.organization_id=?
            """,p.organization()));
        assertEquals(0L,count("""
            SELECT count(*) FROM fin_exec.outbox_delivery d
              JOIN fin_exec.fact_publication_outbox o ON o.event_id=d.event_id
             WHERE o.organization_id=?
            """,p.organization()));
        assertEquals(0L,count("""
            SELECT count(*) FROM fin_calc.report_run WHERE organization_id=?
            """,p.organization()));
        assertEquals(0L,count("""
            SELECT count(*) FROM fin_calc.report_publication p
              JOIN fin_calc.report_run r ON r.id=p.report_run_id
             WHERE r.organization_id=?
            """,p.organization()));
    }

    @Test void actualPublishedFactSetCannotRunEvenIfLegacyMethodIsAutoEligible() {
        assertInstanceOf(DenyAllLegacyExecutionPermitGuard.class,permitGuard);
        var p=published(AnalysisContext.DataSpace.ACTUAL);
        var error=assertThrows(IllegalStateException.class,scheduler::tick);
        assertEquals("FIN_R2_LEGACY_EXECUTION_PERMIT_UNAVAILABLE",error.getMessage());
        assertNotConsumed(p);
    }

    @Test void syntheticPublishedFactSetCannotBypassTheUnpinnedLegacyScheduler() {
        var p=published(AnalysisContext.DataSpace.SYNTHETIC);
        assertThrows(IllegalStateException.class,scheduler::tick);
        assertNotConsumed(p);
    }

    @Test void disabledScheduledPollingLeavesPublishedOutboxUntouched() {
        var p=published(AnalysisContext.DataSpace.ACTUAL);
        assertDoesNotThrow(scheduler::scheduledTick);
        assertNotConsumed(p);
    }
}
