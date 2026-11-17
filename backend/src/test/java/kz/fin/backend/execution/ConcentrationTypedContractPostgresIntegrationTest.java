package kz.fin.backend.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kz.fin.backend.analytics.ConcentrationAnalysisEngine;
import kz.fin.backend.methodology.ConcentrationTypedParameters;
import kz.fin.backend.methodology.persistence.MethodConfigVersionEntity;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL 16 R2.1: contracts are typed, immutable, and never permits. */
@SpringBootTest(properties = "fin.execution.enabled=false")
@Testcontainers
class ConcentrationTypedContractPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @PersistenceContext EntityManager em;
    private static final String SHA="a".repeat(64);
    private static final String PARETO_PARAMS="""
        {"tieSortPolicy":"AMOUNT_DESC_SKU_CODE_ASC",
         "populationMetric":"NET_SALES",
         "returnsPolicy":"REVIEWED_NET_RECONCILED"}
        """;
    private static final String ABC_PARAMS="""
        {"aLimitPercent":80,"bLimitPercent":95,
         "boundaryCrossingPolicy":"CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED"}
        """;

    private UUID contract(String code,String variant,String profile) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.method_parameter_contract
              (contract_id,method_code,variant_code,contract_version,
               validation_profile,parameter_schema,schema_sha256,authored_by)
            VALUES (?,?,?,?,?,fin_calc.method_typed_schema_r21(?),?,'r21-author')
            """,id,code,variant,"R21-"+UUID.randomUUID(),profile,profile,SHA);
        return id;
    }

    private UUID version(String code,String variant,UUID contract,
                         UUID prior,int number,String parameters,String engine,
                         String algorithmVersion) {
        // This Testcontainers database is shared by the test methods. Preserve
        // the real UNIQUE(method_code,variant_code,version_no) and immutable
        // parent chain instead of trying to create several unrelated v1 rows.
        var latest=jdbc.queryForList("""
            SELECT config_id,version_no FROM fin_calc.method_config_version
             WHERE method_code=? AND variant_code=?
             ORDER BY version_no DESC LIMIT 1
            """,code,variant);
        if(!latest.isEmpty()) {
            var predecessor=(UUID)latest.getFirst().get("config_id");
            if(prior!=null) assertEquals(prior,predecessor,
                    "Test fixture must extend the latest immutable config");
            prior=predecessor;
            number=((Number)latest.getFirst().get("version_no")).intValue()+1;
        }
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.method_config_version
              (config_id,method_code,variant_code,version_no,parent_config_id,
               contract_id,engine,algorithm_id,algorithm_version,algorithm_sha256,
               parameters,params_sha256,effective_sha256,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,
                    CAST(? AS jsonb),?,?,'r21-author')
            """,id,code,variant,number,prior,contract,engine,
                code+"/"+variant,algorithmVersion,SHA,parameters,SHA,SHA);
        return id;
    }

    private UUID paretoVersion(UUID contract,String parameters) {
        return version("CON-01","PARETO_SALES",contract,null,1,parameters,
                "JAVA",ConcentrationAnalysisEngine.PARETO_VERSION);
    }

    private UUID abcVersion(UUID contract,UUID parent,int number,
                            String parameters) {
        return version("CON-02","ABC_SALES",contract,parent,number,parameters,
                "JAVA",ConcentrationAnalysisEngine.ABC_VERSION);
    }

    @Test void typedSchemasAreServerOwnedAndSeparateInputLineage() throws Exception {
        var con01=json.readTree(jdbc.queryForObject("""
            SELECT fin_calc.method_typed_schema_r21('CON01_PARETO_FIXED_V1')::text
            """,String.class));
        var con02=json.readTree(jdbc.queryForObject("""
            SELECT fin_calc.method_typed_schema_r21('CON02_ABC_TYPED_V2')::text
            """,String.class));
        assertEquals(3,con01.path("configProperties").size());
        assertEquals("REPORT_CUTOFF_NOT_IMPLEMENTED",con01
                .path("candidateNotExecutable").path("reportCutoffPercent")
                .path("reason").asText());
        assertEquals(3,con02.path("configProperties").size());
        assertEquals("DECIMAL",con02.path("configProperties")
                .path("aLimitPercent").path("type").asText());
        assertEquals("CON01_PARETO_V1",con02.path("inputLineage")
                .path("sourceParetoVersion").path("equals").asText());
        assertFalse(con02.path("configProperties").has("sourceParetoVersion"));
        assertEquals(60L,jdbc.queryForObject(
                "SELECT count(*) FROM fin_calc.method_family",Long.class));
        assertEquals(259L,jdbc.queryForObject(
                "SELECT count(*) FROM fin_calc.method_parameter_candidate",Long.class));
    }

    @Test void paretoRequiresExplicitFixedSemanticsAndRejectsInventedFeatures() throws Exception {
        UUID contract=contract("CON-01","PARETO_SALES","CON01_PARETO_FIXED_V1");
        UUID config=paretoVersion(contract,PARETO_PARAMS);
        var persisted=em.find(MethodConfigVersionEntity.class,config);
        assertNotNull(persisted);
        var parsed=ConcentrationTypedParameters.pareto(
                json.valueToTree(persisted.getParameters()));
        assertEquals("NET_SALES",parsed.populationMetric());
        assertEquals("AMOUNT_DESC_SKU_CODE_ASC",parsed.tieSortPolicy());
        for(String bad:List.of(
                "{}",
                "{\"tieSortPolicy\":\"NET_SALES_ASC_SKU_ASC\",\"populationMetric\":\"NET_SALES\",\"returnsPolicy\":\"REVIEWED_NET_RECONCILED\"}",
                "{\"tieSortPolicy\":\"AMOUNT_DESC_SKU_CODE_ASC\",\"populationMetric\":99,\"returnsPolicy\":\"REVIEWED_NET_RECONCILED\"}",
                "{\"tieSortPolicy\":\"AMOUNT_DESC_SKU_CODE_ASC\",\"populationMetric\":\"100\",\"returnsPolicy\":\"REVIEWED_NET_RECONCILED\"}",
                "{\"tieSortPolicy\":\"AMOUNT_DESC_SKU_CODE_ASC\",\"populationMetric\":\"NET_SALES\",\"unexpected\":\"x\"}",
                "{\"tieSortPolicy\":\"AMOUNT_DESC_SKU_CODE_ASC\",\"populationMetric\":\"NET_SALES\",\"returnsPolicy\":\"REVIEWED_NET_RECONCILED\",\"reportCutoffPercent\":80}")) {
            assertThrows(DataAccessException.class, () ->
                    version("CON-01","PARETO_SALES",contract,config,2,bad,
                            "JAVA",ConcentrationAnalysisEngine.PARETO_VERSION),bad);
            assertThrows(IllegalArgumentException.class, () ->
                    ConcentrationTypedParameters.pareto(json.readTree(bad)),bad);
        }
        assertThrows(DataAccessException.class, () ->
                jdbc.update("DELETE FROM fin_calc.method_parameter_contract WHERE contract_id=?",
                        contract));
        assertThrows(DataAccessException.class, () ->
                jdbc.update("UPDATE fin_calc.method_config_version SET parameters='{}'::jsonb WHERE config_id=?",
                        config));
    }

    @Test void abcConfigVersionsChangeHashAndFinancialClassificationOnlyThroughExplicitValues()
            throws Exception {
        UUID contract=contract("CON-02","ABC_SALES","CON02_ABC_TYPED_V2");
        UUID v1=abcVersion(contract,null,1,ABC_PARAMS);
        UUID v2=abcVersion(contract,v1,2,"""
            {"bLimitPercent":90.0,"aLimitPercent":75.00,
             "boundaryCrossingPolicy":"CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED"}
            """);
        var rows=jdbc.queryForList("""
            SELECT params_sha256,effective_sha256,algorithm_sha256,parameters::text AS p
              FROM fin_calc.method_config_version
             WHERE config_id IN (?,?) ORDER BY version_no
            """,v1,v2);
        assertEquals(rows.get(0).get("algorithm_sha256"),rows.get(1).get("algorithm_sha256"));
        assertNotEquals(rows.get(0).get("params_sha256"),rows.get(1).get("params_sha256"));
        assertNotEquals(rows.get(0).get("effective_sha256"),rows.get(1).get("effective_sha256"));
        var source=ConcentrationAnalysisEngine.pareto(
                List.of(new ConcentrationAnalysisEngine.SkuAmount("A",new BigDecimal("77")),
                        new ConcentrationAnalysisEngine.SkuAmount("B",new BigDecimal("20")),
                        new ConcentrationAnalysisEngine.SkuAmount("C",new BigDecimal("3"))),
                new BigDecimal("100"));
        var policy1=ConcentrationTypedParameters.abc(
                json.readTree((String)rows.get(0).get("p")),source.methodVersion());
        var policy2=ConcentrationTypedParameters.abc(
                json.readTree((String)rows.get(1).get("p")),source.methodVersion());
        assertEquals("A",ConcentrationAnalysisEngine.abc(source,policy1)
                .rows().get(1).classification());
        assertEquals("B",ConcentrationAnalysisEngine.abc(source,policy2)
                .rows().get(1).classification());
        assertThrows(IllegalArgumentException.class,() ->
                ConcentrationTypedParameters.abc(json.readTree(ABC_PARAMS),"CON01_OLD"));
        assertThrows(DataAccessException.class,() ->
                abcVersion(contract,v2,3,"""
                  {"aLimitPercent":80.0,"bLimitPercent":95.00,
                   "boundaryCrossingPolicy":"CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED"}
                  """),"Same effective configuration must not be duplicated by numeric formatting");
    }

    @Test void abcPolicyLineageAndTypeMismatchCannotPassPostgresOrPureDecoder()
            throws Exception {
        UUID contract=contract("CON-02","ABC_SALES","CON02_ABC_TYPED_V2");
        for(String bad:List.of(
                "{\"aLimitPercent\":80,\"bLimitPercent\":95}",
                "{\"aLimitPercent\":\"80\",\"bLimitPercent\":95,\"boundaryCrossingPolicy\":\"CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED\"}",
                "{\"aLimitPercent\":80,\"bLimitPercent\":95,\"boundaryCrossingPolicy\":\"CROSSING_ITEM_CURRENT_CLASS\"}",
                "{\"aLimitPercent\":80,\"bLimitPercent\":95,\"sourceParetoVersion\":\"CON01_PARETO_V1\"}",
                "{\"aLimitPercent\":80,\"bLimitPercent\":95,\"boundaryCrossingPolicy\":\"CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED\",\"sourceParetoVersion\":\"CON01_PARETO_V1\"}",
                "{\"aLimitPercent\":80,\"bLimitPercent\":80,\"boundaryCrossingPolicy\":\"CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED\"}",
                "{\"aLimitPercent\":0,\"bLimitPercent\":95,\"boundaryCrossingPolicy\":\"CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED\"}")) {
            assertThrows(DataAccessException.class,() ->
                    abcVersion(contract,null,1,bad),bad);
            assertThrows(IllegalArgumentException.class,() ->
                    ConcentrationTypedParameters.abc(json.readTree(bad),
                            ConcentrationAnalysisEngine.PARETO_VERSION),bad);
        }
        assertThrows(DataAccessException.class,() -> version(
                "CON-02","ABC_SALES",contract,null,1,ABC_PARAMS,
                "SQL",ConcentrationAnalysisEngine.ABC_VERSION));
        assertThrows(DataAccessException.class,() -> version(
                "CON-02","ABC_SALES",contract,null,1,ABC_PARAMS,
                "JAVA","UNSUPPORTED_ALGORITHM_V99"));
    }

    @Test void forgedTypedSchemasAndWrongVariantsCannotBeRegistered() {
        assertThrows(DataAccessException.class,() -> jdbc.update("""
            INSERT INTO fin_calc.method_parameter_contract
              (contract_id,method_code,variant_code,contract_version,
               validation_profile,parameter_schema,schema_sha256,authored_by)
            VALUES (?,'CON-02','ABC_SALES',?,
                    'CON02_ABC_TYPED_V2','{}'::jsonb,?,'r21-author')
            """,UUID.randomUUID(),"FORGED-"+UUID.randomUUID(),SHA));
        assertThrows(DataAccessException.class,() -> jdbc.update("""
            INSERT INTO fin_calc.method_parameter_contract
              (contract_id,method_code,variant_code,contract_version,
               validation_profile,parameter_schema,schema_sha256,authored_by)
            VALUES (?,'FIN-01','REV_NET_MOM',?,
                    'CON01_PARETO_FIXED_V1',
                    fin_calc.method_typed_schema_r21('CON01_PARETO_FIXED_V1'),
                    ?,'r21-author')
            """,UUID.randomUUID(),"WRONG-"+UUID.randomUUID(),SHA));
    }

    @Test void approvedParameterConfigurationStillHasNoActualExecutionPermit() {
        UUID contract=contract("CON-02","ABC_SALES","CON02_ABC_TYPED_V2");
        UUID config=abcVersion(contract,null,1,ABC_PARAMS);
        assertThrows(DataAccessException.class,() -> jdbc.update("""
            INSERT INTO fin_calc.method_config_review
              (review_id,config_id,submitted_by,reviewed_by,decision,
               evidence_sha256,review_reason)
            VALUES (?,?,'r21-author','r21-author','APPROVED',?,
                    'Must reject self-review')
            """,UUID.randomUUID(),config,SHA));
        jdbc.update("""
            INSERT INTO fin_calc.method_config_review
              (review_id,config_id,submitted_by,reviewed_by,decision,
               evidence_sha256,review_reason)
            VALUES (?,?,'r21-author','independent-r21-reviewer','APPROVED',?,
                    'Typed engineering contract verified, not financial S2')
            """,UUID.randomUUID(),config,SHA);
        jdbc.update("""
            INSERT INTO fin_calc.method_active_config
              (method_code,variant_code,config_id,activated_by)
            VALUES ('CON-02','ABC_SALES',?,'independent-r21-reviewer')
            """,config);
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.methodology_catalog_view
             WHERE method_execution_permitted OR release_qualified OR auto_run_allowed
            """,Long.class));
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.method_s2_review
            WHERE method_code='CON-02/ABC_SALES'
            """,Long.class));
    }

    @Test void r24bSemanticBindingRejectsFalseConfigAndStillGrantsNoPermit() throws Exception {
        var bindings=jdbc.queryForList("""
            SELECT method_code,candidate_code,semantic_kind,runtime_key,
                   review_state,approved_by_binding,executable_by_binding
            FROM fin_calc.concentration_parameter_binding_v1
            ORDER BY method_code,candidate_code
            """);
        assertEquals(8,bindings.size());
        assertEquals(6,bindings.stream()
                .filter(m -> "CONFIG_TYPED".equals(m.get("semantic_kind"))).count());
        assertEquals(1,bindings.stream()
                .filter(m -> "UNSUPPORTED_REPORT_ONLY".equals(m.get("semantic_kind"))).count());
        assertEquals(1,bindings.stream()
                .filter(m -> "UPSTREAM_RESULT_LINEAGE".equals(m.get("semantic_kind"))).count());
        for(var row:bindings) {
            assertEquals("PROPOSED_UNREVIEWED",row.get("review_state"));
            assertEquals(false,row.get("approved_by_binding"));
            assertEquals(false,row.get("executable_by_binding"));
        }
        var cutoff=bindings.stream()
                .filter(m -> "reportCutoffPercent".equals(m.get("candidate_code")))
                .findFirst().orElseThrow();
        assertEquals("UNSUPPORTED_REPORT_ONLY",cutoff.get("semantic_kind"));
        assertNull(cutoff.get("runtime_key"));
        var upstream=bindings.stream()
                .filter(m -> "sourceParetoVersion".equals(m.get("candidate_code")))
                .findFirst().orElseThrow();
        assertEquals("UPSTREAM_RESULT_LINEAGE",upstream.get("semantic_kind"));
        assertNull(upstream.get("runtime_key"));

        var reportOnly=json.readTree("""
            {"tieSortPolicy":"AMOUNT_DESC_SKU_CODE_ASC","populationMetric":"NET_SALES",
             "returnsPolicy":"REVIEWED_NET_RECONCILED","reportCutoffPercent":80}
            """);
        assertThrows(IllegalArgumentException.class,
                () -> ConcentrationTypedParameters.pareto(reportOnly));
        var injectedLineage=json.readTree("""
            {"aLimitPercent":80,"bLimitPercent":95,
             "boundaryCrossingPolicy":"CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED",
             "sourceParetoVersion":"CON01_PARETO_V1"}
            """);
        assertThrows(IllegalArgumentException.class,
                () -> ConcentrationTypedParameters.abc(injectedLineage,
                        ConcentrationAnalysisEngine.PARETO_VERSION));
    }

    @Test void r24bGoldenBoundariesUseExactContributionsAndNotRoundedDisplay() throws Exception {
        var source=ConcentrationAnalysisEngine.pareto(List.of(
                new ConcentrationAnalysisEngine.SkuAmount("SKU_A",new BigDecimal("79.999999999999")),
                new ConcentrationAnalysisEngine.SkuAmount("SKU_B",new BigDecimal("15.000000000001")),
                new ConcentrationAnalysisEngine.SkuAmount("SKU_C",new BigDecimal("5.000000000000"))),
                new BigDecimal("100.000000000000"));
        var policy=ConcentrationTypedParameters.abc(
                json.readTree(ABC_PARAMS),source.methodVersion());
        var classified=ConcentrationAnalysisEngine.abc(source,policy);
        assertEquals(List.of("A","A","B"),classified.rows().stream()
                .map(ConcentrationAnalysisEngine.AbcRow::classification).toList());
        // Unrounded cumulative amount BEFORE the next SKU is used for boundaries.
        var strict=json.readTree("""
            {"aLimitPercent":79.999999999998,"bLimitPercent":95,
             "boundaryCrossingPolicy":"CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED"}
            """);
        var changed=ConcentrationTypedParameters.abc(strict,source.methodVersion());
        assertEquals(List.of("A","B","B"),
                ConcentrationAnalysisEngine.abc(source,changed).rows().stream()
                        .map(ConcentrationAnalysisEngine.AbcRow::classification).toList());

        for(String bad:List.of("0","100","-0.1")) {
            var params=json.readTree("""
                {"aLimitPercent":%s,"bLimitPercent":95,
                 "boundaryCrossingPolicy":"CROSSING_ITEM_PREVIOUS_CLASS_UNROUNDED"}
                """.formatted(bad));
            assertThrows(IllegalArgumentException.class,
                    () -> ConcentrationTypedParameters.abc(params,source.methodVersion()));
        }
    }

}
