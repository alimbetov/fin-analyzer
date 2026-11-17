package kz.fin.backend.execution;

import kz.fin.backend.methodology.Fin01RevenueMomSqlModule;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTAP-V1.3B: PostgreSQL owns FIN-01 typed parameter integrity and hashes.
 * Editorial review is never a financial S2 or an ExecutionPermit.
 */
@SpringBootTest(properties="fin.execution.enabled=false")
@Testcontainers
class HtapFin01TypedConfigPostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",postgres::getJdbcUrl);
        properties.add("spring.datasource.username",postgres::getUsername);
        properties.add("spring.datasource.password",postgres::getPassword);
    }
    @Autowired JdbcTemplate jdbc;

    private static final String VALID="""
        {"baselinePolicy":"POSITIVE_ONLY","roundingMode":"HALF_EVEN",
         "percentageScale":12,"periodPolicy":"ISO_MONTH_FLOW_PERIOD_ONLY_V1"}
        """;
    private String canonicalSchema() {
        return jdbc.queryForObject(
                "SELECT fin_calc.fin01_monthly_mom_typed_schema_v1()::text",String.class);
    }
    private UUID contract(String schema) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_calc.method_parameter_contract(
                contract_id,method_code,variant_code,contract_version,
                validation_profile,parameter_schema,schema_sha256,authored_by)
            VALUES (?,'FIN-01','REV_NET_MOM',?,
                'FIN01_MONTHLY_MOM_TYPED_V1',?::jsonb,?,'contract-author')
            """,id,"FIN01-TYPED-"+id,schema,"0".repeat(64));
        return id;
    }
    private UUID config(UUID contractId,String parameters,String engine,String version) {
        UUID id=UUID.randomUUID();
        record Parent(UUID id,int version) {}
        var prior=jdbc.query("""
            SELECT config_id,version_no FROM fin_calc.method_config_version
            WHERE method_code='FIN-01' AND variant_code='REV_NET_MOM'
            ORDER BY version_no DESC LIMIT 1
            """,(rs,i)->new Parent(rs.getObject(1,UUID.class),rs.getInt(2)));
        UUID parentId=prior.isEmpty()?null:prior.getFirst().id();
        int nextVersion=prior.isEmpty()?1:prior.getFirst().version()+1;
        String classSha=HtapRuntimeClassDigest.inspect(Fin01RevenueMomSqlModule.class)
                .classSha256();
        jdbc.update("""
            INSERT INTO fin_calc.method_config_version(
                config_id,method_code,variant_code,version_no,parent_config_id,
                contract_id,engine,algorithm_id,algorithm_version,algorithm_sha256,
                parameters,params_sha256,effective_sha256,created_by)
            VALUES (?,'FIN-01','REV_NET_MOM',?,?,?,?,
                'FIN-01/REV_NET_MOM',?,?,?::jsonb,?,?,'config-author')
            """,id,nextVersion,parentId,contractId,engine,version,classSha,parameters,
                "0".repeat(64),"0".repeat(64));
        return id;
    }
    @Test void typedSqlConfigIsDbHashedImmutableAndRequiresIndependentModeration() {
        UUID contractId=contract(canonicalSchema());
        UUID configId=config(contractId,VALID,"SQL","R2_SQL_PILOT_1");
        String hash=jdbc.queryForObject("""
            SELECT effective_sha256 FROM fin_calc.method_config_version WHERE config_id=?
            """,String.class,configId);
        String parameterHash=jdbc.queryForObject("""
            SELECT params_sha256 FROM fin_calc.method_config_version WHERE config_id=?
            """,String.class,configId);
        assertEquals(64,hash.length());
        assertEquals(64,parameterHash.length());
        assertNotEquals("0".repeat(64),hash);
        assertNotEquals("0".repeat(64),parameterHash);
        assertEquals("POSITIVE_ONLY",jdbc.queryForObject("""
            SELECT parameters->>'baselinePolicy' FROM fin_calc.method_config_version WHERE config_id=?
            """,String.class,configId));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            INSERT INTO fin_calc.method_config_review(
                review_id,config_id,submitted_by,reviewed_by,decision,
                evidence_sha256,review_reason)
            VALUES (?,?,'config-author','config-author','APPROVED',?,'self review')
            """,UUID.randomUUID(),configId,"f".repeat(64)));
        jdbc.update("""
            INSERT INTO fin_calc.method_config_review(
                review_id,config_id,submitted_by,reviewed_by,decision,
                evidence_sha256,review_reason)
            VALUES (?,?,'config-author','independent-reviewer','APPROVED',?,'pilot only')
            """,UUID.randomUUID(),configId,"f".repeat(64));
        jdbc.update("""
            INSERT INTO fin_calc.method_active_config(
                method_code,variant_code,config_id,pointer_version,activated_by)
            VALUES ('FIN-01','REV_NET_MOM',?,1,'config-publisher')
            """,configId);
        assertEquals(configId,jdbc.queryForObject("""
            SELECT config_id FROM fin_calc.method_active_config
            WHERE method_code='FIN-01' AND variant_code='REV_NET_MOM'
            """,UUID.class));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            UPDATE fin_calc.method_config_version SET algorithm_version='forged'
            WHERE config_id=?
            """,configId));
        assertThrows(DataAccessException.class,()->jdbc.update("""
            DELETE FROM fin_calc.method_parameter_contract WHERE contract_id=?
            """,contractId));
    }

    @Test void unknownOrForgedSchemaAndUnsupportedConfigAreRejectedByPostgres() {
        assertThrows(DataAccessException.class,()->contract("""
            {"schemaVersion":"HTAP-V1.3B-1","configProperties":{}}
            """));
        UUID id=contract(canonicalSchema());
        assertThrows(DataAccessException.class,()->config(
                id,VALID.replace("HALF_EVEN","HALF_UP"),"SQL","R2_SQL_PILOT_1"));
        assertThrows(DataAccessException.class,()->config(
                id,VALID.replace("12","6"),"SQL","R2_SQL_PILOT_1"));
        assertThrows(DataAccessException.class,()->config(
                id,VALID.replace("POSITIVE_ONLY","ANY"),"SQL","R2_SQL_PILOT_1"));
        assertThrows(DataAccessException.class,()->config(
                id,VALID.replace("}",",\"unknown\":true}"),"SQL","R2_SQL_PILOT_1"));
        assertThrows(DataAccessException.class,()->config(
                id,VALID,"JAVA","R2_SQL_PILOT_1"));
        assertThrows(DataAccessException.class,()->config(
                id,VALID,"SQL","R2_SQL_PILOT_99"));
    }

    @Test void wrongVariantCannotBorrowFin01TypedPolicyAndNoAutomaticApprovalsExist() {
        UUID candidate=UUID.randomUUID();
        assertThrows(DataAccessException.class,()->jdbc.update("""
            INSERT INTO fin_calc.method_parameter_contract(
                contract_id,method_code,variant_code,contract_version,
                validation_profile,parameter_schema,schema_sha256,authored_by)
            VALUES (?,'FIN-02','REV_NET_MOM',?,
                'FIN01_MONTHLY_MOM_TYPED_V1',?::jsonb,?,'forger')
            """,candidate,"wrong-"+candidate,canonicalSchema(),"0".repeat(64)));
        UUID valid=contract(canonicalSchema());
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.method_config_version WHERE contract_id=?
            """,Long.class,valid));
        // The contract never creates a review, active pointer, task or permit.
        assertEquals(0L,jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.method_config_review r
            JOIN fin_calc.method_config_version v ON v.config_id=r.config_id
            WHERE v.contract_id=?
            """,Long.class,valid));
    }
}
