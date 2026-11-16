package kz.fin.backend.intake;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * FIN-BE-R1-G. Only a server-issued V2 XLSX can enter this contextual path.
 * The issuance record proves that THIS application issued the template for
 * the named project/period/schema; it is not a signature, authorization decision,
 * external-source verification, or permission to publish financial facts.
 *
 * The legacy V1 generate/parse/saveDraft remains isolated for regression tests.
 */
@Service
public class CatalogIssuedTemplateService {
    private final JdbcTemplate jdbc;
    private final CatalogXlsxRoundTripService xlsx;

    public CatalogIssuedTemplateService(JdbcTemplate jdbc, CatalogXlsxRoundTripService xlsx) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.xlsx = Objects.requireNonNull(xlsx);
    }

    public record Issued(UUID templateId, UUID projectId, String formCode, String periodCode,
                         String schemaFingerprint, String templateSha256, byte[] workbook) {
        public Issued {
            workbook = workbook.clone();
        }
        @Override public byte[] workbook() { return workbook.clone(); }
    }

    public record Preview(UUID templateId, UUID projectId,
                          CatalogXlsxRoundTripService.Parsed parsed) {}

    /** Source provenance must originate from an X1 snapshot and a trusted actor. */
    public record ExportSource(UUID revisionId, UUID draftId, String normalizedSha,
                               String displayLocale, String actor) {
        public ExportSource {
            Objects.requireNonNull(revisionId);
            Objects.requireNonNull(draftId);
            if (normalizedSha == null || !normalizedSha.matches("[0-9a-f]{64}")
                    || actor == null || actor.isBlank() || actor.length() > 200)
                throw new IllegalArgumentException("FIN_EXPORT_INVALID_PROVENANCE");
            CatalogReportLocale.requireDisplay(displayLocale);
        }
    }

    public record PopulatedIssue(UUID exportId, Issued issued) {}



    private record Stored(UUID projectId, CatalogXlsxRoundTripService.Context context,
                          String schemaFingerprint, String templateSha256) {}

    /** Persist the server context at the same transaction boundary as issuance. */
    @Transactional
    public Issued issue(UUID projectId, CatalogXlsxRoundTripService.Context context) {
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(context);
        requireProjectMatches(projectId, context);
        UUID id = UUID.randomUUID();
        byte[] bytes = xlsx.generateIssued(context, id);
        String fp = CatalogXlsxRoundTripService.schemaFingerprint(context.formCode());
        String rawHash = sha256(bytes);
        jdbc.update("""
            INSERT INTO fin_intake.catalog_xlsx_issued_template (
                id,organization_id,project_id,economic_entity_id,business_scope_id,
                project_code,form_code,reporting_basis,period_type,period_code,
                coverage,currency,amount_scale,input_locale,format_version,
                schema_fingerprint,original_template_sha)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, context.organizationId(), projectId, context.entityId(),
                context.businessScopeId(), context.projectCode(), context.formCode(),
                context.reportingBasis(), context.periodType().name(), context.periodCode(),
                context.coverage().name(), context.currency(), context.amountScale(),
                context.locale(), CatalogXlsxRoundTripService.ISSUED_FORMAT, fp, rawHash);
        return new Issued(id, projectId, context.formCode(), context.periodCode(), fp,
                rawHash, bytes);
    }

    /**
     * X2: register the complete populated workbook, not a blank template.
     * The original_template_sha in the manifest must hash these actual bytes.
     * This transaction never inserts a catalog draft or confirms a revision.
     */
    @Transactional
    public PopulatedIssue issuePopulated(UUID projectId, CatalogXlsxRoundTripService.Context context,
                                 List<CatalogCaptureRegistry.EntryRow> rows,
                                 List<String> reportInstructions, ExportSource source) {
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(context);
        Objects.requireNonNull(source);
        requireProjectMatches(projectId, context);
        UUID id = UUID.randomUUID();
        byte[] bytes = xlsx.generatePopulatedIssued(context, id, rows, reportInstructions);
        var parsed = xlsx.parseIssued(context, bytes, id);
        if (!parsed.rows().equals(rows) || parsed.rows().size() != rows.size())
            throw new IllegalStateException("FIN_EXPORT_XLSX_ROUNDTRIP_MISMATCH");
        String fp = CatalogXlsxRoundTripService.schemaFingerprint(context.formCode());
        String rawHash = sha256(bytes);
        jdbc.update("""
            INSERT INTO fin_intake.catalog_xlsx_issued_template (
                id,organization_id,project_id,economic_entity_id,business_scope_id,
                project_code,form_code,reporting_basis,period_type,period_code,
                coverage,currency,amount_scale,input_locale,format_version,
                schema_fingerprint,original_template_sha)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, id, context.organizationId(), projectId, context.entityId(),
                context.businessScopeId(), context.projectCode(), context.formCode(),
                context.reportingBasis(), context.periodType().name(), context.periodCode(),
                context.coverage().name(), context.currency(), context.amountScale(),
                context.locale(), CatalogXlsxRoundTripService.ISSUED_FORMAT, fp, rawHash);
        // Both the filled issued-template manifest and source-bound receipt
        // participate in one transaction. A mismatch must roll back both.
        UUID exportId = UUID.randomUUID();
        int recorded = jdbc.update("""
            INSERT INTO fin_intake.catalog_xlsx_export_receipt(
                id,organization_id,project_id,source_revision_id,source_draft_id,
                issued_template_id,form_code,source_normalized_sha,workbook_sha,
                source_input_locale,display_locale,generated_by)
            SELECT ?,?,?,?,?,?,?,?,?,?,?,?
              FROM fin_intake.catalog_xlsx_revision r
              JOIN fin_intake.catalog_xlsx_slot s
                ON s.id=r.slot_id AND s.organization_id=r.organization_id
             WHERE r.id=? AND r.draft_id=? AND r.normalized_sha=?
               AND r.state='CONFIRMED_UNQUALIFIED_DRAFT'
               AND s.organization_id=? AND s.project_id=? AND s.form_code=?
               AND s.reporting_basis=? AND s.period_type=? AND s.period_code=?
               AND s.coverage=? AND s.currency=? AND s.amount_scale=?
            """, exportId, context.organizationId(), projectId,
                source.revisionId(), source.draftId(), id, context.formCode(),
                source.normalizedSha(), rawHash, context.locale(), source.displayLocale(),
                source.actor(), source.revisionId(), source.draftId(), source.normalizedSha(),
                context.organizationId(), projectId, context.formCode(),
                context.reportingBasis(), context.periodType().name(), context.periodCode(),
                context.coverage().name(), context.currency(), context.amountScale());
        if (recorded != 1)
            throw new IllegalStateException("FIN_EXPORT_SOURCE_PROVENANCE_MISMATCH");
        return new PopulatedIssue(exportId,
                new Issued(id, projectId, context.formCode(), context.periodCode(),
                        fp, rawHash, bytes));
    }

    /** Returns the exact normalization to be persisted, without writing a draft. */
    @Transactional(readOnly = true)
    public Preview preview(UUID organizationId, UUID projectId, UUID templateId,
                           byte[] uploadedWorkbook) {
        var stored = loadIssued(organizationId, projectId, templateId);
        var parsed = xlsx.parseIssued(stored.context(), uploadedWorkbook, templateId);
        if (!stored.schemaFingerprint().equals(parsed.schemaFingerprint())) {
            throw new IllegalArgumentException("FIN_CATALOG_ISSUED_SCHEMA_STALE");
        }
        return new Preview(templateId, projectId, parsed);
    }

    /** Creates an immutable *unqualified* draft, never an approved source/FactSet. */
    @Transactional
    public CatalogXlsxRoundTripService.Receipt upload(UUID organizationId, UUID projectId,
            UUID templateId, byte[] uploadedWorkbook,
            CatalogXlsxRoundTripService.OriginalDocument original) {
        var stored = loadIssued(organizationId, projectId, templateId);
        // The same parser+normalization is used by preview and persistence.
        var parsed = xlsx.parseIssued(stored.context(), uploadedWorkbook, templateId);
        if (parsed.empty()) {
            throw new IllegalArgumentException("FIN_CATALOG_ISSUED_EMPTY_DRAFT");
        }
        if (!stored.schemaFingerprint().equals(parsed.schemaFingerprint())) {
            throw new IllegalArgumentException("FIN_CATALOG_ISSUED_SCHEMA_STALE");
        }
        return xlsx.saveIssuedDraft(stored.context(), uploadedWorkbook, original, templateId);
    }

    private void requireProjectMatches(UUID projectId,
                                       CatalogXlsxRoundTripService.Context context) {
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM fin_intake.project_v5
             WHERE id=? AND organization_id=? AND economic_entity_id=?
               AND business_scope_id=? AND project_code=? AND accounting_currency=?
            """, Long.class, projectId, context.organizationId(), context.entityId(),
                context.businessScopeId(), context.projectCode(), context.currency());
        if (!Long.valueOf(1L).equals(count)) {
            throw new IllegalArgumentException("FIN_CATALOG_ISSUED_PROJECT_MISMATCH");
        }
    }

    private Stored loadIssued(UUID organizationId, UUID projectId, UUID templateId) {
        Objects.requireNonNull(organizationId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(templateId);
        var rows = jdbc.query("""
            SELECT economic_entity_id,business_scope_id,project_code,form_code,
                   reporting_basis,period_type,period_code,coverage,currency,
                   amount_scale,input_locale,schema_fingerprint,original_template_sha,
                   format_version
              FROM fin_intake.catalog_xlsx_issued_template
             WHERE id=? AND organization_id=? AND project_id=?
            """, (rs, row) -> {
            if (!CatalogXlsxRoundTripService.ISSUED_FORMAT.equals(rs.getString(14))) {
                throw new IllegalStateException("FIN_CATALOG_ISSUED_FORMAT_UNSUPPORTED");
            }
            var context = new CatalogXlsxRoundTripService.Context(organizationId,
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5),
                ReportingPeriodV5.Type.valueOf(rs.getString(6)), rs.getString(7),
                ReportingPeriodV5.Coverage.valueOf(rs.getString(8)), rs.getString(9),
                rs.getInt(10), rs.getString(11));
            return new Stored(projectId, context, rs.getString(12), rs.getString(13));
        }, templateId, organizationId, projectId);
        if (rows.size() != 1) {
            throw new IllegalArgumentException("FIN_CATALOG_ISSUED_NOT_FOUND");
        }
        return rows.getFirst();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("FIN_CATALOG_SHA256_UNAVAILABLE", e);
        }
    }
}
