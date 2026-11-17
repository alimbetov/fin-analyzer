package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.InputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIN HTAP MR-R0: PostgreSQL 16 proofs for the one-table 60-family read model
 * and immutable versioned method parameter moderation. Approval of a config
 * is NEVER method S2 or ACTUAL execution permission.
 */
@SpringBootTest
@Testcontainers
class MethodologyConfigurationRegistryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fin")
            .withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired kz.fin.backend.methodology.persistence.MethodologyCatalogReadService methodReadService;
    @Autowired kz.fin.backend.methodology.persistence.MethodologyRepository methodRepository;
    private static final String ZERO_SHA = "a".repeat(64);

    private UUID contract(String code, String variant, String profile) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.method_parameter_contract
              (contract_id,method_code,variant_code,contract_version,
               validation_profile,parameter_schema,schema_sha256,authored_by)
            VALUES (?,?,?,?,?,CAST(? AS jsonb),?,'config-author')
            """, id, code, variant, "TEST-"+UUID.randomUUID(), profile,
            "{\"type\":\"object\",\"required\":[\"aLimitPercent\",\"bLimitPercent\"]}", ZERO_SHA);
        return id;
    }

    private UUID version(String code, String variant, UUID contract, UUID prior,
                         int number, String parameterJson) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.method_config_version
              (config_id,method_code,variant_code,version_no,parent_config_id,
               contract_id,engine,algorithm_id,algorithm_version,algorithm_sha256,
               parameters,params_sha256,effective_sha256,created_by)
            VALUES (?,?,?,?,?,?,'JAVA',?,'CON02_ABC_BOUNDARY_V1',?,
                    CAST(? AS jsonb),?,?,'config-author')
            """, id, code, variant, number, prior, contract,
            code + "/" + variant, ZERO_SHA, parameterJson, ZERO_SHA, ZERO_SHA);
        return id;
    }

    private void review(UUID config, String submitted, String reviewed, String decision) {
        jdbc.update("""
            INSERT INTO fin_calc.method_config_review
              (review_id,config_id,submitted_by,reviewed_by,decision,
               evidence_sha256,review_reason)
            VALUES (?,?,?,?,?,?,'Synthetic independent parameter review')
            """, UUID.randomUUID(), config, submitted, reviewed, decision, ZERO_SHA);
    }

    @Test void methodologyEntityContainsAll259PersistedParameterCandidates() {
        var catalogue = methodReadService.allWithParameters();
        assertEquals(60, catalogue.size());
        assertEquals(259, catalogue.stream().mapToInt(m -> m.parameters().size()).sum());
        assertEquals(259L, jdbc.queryForObject("""
                SELECT count(*) FROM fin_calc.method_parameter_candidate
                """, Long.class));
        assertEquals(60L, jdbc.queryForObject("""
                SELECT count(DISTINCT method_code)
                FROM fin_calc.method_parameter_candidate
                """, Long.class));

        var abc = catalogue.stream().filter(m -> m.code().equals("CON-02"))
                .findFirst().orElseThrow();
        assertEquals(java.util.Set.of("aLimitPercent", "bLimitPercent",
                "boundaryCrossingPolicy", "sourceParetoVersion"),
                abc.parameters().stream().map(p -> p.code()).collect(
                        java.util.stream.Collectors.toSet()));
        assertTrue(abc.parameters().stream().allMatch(p ->
                "PROPOSED_UNREVIEWED".equals(p.reviewState())));
        assertFalse(abc.releaseQualified());
        assertFalse(abc.autoRunAllowed());

        assertEquals(60, methodRepository.count());
        var persisted = methodRepository.findAllWithParameterCandidates().stream()
                .filter(m -> m.getMethodCode().equals("FIN-01"))
                .findFirst().orElseThrow();
        assertEquals("Horizontal Analysis", persisted.getTitle());
        assertEquals(5, persisted.getParameters().size());
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                UPDATE fin_calc.method_parameter_candidate
                SET proposed_type='DECIMAL' WHERE method_code='CON-02'
                """));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                DELETE FROM fin_calc.method_parameter_candidate
                WHERE method_code='CON-02' AND parameter_code='aLimitPercent'
                """));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM fin_calc.methodology_catalog_view
                WHERE method_execution_permitted OR release_qualified OR auto_run_allowed
                """, Long.class));
        assertEquals(259L, jdbc.queryForObject("""
                SELECT sum(jsonb_array_length(parameter_candidates))
                FROM fin_calc.methodology_catalog_view
                """, Long.class));
        assertEquals(4, jdbc.queryForObject("""
                SELECT jsonb_array_length(parameter_candidates)
                FROM fin_calc.methodology_catalog_view WHERE method_code='CON-02'
                """, Integer.class));
    }

    @Test void allSixtyFamiliesAppearExactlyOnceInOneQueryableTable() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/fin/methodology/methodology-catalog-r1.json")) {
            assertNotNull(stream);
            var catalogue = new ObjectMapper().readTree(stream).path("methods");
            assertEquals(60, catalogue.size());
            assertEquals(60L, jdbc.queryForObject(
                    "SELECT count(*) FROM fin_calc.methodology_catalog_view",Long.class));
            assertEquals(0L, jdbc.queryForObject("""
                    SELECT count(*) FROM fin_calc.methodology_catalog_view
                     WHERE release_qualified OR auto_run_allowed OR method_execution_permitted
                    """, Long.class));
            for (var m : catalogue) {
                var actual = jdbc.queryForMap("""
                    SELECT method_group,title,implementation_status
                    FROM fin_calc.method_family WHERE method_code=?
                    """, m.path("code").asText());
                assertEquals(m.path("group").asText(), actual.get("method_group"));
                assertEquals(m.path("title").asText(), actual.get("title"));
                assertEquals(m.path("implementationStatus").asText(),
                        actual.get("implementation_status"));
            }
        }
    }

    @Test void abcThresholdParameterChangesMakeNewVersionAndSHAWithoutChangingAlgorithm() {
        UUID schema = contract("CON-02","ABC_SALES","CON02_ABC_BOUNDARIES_V1");
        UUID initial = version("CON-02","ABC_SALES",schema,null,1,
                "{\"aLimitPercent\":80,\"bLimitPercent\":95}");
        UUID revised = version("CON-02","ABC_SALES",schema,initial,2,
                "{\"aLimitPercent\":75,\"bLimitPercent\":90}");

        var v1 = jdbc.queryForMap("""
                SELECT algorithm_version,params_sha256,effective_sha256,parameters::text AS params
                FROM fin_calc.method_config_version WHERE config_id=?
                """,initial);
        var v2 = jdbc.queryForMap("""
                SELECT algorithm_version,params_sha256,effective_sha256,parameters::text AS params
                FROM fin_calc.method_config_version WHERE config_id=?
                """,revised);
        assertEquals(v1.get("algorithm_version"),v2.get("algorithm_version"));
        assertNotEquals(v1.get("params_sha256"),v2.get("params_sha256"));
        assertNotEquals(v1.get("effective_sha256"),v2.get("effective_sha256"));
        assertEquals(64, ((String)v2.get("effective_sha256")).length());
        // JSONB key order cannot impersonate a "new" parameter version.
        assertThrows(DataAccessException.class, () -> version("CON-02","ABC_SALES",
                schema,revised,3,"{\"bLimitPercent\":95,\"aLimitPercent\":80}"));
        assertThrows(DataAccessException.class, () -> version("CON-02","ABC_SALES",
                schema,revised,4,"{\"aLimitPercent\":80.00,\"bLimitPercent\":95.0}"),
                "Numeric display scale must not create a different financial config SHA");

        // New version is initially pending moderation. It cannot become active.
        assertThrows(DataAccessException.class,() -> jdbc.update("""
                INSERT INTO fin_calc.method_active_config
                 (method_code,variant_code,config_id,activated_by)
                VALUES ('CON-02','ABC_SALES',?,'moderator')
                """,initial));

        assertThrows(DataAccessException.class,
                () -> review(initial,"config-author","config-author","APPROVED"));
        assertThrows(DataAccessException.class,
                () -> review(initial,"forged-submitter","moderator","APPROVED"));
        review(initial,"config-author","moderator","APPROVED");
        jdbc.update("""
                INSERT INTO fin_calc.method_active_config
                 (method_code,variant_code,config_id,activated_by)
                VALUES ('CON-02','ABC_SALES',?,'moderator')
                """,initial);
        assertThrows(DataAccessException.class,()->jdbc.update("""
                UPDATE fin_calc.method_active_config
                SET config_id=?,pointer_version=pointer_version+1,
                    activated_by='moderator'
                WHERE method_code='CON-02' AND variant_code='ABC_SALES'
                """,revised));

        review(revised,"config-author","another-moderator","APPROVED");
        assertEquals(1, jdbc.update("""
                UPDATE fin_calc.method_active_config
                SET config_id=?,pointer_version=pointer_version+1,
                    activated_by='another-moderator'
                WHERE method_code='CON-02' AND variant_code='ABC_SALES'
                    AND pointer_version=1
                """,revised));
        assertEquals(0, jdbc.update("""
                UPDATE fin_calc.method_active_config
                SET config_id=?,pointer_version=pointer_version+1,
                    activated_by='moderator'
                WHERE method_code='CON-02' AND variant_code='ABC_SALES'
                    AND pointer_version=1
                """,initial));
        assertEquals(2L, jdbc.queryForObject("""
                SELECT count(*) FROM fin_calc.method_config_activation
                WHERE method_code='CON-02' AND variant_code='ABC_SALES'
                """,Long.class));
        assertEquals(2L, jdbc.queryForObject("""
                SELECT pointer_version FROM fin_calc.method_active_config
                WHERE method_code='CON-02' AND variant_code='ABC_SALES'
                """,Long.class));
        assertEquals(60L, jdbc.queryForObject(
                "SELECT count(*) FROM fin_calc.methodology_catalog_view",Long.class));
        assertEquals(1L, jdbc.queryForObject("""
                SELECT active_variant_count FROM fin_calc.methodology_catalog_view
                 WHERE method_code='CON-02'
                """,Long.class));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*) FROM fin_calc.methodology_catalog_view
                WHERE method_execution_permitted OR release_qualified OR auto_run_allowed
                """,Long.class));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("DELETE FROM fin_calc.method_config_version WHERE config_id=?",initial));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE fin_calc.method_config_review SET decision='REJECTED' WHERE config_id=?",revised));
    }

    @Test void invalidAbcThresholdsUnknownFieldsAndForgedHashesFailClosed() {
        UUID schema=contract("CON-02","ABC_SALES","CON02_ABC_BOUNDARIES_V1");
        for(String bad:new String[]{
                "{\"aLimitPercent\":0,\"bLimitPercent\":95}",
                "{\"aLimitPercent\":80,\"bLimitPercent\":80}",
                "{\"aLimitPercent\":90,\"bLimitPercent\":100}",
                "{\"aLimitPercent\":\"80\",\"bLimitPercent\":95}",
                "{\"aLimitPercent\":80,\"bLimitPercent\":95,\"sql\":\"DROP TABLE x\"}"}) {
            assertThrows(DataAccessException.class,
                    () -> version("CON-02","ABC_SALES",schema,null,1,bad),bad);
        }
        assertThrows(DataAccessException.class,
                () -> jdbc.update("""
                    INSERT INTO fin_calc.method_parameter_contract
                    (contract_id,method_code,variant_code,contract_version,
                     validation_profile,parameter_schema,schema_sha256,authored_by)
                    VALUES (?,'CON-01','ABC_SALES','1','CON02_ABC_BOUNDARIES_V1',
                            '{}'::jsonb,?,'config-author')
                    """,UUID.randomUUID(),ZERO_SHA));
    }

    @Test void unqualifiedFamiliesMayHaveProposalsButCannotBeApprovedToRun() {
        UUID schema = contract("FIN-05","FACTOR_DECOMPOSITION","NOT_EXECUTABLE");
        UUID config = version("FIN-05","FACTOR_DECOMPOSITION",schema,null,1,"{}");
        assertThrows(DataAccessException.class,() ->
                review(config,"config-author","moderator","APPROVED"));
        review(config,"config-author","moderator","REJECTED");
        assertThrows(DataAccessException.class,() -> jdbc.update("""
                INSERT INTO fin_calc.method_active_config
                (method_code,variant_code,config_id,activated_by)
                VALUES ('FIN-05','FACTOR_DECOMPOSITION',?,'moderator')
                """,config));
    }

    @Test void postgresOwnsBothHashesAndJsonKeyOrderDoesNotChangeIdentity() {
        String first=jdbc.queryForObject("""
                SELECT fin_calc.method_params_sha_v1(?::jsonb)
                """,String.class,"{\"aLimitPercent\":80,\"bLimitPercent\":95}");
        String reordered=jdbc.queryForObject("""
                SELECT fin_calc.method_params_sha_v1(?::jsonb)
                """,String.class,"{\"bLimitPercent\":95,\"aLimitPercent\":80}");
        assertEquals(first,reordered);
        assertEquals(64,first.length());
        assertNotEquals(first,jdbc.queryForObject("""
                SELECT fin_calc.method_params_sha_v1(?::jsonb)
                """,String.class,"{\"aLimitPercent\":75,\"bLimitPercent\":95}"));
    }

    @Test void pendingAndRejectedProposalsAreVisibleWithoutRemovingAnyFamily() {
        UUID schema = contract("FIN-06","INDEX_ANALYSIS","NOT_EXECUTABLE");
        UUID config = version("FIN-06","INDEX_ANALYSIS",schema,null,1,"{}");
        assertEquals("PENDING", jdbc.queryForObject("""
                SELECT latest_variant_configs->0->>'moderation'
                FROM fin_calc.methodology_catalog_view WHERE method_code='FIN-06'
                """, String.class));
        review(config,"config-author","moderator","REJECTED");
        assertEquals("REJECTED", jdbc.queryForObject("""
                SELECT latest_variant_configs->0->>'moderation'
                FROM fin_calc.methodology_catalog_view WHERE method_code='FIN-06'
                """, String.class));
        assertEquals(0L,jdbc.queryForObject("""
                SELECT active_variant_count FROM fin_calc.methodology_catalog_view
                WHERE method_code='FIN-06'
                """,Long.class));
        assertEquals(60L,jdbc.queryForObject("""
                SELECT count(*) FROM fin_calc.methodology_catalog_view
                """,Long.class));
    }

    @Test void engineeringEvidenceCanProgressWithoutPromotingFinancialQualification() {
        assertEquals("CODE_CANDIDATE_NOT_QUALIFIED",jdbc.queryForObject("""
                SELECT implementation_status FROM fin_calc.methodology_catalog_view
                WHERE method_code='FIN-03'
                """,String.class));
        jdbc.update("""
                INSERT INTO fin_calc.method_implementation_evidence
                  (method_code,implementation_status,evidence_sha256,recorded_by,reason)
                VALUES ('FIN-03','INTEGRATION_TESTED_UNQUALIFIED',?,
                        'engineering-reviewer','Synthetic code integration test evidence')
                """,ZERO_SHA);
        assertEquals("INTEGRATION_TESTED_UNQUALIFIED",jdbc.queryForObject("""
                SELECT implementation_status FROM fin_calc.methodology_catalog_view
                WHERE method_code='FIN-03'
                """,String.class));
        assertEquals(0L,jdbc.queryForObject("""
                SELECT count(*) FROM fin_calc.methodology_catalog_view
                WHERE release_qualified OR auto_run_allowed OR method_execution_permitted
                """,Long.class));
        assertThrows(DataAccessException.class,() -> jdbc.update("""
                DELETE FROM fin_calc.method_implementation_evidence
                WHERE method_code='FIN-03'
                """));
    }
}
