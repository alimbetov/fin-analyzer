package kz.fin.formlibrary.contract;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Narrow PostgreSQL integration test of R16.B only, using the actual 014/015 changelogs.
 * This does not prove full 001-015 upgrade compatibility or intake E2E.
 */
@Testcontainers
class R16BPostgresqlContractTest {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    static String version = UUID.randomUUID().toString();
    static String duplicateVersion = UUID.randomUUID().toString();
    static String field = "REVENUE";

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA fin_lib");
            s.execute("""
                CREATE TABLE fin_lib.lib_form_version(id uuid primary key)
                """);
            s.execute("""
                CREATE TABLE fin_lib.lib_form_field(
                  form_version_id uuid not null references fin_lib.lib_form_version(id),
                  field_code varchar(128) not null,
                  unique(form_version_id,field_code))
                """);
            s.execute("CREATE TABLE fin_lib.lib_period_type(code varchar(8) primary key)");
            s.execute("CREATE TABLE fin_lib.lib_metric_definition(metric_code varchar(160) unique not null)");
            s.execute("INSERT INTO fin_lib.lib_form_version(id) VALUES ('" + version + "')");
            s.execute("INSERT INTO fin_lib.lib_form_version(id) VALUES ('" + duplicateVersion + "')");
            s.execute("INSERT INTO fin_lib.lib_form_field(form_version_id,field_code) VALUES ('"
                    + version + "','REVENUE')");
            s.execute("INSERT INTO fin_lib.lib_period_type(code) VALUES ('M')");
            s.execute("INSERT INTO fin_lib.lib_metric_definition(metric_code) VALUES ('NET_REVENUE')");
        }
        DriverManagerDataSource ds = new DriverManagerDataSource(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(ds);
        liquibase.setChangeLog("classpath:db/r16b-test-changelog.yaml");
        liquibase.afterPropertiesSet();
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void reject(String sql, String sqlState) {
        SQLException ex = assertThrows(SQLException.class, () -> execute(sql));
        assertEquals(sqlState, ex.getSQLState(), ex.toString());
    }

    @Test
    void rejectsInvalidNumericScaleAndMissingFieldReference() {
        reject("""
            INSERT INTO fin_lib.lib_form_field_policy
              (form_version_id,field_code,numeric_precision,numeric_scale,rounding_mode,
               missing_value_policy,min_occurs,semantic_definition,aggregation_policy)
            VALUES ('%s','REVENUE',20,21,'HALF_EVEN','REJECT',1,'net revenue','SUM')
            """.formatted(version), "23514");
        reject("""
            INSERT INTO fin_lib.lib_form_field_policy
              (form_version_id,field_code,missing_value_policy,min_occurs,
               semantic_definition,aggregation_policy)
            VALUES ('%s','UNKNOWN','REJECT',1,'unknown','NONE')
            """.formatted(version), "23503");
    }

    @Test
    void rejectsUnknownPeriodAndInvalidMetricReference() {
        reject("""
            INSERT INTO fin_lib.lib_form_allowed_period
              (form_version_id,period_type,calendar_code,reporting_basis,coverage_policy)
            VALUES ('%s','X','GREGORIAN','MANAGEMENT','FULL')
            """.formatted(version), "23503");
        reject("""
            INSERT INTO fin_lib.lib_form_contract_metric_binding
              (form_version_id,field_code,metric_code,metric_contract_version,transformation_policy)
            VALUES ('%s','REVENUE','UNKNOWN','1','DIRECT')
            """.formatted(version), "23503");
    }

    @Test
    void rejectsMutationsAndRetainsOriginalRow() throws SQLException {
        execute("""
            INSERT INTO fin_lib.lib_form_contract_policy
              (form_version_id,canonicalization_version,source_descriptor_version,
               compatibility_class,governance_state,created_at)
            VALUES ('%s',1,'catalog-1','UNCLASSIFIED','CANDIDATE_UNQUALIFIED',CURRENT_TIMESTAMP)
            """.formatted(version));
        reject("""
            UPDATE fin_lib.lib_form_contract_policy
              SET compatibility_class='BREAKING' WHERE form_version_id='%s'
            """.formatted(version), "23514");
        reject("""
            DELETE FROM fin_lib.lib_form_contract_policy WHERE form_version_id='%s'
            """.formatted(version), "23514");
        try (Connection c = connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                SELECT compatibility_class FROM fin_lib.lib_form_contract_policy
                  WHERE form_version_id='%s'""".formatted(version))) {
            assertTrue(rs.next());
            assertEquals("UNCLASSIFIED", rs.getString(1));
        }
    }

    @Test
    void duplicatePolicyAndUnknownVersionFailClosed() throws SQLException {
        // Independent fixture: test order must not affect the primary-key assertion.
        execute("""
            INSERT INTO fin_lib.lib_form_contract_policy
              (form_version_id,canonicalization_version,source_descriptor_version,
               compatibility_class,governance_state,created_at)
            VALUES ('%s',1,'catalog-1','UNCLASSIFIED','CANDIDATE_UNQUALIFIED',CURRENT_TIMESTAMP)
            ON CONFLICT (form_version_id) DO NOTHING
            """.formatted(duplicateVersion));
        reject("""
            INSERT INTO fin_lib.lib_form_contract_policy
              (form_version_id,canonicalization_version,source_descriptor_version,
               compatibility_class,governance_state,created_at)
            VALUES ('%s',1,'catalog-1','UNCLASSIFIED','CANDIDATE_UNQUALIFIED',CURRENT_TIMESTAMP)
            """.formatted(duplicateVersion), "23505");
        reject("""
            INSERT INTO fin_lib.lib_form_contract_policy
              (form_version_id,canonicalization_version,source_descriptor_version,
               compatibility_class,governance_state,created_at)
            VALUES ('00000000-0000-0000-0000-000000000099',1,'catalog-1','UNCLASSIFIED',
              'CANDIDATE_UNQUALIFIED',CURRENT_TIMESTAMP)
            """, "23503");
    }
}
