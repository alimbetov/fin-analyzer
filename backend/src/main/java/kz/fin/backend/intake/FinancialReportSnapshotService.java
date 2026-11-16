package kz.fin.backend.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * FIN-BE-R1-X / X1: a scoped, read-only catalog revision snapshot.
 * No FactSet or ACTUAL publication. No display-language-dependent transformation.
 * The caller at the HTTP boundary MUST enforce FIN_READ before calling load().
 */
@Service
public class FinancialReportSnapshotService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public FinancialReportSnapshotService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
    }

    /**
     * Null optional coordinates are allowed only if they resolve to one slot.
     * exactRevisionId pins a historical revision; otherwise the slot head wins.
     * expectedLatestRevisionId rejects a head change between preview and read.
     */
    public record Query(UUID organizationId, UUID projectId, String formCode,
                        ReportingPeriodV5.Type periodType, String periodCode,
                        String reportingBasis, ReportingPeriodV5.Coverage coverage,
                        String currency, Integer amountScale,
                        UUID exactRevisionId, UUID expectedLatestRevisionId) {
        public Query {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(projectId, "projectId");
            CatalogCaptureRegistry.require(formCode);
            if (periodType == null || periodCode == null)
                throw new IllegalArgumentException("FIN_EXPORT_INVALID_CONTEXT");
            var periodCoverage = coverage == null
                    ? (periodType == ReportingPeriodV5.Type.AS_OF
                    ? ReportingPeriodV5.Coverage.AS_OF
                    : ReportingPeriodV5.Coverage.PERIOD_ONLY)
                    : coverage;
            ReportingPeriodV5.resolve(periodType, periodCode, periodCoverage);
            if ((reportingBasis != null && !Set.of(
                        "MANAGEMENT", "SEPARATE", "CONSOLIDATED").contains(reportingBasis))
                    || (currency != null && !currency.matches("[A-Z]{3}"))
                    || (amountScale != null
                        && !Set.of(1, 1000, 1000000).contains(amountScale))
                    || (exactRevisionId != null && expectedLatestRevisionId != null))
                throw new IllegalArgumentException("FIN_EXPORT_INVALID_CONTEXT");
        }
    }

    public record Snapshot(UUID organizationId, UUID projectId,
                           UUID economicEntityId, UUID businessScopeId,
                           String projectCode, String formCode,
                           String captureMode, String reportingBasis,
                           ReportingPeriodV5.Type periodType, String periodCode,
                           ReportingPeriodV5.Coverage coverage,
                           String currency, int amountScale, String sourceInputLocale,
                           UUID sourceRevisionId, int revisionNumber, UUID sourceDraftId,
                           String normalizedFingerprint, String schemaFingerprint,
                           String originalXlsxSha256, String documentSha256,
                           String sourceStatus,
                           List<CatalogCaptureRegistry.Field> fields,
                           List<CatalogCaptureRegistry.EntryRow> rows) {
        public Snapshot {
            fields = List.copyOf(fields);
            var safe = new ArrayList<CatalogCaptureRegistry.EntryRow>();
            for (var row : rows) {
                safe.add(new CatalogCaptureRegistry.EntryRow(row.rowId(),
                        Collections.unmodifiableMap(new LinkedHashMap<>(row.cells()))));
            }
            rows = List.copyOf(safe);
        }

        /** Stable order-sensitive identity for report consumers. */
        public String contentFingerprintV2() {
            return FinancialReportContentIdentity.digest(this);
        }
    }

    private record Stored(String formCode, String basis, String periodType, String period,
                          String coverage, String currency, int scale,
                          UUID revisionId, int revisionNumber, UUID draftId,
                          String revisionFingerprint, String revisionState,
                          String captureMode, String schemaFingerprint,
                          String sourceLocale, String rowsJson, String documentSha,
                          String draftState, UUID entityId, UUID scopeId,
                          String projectCode, String originalSha,
                          boolean originalDigestValid, boolean documentDigestValid) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot load(Query query) {
        Objects.requireNonNull(query, "query");
        var sql = new StringBuilder("""
            SELECT s.form_code,s.reporting_basis,s.period_type,s.period_code,
                   s.coverage,s.currency,s.amount_scale,
                   r.id,r.revision_number,r.draft_id,r.normalized_sha,r.state,
                   d.capture_mode,d.schema_fingerprint,d.locale,d.normalized_rows::text,
                   d.document_sha,d.state,p.economic_entity_id,p.business_scope_id,
                   p.project_code,d.original_sha,
                   (d.original_sha=encode(sha256(d.original_xlsx),'hex')) AS original_digest_ok,
                   (d.document_sha IS NULL OR
                      (d.document_original IS NOT NULL AND
                       d.document_sha=encode(sha256(d.document_original),'hex')))
                       AS document_digest_ok
              FROM fin_intake.catalog_xlsx_slot s
              JOIN fin_intake.project_v5 p
                ON p.id=s.project_id AND p.organization_id=s.organization_id
              JOIN fin_intake.catalog_xlsx_revision r
                ON r.slot_id=s.id AND r.organization_id=s.organization_id
              JOIN fin_intake.catalog_xlsx_draft d
                ON d.id=r.draft_id AND d.organization_id=r.organization_id
               AND d.economic_entity_id=p.economic_entity_id
               AND d.business_scope_id=p.business_scope_id
               AND d.project_code=p.project_code
               AND d.form_code=s.form_code
               AND d.reporting_basis=s.reporting_basis
               AND d.period_type=s.period_type
               AND d.period_code=s.period_code
               AND d.coverage=s.coverage AND d.currency=s.currency
               AND d.amount_scale=s.amount_scale
             WHERE s.organization_id=? AND s.project_id=?
               AND s.form_code=? AND s.period_type=? AND s.period_code=?
               AND p.accounting_currency=s.currency
            """);
        var params = new ArrayList<Object>();
        Collections.addAll(params, query.organizationId(), query.projectId(),
                query.formCode(), query.periodType().name(), query.periodCode());
        if (query.reportingBasis() != null) {
            sql.append(" AND s.reporting_basis=?");
            params.add(query.reportingBasis());
        }
        if (query.coverage() != null) {
            sql.append(" AND s.coverage=?");
            params.add(query.coverage().name());
        }
        if (query.currency() != null) {
            sql.append(" AND s.currency=?");
            params.add(query.currency());
        }
        if (query.amountScale() != null) {
            sql.append(" AND s.amount_scale=?");
            params.add(query.amountScale());
        }
        if (query.exactRevisionId() != null) {
            sql.append(" AND r.id=?");
            params.add(query.exactRevisionId());
        } else {
            sql.append(" AND r.revision_number=s.latest_revision");
        }
        sql.append(" LIMIT 2");
        var matches = jdbc.query(sql.toString(), (rs, i) -> new Stored(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7),
                rs.getObject(8, UUID.class), rs.getInt(9),
                rs.getObject(10, UUID.class), rs.getString(11), rs.getString(12),
                rs.getString(13), rs.getString(14), rs.getString(15),
                rs.getString(16), rs.getString(17), rs.getString(18),
                rs.getObject(19, UUID.class), rs.getObject(20, UUID.class),
                rs.getString(21), rs.getString(22),
                rs.getBoolean(23), rs.getBoolean(24)), params.toArray());
        if (matches.isEmpty())
            throw new IllegalArgumentException("FIN_EXPORT_REVISION_NOT_FOUND");
        if (matches.size() > 1)
            throw new IllegalArgumentException("FIN_EXPORT_AMBIGUOUS_CONTEXT");
        Stored stored = matches.getFirst();

        if (query.expectedLatestRevisionId() != null
                && !query.expectedLatestRevisionId().equals(stored.revisionId()))
            throw new IllegalArgumentException("FIN_EXPORT_REVISION_CHANGED");

        var definition = CatalogCaptureRegistry.require(stored.formCode());
        if (!"CONFIRMED_UNQUALIFIED_DRAFT".equals(stored.revisionState())
                || !"UNQUALIFIED_DRAFT".equals(stored.draftState())
                || !definition.captureMode().name().equals(stored.captureMode())
                || !stored.revisionFingerprint().matches("[a-f0-9]{64}")
                || !stored.originalDigestValid() || !stored.documentDigestValid()
                || (definition.captureMode() == CatalogCaptureRegistry.Mode.EVIDENCE_ONLY
                && stored.documentSha() == null)) {
            throw new IllegalStateException("FIN_EXPORT_SOURCE_INTEGRITY_FAILED");
        }
        String schema = CatalogXlsxRoundTripService.schemaFingerprint(stored.formCode());
        if (!schema.equals(stored.schemaFingerprint()))
            throw new IllegalArgumentException("FIN_EXPORT_SCHEMA_INCOMPATIBLE");

        var source = new CatalogXlsxRoundTripService.Context(
                query.organizationId(), stored.entityId(), stored.scopeId(),
                stored.projectCode(), stored.formCode(), stored.basis(),
                ReportingPeriodV5.Type.valueOf(stored.periodType()), stored.period(),
                ReportingPeriodV5.Coverage.valueOf(stored.coverage()),
                stored.currency(), stored.scale(),
                CatalogReportLocale.requireSourceInput(stored.sourceLocale()));
        var fields = List.copyOf(CatalogXlsxRoundTripService.fields(stored.formCode()));
        var rows = decode(stored.rowsJson(), fields);
        String verified = CatalogXlsxRoundTripService.normalizedFingerprint(source, rows);
        if (!stored.revisionFingerprint().equals(verified))
            throw new IllegalStateException("FIN_EXPORT_NORMALIZED_FINGERPRINT_MISMATCH");
        return new Snapshot(query.organizationId(), query.projectId(),
                stored.entityId(), stored.scopeId(), stored.projectCode(),
                stored.formCode(), stored.captureMode(), stored.basis(),
                source.periodType(), stored.period(), source.coverage(),
                stored.currency(), stored.scale(), source.locale(),
                stored.revisionId(), stored.revisionNumber(), stored.draftId(),
                verified, schema, stored.originalSha(), stored.documentSha(),
                stored.revisionState(), fields, rows);
    }

    private List<CatalogCaptureRegistry.EntryRow> decode(
            String rowsJson, List<CatalogCaptureRegistry.Field> fields) {
        try {
            JsonNode root = json.readTree(rowsJson);
            if (root == null || !root.isArray() || root.isEmpty()
                    || root.size() > CatalogCaptureRegistry.MAX_ROWS)
                throw new IllegalStateException("FIN_EXPORT_STORED_ROWS_INVALID");
            var output = new ArrayList<CatalogCaptureRegistry.EntryRow>();
            Set<String> ids = new HashSet<>();
            for (var row : root) {
                JsonNode id = row.path("rowId");
                JsonNode cells = row.path("cells");
                if (!id.isTextual() || !id.textValue().matches("[A-Za-z0-9_-]{1,60}")
                        || !ids.add(id.textValue()) || !cells.isObject()
                        || cells.size() != fields.size())
                    throw new IllegalStateException("FIN_EXPORT_STORED_ROWS_INVALID");
                var values = new LinkedHashMap<String, String>();
                for (var field : fields) {
                    JsonNode value = cells.get(field.code());
                    if (value == null || !value.isTextual())
                        throw new IllegalStateException("FIN_EXPORT_STORED_FIELD_INVALID");
                    values.put(field.code(), value.textValue());
                }
                if (values.values().stream().allMatch(String::isBlank))
                    throw new IllegalStateException("FIN_EXPORT_STORED_ROWS_INVALID");
                output.add(new CatalogCaptureRegistry.EntryRow(
                        id.textValue(), Collections.unmodifiableMap(values)));
            }
            return List.copyOf(output);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("FIN_EXPORT_STORED_JSON_INVALID", e);
        }
    }
}
