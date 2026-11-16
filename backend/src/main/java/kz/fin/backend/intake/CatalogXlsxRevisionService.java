package kz.fin.backend.intake;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * FIN-BE-R1: catalog draft confirmations reuse the existing FIN V5 project
 * identity. Confirming a draft is NEVER independent financial source approval,
 * methodology qualification or publication of an ACTUAL FactSet.
 */
@Service
public class CatalogXlsxRevisionService {
    private final JdbcTemplate jdbc;

    public CatalogXlsxRevisionService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public record Confirmed(UUID revisionId, UUID slotId, UUID draftId,
                            int revisionNumber, UUID parentRevisionId,
                            String normalizedFingerprint) {}

    public record RevisionView(UUID revisionId, UUID slotId, UUID draftId,
                               int revisionNumber, UUID parentRevisionId,
                               String normalizedFingerprint, String confirmedBy,
                               String changeReason, String state) {}

    private record Draft(String formCode, String basis, String periodType, String periodCode,
                         String coverage, String currency, int scale, String fingerprint,
                         String documentSha) {}

    private record Prior(UUID id, UUID draftId, UUID parentId,
                         String fingerprint, String documentSha,
                         boolean sameOrderedRows) {}

    private record Slot(UUID id, int current) {}

    @Transactional
    public Confirmed confirm(UUID organizationId, UUID projectId, UUID draftId,
                             String expectedFingerprint, int expectedRevision,
                             String confirmedBy, String changeReason) {
        Objects.requireNonNull(organizationId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(draftId);
        if (expectedFingerprint == null || !expectedFingerprint.matches("[a-f0-9]{64}") ||
                expectedRevision < 0 || confirmedBy == null || confirmedBy.isBlank() ||
                confirmedBy.length() > 200 || changeReason == null ||
                changeReason.length() > 1000 ||
                (expectedRevision > 0 && changeReason.isBlank())) {
            throw new IllegalArgumentException("FIN_CATALOG_CONFIRM_INVALID_INPUT");
        }

        // Project V5 is the existing immutable project identity. No project
        // can claim an unrelated draft merely by matching projectCode.
        var drafts = jdbc.query("""
            SELECT d.form_code,d.reporting_basis,d.period_type,d.period_code,
                   d.coverage,d.currency,d.amount_scale,d.normalized_sha,
                   d.document_sha
              FROM fin_intake.catalog_xlsx_draft d
              JOIN fin_intake.project_v5 p
                ON p.organization_id=d.organization_id
               AND p.economic_entity_id=d.economic_entity_id
               AND p.business_scope_id=d.business_scope_id
               AND p.project_code=d.project_code
               AND p.accounting_currency=d.currency
             WHERE d.id=? AND d.organization_id=?
               AND p.id=? AND p.organization_id=?
               AND d.state='UNQUALIFIED_DRAFT'
            """, (rs, i) -> new Draft(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getInt(7),
                    rs.getString(8), rs.getString(9)),
                draftId, organizationId, projectId, organizationId);
        if (drafts.size() != 1)
            throw new IllegalArgumentException("FIN_CATALOG_DRAFT_PROJECT_MISMATCH");
        Draft draft = drafts.getFirst();
        if (!draft.fingerprint().equals(expectedFingerprint))
            throw new IllegalArgumentException("FIN_CATALOG_STALE_FINGERPRINT");

        UUID proposedSlot = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fin_intake.catalog_xlsx_slot (
              id,organization_id,project_id,form_code,reporting_basis,period_type,
              period_code,coverage,currency,amount_scale)
            VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT ON CONSTRAINT uq_fin_catalog_slot_identity DO NOTHING
            """, proposedSlot, organizationId, projectId,
                draft.formCode(), draft.basis(), draft.periodType(), draft.periodCode(),
                draft.coverage(), draft.currency(), draft.scale());

        var slots = jdbc.query("""
            SELECT id,latest_revision
              FROM fin_intake.catalog_xlsx_slot
             WHERE organization_id=? AND project_id=? AND form_code=?
               AND reporting_basis=? AND period_type=? AND period_code=?
               AND coverage=? AND currency=? AND amount_scale=?
             FOR UPDATE
            """, (rs, i) -> new Slot((UUID) rs.getObject(1), rs.getInt(2)),
                organizationId, projectId, draft.formCode(), draft.basis(),
                draft.periodType(), draft.periodCode(), draft.coverage(),
                draft.currency(), draft.scale());
        if (slots.size() != 1)
            throw new IllegalStateException("FIN_CATALOG_SLOT_MISSING");
        Slot slot = slots.getFirst();
        if (slot.current() != expectedRevision)
            throw new IllegalArgumentException("FIN_CATALOG_REVISION_CONFLICT");

        UUID previous = null;
        if (slot.current() > 0) {
            var previousRows = jdbc.query("""
                SELECT r.id,r.draft_id,r.parent_revision_id,r.normalized_sha,
                       d.document_sha,
                       (d.normalized_rows = candidate.normalized_rows) AS same_ordered_rows
                  FROM fin_intake.catalog_xlsx_revision r
                  JOIN fin_intake.catalog_xlsx_draft d
                    ON d.id=r.draft_id AND d.organization_id=r.organization_id
                  JOIN fin_intake.catalog_xlsx_draft candidate
                    ON candidate.id=? AND candidate.organization_id=d.organization_id
                 WHERE r.organization_id=? AND r.slot_id=?
                   AND r.revision_number=?
                """, (rs, i) -> new Prior(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                    rs.getBoolean(6)),
                    draftId, organizationId, slot.id(), slot.current());
            if (previousRows.size() != 1)
                throw new IllegalStateException("FIN_CATALOG_REVISION_HISTORY_BROKEN");
            Prior prior = previousRows.getFirst();
            // V5-compatible duplicate behavior: repeated content does not
            // advance the current revision. For evidence-only forms the
            // independently stored original document hash is part of the
            // logical content identity, not merely the Excel register cells.
            // Legacy normalized_sha deliberately sorts rowIds, so permutations
            // produce the same SHA. The source's immutable JSONB array keeps
            // row order: PostgreSQL JSONB array equality is order-sensitive.
            // Require BOTH equality checks; never silently suppress an
            // ordered-row revision nor change stored V1 hash semantics.
            if (prior.fingerprint().equals(draft.fingerprint())
                    && prior.sameOrderedRows()
                    && Objects.equals(prior.documentSha(), draft.documentSha())) {
                return new Confirmed(prior.id(), slot.id(), prior.draftId(),
                        slot.current(), prior.parentId(), prior.fingerprint());
            }
            previous = prior.id();
        }

        UUID revisionId = UUID.randomUUID();
        int next = slot.current() + 1;
        jdbc.update("""
            INSERT INTO fin_intake.catalog_xlsx_revision (
              id,organization_id,slot_id,draft_id,revision_number,parent_revision_id,
              normalized_sha,confirmed_by,change_reason)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, revisionId, organizationId, slot.id(), draftId, next,
                previous, draft.fingerprint(), confirmedBy, changeReason);
        if (jdbc.update("""
            UPDATE fin_intake.catalog_xlsx_slot SET latest_revision=?
             WHERE id=? AND organization_id=? AND latest_revision=?
            """, next, slot.id(), organizationId, slot.current()) != 1)
            throw new IllegalStateException("FIN_CATALOG_SLOT_CAS_FAILED");

        return new Confirmed(revisionId, slot.id(), draftId, next,
                previous, draft.fingerprint());
    }

