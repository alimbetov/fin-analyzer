package kz.fin.backend.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIN-R8: tests real PostgreSQL fresh install and an existing deployment ending at
 * the original CAT-A3.1 migration .08. A migration rename must never rewrite a
 * previously recorded version or require flyway repair.
 */
@Testcontainers
class FlywayMigrationRecoveryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    private String newDatabase(String name) throws Exception {
        try (Connection connection=DriverManager.getConnection(
                postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
             Statement statement=connection.createStatement()) {
            statement.execute("CREATE DATABASE "+name);
        }
        return "jdbc:postgresql://"+postgres.getHost()+":"+
                postgres.getMappedPort(5432)+"/"+name;
    }

    private Flyway flyway(String url) {
        return Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword())
                .locations("classpath:db/migration").load();
    }

    private String scalar(String url,String sql) throws Exception {
        try (Connection connection=DriverManager.getConnection(
                url,postgres.getUsername(),postgres.getPassword());
             Statement statement=connection.createStatement();
             ResultSet rows=statement.executeQuery(sql)) {
            assertTrue(rows.next(),sql);
            return rows.getString(1);
        }
    }

    @Test void newPostgresDatabaseAppliesAllMigrationsAndRemainsIdempotent() throws Exception {
        String url=newDatabase("fin_r8_fresh");
        Flyway target=flyway(url);
        assertTrue(target.migrate().migrationsExecuted>0);
        assertEquals("1",scalar(url,
                "SELECT count(*)::text FROM flyway_schema_history WHERE version='20261116.08' AND success"));
        assertEquals("1",scalar(url,
                "SELECT count(*)::text FROM flyway_schema_history WHERE version='20261116.09' AND success"));
        assertEquals("fin_calc.method_s2_review",scalar(url,
                "SELECT to_regclass('fin_calc.method_s2_review')::text"));
        assertEquals("fin_con.sku_source_original",scalar(url,
                "SELECT to_regclass('fin_con.sku_source_original')::text"));
        assertEquals(0,target.migrate().migrationsExecuted);
    }

    @Test void existingPostgresAtOriginalCatA31VersionUpgradesWithoutRepair() throws Exception {
        String url=newDatabase("fin_r8_existing");
        Flyway previous=Flyway.configure()
                .dataSource(url,postgres.getUsername(),postgres.getPassword())
                .locations("classpath:db/migration").target("20261116.08").load();
        assertTrue(previous.migrate().migrationsExecuted>0);
        String oldChecksum=scalar(url,
                "SELECT checksum::text FROM flyway_schema_history WHERE version='20261116.08'");
        assertEquals("fin_calc.method_s2_review",scalar(url,
                "SELECT to_regclass('fin_calc.method_s2_review')::text"));
        assertNull(scalar(url,"SELECT to_regclass('fin_con.sku_source_original')::text"));
        try (Connection connection=DriverManager.getConnection(
                url,postgres.getUsername(),postgres.getPassword());
             Statement statement=connection.createStatement()) {
            statement.execute("CREATE TABLE public.fin_r8_preexisting_marker (id integer PRIMARY KEY)");
            statement.execute("INSERT INTO public.fin_r8_preexisting_marker VALUES (42)");
        }
        Flyway upgraded=flyway(url);
        assertTrue(upgraded.migrate().migrationsExecuted>0);
        assertEquals(oldChecksum,scalar(url,
                "SELECT checksum::text FROM flyway_schema_history WHERE version='20261116.08'"));
        assertEquals("1",scalar(url,
                "SELECT count(*)::text FROM flyway_schema_history WHERE version='20261116.09' AND success"));
        assertEquals("42",scalar(url,"SELECT id::text FROM public.fin_r8_preexisting_marker"));
        assertEquals("fin_con.sku_source_original",scalar(url,
                "SELECT to_regclass('fin_con.sku_source_original')::text"));
        assertEquals(0,upgraded.migrate().migrationsExecuted);
    }
}
