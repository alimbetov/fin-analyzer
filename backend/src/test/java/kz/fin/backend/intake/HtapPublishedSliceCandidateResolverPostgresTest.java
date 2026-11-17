package kz.fin.backend.intake;

import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.domain.EconomicKernel;
import kz.fin.backend.persistence.FactSetPublicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W1.2.A: real PostgreSQL 16 fresh migration, read-only exact published
 * source selection, tenant authorization, immutable mapping SHA and fail-close.
 */
@SpringBootTest(properties="fin.execution.enabled=false")
@Testcontainers
class HtapPublishedSliceCandidateResolverPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FactSetPublicationService publications;
    @Autowired HtapPublishedSliceCandidateResolver resolver;

    private static final String DIM_SHA="a".repeat(64);
    private static final String FAKE_SHA="b".repeat(64);

    private Jwt readToken(UUID organization) {
        return Jwt.withTokenValue("verified-test-token")
                .header("alg","RS256")
                .subject("internal-test-reader")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
                .claim("fin_access",List.of(Map.of("organizationId",organization.toString(),
                        "permissions",List.of("FIN_READ"))))
                .build();
    }

    @Test void publishedSourceIsLocatedExactlyButUnapprovedMappingCannotExecute() {
        UUID org=UUID.randomUUID(), entity=UUID.randomUUID(), scope=UUID.randomUUID(),
                source=UUID.randomUUID(), fact=UUID.randomUUID(), revision=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_fact.approved_source_revision(
                organization_id,source_revision_id,form_code,form_version,approved_at)
            VALUES (?,?,'F01','1.0',now())
            """,org,source);
        jdbc.update("""
            INSERT INTO fin_fact.fact_assertion(
                id,organization_id,economic_entity_id,business_scope_id,metric_code,
                metric_version,time_kind,start_inclusive,end_exclusive,reporting_basis,
                unit_code,currency_code,dimension_hash,dimensions,value_state,numeric_value,
                source_revision_id,recorded_at)
            VALUES (?,?,?,?,'REV_NET','1.0','INTERVAL',
                DATE '2026-01-01',DATE '2026-02-01','MANAGEMENT',
                'MONEY','KZT',?,'{}'::jsonb,'PRESENT',?, ?,now())
            """,fact,org,entity,scope,DIM_SHA,new BigDecimal("100"),source);
        jdbc.update("""
            INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at)
            VALUES (?,?,'APPROVED',now())
            """,revision,org);
        String identity=jdbc.queryForObject(
                "SELECT fin_fact.fact_identity_hash(?)",String.class,fact);
        jdbc.update("""
            INSERT INTO fin_fact.fact_selection(
                organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
            VALUES (?,?,?,?)
            """,org,revision,identity,fact);
        var published=publications.publish(new FactSetPublicationService.Request(
                org,entity,scope,EconomicKernel.ReportingBasis.MANAGEMENT,
                AnalysisContext.DataSpace.SYNTHETIC,revision,null));

        HtapFinancialSliceKey key=new HtapFinancialSliceKey(
                org,entity,scope,revision,published.snapshotHash(),source,
                "F01","1.0","SYNTHETIC","MANAGEMENT",
                "REV_NET","1.0",EconomicKernel.TimeKind.INTERVAL,
                ReportingPeriodV5.Type.MONTH,ReportingPeriodV5.Coverage.PERIOD_ONLY,
                "2026.01","ISO_CALENDAR","1","MONEY","KZT",DIM_SHA,"MAP-1",FAKE_SHA);

        var first=resolver.resolve(key,readToken(org));
        assertEquals(HtapPublishedSliceCandidateResolver.Status.MAPPING_CANDIDATE_NOT_FOUND,
                first.status());
        assertFalse(first.executable());

        UUID mapping=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.htap_mapping_candidate_version(
                mapping_id,organization_id,form_code,form_version,mapping_version,
                mapping_payload,mapping_sha256,authored_by)
            VALUES (?,?,'F01','1.0','MAP-1',
                '{"metricCode":"REV_NET","source":"review-needed"}'::jsonb,?,'test-provenance')
            """,mapping,org,FAKE_SHA);
        String serverHash=jdbc.queryForObject("""
            SELECT mapping_sha256 FROM fin_calc.htap_mapping_candidate_version
             WHERE mapping_id=?
            """,String.class,mapping);
        assertNotEquals(FAKE_SHA,serverHash);
        var pinned=new HtapFinancialSliceKey(
                org,entity,scope,revision,published.snapshotHash(),source,
                "F01","1.0","SYNTHETIC","MANAGEMENT",
                "REV_NET","1.0",EconomicKernel.TimeKind.INTERVAL,
                ReportingPeriodV5.Type.MONTH,ReportingPeriodV5.Coverage.PERIOD_ONLY,
                "2026.01","ISO_CALENDAR","1","MONEY","KZT",DIM_SHA,"MAP-1",serverHash);
        var found=resolver.resolve(pinned,readToken(org));
        assertEquals(HtapPublishedSliceCandidateResolver.Status.MAPPING_NOT_QUALIFIED,
                found.status());
        assertEquals(fact,found.assertionId());
        assertFalse(found.executable());
        assertThrows(AccessDeniedException.class,
                () -> resolver.resolve(pinned,readToken(UUID.randomUUID())));
        assertThrows(DataAccessException.class,() -> jdbc.update("""
            UPDATE fin_calc.htap_mapping_candidate_version
               SET mapping_payload='{"metricCode":"OTHER"}'::jsonb
             WHERE mapping_id=?
            """,mapping));
        assertThrows(DataAccessException.class,() -> jdbc.update("""
            DELETE FROM fin_calc.htap_mapping_candidate_version WHERE mapping_id=?
            """,mapping));

        var wrongSource=new HtapFinancialSliceKey(
                org,entity,scope,revision,published.snapshotHash(),UUID.randomUUID(),
                "F01","1.0","SYNTHETIC","MANAGEMENT",
                "REV_NET","1.0",EconomicKernel.TimeKind.INTERVAL,
                ReportingPeriodV5.Type.MONTH,ReportingPeriodV5.Coverage.PERIOD_ONLY,
                "2026.01","ISO_CALENDAR","1","MONEY","KZT",DIM_SHA,"MAP-1",serverHash);
        assertEquals(HtapPublishedSliceCandidateResolver.Status.SOURCE_NOT_FOUND,
                resolver.resolve(wrongSource,readToken(org)).status());

        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.report_run
             WHERE organization_id=?
            """,Long.class,org));
    }
}
