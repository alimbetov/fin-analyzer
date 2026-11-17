package kz.fin.backend.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * FIN-BE-R2-W / ADR-R2-08: safe, read-only PostgreSQL partition inspector.
 *
 * This utility deliberately does NOT execute ALTER TABLE, DROP TABLE or
 * arbitrary user SQL. A destructive reaper is forbidden until the durable
 * cohort-membership, no-live-lease, retention and DDL-recovery gates of W1D/W4D
 * exist and pass independent review. The tool is internal: no HTTP endpoints,
 * scheduler or command-line destructive switch.
 */
@Component
public final class WorkspacePartitionMaintenanceUtility {
    private static final String SCHEMA = "fin_workspace";
    private static final DateTimeFormatter PARTITION_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** Exactly the two *future*, reconstructible workspace payload parents. */
    public enum Parent {
        FACT_STAGE("workspace_fact_stage"),
        INTERMEDIATE_STAGE("workspace_intermediate_stage");

        private final String relation;

        Parent(String relation) { this.relation = relation; }
        public String relation() { return relation; }
    }

    public enum InspectionStatus {
        MANAGED_ATTACHED,
        DETACH_PENDING,
        UNMANAGED_NAME,
        INVALID_PARTITION_STRATEGY
    }

    public enum RetirementDecision {
        NOT_FOUND,
        BLOCKED_UNTIL_LIFECYCLE_BARRIER
    }

    public record PartitionInfo(
            Parent parent,
            String childName,
            String partitionBound,
            long totalBytes,
            InspectionStatus status) {
        public PartitionInfo {
            Objects.requireNonNull(parent);
            Objects.requireNonNull(childName);
            Objects.requireNonNull(partitionBound);
            Objects.requireNonNull(status);
            if (totalBytes < 0) throw new IllegalArgumentException("FIN_PARTITION_SIZE_INVALID");
        }
    }

    public record RetirementPreview(
            Parent parent,
            String childName,
            RetirementDecision decision,
            String reasonCode,
            Optional<PartitionInfo> partition) {
        public RetirementPreview {
            Objects.requireNonNull(parent);
            Objects.requireNonNull(childName);
            Objects.requireNonNull(decision);
            Objects.requireNonNull(reasonCode);
            partition = Objects.requireNonNull(partition);
        }
    }

    private final JdbcTemplate jdbc;

    public WorkspacePartitionMaintenanceUtility(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /**
     * Pure catalog inspection, safe even before the fin_workspace schema exists.
     * No table or partition identifier is taken from HTTP, dynamic SQL or a
     * client-provided schema name. All relation names are server-owned enums.
     */
    public List<PartitionInfo> inspect(Parent parent) {
        Objects.requireNonNull(parent);
        var rows = jdbc.query("""
                SELECT child.relname, pg_get_expr(child.relpartbound,child.oid),
                       pg_total_relation_size(child.oid),
                       inherited.inhdetachpending, config.partstrat
                FROM pg_catalog.pg_inherits inherited
                JOIN pg_catalog.pg_class child ON child.oid=inherited.inhrelid
                JOIN pg_catalog.pg_namespace child_ns ON child_ns.oid=child.relnamespace
                JOIN pg_catalog.pg_class owner ON owner.oid=inherited.inhparent
                JOIN pg_catalog.pg_namespace owner_ns ON owner_ns.oid=owner.relnamespace
                JOIN pg_catalog.pg_partitioned_table config ON config.partrelid=owner.oid
                WHERE owner_ns.nspname=? AND child_ns.nspname=?
                  AND owner.relname=? AND child.relkind='r'
                ORDER BY child.relname
                """, (rs, i) -> {
                    String name = rs.getString(1);
                    String bound = rs.getString(2);
                    boolean pending = rs.getBoolean(4);
                    String strategy = rs.getString(5);
                    InspectionStatus status;
                    if (!"r".equals(strategy)) {
                        status=InspectionStatus.INVALID_PARTITION_STRATEGY;
                    } else if (pending) {
                        status=InspectionStatus.DETACH_PENDING;
                    } else if (!isManagedName(parent, name) || "DEFAULT".equals(bound)) {
                        status=InspectionStatus.UNMANAGED_NAME;
                    } else {
                        status=InspectionStatus.MANAGED_ATTACHED;
                    }
                    return new PartitionInfo(parent, name, bound, rs.getLong(3), status);
                }, SCHEMA, SCHEMA, parent.relation());
        return List.copyOf(rows);
    }

    /** Collects diagnostics for both stage parents; an absent parent is empty. */
    public List<PartitionInfo> inspectAll() {
        var found = new ArrayList<PartitionInfo>();
        for (Parent parent : Parent.values()) found.addAll(inspect(parent));
        return List.copyOf(found);
    }

    /**
     * Fail-closed dry-run candidate preview. No irreversible action is
     * authorized by an attached partition or a naming convention alone.
     *
     * A future privileged reaper must independently verify a durable DRAINING
     * catalog state, EVERY cohort member, all refs/leases and SQL query liveness
     * and execute DETACH CONCURRENTLY outside any transaction.
     */
    public RetirementPreview previewRetirement(Parent parent, String childName) {
        Objects.requireNonNull(parent);
        if (!isManagedName(parent, childName))
            throw new IllegalArgumentException("FIN_PARTITION_UNMANAGED_IDENTIFIER");
        var candidate = inspect(parent).stream()
                .filter(row -> row.childName().equals(childName)).findFirst();
        if (candidate.isEmpty()) {
            return new RetirementPreview(parent, childName,
                    RetirementDecision.NOT_FOUND, "FIN_PARTITION_NOT_ATTACHED", Optional.empty());
        }
        return new RetirementPreview(parent, childName,
                RetirementDecision.BLOCKED_UNTIL_LIFECYCLE_BARRIER,
                candidate.get().status() == InspectionStatus.DETACH_PENDING
                        ? "FIN_PARTITION_DETACH_RECOVERY_REQUIRED"
                        : "FIN_PARTITION_W1D_DRAIN_BARRIER_NOT_IMPLEMENTED",
                candidate);
    }

    static boolean isManagedName(Parent parent, String childName) {
        if (parent == null || childName == null) return false;
        String prefix = parent.relation() + "_";
        if (!childName.startsWith(prefix)) return false;
        String date = childName.substring(prefix.length());
        if (!date.matches("[0-9]{8}")) return false;
        try {
            LocalDate.parse(date, PARTITION_DATE);
            return true;
        } catch (DateTimeException invalidDate) {
            return false;
        }
    }
}
