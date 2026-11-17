package kz.fin.backend.maintenance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class WorkspacePartitionMaintenanceUtilityTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin_partition_test").withUsername("fin").withPassword("fin");

    private JdbcTemplate jdbc;
    private WorkspacePartitionMaintenanceUtility utility;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP SCHEMA IF EXISTS fin_workspace CASCADE");
        utility = new WorkspacePartitionMaintenanceUtility(jdbc);
    }

    @Test
    void missingWorkspaceSchemaReturnsEmptyWithoutCreatingIt() {
        assertTrue(utility.inspectAll().isEmpty());
        var plan = utility.previewRetirement(
                WorkspacePartitionMaintenanceUtility.Parent.FACT_STAGE,
                "workspace_fact_stage_20261117");
        assertEquals(WorkspacePartitionMaintenanceUtility.RetirementDecision.NOT_FOUND,
                plan.decision());
        assertTrue(plan.partition().isEmpty());
        assertNull(jdbc.queryForObject("SELECT to_regclass('fin_workspace.workspace_fact_stage')",
                String.class));
    }

    @Test
    void realPostgresRangeChildrenCanBeInspectedButNotDropped() {
        createParents();
        jdbc.execute("""
                CREATE TABLE fin_workspace.workspace_fact_stage_20261117
                PARTITION OF fin_workspace.workspace_fact_stage
                FOR VALUES FROM ('2026-11-17') TO ('2026-11-18')
                """);
        jdbc.execute("""
                CREATE TABLE fin_workspace.workspace_intermediate_stage_20261117
                PARTITION OF fin_workspace.workspace_intermediate_stage
                FOR VALUES FROM ('2026-11-17') TO ('2026-11-18')
                """);
        jdbc.execute("""
                INSERT INTO fin_workspace.workspace_fact_stage
                    (reclaim_epoch, organization_id, amount)
                VALUES ('2026-11-17', '00000000-0000-0000-0000-000000000001', 150)
                """);

        var all = utility.inspectAll();
        assertEquals(2, all.size());
        assertTrue(all.stream().allMatch(p ->
                p.status() == WorkspacePartitionMaintenanceUtility.InspectionStatus.MANAGED_ATTACHED));
        assertTrue(all.stream().allMatch(p -> p.totalBytes() >= 0));
        var preview = utility.previewRetirement(
                WorkspacePartitionMaintenanceUtility.Parent.FACT_STAGE,
                "workspace_fact_stage_20261117");
        assertEquals(WorkspacePartitionMaintenanceUtility.RetirementDecision.BLOCKED_UNTIL_LIFECYCLE_BARRIER,
                preview.decision());
        assertEquals("FIN_PARTITION_W1D_DRAIN_BARRIER_NOT_IMPLEMENTED", preview.reasonCode());
        assertTrue(preview.partition().isPresent());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM fin_workspace.workspace_fact_stage", Long.class));
    }

    @Test
    void unmanagedNamesDefaultChildrenAndForgedIdentifiersAreRejected() {
        createParents();
        jdbc.execute("""
                CREATE TABLE fin_workspace.workspace_fact_stage_default
                PARTITION OF fin_workspace.workspace_fact_stage DEFAULT
                """);
        var infos = utility.inspect(
                WorkspacePartitionMaintenanceUtility.Parent.FACT_STAGE);
        assertEquals(1, infos.size());
        assertEquals(WorkspacePartitionMaintenanceUtility.InspectionStatus.UNMANAGED_NAME,
                infos.getFirst().status());
        for (var identifier : List.of(
                "workspace_fact_stage_default",
                "workspace_fact_stage_20260230",
                "workspace_fact_stage_20261117;DROP TABLE fin_fact.fact_assertion",
                "workspace_intermediate_stage_20261117",
                "fin_fact.fact_assertion",
                "workspace_fact_stage_20261117_suffix")) {
            assertEquals("FIN_PARTITION_UNMANAGED_IDENTIFIER",
                    assertThrows(IllegalArgumentException.class, () ->
                            utility.previewRetirement(
                                    WorkspacePartitionMaintenanceUtility.Parent.FACT_STAGE,
                                    identifier)).getMessage());
        }
        assertFalse(WorkspacePartitionMaintenanceUtility.isManagedName(
                WorkspacePartitionMaintenanceUtility.Parent.FACT_STAGE, null));
    }

    private void createParents() {
        jdbc.execute("CREATE SCHEMA fin_workspace");
        jdbc.execute("""
                CREATE TABLE fin_workspace.workspace_fact_stage(
                    reclaim_epoch date NOT NULL,
                    organization_id uuid NOT NULL,
                    amount numeric(38,12)
                ) PARTITION BY RANGE (reclaim_epoch)
                """);
        jdbc.execute("""
                CREATE TABLE fin_workspace.workspace_intermediate_stage(
                    reclaim_epoch date NOT NULL,
                    organization_id uuid NOT NULL,
                    amount numeric(38,12)
                ) PARTITION BY RANGE (reclaim_epoch)
                """);
    }
}
