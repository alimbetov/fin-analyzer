package kz.fin.backend.methodology;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.backend.analytics.ConcentrationAnalysisEngine;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL 16 integrity proof of non-executable, immutable methodology editions. */
@SpringBootTest(properties="fin.execution.enabled=false")
@Testcontainers
class MethodVariantEditionPresetPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
    }

    private static final String FAKE_HASH="a".repeat(64);
    private static final String GRID="""
        {"mode":"GRID_NEAR_BASE","generatorVersion":"CON02_GRID_NEAR_BASE_V1",
         "baseA":80,"baseB":95,"stepPercentPoints":5}
        """;
    private static final String FIXED="""
        {"mode":"FIXED_ONE","generatorVersion":"CON01_FIXED_ONE_V1"}
        """;

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MethodVariantPresetReadService readService;

    private UUID contract(String code,String variant,String profile) {
        UUID id=UUID.randomUUID();
        String schema = "NOT_EXECUTABLE".equals(profile)
                ? "{}"
                : jdbc.queryForObject(
                    "SELECT fin_calc.method_typed_schema_r21(?)::text",
                    String.class,profile);
        jdbc.update("""
            INSERT INTO fin_calc.method_parameter_contract
                (contract_id,method_code,variant_code,contract_version,
                 validation_profile,parameter_schema,schema_sha256,authored_by)
            VALUES (?,?,?,?,?,CAST(? AS jsonb),?,'editor')
            """,id,code,variant,"ED-"+UUID.randomUUID(),profile,schema,FAKE_HASH);
        return id;
    }

    private UUID edition(String method,String variant,int editionNo,UUID contract,
                         String algorithm,String policy) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.method_variant_edition
                (edition_id,method_code,variant_code,edition_no,contract_id,
                 algorithm_version,generation_policy,description,authored_by)
            VALUES (?,?,?,?,?,?,CAST(? AS jsonb),'Editorial option set','editor')
            """,id,method,variant,editionNo,contract,algorithm,policy);
        return id;
    }

    private String abc(int a,int b) {
        return """
            {"aLimitPercent":%d,"bLimitPercent":%d,
             "boundaryCrossingPolicy":"CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED"}
            """.formatted(a,b);
    }

    private void option(UUID edition,int ordinal,String parameters) {
        jdbc.update("""
            INSERT INTO fin_calc.method_variant_edition_option
                 (edition_id,option_no,parameters,params_sha256,option_sha256)
            VALUES (?,?,CAST(? AS jsonb),?,?)
            """,edition,ordinal,parameters,FAKE_HASH,FAKE_HASH);
    }

    private void freeze(UUID edition) {
        jdbc.update("""
            INSERT INTO fin_calc.method_variant_edition_freeze
                 (edition_id,option_count,options_sha256,frozen_by)
            VALUES (?,1,?,'editor-not-financial-reviewer')
            """,edition,FAKE_HASH);
    }

    @Test void tenSteppedAbcOptionsAreImmutablePinnedAndNeverExecutable() throws Exception {
        UUID contract=contract("CON-02","ABC_SALES","CON02_ABC_TYPED_V2");
        UUID edition=edition("CON-02","ABC_SALES",101,contract,
                ConcentrationAnalysisEngine.ABC_VERSION,GRID);
        var generated=MethodParameterPresetGenerator.abcNear(
                new BigDecimal("80"),new BigDecimal("95"),
                new BigDecimal("5"),10);
        assertEquals(10,generated.size());
        for(var preset:generated)
            option(edition,preset.optionNo(),json.writeValueAsString(preset.parameters()));

        // An incomplete edition may exist, but must not be offered for use.
        assertTrue(readService.choices("CON-02","ABC_SALES").stream()
                .noneMatch(e->e.editionId().equals(edition)));
        assertThrows(DataAccessException.class,() -> option(edition,11,abc(70,90)));
        freeze(edition);

        var saved=readService.choices("CON-02","ABC_SALES").stream()
                .filter(e->e.editionId().equals(edition)).findFirst().orElseThrow();
        assertEquals(10,saved.choices().size());
        assertEquals(101,saved.editionNo());
        assertEquals(contract,saved.contractId());
        assertEquals(64,saved.optionsSha256().length());
        assertNotEquals(FAKE_HASH,saved.optionsSha256());
        assertFalse(saved.methodS2Qualified());
        assertFalse(saved.executionPermitted());
        assertNotEquals(saved.choices().getFirst().optionSha256(),
                saved.choices().get(1).optionSha256());
        for(var entry:saved.choices()) {
            assertEquals(64,entry.parametersSha256().length());
            assertEquals(64,entry.optionSha256().length());
            assertNotEquals(FAKE_HASH,entry.parametersSha256());
            assertNotEquals(FAKE_HASH,entry.optionSha256());
        }
        assertThrows(DataAccessException.class,() -> option(edition,11,abc(70,90)));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE fin_calc.method_variant_edition_option
               SET parameters='{}'::jsonb WHERE edition_id=?
            """,edition));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            DELETE FROM fin_calc.method_variant_edition_freeze WHERE edition_id=?
            """,edition));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            DELETE FROM fin_calc.method_variant_edition WHERE edition_id=?
            """,edition));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.method_config_version
            WHERE method_code='CON-02'
            """,Long.class));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.method_active_config
            WHERE method_code='CON-02'
            """,Long.class));
    }

    @Test void invalidTypedOrOffGridOptionsCannotBeFrozen() {
        UUID contract=contract("CON-02","ABC_SALES","CON02_ABC_TYPED_V2");
        UUID edition=edition("CON-02","ABC_SALES",102,contract,
                ConcentrationAnalysisEngine.ABC_VERSION,GRID);
        assertThrows(DataAccessException.class,() -> freeze(edition));
        assertThrows(DataAccessException.class,() -> option(edition,1,abc(77,95)));
        assertThrows(DataAccessException.class,() -> option(edition,1,abc(40,60)),
                "A grid-aligned option far from the reviewed candidate base is invalid");
        assertThrows(DataAccessException.class,() -> option(edition,1,abc(95,90)));
        assertThrows(DataAccessException.class,() -> option(edition,1,abc(80,100)));
        assertThrows(DataAccessException.class,() -> option(edition,1,"""
            {"aLimitPercent":"80","bLimitPercent":95,
             "boundaryCrossingPolicy":"CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED"}
            """));
        assertThrows(DataAccessException.class,() -> option(edition,1,"""
            {"aLimitPercent":80,"bLimitPercent":95,
             "boundaryCrossingPolicy":"UNIMPLEMENTED_POLICY"}
            """));
        assertThrows(DataAccessException.class,() -> option(edition,1,"""
            {"aLimitPercent":80,"bLimitPercent":95,
             "sourceParetoVersion":"CON01_PARETO_V1"}
            """));
        option(edition,1,abc(80,95));
        assertThrows(DataAccessException.class,() -> option(edition,2,abc(80,95)),
                "Duplicate semantics must be rejected independent of ordinal");
        option(edition,2,abc(75,95));
        freeze(edition);
        var snapshot=readService.choices("CON-02","ABC_SALES").stream()
                .filter(e->e.editionId().equals(edition)).findFirst().orElseThrow();
        assertEquals(2,snapshot.choices().size());
    }

    @Test void paretoAllowsOnlyOneFixedPresetAndOtherFamiliesRemainBlocked() throws Exception {
        UUID contract=contract("CON-01","PARETO_SALES","CON01_PARETO_FIXED_V1");
        UUID edition=edition("CON-01","PARETO_SALES",201,contract,
                ConcentrationAnalysisEngine.PARETO_VERSION,FIXED);
        String fixed=json.writeValueAsString(
                MethodParameterPresetGenerator.fixedPareto().getFirst().parameters());
        option(edition,1,fixed);
        assertThrows(DataAccessException.class,() -> option(edition,2,fixed));
        assertThrows(DataAccessException.class,() -> option(edition,2,"{}"));
        freeze(edition);
        var result=readService.choices("CON-01","PARETO_SALES");
        assertEquals(1,result.stream().filter(e->e.editionId().equals(edition))
                .findFirst().orElseThrow().choices().size());
        assertThrows(DataAccessException.class,()->edition(
                "CON-01","PARETO_SALES",202,contract,
                ConcentrationAnalysisEngine.PARETO_VERSION,GRID));

        UUID unqualified=contract("FIN-03","UNREVIEWED","NOT_EXECUTABLE");
        assertThrows(DataAccessException.class,()->edition(
                "FIN-03","UNREVIEWED",1,unqualified,"ANY",FIXED));
    }

    @Test void newEditorialEditionChangesSetHashButDoesNotRewriteOldEdition() {
        UUID contract=contract("CON-02","ABC_SALES","CON02_ABC_TYPED_V2");
        UUID oldEdition=edition("CON-02","ABC_SALES",301,contract,
                ConcentrationAnalysisEngine.ABC_VERSION,GRID);
        option(oldEdition,1,abc(80,95));
        freeze(oldEdition);
        String oldHash=jdbc.queryForObject("""
            SELECT options_sha256 FROM fin_calc.method_variant_edition_freeze
            WHERE edition_id=?
            """,String.class,oldEdition);

        UUID revised=edition("CON-02","ABC_SALES",302,contract,
                ConcentrationAnalysisEngine.ABC_VERSION,GRID);
        option(revised,1,abc(75,90));
        freeze(revised);
        String newHash=jdbc.queryForObject("""
            SELECT options_sha256 FROM fin_calc.method_variant_edition_freeze
            WHERE edition_id=?
            """,String.class,revised);
        assertNotEquals(oldHash,newHash);
        assertEquals(oldHash,jdbc.queryForObject("""
            SELECT options_sha256 FROM fin_calc.method_variant_edition_freeze
            WHERE edition_id=?
            """,String.class,oldEdition));
        assertThrows(DataAccessException.class,() -> jdbc.update("""
            UPDATE fin_calc.method_variant_edition SET edition_no=999
            WHERE edition_id=?
            """,oldEdition));
        assertThrows(DataAccessException.class,() -> edition("CON-02","ABC_SALES",
                301,contract,ConcentrationAnalysisEngine.ABC_VERSION,GRID));
    }
}
