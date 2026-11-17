package kz.fin.backend.intake;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.domain.EconomicKernel;
import kz.fin.backend.persistence.FactSetPublicationService;
import kz.fin.backend.execution.HtapV12Fin01BlockedPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTAP-V1.1 is a real published, source-population-verified, same-revision
 * two-month financial vertical. It does NOT calculate MoM or issue a permit.
 */
@SpringBootTest(properties="fin.execution.enabled=false")
@Testcontainers
class HtapV11TwoMonthFinancialSourcePostgresTest {
    @Container
    static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",db::getJdbcUrl);
        props.add("spring.datasource.username",db::getUsername);
        props.add("spring.datasource.password",db::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired FactSetPublicationService publications;
    @Autowired HtapMappingGovernanceService governance;
    @Autowired HtapV11MonthlyRevenueSourceService sources;
    @Autowired HtapV12Fin01BlockedPlanService fin01Plans;

    private record Fixture(UUID org, UUID entity, UUID scope, UUID source,
                           UUID factSet, UUID mapping, String snapshotSha) {}

    private Jwt token(UUID org,String subject,String...permissions) {
        return Jwt.withTokenValue("trusted-test-jwt").header("alg","RS256")
                .subject(subject)
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
                .claim("fin_access",List.of(Map.of("organizationId",org.toString(),
                        "permissions",List.of(permissions))))
                .build();
    }

    private Fixture publishedTwoMonths(String febCurrency,boolean missingFebruary,
                                       boolean wrongFebruaryEntity) {
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),scope=UUID.randomUUID(),
                source=UUID.randomUUID(),factSet=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.metric_contract(
                metric_code,metric_version,measure_kind,unit_code,qualification,method_ref)
            VALUES ('REV_NET','1.0.0','FLOW','MONEY','QUALIFIED','HTAP_V11_SYNTHETIC')
            ON CONFLICT (metric_code,metric_version) DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision(
                organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?,?,'F02','FIN-PILOT-V1',now())
            """,org,source);
        for(int month=1;month<=2;month++) {
            if(month==2 && missingFebruary)continue;
            LocalDate start=LocalDate.of(2026,month,1),end=start.plusMonths(1);
            UUID fact=UUID.randomUUID();
            BigDecimal amount=month==1?new BigDecimal("100.00"):new BigDecimal("120.00");
            jdbc.update("""
                INSERT INTO fin_fact.fact_assertion(
                    id,organization_id,economic_entity_id,business_scope_id,
                    metric_code,metric_version,time_kind,start_inclusive,end_exclusive,
                    reporting_basis,unit_code,currency_code,dimension_hash,dimensions,
                    value_state,numeric_value,estimate_method_ref,source_revision_id,recorded_at)
                VALUES (?,?,?,?,'REV_NET','1.0.0','INTERVAL',?,?,
                    'MANAGEMENT','MONEY',?,
                    encode(sha256(convert_to('{}'::jsonb::text,'UTF8')),'hex'),
                    '{}'::jsonb,'PRESENT',?,NULL,?,now())
                """,fact,org,wrongFebruaryEntity && month==2?UUID.randomUUID():entity,
                    scope,start,end,month==1?"KZT":febCurrency,amount,source);
            jdbc.update("""
                INSERT INTO fin_fact.fact_origin(
                    fact_assertion_id,organization_id,source_revision_id,source_row_id,source_field)
                VALUES (?,?,?,?, 'netRevenue')
                """,fact,org,source,"net-revenue-"+month);
            jdbc.update("""
                INSERT INTO fin_fact.source_population_control(
                    organization_id,source_revision_id,metric_code,time_start,time_end,
                    expected_rows,expected_total,source_document_hash)
                VALUES (?,?,'REV_NET',?,?,1,?,?)
                """,org,source,start,end,amount,"c".repeat(64));
            if(month==1) {
                jdbc.update("""
                    INSERT INTO fin_fact.fact_set_revision(
                        id,organization_id,status,approved_at)
                    VALUES (?,?,'APPROVED',now())
                    """,factSet,org);
            }
            String identity=jdbc.queryForObject(
                    "SELECT fin_fact.fact_identity_hash(?)",String.class,fact);
            jdbc.update("""
                INSERT INTO fin_fact.fact_selection(
                    organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
                VALUES (?,?,?,?)
                """,org,factSet,identity,fact);
        }

        UUID mapping=governance.propose(org,"F02","FIN-PILOT-V1","HTAP-V11",
            "{\"sourceField\":\"netRevenue\",\"metricCode\":\"REV_NET\",\"metricVersion\":\"1.0.0\"}",
            token(org,"author","FIN_SUBMIT"));
        governance.review(org,mapping,true,"d".repeat(64),
                token(org,"reviewer","FIN_REVIEW"));
        governance.validatePilotSemantics(org,mapping,token(org,"reviewer","FIN_REVIEW"));
        assertEquals(1L,governance.activate(org,"F02","FIN-PILOT-V1",
                mapping,0,token(org,"publisher","FIN_PUBLISH")));
        var published=publications.publish(new FactSetPublicationService.Request(
                org,entity,scope,EconomicKernel.ReportingBasis.MANAGEMENT,
                AnalysisContext.DataSpace.SYNTHETIC,factSet,null));
        return new Fixture(org,entity,scope,source,factSet,mapping,published.snapshotHash());
    }

    private HtapV11MonthlyRevenueSourceService.VerifiedPair resolve(Fixture f,Jwt jwt) {
        return sources.verify(f.org(),f.entity(),f.scope(),f.factSet(),f.snapshotSha(),
                f.source(),f.mapping(),1,YearMonth.of(2026,1),YearMonth.of(2026,2),jwt);
    }

    @Test void exactPublishedSourceJanuaryFebruaryHasTwoImmutableOriginRows() {
        Fixture f=publishedTwoMonths("KZT",false,false);
        Jwt reader=token(f.org(),"reader","FIN_READ","FIN_ANALYZE");
        var result=resolve(f,reader);
        assertEquals(result,resolve(f,reader),"Read-only source selection must be deterministic");
        assertEquals(0,result.previous().netRevenue().compareTo(new BigDecimal("100.00")));
        assertEquals(0,result.current().netRevenue().compareTo(new BigDecimal("120.00")));
        assertEquals("KZT",result.previous().currency());
        assertNotEquals(result.previous().factId(),result.current().factId());
        assertEquals("ISO_MONTH_FLOW_PERIOD_ONLY_V1",result.periodPolicyCode());
        assertFalse(result.executionPermitted());
        assertEquals(2L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_fact.fact_origin
            WHERE organization_id=? AND source_revision_id=? AND source_field='netRevenue'
            """,Long.class,f.org(),f.source()));
        assertEquals(2L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_fact.source_population_control
            WHERE organization_id=? AND source_revision_id=? AND metric_code='REV_NET'
            """,Long.class,f.org(),f.source()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_run WHERE organization_id=?
            """,Long.class,f.org()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.report_run WHERE organization_id=?
            """,Long.class,f.org()));

        assertThrows(AccessDeniedException.class,
                ()->resolve(f,token(UUID.randomUUID(),"foreign","FIN_READ","FIN_ANALYZE")));
        assertThrows(IllegalArgumentException.class,
                ()->sources.verify(f.org(),f.entity(),f.scope(),f.factSet(),
                    "a".repeat(64),f.source(),f.mapping(),1,YearMonth.of(2026,1),
                    YearMonth.of(2026,2),reader));
        assertThrows(IllegalArgumentException.class,
                ()->sources.verify(f.org(),f.entity(),f.scope(),f.factSet(),
                    f.snapshotSha(),f.source(),f.mapping(),2,YearMonth.of(2026,1),
                    YearMonth.of(2026,2),reader));
        assertThrows(IllegalArgumentException.class,
                ()->sources.verify(f.org(),f.entity(),f.scope(),f.factSet(),
                    f.snapshotSha(),f.source(),f.mapping(),1,YearMonth.of(2026,1),
                    YearMonth.of(2026,3),reader));
    }

    @Test void missingMonthIsNeverTreatedAsPreviousAvailablePeriod() {
        Fixture f=publishedTwoMonths("KZT",true,false);
        assertThrows(IllegalArgumentException.class,
                ()->resolve(f,token(f.org(),"reader","FIN_READ","FIN_ANALYZE")));
    }

    @Test void mixedCurrencyCannotBecomeFinancialMoMSource() {
        Fixture f=publishedTwoMonths("USD",false,false);
        assertThrows(IllegalArgumentException.class,
                ()->resolve(f,token(f.org(),"reader","FIN_READ","FIN_ANALYZE")));
    }

    @Test void v12PinsTwoExactPublishedMonthsAndCommitsOnlyBlockedPlanIdempotently() {
        Fixture f=publishedTwoMonths("KZT",false,false);
        Jwt reader=token(f.org(),"planner","FIN_READ","FIN_ANALYZE");
        var req=new HtapV12Fin01BlockedPlanService.Request(f.org(),f.entity(),
                f.scope(),f.factSet(),f.snapshotSha(),f.source(),f.mapping(),1,
                YearMonth.of(2026,1),YearMonth.of(2026,2));
        var preview=fin01Plans.preview(req,reader);
        assertEquals(preview,fin01Plans.preview(req,reader));
        assertFalse(preview.executionPermitted());
        assertEquals("REV_NET",preview.previousSlice().metricCode());
        assertEquals(f.factSet(),preview.pair().factSetRevisionId());
        assertEquals(64,preview.algorithmClassSha256().length());
        assertTrue(preview.reasons().contains("FIN_HTAP_BUILD_ARTIFACT_NOT_ATTESTED"));
        assertTrue(preview.reasons().contains("FIN_HTAP_INDEPENDENT_METHOD_S2_PENDING"));

        var committed=fin01Plans.commitBlocked(req,reader);
        assertEquals(committed,fin01Plans.commitBlocked(req,reader));
        assertFalse(committed.executionPermitted());
        assertEquals("BLOCKED_PLAN_ONLY",committed.state());
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.htap_fin01_two_month_blocked_plan
            WHERE organization_id=? AND fact_set_revision_id=?
              AND previous_fact_id<>current_fact_id
              AND previous_month=DATE '2026-01-01'
              AND current_month=DATE '2026-02-01'
              AND catalog_epoch=1 AND currency_code='KZT'
              AND NOT execution_permitted
            """,Long.class,f.org(),f.factSet()));
        assertEquals(1L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.htap_blocked_method_plan
            WHERE organization_id=? AND plan_state='BLOCKED_PLAN_ONLY'
            """,Long.class,f.org()));
        assertThrows(org.springframework.dao.DataAccessException.class,
                ()->jdbc.update("""
                    UPDATE fin_exec.htap_fin01_two_month_blocked_plan
                    SET execution_permitted=true WHERE id=?
                    """,committed.id()));
        assertThrows(org.springframework.dao.DataAccessException.class,
                ()->jdbc.update("""
                    DELETE FROM fin_exec.htap_fin01_two_month_blocked_plan WHERE id=?
                    """,committed.id()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_exec.analysis_run WHERE organization_id=?
            """,Long.class,f.org()));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.report_run WHERE organization_id=?
            """,Long.class,f.org()));

        assertThrows(IllegalArgumentException.class,()->fin01Plans.preview(
                new HtapV12Fin01BlockedPlanService.Request(f.org(),f.entity(),
                    f.scope(),f.factSet(),f.snapshotSha(),f.source(),f.mapping(),2,
                    YearMonth.of(2026,1),YearMonth.of(2026,2)),reader));
        assertThrows(AccessDeniedException.class,()->fin01Plans.preview(req,
                token(UUID.randomUUID(),"foreign","FIN_READ","FIN_ANALYZE")));
    }

}
