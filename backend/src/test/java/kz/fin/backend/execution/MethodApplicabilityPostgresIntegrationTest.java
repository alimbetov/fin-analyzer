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
class MethodApplicabilityPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",db::getJdbcUrl);
        p.add("spring.datasource.username",db::getUsername);
        p.add("spring.datasource.password",db::getPassword);
        p.add("fin.r2.synthetic-lab.enabled",()->"true");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired FactAssertionJpaRepository facts;
    @Autowired FactSetPublicationService publisher;
    @Autowired MethodVariantRegistry registry;
    @Autowired MethodApplicabilityEvaluator evaluator;

    private MethodApplicabilityEvaluator.SourceKey published(
            String qualification,int months,AnalysisContext.DataSpace space) {
        return published(qualification,months,space,false,false);
    }
    private MethodApplicabilityEvaluator.SourceKey published(
            String qualification,int months,AnalysisContext.DataSpace space,boolean gap) {
        return published(qualification,months,space,gap,false);
    }
    private MethodApplicabilityEvaluator.SourceKey published(
            String qualification,int months,AnalysisContext.DataSpace space,
            boolean gap,boolean differentSource) {
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),
                scope=UUID.randomUUID(),revision=UUID.randomUUID(),source=UUID.randomUUID();
        String version="R2A2"+qualification;
        jdbc.update("""
            INSERT INTO fin_calc.metric_contract
               (metric_code,metric_version,measure_kind,unit_code,qualification,method_ref)
            VALUES ('REV_NET',?,'FLOW','MONEY',?,'R2_A2_TEST')
            ON CONFLICT DO NOTHING
            """,version,qualification);
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision
               (organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?,?,'DF-SAL-01','1.0',now())
            """,org,source);
        jdbc.update("""
            INSERT INTO fin_fact.fact_set_revision
               (id,organization_id,status,approved_at)
            VALUES (?,?,'APPROVED',now())
            """,revision,org);
        UUID otherSource=UUID.randomUUID();
        if(differentSource) jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision
               (organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?,?,'DF-SAL-01','1.0',now())
            """,org,otherSource);
        for(int i=0;i<months;i++) {
            YearMonth ym=YearMonth.of(2026,i+1+(gap && i>0 ? 1 : 0));
            var period=EconomicKernel.TemporalReference.interval(
                    ym.atDay(1),ym.plusMonths(1).atDay(1));
            var key=new EconomicKernel.FactKey(org,entity,scope,
                    "REV_NET",version,period,
                    EconomicKernel.ReportingBasis.MANAGEMENT,
                    "MONEY","KZT",new TreeMap<>());
            UUID id=UUID.randomUUID();
            facts.saveAndFlush(FactAssertionJpaEntity.from(
                    new EconomicKernel.FactAssertion(id,key,
                        EconomicKernel.EconomicValue.present("150.0000"),
                        differentSource && i>0 ? otherSource : source,
                        Instant.parse("2026-11-16T00:00:00Z"))));
            String identity=jdbc.queryForObject(
                    "SELECT fin_fact.fact_identity_hash(?)",String.class,id);
            jdbc.update("""
                INSERT INTO fin_fact.fact_selection
                   (organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
                VALUES (?,?,?,?)
                """,org,revision,identity,id);
        }
        var result=publisher.publish(new FactSetPublicationService.Request(
                org,entity,scope,EconomicKernel.ReportingBasis.MANAGEMENT,
                space,revision,null));
        return new MethodApplicabilityEvaluator.SourceKey(
                org,entity,scope,revision,result.snapshotHash(),space.name());
    }

    private static MethodApplicabilityEvaluator.Decision find(
            List<MethodApplicabilityEvaluator.Decision> rows,String code) {
        return rows.stream().filter(r->code.equals(r.methodCode())).findFirst().orElseThrow();
    }

    @Test void publishedSyntheticSourceProducesReasonCodesWithoutAnyExecution() {
        var src=published("QUALIFIED",1,AnalysisContext.DataSpace.SYNTHETIC);
        var rows=evaluator.evaluate(src);
        assertEquals(7,rows.size());
        assertEquals(MethodApplicabilityEvaluator.Status.ELIGIBLE,
                find(rows,"SYS/VERIFY_PUBLISHED_FACT_SET").status());
        assertEquals(MethodApplicabilityEvaluator.Status.BLOCKED_MISSING,
                find(rows,"FIN-01/REV_NET_MOM").status());
        assertTrue(find(rows,"FIN-01/REV_NET_MOM").missingMetrics()
                .contains("REV_NET_PREVIOUS_COMPARABLE_MONTH"));
        assertEquals(MethodApplicabilityEvaluator.Status.BLOCKED_MISSING,
                find(rows,"FIN-04/CURRENT_RATIO").status());
        assertEquals(MethodApplicabilityEvaluator.Status.UNQUALIFIED,
                find(rows,"SYS/DIAGNOSTIC_PILOT").status());
        assertEquals(MethodApplicabilityEvaluator.Status.UNQUALIFIED,
                find(rows,"CON-01/PARETO_SALES").status());
        assertEquals(rows,evaluator.evaluate(src));
        assertEquals(1,find(rows,"SYS/DIAGNOSTIC_PILOT").matchedFacts().size());
        assertThrows(UnsupportedOperationException.class,rows::clear);
        assertThrows(UnsupportedOperationException.class,()->
                find(rows,"SYS/DIAGNOSTIC_PILOT").matchedFacts().clear());
        for(var row:rows) {
            assertFalse(row.executionPermitted());
            assertEquals(src.snapshotHash(),row.sourceFingerprint());
            assertEquals(registry.fingerprint(),row.registryFingerprint());
        }
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_run WHERE organization_id=?
            """,Long.class,src.organizationId()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.outbox_delivery d
            JOIN fin_exec.fact_publication_outbox o ON o.event_id=d.event_id
            WHERE o.organization_id=?
            """,Long.class,src.organizationId()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.core_report_snapshot WHERE organization_id=?
            """,Long.class,src.organizationId()));
    }

    @Test void qualifiedMonthlyValuesNeverPromoteActualMethodWithoutS2Permit() {
        var src=published("QUALIFIED",2,AnalysisContext.DataSpace.ACTUAL);
        var rows=evaluator.evaluate(src);
        var revenue=find(rows,"FIN-01/REV_NET_MOM");
        assertEquals(MethodApplicabilityEvaluator.Status.UNQUALIFIED,revenue.status());
        assertTrue(revenue.reasonCodes().contains("R2_ACTUAL_EXECUTION_PERMIT_HOLD"));
        assertEquals(MethodApplicabilityEvaluator.Status.UNQUALIFIED,
                find(rows,"SYS/VERIFY_PUBLISHED_FACT_SET").status());
        assertFalse(revenue.executionPermitted());
    }

    @Test void unqualifiedMetricContractBecomesBlockingConflict() {
        var src=published("PILOT",1,AnalysisContext.DataSpace.SYNTHETIC);
        var row=find(evaluator.evaluate(src),"FIN-01/REV_NET_MOM");
        assertEquals(MethodApplicabilityEvaluator.Status.BLOCKED_CONFLICT,row.status());
        assertTrue(row.reasonCodes().contains("R2_INPUT_UNQUALIFIED_OR_INCOMPATIBLE"));
    }

    @Test void nonconsecutiveRevenueMonthsCannotPassInputValidation() {
        var src=published("QUALIFIED",2,AnalysisContext.DataSpace.SYNTHETIC,true);
        var decision=find(evaluator.evaluate(src),"FIN-01/REV_NET_MOM");
        assertEquals(MethodApplicabilityEvaluator.Status.BLOCKED_CONFLICT,
                decision.status());
        assertTrue(decision.reasonCodes().contains("R2_NONCONSECUTIVE_REVENUE_MONTHS"));
        assertFalse(decision.executionPermitted());
    }

    @Test void conflictingApprovedSourceRevisionsMustNotBecomeComparableRevenueInput() {
        var src=published("QUALIFIED",2,AnalysisContext.DataSpace.SYNTHETIC,
                false,true);
        var row=find(evaluator.evaluate(src),"FIN-01/REV_NET_MOM");
        assertEquals(MethodApplicabilityEvaluator.Status.BLOCKED_CONFLICT,row.status());
        assertTrue(row.reasonCodes().contains("R2_INPUT_SOURCE_REVISION_CONFLICT"));
        assertEquals(2,row.matchedFacts().size());
        assertNotEquals(row.matchedFacts().getFirst().sourceRevisionId(),
                row.matchedFacts().getLast().sourceRevisionId());
        assertFalse(row.executionPermitted());
    }

    @Test void foreignTenantWrongSourceHashAndDisabledLabFailClosed() {
        var src=published("QUALIFIED",1,AnalysisContext.DataSpace.SYNTHETIC);
        assertEquals("R2_SOURCE_NOT_PUBLISHED_OR_FORBIDDEN",
                assertThrows(IllegalArgumentException.class,()->evaluator.evaluate(
                    new MethodApplicabilityEvaluator.SourceKey(
                        UUID.randomUUID(),src.economicEntityId(),src.businessScopeId(),
                        src.factSetRevisionId(),src.snapshotHash(),src.dataSpace())))
                        .getMessage());
        assertEquals("R2_SOURCE_FINGERPRINT_CONFLICT",
                assertThrows(IllegalArgumentException.class,()->evaluator.evaluate(
                    new MethodApplicabilityEvaluator.SourceKey(
                        src.organizationId(),src.economicEntityId(),src.businessScopeId(),
                        src.factSetRevisionId(),"a".repeat(64),src.dataSpace())))
                        .getMessage());
        var disabled=new MethodApplicabilityEvaluator(jdbc,registry,false);
        assertEquals(MethodApplicabilityEvaluator.Status.DISABLED_POLICY,
                find(disabled.evaluate(src),"SYS/VERIFY_PUBLISHED_FACT_SET").status());
    }
}
