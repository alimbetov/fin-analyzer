package kz.fin.backend.intake;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only qualification of immutable catalog revision schema metadata.
 * Historical schema mismatches are explicitly rejected, never implicitly
 * remapped to today's field types or silently converted into ACTUAL.
 */
@Service
public class CatalogHistoricalSchemaService {
    private final JdbcTemplate jdbc;

    public CatalogHistoricalSchemaService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public record Qualification(UUID revisionId, String formCode,
                                String storedFingerprint, String activeFingerprint,
                                String sourceFormat, String status,
                                List<String> storedColumns, List<String> activeColumns) {
        public Qualification {
            storedColumns = List.copyOf(storedColumns);
            activeColumns = List.copyOf(activeColumns);
        }
    }
    private record Source(String fingerprint, byte[] bytes, boolean digestValid) {}

    @Transactional(readOnly = true)
    public Qualification qualify(UUID org, UUID project, String formCode, UUID revision) {
        Objects.requireNonNull(org);
        Objects.requireNonNull(project);
        Objects.requireNonNull(revision);
        CatalogCaptureRegistry.require(formCode);
        var sources = jdbc.query("""
            SELECT d.schema_fingerprint,d.original_xlsx,
                   d.original_sha=encode(sha256(d.original_xlsx),'hex')
              FROM fin_intake.catalog_xlsx_revision r
              JOIN fin_intake.catalog_xlsx_slot s
                ON s.id=r.slot_id AND s.organization_id=r.organization_id
              JOIN fin_intake.catalog_xlsx_draft d
                ON d.id=r.draft_id AND d.organization_id=r.organization_id
               AND d.form_code=s.form_code AND d.reporting_basis=s.reporting_basis
               AND d.period_type=s.period_type AND d.period_code=s.period_code
               AND d.coverage=s.coverage AND d.currency=s.currency
               AND d.amount_scale=s.amount_scale
              JOIN fin_intake.project_v5 p
                ON p.id=s.project_id AND p.organization_id=s.organization_id
               AND p.economic_entity_id=d.economic_entity_id
               AND p.business_scope_id=d.business_scope_id
               AND p.project_code=d.project_code
               AND p.accounting_currency=d.currency
             WHERE r.id=? AND s.organization_id=? AND s.project_id=?
               AND s.form_code=? AND r.state='CONFIRMED_UNQUALIFIED_DRAFT'
               AND d.state='UNQUALIFIED_DRAFT'
            """, (rs, index) -> new Source(rs.getString(1), rs.getBytes(2),
                    rs.getBoolean(3)), revision, org, project, formCode);
        if (sources.size() != 1)
            throw new IllegalArgumentException("FIN_EXPORT_REVISION_NOT_FOUND");
        var source = sources.getFirst();
        if (!source.digestValid() || source.bytes() == null ||
                source.bytes().length > CatalogXlsxRoundTripService.MAX_XLSX_BYTES)
            throw new IllegalStateException("FIN_EXPORT_SOURCE_INTEGRITY_FAILED");

        List<String> oldColumns = new ArrayList<>();
        String embeddedFingerprint;
        String format;
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(source.bytes()))) {
            if (book.getNumberOfSheets() != 3 ||
                    book.getSheet("FIN_Data") == null ||
                    book.getSheet("_FIN_Metadata") == null)
                throw new IllegalStateException("FIN_HISTORICAL_SCHEMA_CORRUPTED");
            var metadata = book.getSheet("_FIN_Metadata");
            format = metadata.getRow(0).getCell(1).getStringCellValue();
            embeddedFingerprint = metadata.getRow(3).getCell(1).getStringCellValue();
            String embeddedCode = metadata.getRow(1).getCell(1).getStringCellValue();
            if (!formCode.equals(embeddedCode) ||
                    !source.fingerprint().equals(embeddedFingerprint) ||
                    (!CatalogXlsxRoundTripService.FORMAT.equals(format)
                    && !CatalogXlsxRoundTripService.ISSUED_FORMAT.equals(format)))
                throw new IllegalStateException("FIN_HISTORICAL_SCHEMA_CORRUPTED");
            var header = book.getSheet("FIN_Data").getRow(0);
            if (header == null || header.getLastCellNum() < 2 ||
                    header.getLastCellNum() > 100 ||
                    header.getCell(0).getCellType() != CellType.STRING ||
                    !"ROW_ID".equals(header.getCell(0).getStringCellValue()))
                throw new IllegalStateException("FIN_HISTORICAL_SCHEMA_CORRUPTED");
            for (int i = 1; i < header.getLastCellNum(); i++) {
                var cell = header.getCell(i);
                if (cell == null || cell.getCellType() != CellType.STRING ||
                        !cell.getStringCellValue().matches("[A-Za-z][A-Za-z0-9_]*") ||
                        oldColumns.contains(cell.getStringCellValue()))
                    throw new IllegalStateException("FIN_HISTORICAL_SCHEMA_CORRUPTED");
                oldColumns.add(cell.getStringCellValue());
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalStateException illegal) throw illegal;
            throw new IllegalStateException("FIN_HISTORICAL_SCHEMA_CORRUPTED", e);
        }
        String active = CatalogXlsxRoundTripService.schemaFingerprint(formCode);
        List<String> activeColumns = CatalogXlsxRoundTripService.fields(formCode)
                .stream().map(CatalogCaptureRegistry.Field::code).toList();
        // Column equality alone is NOT enough: field types and grain contribute
        // to schema fingerprint; changed semantics must be explicitly migrated.
        boolean compatible = source.fingerprint().equals(active)
                && oldColumns.equals(activeColumns);
        return new Qualification(revision, formCode, source.fingerprint(), active,
                format, compatible ? "COMPATIBLE" : "INCOMPATIBLE_REQUIRES_REVIEW",
                oldColumns, activeColumns);
    }
}
