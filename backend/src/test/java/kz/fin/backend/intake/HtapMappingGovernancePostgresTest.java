package kz.fin.backend.intake;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL 16, independent actors, append-only review and CAS epoch. */
@SpringBootTest(properties = "fin.execution.enabled=false")
@Testcontainers
class HtapMappingGovernancePostgresTest {
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
    @Autowired HtapMappingGovernanceService governance;
    static final String EVIDENCE = "c".repeat(64);

    Jwt token(UUID org, String user, String... permissions) {
        return Jwt.withTokenValue("verified-test-fixture")
                .header("alg","RS256")
                .subject(user)
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
                .claim("fin_access",List.of(Map.of(
                        "organizationId",org.toString(),
                        "permissions",List.of(permissions))))
                .build();
    }

    @Test void reviewedMappingsRequireDistinctSignedActorAndMonotonicCas() {
        UUID org=UUID.randomUUID();
        Jwt author=token(org,"author","FIN_SUBMIT","FIN_REVIEW");
        Jwt reviewer=token(org,"reviewer","FIN_REVIEW");
        Jwt activator=token(org,"publisher","FIN_PUBLISH");
        UUID m1=governance.propose(org,"F01","1.0","V1",
                "{\"metricCode\":\"REV_NET\",\"sourceField\":\"SOURCE_NET\"}",author);
        String hash=jdbc.queryForObject("""
            SELECT mapping_sha256 FROM fin_calc.htap_mapping_candidate_version
            WHERE mapping_id=?
            """,String.class,m1);
        assertNotNull(hash);
        assertEquals(64,hash.length());
        assertNotEquals("0".repeat(64),hash);
        assertThrows(DataAccessException.class,
                ()->governance.review(org,m1,true,EVIDENCE,author),
                "Same authenticated actor cannot review own mapping");
        assertThrows(AccessDeniedException.class,
                ()->governance.review(org,m1,true,EVIDENCE,token(UUID.randomUUID(),"other","FIN_REVIEW")));
        assertThrows(DataAccessException.class,
                ()->governance.activate(org,"F01","1.0",m1,0,activator),
                "Unreviewed mapping must not be activated");

        UUID reviewId=governance.review(org,m1,true,EVIDENCE,reviewer);
        assertNotNull(reviewId);
        assertThrows(DataAccessException.class,
                ()->governance.review(org,m1,true,EVIDENCE,reviewer),
                "Review decision cannot be overwritten");
        assertEquals(1L,governance.activate(org,"F01","1.0",m1,0,activator));
        assertThrows(DataAccessException.class,
                ()->governance.activate(org,"F01","1.0",m1,0,activator),
                "Duplicate CAS with stale initial epoch must fail");

        UUID m2=governance.propose(org,"F01","1.0","V2",
                "{\"metricCode\":\"REV_NET\",\"sourceField\":\"SOURCE_NET_2\"}",author);
        governance.review(org,m2,true,EVIDENCE,reviewer);
        assertThrows(DataAccessException.class,
                ()->governance.activate(org,"F01","1.0",m2,0,activator),
                "Outdated expected catalog epoch must fail");
        assertEquals(2L,governance.activate(org,"F01","1.0",m2,1,activator));
        assertEquals(2L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.htap_mapping_activation_journal
             WHERE organization_id=?
            """,Long.class,org));
        assertEquals(2L,jdbc.queryForObject("""
            SELECT catalog_epoch FROM fin_calc.htap_catalog_epoch
             WHERE organization_id=?
            """,Long.class,org));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            DELETE FROM fin_calc.htap_mapping_active WHERE organization_id=?
            """,org));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE fin_calc.htap_mapping_active SET form_version='2.0'
             WHERE organization_id=?
            """,org));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE fin_calc.htap_catalog_epoch
               SET catalog_epoch=catalog_epoch+2 WHERE organization_id=?
            """,org));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            INSERT INTO fin_calc.htap_catalog_epoch(organization_id,catalog_epoch)
            VALUES (?,1)
            """,UUID.randomUUID()),
            "A stand-alone epoch without an activation journal must fail at COMMIT");
        assertThrows(DataAccessException.class,()->jdbc.update("""
            DELETE FROM fin_calc.htap_mapping_review WHERE review_id=?
            """,reviewId));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE fin_calc.htap_mapping_authorship
               SET author_subject='forged' WHERE mapping_id=?
            """,m1));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.report_run WHERE organization_id=?
            """,Long.class,org));
    }

    @Test void forgedUnattestedAuthorAndRejectedMappingNeverActivate() {
        UUID org=UUID.randomUUID();
        UUID forged=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.htap_mapping_candidate_version(
                mapping_id,organization_id,form_code,form_version,
                mapping_version,mapping_payload,mapping_sha256,authored_by)
            VALUES (?,?,'F02','1.0','V1','{"metricCode":"REV_NET"}'::jsonb,?,'forged')
            """,forged,org,"f".repeat(64));
        assertThrows(DataAccessException.class,
                ()->governance.review(org,forged,true,EVIDENCE,
                        token(org,"reviewer","FIN_REVIEW")));
        UUID rejected=governance.propose(org,"F02","1.0","V2",
                "{\"metricCode\":\"REV_NET\"}",token(org,"author","FIN_SUBMIT"));
        governance.review(org,rejected,false,EVIDENCE,token(org,"reviewer","FIN_REVIEW"));
        assertThrows(DataAccessException.class,
                ()->governance.activate(org,"F02","1.0",rejected,0,
                        token(org,"publisher","FIN_PUBLISH")));
    }

    @Test void semanticMappingRequiresRealPilotFieldQualifiedMetricAndIndependentReviewer() {
        UUID org=UUID.randomUUID();
        JdbcTemplate db=jdbc;
        db.update("""
            INSERT INTO fin_calc.metric_contract(
               metric_code,metric_version,measure_kind,unit_code,qualification,method_ref)
            VALUES ('REV_NET','1.0.0','FLOW','MONEY','QUALIFIED','HTAP_W12A_PILOT')
            ON CONFLICT (metric_code,metric_version) DO NOTHING
            """);
        UUID approved=governance.propose(org,"F02","FIN-PILOT-V1","MAP-F02-01",
            "{\\"sourceField\\":\\"netRevenue\\",\\"metricCode\\":\\"REV_NET\\",\\"metricVersion\\":\\"1.0.0\\"}",
            token(org,"author","FIN_SUBMIT"));
        Jwt reviewer=token(org,"reviewer","FIN_REVIEW");
        governance.review(org,approved,true,EVIDENCE,reviewer);
        assertThrows(AccessDeniedException.class,
            ()->governance.validatePilotSemantics(org,approved,
                token(UUID.randomUUID(),"reviewer","FIN_REVIEW")));
        assertEquals(approved,governance.validatePilotSemantics(org,approved,reviewer));
        assertEquals(1L,db.queryForObject("""
            SELECT count(*) FROM fin_calc.htap_mapping_semantic_evidence
             WHERE organization_id=? AND mapping_id=? AND metric_code='REV_NET'
               AND metric_version='1.0.0' AND unit_code='MONEY'
               AND semantic_standard='INTERNAL_FIN_PILOT'
               AND semantic_state='VALIDATED_PILOT_NOT_S2'
               AND NOT execution_permitted
            """,Long.class,org,approved));
        assertThrows(DataAccessException.class,()->db.update("""
            UPDATE fin_calc.htap_mapping_semantic_evidence
               SET unit_code='COUNT' WHERE organization_id=? AND mapping_id=?
            """,org,approved));
        assertThrows(DataAccessException.class,()->governance.validatePilotSemantics(
            org,approved,reviewer),"Semantic evidence must be immutable, not overwritten");

        UUID wrong=governance.propose(org,"F02","FIN-PILOT-V1","MAP-F02-02",
            "{\\"sourceField\\":\\"netRevenue\\",\\"metricCode\\":\\"COGS\\",\\"metricVersion\\":\\"1.0.0\\"}",
            token(org,"author","FIN_SUBMIT"));
        governance.review(org,wrong,true,EVIDENCE,reviewer);
        assertEquals("FIN_HTAP_MAPPING_SEMANTICS_UNQUALIFIED",
            assertThrows(IllegalArgumentException.class,
              ()->governance.validatePilotSemantics(org,wrong,reviewer)).getMessage());
        assertEquals(0L,db.queryForObject("""
            SELECT count(*) FROM fin_exec.htap_blocked_method_plan
            WHERE organization_id=?
            """,Long.class,org));
    }

}