    @Transactional(readOnly = true)
    public RevisionView current(UUID organizationId, UUID projectId, UUID slotId) {
        Objects.requireNonNull(organizationId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(slotId);
        var rows = jdbc.query("""
            SELECT r.id,r.slot_id,r.draft_id,r.revision_number,
                   r.parent_revision_id,r.normalized_sha,r.confirmed_by,
                   r.change_reason,r.state
              FROM fin_intake.catalog_xlsx_slot s
              JOIN fin_intake.catalog_xlsx_revision r
                ON r.slot_id=s.id AND r.organization_id=s.organization_id
               AND r.revision_number=s.latest_revision
             WHERE s.id=? AND s.project_id=? AND s.organization_id=?
            """, CatalogXlsxRevisionService::mapRevision,
                slotId, projectId, organizationId);
        if (rows.size() != 1) throw new IllegalArgumentException("FIN_CATALOG_CURRENT_NOT_FOUND");
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public List<RevisionView> history(UUID organizationId, UUID projectId, UUID slotId) {
        Objects.requireNonNull(organizationId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(slotId);
        return jdbc.query("""
            SELECT r.id,r.slot_id,r.draft_id,r.revision_number,
                   r.parent_revision_id,r.normalized_sha,r.confirmed_by,
                   r.change_reason,r.state
              FROM fin_intake.catalog_xlsx_slot s
              JOIN fin_intake.catalog_xlsx_revision r
                ON r.slot_id=s.id AND r.organization_id=s.organization_id
             WHERE s.id=? AND s.project_id=? AND s.organization_id=?
             ORDER BY r.revision_number ASC
            """, CatalogXlsxRevisionService::mapRevision,
                slotId, projectId, organizationId);
    }

    private static RevisionView mapRevision(java.sql.ResultSet rs, int index)
            throws java.sql.SQLException {
        return new RevisionView(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getInt(4),
                rs.getObject(5, UUID.class), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9));
    }
}
