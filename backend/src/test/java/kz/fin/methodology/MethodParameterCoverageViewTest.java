package kz.fin.methodology;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIN-MR-R2.4-A: a PostgreSQL-backed coverage audit, not a method-execution qualification.
 */
class MethodParameterCoverageViewTest {

    @Test
    void freshMigrationPreservesUnreviewedCoverageAndNeverGrantsExecution() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection db = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement sql = db.createStatement()) {
                try (ResultSet rs = sql.executeQuery("""
                    SELECT count(*) AS family_count,
                           sum(candidate_parameter_count) AS candidate_count,
                           sum(unreviewed_parameter_count) AS unreviewed_count,
                           count(*) FILTER (WHERE candidate_parameter_count > 0) AS populated_count,
                           count(*) FILTER (WHERE typed_contract_count > 0) AS typed_family_count,
                           count(*) FILTER (WHERE method_s2_qualified_by_coverage
                                                OR execution_permitted_by_coverage) AS permit_count
                    FROM fin_calc.method_parameter_coverage_view
                    """)) {
                    assertTrue(rs.next());
                    assertEquals(60L, rs.getLong("family_count"));
                    assertEquals(259L, rs.getLong("candidate_count"));
                    assertEquals(259L, rs.getLong("unreviewed_count"));
                    assertEquals(60L, rs.getLong("populated_count"));
                    // Only CON-01 and CON-02 have versioned typed candidates at this baseline.
                    assertEquals(2L, rs.getLong("typed_family_count"));
                    assertEquals(0L, rs.getLong("permit_count"));
                    assertFalse(rs.next());
                }

                try (ResultSet rs = sql.executeQuery("""
                    SELECT method_code, coverage_state, typed_contract_count
                    FROM fin_calc.method_parameter_coverage_view
                    WHERE method_code IN ('CON-01', 'CON-02', 'INV-08')
                    ORDER BY method_code
                    """)) {
                    assertTrue(rs.next());
                    assertEquals("CON-01", rs.getString("method_code"));
                    assertEquals("TYPED_CONTRACT_PRESENT_UNQUALIFIED", rs.getString("coverage_state"));
                    assertTrue(rs.getLong("typed_contract_count") > 0);

                    assertTrue(rs.next());
                    assertEquals("CON-02", rs.getString("method_code"));
                    assertEquals("TYPED_CONTRACT_PRESENT_UNQUALIFIED", rs.getString("coverage_state"));
                    assertTrue(rs.getLong("typed_contract_count") > 0);

                    assertTrue(rs.next());
                    assertEquals("INV-08", rs.getString("method_code"));
                    assertEquals("NO_TYPED_EXECUTION_CONTRACT", rs.getString("coverage_state"));
                    assertEquals(0L, rs.getLong("typed_contract_count"));
                    assertFalse(rs.next());
                }
            }
        }
    }
}
