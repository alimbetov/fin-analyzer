package kz.fin.backend.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * FIN-BE-R1: one internal Java XLSX issuer/parser for 122 typed drafts,
 * 32 unqualified specialized draft schemas and 12 document registers.
 *
 * This service is NOT a public HTTP API, source approval, a method qualification
 * or a means of publishing ACTUAL. Its callers must stay inside the existing
 * trusted application boundary. Workbook metadata proves compatibility only,
 * not external document authenticity or authorization.
 */
@Service
public class CatalogXlsxRoundTripService {
    public static final String FORMAT = "FIN-BE-CATALOG-XLSX-V1";
    public static final String ISSUED_FORMAT = "FIN-BE-CATALOG-XLSX-V2";
    private static final String ISSUED_KEY = "ISSUED_TEMPLATE_ID";
    public static final int MAX_XLSX_BYTES = 2_000_000;
    private static final int MAX_ZIP_BYTES = 16_000_000;
    private static final String DATA = "FIN_Data";
    private static final String META = "_FIN_Metadata";
    private static final String INSTRUCTIONS = "Instructions";
    private static final List<String> META_KEYS = List.of(
            "FIN_FORMAT", "FORM_CODE", "CATALOG_VERSION", "SCHEMA_FINGERPRINT",
            "ORGANIZATION_ID", "ENTITY_ID", "BUSINESS_SCOPE_ID", "PROJECT_CODE",
            "REPORTING_BASIS", "PERIOD_TYPE", "PERIOD_CODE", "COVERAGE",
            "CURRENCY", "AMOUNT_SCALE", "LOCALE", "CAPTURE_MODE");

    private static final List<CatalogCaptureRegistry.Field> DOCUMENT_FIELDS = List.of(
            new CatalogCaptureRegistry.Field("documentRef", CatalogCaptureRegistry.FieldType.REF),
            new CatalogCaptureRegistry.Field("documentType", CatalogCaptureRegistry.FieldType.CODE),
            new CatalogCaptureRegistry.Field("documentDate", CatalogCaptureRegistry.FieldType.DATE),
            new CatalogCaptureRegistry.Field("version", CatalogCaptureRegistry.FieldType.CODE),
            new CatalogCaptureRegistry.Field("issuerRef", CatalogCaptureRegistry.FieldType.REF),
            new CatalogCaptureRegistry.Field("attachmentRef", CatalogCaptureRegistry.FieldType.REF),
            new CatalogCaptureRegistry.Field("status", CatalogCaptureRegistry.FieldType.ENUM));

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CatalogDocumentPreflight documentPreflight;

    public CatalogXlsxRoundTripService(JdbcTemplate jdbc, ObjectMapper json,
                                       CatalogDocumentPreflight documentPreflight) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.json = Objects.requireNonNull(json);
        this.documentPreflight = Objects.requireNonNull(documentPreflight);
    }

    public record Context(UUID organizationId, UUID entityId, UUID businessScopeId,
                          String projectCode, String formCode, String reportingBasis,
                          ReportingPeriodV5.Type periodType, String periodCode,
                          ReportingPeriodV5.Coverage coverage, String currency,
                          int amountScale, String locale) {
        public Context {
            Objects.requireNonNull(organizationId, "organizationId");
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(businessScopeId, "businessScopeId");
            CatalogCaptureRegistry.require(formCode);
            if (projectCode == null || !projectCode.matches("[A-Za-z0-9_-]{1,50}") ||
                    !Set.of("MANAGEMENT", "SEPARATE", "CONSOLIDATED").contains(reportingBasis) ||
                    currency == null || !currency.matches("[A-Z]{3}") ||
                    !Set.of(1, 1000, 1000000).contains(amountScale) ||
                    !Set.of("ru", "kk", "en").contains(locale)) {
                throw new IllegalArgumentException("CATALOG_XLSX_INVALID_CONTEXT");
            }
            ReportingPeriodV5.resolve(periodType, periodCode, coverage);
        }

        CatalogCaptureRegistry.Context asCatalogContext() {
            return new CatalogCaptureRegistry.Context(formCode, projectCode, locale,
                    periodType, periodCode, coverage, currency, amountScale);
        }
    }

    public record OriginalDocument(String fileName, String mediaType, byte[] bytes) {
        public OriginalDocument {
            Objects.requireNonNull(bytes, "bytes");
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public record Parsed(Context context, String captureMode, String schemaFingerprint,
                         List<CatalogCaptureRegistry.EntryRow> rows,
                         String normalizedFingerprint) {
        public Parsed { rows = List.copyOf(rows); }
        public boolean empty() { return rows.isEmpty(); }
    }

    public record Receipt(UUID id, String formCode, String captureMode,
                          String rawSha256, String normalizedFingerprint,
                          String documentSha256) {}

    public record Stored(UUID id, String formCode, String captureMode, String schemaFingerprint,
                         String rawSha256, String normalizedFingerprint,
                         String documentSha256, JsonNode normalizedRows) {}

    public static List<CatalogCaptureRegistry.Field> fields(String formCode) {
        var definition = CatalogCaptureRegistry.require(formCode);
        return switch (definition.captureMode()) {
            case TYPED_DRAFT -> definition.fields();
            case SCHEMA_NOT_APPROVED -> SpecializedSchemaCandidateRegistry.require(formCode).fields();
            case EVIDENCE_ONLY -> DOCUMENT_FIELDS;
        };
    }

    public static String schemaFingerprint(String formCode) {
        var form = CatalogCaptureRegistry.require(formCode);
        StringBuilder schema = new StringBuilder(FORMAT).append('|')
                .append(CatalogCaptureRegistry.VERSION).append('|')
                .append(form.code()).append('|')
                .append(form.captureMode()).append('|').append(form.grain());
        for (var field : fields(formCode)) {
            schema.append('|').append(field.code()).append(':').append(field.type());
        }
        return sha256(schema.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String[] expectedMeta(Context c) {
        return new String[] {
                FORMAT, c.formCode(), CatalogCaptureRegistry.VERSION,
                schemaFingerprint(c.formCode()), c.organizationId().toString(),
                c.entityId().toString(), c.businessScopeId().toString(),
                c.projectCode(), c.reportingBasis(), c.periodType().name(),
                c.periodCode(), c.coverage().name(), c.currency(),
                Integer.toString(c.amountScale()), c.locale(),
                CatalogCaptureRegistry.require(c.formCode()).captureMode().name()
        };
    }

    public byte[] generate(Context context) {
        return generate(context, null);
    }

    byte[] generateIssued(Context context, UUID issuedTemplateId) {
        return generate(context, Objects.requireNonNull(issuedTemplateId));
    }

    private byte[] generate(Context context, UUID issuedTemplateId) {
        Objects.requireNonNull(context);
        var columns = fields(context.formCode());
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var instructions = book.createSheet(INSTRUCTIONS);
            instructions.createRow(0).createCell(0).setCellValue(
                    issuedTemplateId == null ? FORMAT : ISSUED_FORMAT);
            instructions.createRow(1).createCell(0).setCellValue(
                    "UNQUALIFIED DRAFT: an Excel file is not independently reviewed financial evidence.");
            instructions.createRow(2).createCell(0).setCellValue(
                    "Enter ROW_ID and exact literal values; no formulas, macros or external links.");
            instructions.createRow(3).createCell(0).setCellValue(
                    "Form " + context.formCode() + " / period " + context.periodCode());
            instructions.setColumnWidth(0, 11000);

            var data = book.createSheet(DATA);
            Row header = data.createRow(0);
            header.createCell(0).setCellValue("ROW_ID");
            for (int i = 0; i < columns.size(); i++) {
                header.createCell(i + 1).setCellValue(columns.get(i).code());
                data.setColumnWidth(i + 1, 5400);
            }
            data.setColumnWidth(0, 5000);
            data.createFreezePane(1, 1);
            data.createRow(1).createCell(0).setCellValue("ROW-001");

            var meta = book.createSheet(META);
            var values = expectedMeta(context);
            if (issuedTemplateId != null) values[0] = ISSUED_FORMAT;
            for (int i = 0; i < META_KEYS.size(); i++) {
                Row row = meta.createRow(i);
                row.createCell(0).setCellValue(META_KEYS.get(i));
                row.createCell(1).setCellValue(values[i]);
            }
            if (issuedTemplateId != null) {
                Row issuedRow = meta.createRow(META_KEYS.size());
                issuedRow.createCell(0).setCellValue(ISSUED_KEY);
                issuedRow.createCell(1).setCellValue(issuedTemplateId.toString());
            }
            book.setSheetVisibility(2, SheetVisibility.VERY_HIDDEN);
            book.write(output);
            if (output.size() > MAX_XLSX_BYTES) {
                throw new IllegalArgumentException("CATALOG_XLSX_SIZE_LIMIT");
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("CATALOG_XLSX_GENERATION_FAILED", e);
        }
    }

    /**
     * X2: create an ACTUALLY POPULATED server-issued V2 workbook.
     * Keep V1 and existing empty issued templates unchanged.
     * Metadata is never rewritten for the user's display language.
     */
    byte[] generatePopulatedIssued(Context context, UUID issuedTemplateId,
                                   List<CatalogCaptureRegistry.EntryRow> sourceRows,
                                   List<String> instructions) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(issuedTemplateId);
        if (sourceRows == null || sourceRows.isEmpty()
                || sourceRows.size() > CatalogCaptureRegistry.MAX_ROWS
                || instructions == null || instructions.size() > 60
                || instructions.stream().anyMatch(s -> s == null || s.length() > 5000))
            throw new IllegalArgumentException("FIN_EXPORT_XLSX_INVALID_DATA");

        // This starts with the exact V2 contract already checked by parseIssued.
        byte[] template = generateIssued(context, issuedTemplateId);
        var fields = fields(context.formCode());
        var seen = new HashSet<String>();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(template));
             var output = new ByteArrayOutputStream()) {
            var data = book.getSheet(DATA);
            Row placeholder = data.getRow(1);
            if (placeholder != null) data.removeRow(placeholder);
            var textStyle = book.createCellStyle();
            textStyle.setDataFormat(book.createDataFormat().getFormat("@"));
            for (int rowIndex = 0; rowIndex < sourceRows.size(); rowIndex++) {
                var source = sourceRows.get(rowIndex);
                if (source == null || source.rowId() == null
                        || !source.rowId().matches("[A-Za-z0-9_-]{1,60}")
                        || !seen.add(source.rowId()) || source.cells() == null
                        || source.cells().size() != fields.size())
                    throw new IllegalArgumentException("FIN_EXPORT_XLSX_INVALID_SOURCE_ROW");
                Row row = data.createRow(rowIndex + 1);
                var identity = row.createCell(0, CellType.STRING);
                identity.setCellStyle(textStyle);
                identity.setCellValue(source.rowId());
                for (int col = 0; col < fields.size(); col++) {
                    String value = source.cells().get(fields.get(col).code());
                    if (value == null || value.length() > 32767)
                        throw new IllegalArgumentException("FIN_EXPORT_XLSX_INVALID_SOURCE_CELL");
                    var cell = row.createCell(col + 1, CellType.STRING);
                    cell.setCellStyle(textStyle);
                    cell.setCellValue(value);
                }
            }

            var info = book.getSheet(INSTRUCTIONS);
            for (int i = 0; i < instructions.size(); i++) {
                info.createRow(i + 4).createCell(0, CellType.STRING)
                        .setCellValue(instructions.get(i));
            }
            book.write(output);
            if (output.size() > MAX_XLSX_BYTES)
                throw new IllegalArgumentException("CATALOG_XLSX_SIZE_LIMIT");
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("FIN_EXPORT_XLSX_GENERATION_FAILED", e);
        }
    }

    public Parsed parse(Context context, byte[] bytes) {
        return parse(context, bytes, null);
    }

    Parsed parseIssued(Context context, byte[] bytes, UUID issuedTemplateId) {
        return parse(context, bytes, Objects.requireNonNull(issuedTemplateId));
    }

    private Parsed parse(Context context, byte[] bytes, UUID issuedTemplateId) {
        Objects.requireNonNull(context);
        if (bytes == null || bytes.length < 100 || bytes.length > MAX_XLSX_BYTES) {
            throw new IllegalArgumentException("CATALOG_XLSX_SIZE_LIMIT");
        }
        inspectZip(bytes);
        var definition = CatalogCaptureRegistry.require(context.formCode());
        var columns = fields(context.formCode());
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (book.getNumberOfSheets() != 3 ||
                    !INSTRUCTIONS.equals(book.getSheetName(0)) ||
                    !DATA.equals(book.getSheetName(1)) ||
                    !META.equals(book.getSheetName(2)) ||
                    book.getSheetVisibility(2) != SheetVisibility.VERY_HIDDEN) {
                throw new IllegalArgumentException("CATALOG_XLSX_LAYOUT_MISMATCH");
            }
            // Reject an occupied sparse cell, error or formula anywhere in the workbook.
            for (Sheet sheet : book) {
                if (sheet.getLastRowNum() > CatalogCaptureRegistry.MAX_ROWS + 1) {
                    throw new IllegalArgumentException("CATALOG_XLSX_EXCESS_ROWS");
                }
                for (Row row : sheet) {
                    if (row.getLastCellNum() > (sheet.getSheetName().equals(DATA)
                            ? columns.size() + 1 : sheet.getSheetName().equals(META) ? 2 : 1)) {
                        throw new IllegalArgumentException("CATALOG_XLSX_EXTRA_COLUMNS");
                    }
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.FORMULA ||
                                cell.getCellType() == CellType.ERROR) {
                            throw new IllegalArgumentException("CATALOG_XLSX_FORMULA_OR_ERROR");
                        }
                    }
                }
            }
            var meta = book.getSheet(META);
            if (meta.getLastRowNum() != META_KEYS.size() - 1
                    + (issuedTemplateId == null ? 0 : 1)) {
                throw new IllegalArgumentException("CATALOG_XLSX_METADATA_MISMATCH");
            }
            var expected = expectedMeta(context);
            if (issuedTemplateId != null) expected[0] = ISSUED_FORMAT;
            for (int i = 0; i < META_KEYS.size(); i++) {
                var row = meta.getRow(i);
                if (row == null || !META_KEYS.get(i).equals(literal(row.getCell(0))) ||
                        !expected[i].equals(literal(row.getCell(1)))) {
                    throw new IllegalArgumentException("CATALOG_XLSX_METADATA_MISMATCH");
                }
            }
            if (issuedTemplateId != null) {
                Row issuedRow = meta.getRow(META_KEYS.size());
                if (issuedRow == null ||
                        !ISSUED_KEY.equals(literal(issuedRow.getCell(0))) ||
                        !issuedTemplateId.toString().equals(literal(issuedRow.getCell(1)))) {
                    throw new IllegalArgumentException("CATALOG_XLSX_ISSUED_ID_MISMATCH");
                }
            }
            var sheet = book.getSheet(DATA);
            var header = sheet.getRow(0);
            if (header == null || header.getLastCellNum() != columns.size() + 1 ||
                    !"ROW_ID".equals(literal(header.getCell(0)))) {
                throw new IllegalArgumentException("CATALOG_XLSX_HEADER_MISMATCH");
            }
            for (int i = 0; i < columns.size(); i++) {
                if (!columns.get(i).code().equals(literal(header.getCell(i + 1)))) {
                    throw new IllegalArgumentException("CATALOG_XLSX_HEADER_MISMATCH");
                }
            }
            if (sheet.getLastRowNum() > CatalogCaptureRegistry.MAX_ROWS) {
                throw new IllegalArgumentException("CATALOG_XLSX_EXCESS_ROWS");
            }
            var rawRows = new ArrayList<CatalogCaptureRegistry.EntryRow>();
            var ids = new HashSet<String>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String rowId = literal(row.getCell(0));
                Map<String, String> cells = new LinkedHashMap<>();
                boolean filled = false;
                for (int col = 0; col < columns.size(); col++) {
                    String value = literal(row.getCell(col + 1));
                    if (!value.isBlank()) filled = true;
                    cells.put(columns.get(col).code(), value);
                }
                if (!filled) continue;
                if (rowId == null || !rowId.matches("[A-Za-z0-9_-]{1,60}") ||
                        !ids.add(rowId)) {
                    throw new IllegalArgumentException("CATALOG_XLSX_DUPLICATE_OR_INVALID_ROW");
                }
                rawRows.add(new CatalogCaptureRegistry.EntryRow(rowId, cells));
            }

            List<CatalogCaptureRegistry.EntryRow> normalized = switch (definition.captureMode()) {
                case TYPED_DRAFT -> {
                    if (rawRows.isEmpty()) yield List.of();
                    var preview = CatalogCaptureRegistry.validate(context.asCatalogContext(), rawRows);
                    if (!preview.valid()) {
                        throw new IllegalArgumentException("CATALOG_XLSX_TYPED_VALIDATION_FAILED: " +
                                preview.findings());
                    }
                    yield preview.rows();
                }
                case SCHEMA_NOT_APPROVED -> {
                    if (rawRows.isEmpty()) yield List.of();
                    var preview = SpecializedSchemaCandidateRegistry.validate(
                            context.formCode(), rawRows, context.locale());
                    if (!preview.validDraft()) {
                        throw new IllegalArgumentException("CATALOG_XLSX_CANDIDATE_VALIDATION_FAILED: " +
                                preview.findings());
                    }
                    yield preview.rows();
                }
                case EVIDENCE_ONLY -> {
                    var result = new ArrayList<CatalogCaptureRegistry.EntryRow>();
                    for (var raw : rawRows) {
                        var values = new LinkedHashMap<String, String>();
                        for (var field : DOCUMENT_FIELDS) {
                            values.put(field.code(), CatalogCaptureRegistry.canonical(
                                    field.type(), raw.cells().get(field.code()), context.locale()));
                        }
                        if (values.get("attachmentRef").isBlank()) {
                            throw new IllegalArgumentException("CATALOG_XLSX_DOCUMENT_REF_REQUIRED");
                        }
                        result.add(new CatalogCaptureRegistry.EntryRow(
                                raw.rowId(), Collections.unmodifiableMap(values)));
                    }
                    yield List.copyOf(result);
                }
            };
            String fingerprint = normalizedFingerprint(context, normalized);
            return new Parsed(context, definition.captureMode().name(),
                    schemaFingerprint(context.formCode()), normalized, fingerprint);
        } catch (IOException e) {
            throw new IllegalArgumentException("CATALOG_XLSX_INVALID_WORKBOOK", e);
        }
    }

    /**
     * Creates only an immutable unqualified draft. This never touches the
     * approved-source, FactSet, outbox, diagnostic or method tables.
     * For document-register codes a real original is mandatory.
     */
    @Transactional
    public Receipt saveDraft(Context context, byte[] workbook, OriginalDocument document) {
        return saveDraftInternal(context, workbook, document, null);
    }

    @Transactional
    public Receipt saveIssuedDraft(Context context, byte[] workbook,
                                   OriginalDocument document, UUID issuedTemplateId) {
        return saveDraftInternal(context, workbook, document,
                Objects.requireNonNull(issuedTemplateId));
    }

    private Receipt saveDraftInternal(Context context, byte[] workbook,
                                      OriginalDocument document, UUID issuedTemplateId) {
        var parsed = issuedTemplateId == null
                ? parse(context, workbook)
                : parseIssued(context, workbook, issuedTemplateId);
        if (parsed.empty()) {
            throw new IllegalArgumentException("CATALOG_XLSX_EMPTY_DRAFT");
        }
        boolean evidence = CatalogCaptureRegistry.require(context.formCode()).captureMode() ==
                CatalogCaptureRegistry.Mode.EVIDENCE_ONLY;
        if (evidence) {
            if (document == null || parsed.rows().size() != 1 ||
                    !parsed.rows().getFirst().cells().get("attachmentRef").equals(document.fileName())) {
                throw new IllegalArgumentException("CATALOG_XLSX_DOCUMENT_ORIGINAL_REQUIRED");
            }
            documentPreflight.requireRecognizedOriginal(document);
        } else if (document != null) {
            throw new IllegalArgumentException("CATALOG_XLSX_UNEXPECTED_DOCUMENT");
        }
        var id = UUID.randomUUID();
        String originalSha = sha256(workbook);
        String documentSha = evidence ? sha256(document.bytes()) : null;
        String rows;
        try {
            rows = json.writeValueAsString(parsed.rows());
        } catch (Exception e) {
            throw new IllegalStateException("CATALOG_XLSX_SERIALIZATION_FAILED", e);
        }
        jdbc.update("""
            INSERT INTO fin_intake.catalog_xlsx_draft(
                id,organization_id,economic_entity_id,business_scope_id,project_code,
                form_code,capture_mode,reporting_basis,period_type,period_code,coverage,
                currency,amount_scale,locale,schema_fingerprint,original_xlsx,original_sha,
                normalized_rows,normalized_sha,document_original,document_sha,document_media_type,
                issued_template_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,?)
            """, id, context.organizationId(), context.entityId(), context.businessScopeId(),
                context.projectCode(), context.formCode(), parsed.captureMode(),
                context.reportingBasis(), context.periodType().name(), context.periodCode(),
                context.coverage().name(), context.currency(), context.amountScale(), context.locale(),
                parsed.schemaFingerprint(), workbook, originalSha, rows,
                parsed.normalizedFingerprint(), evidence ? document.bytes() : null,
                documentSha, evidence ? document.mediaType() : null, issuedTemplateId);
        return new Receipt(id, context.formCode(), parsed.captureMode(),
                originalSha, parsed.normalizedFingerprint(), documentSha);
    }

    @Transactional(readOnly = true)
    public Stored loadDraft(UUID organizationId, UUID id) {
        var result = jdbc.query("""
            SELECT form_code,capture_mode,schema_fingerprint,original_sha,
                   normalized_sha,document_sha,normalized_rows::text
            FROM fin_intake.catalog_xlsx_draft
            WHERE organization_id=? AND id=?
            """, (rs, i) -> {
                try {
                    return new Stored(id, rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), json.readTree(rs.getString(7)));
                } catch (IOException e) {
                    throw new IllegalStateException("CATALOG_XLSX_CORRUPT_DRAFT", e);
                }
            }, organizationId, id);
        if (result.size() != 1) {
            throw new IllegalArgumentException("CATALOG_XLSX_DRAFT_NOT_FOUND");
        }
        return result.getFirst();
    }

    private static String literal(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return "";
        if (cell.getCellType() != CellType.STRING) {
            throw new IllegalArgumentException("CATALOG_XLSX_LITERAL_STRING_REQUIRED");
        }
        return cell.getStringCellValue();
    }

    static String normalizedFingerprint(Context context,
            List<CatalogCaptureRegistry.EntryRow> rows) {
        StringBuilder builder = new StringBuilder(schemaFingerprint(context.formCode()))
                .append('|').append(context.organizationId())
                .append('|').append(context.entityId())
                .append('|').append(context.businessScopeId())
                .append('|').append(context.projectCode())
                .append('|').append(context.reportingBasis())
                .append('|').append(context.periodType())
                .append('|').append(context.periodCode())
                .append('|').append(context.coverage())
                .append('|').append(context.currency())
                .append('|').append(context.amountScale());
        var sorted = rows.stream().sorted(Comparator.comparing(
                CatalogCaptureRegistry.EntryRow::rowId)).toList();
        for (var row : sorted) {
            appendBounded(builder, row.rowId());
            for (var field : fields(context.formCode())) {
                appendBounded(builder, row.cells().getOrDefault(field.code(), ""));
            }
        }
        return sha256(builder.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendBounded(StringBuilder builder, String value) {
        builder.append('|').append(value.length()).append(':').append(value);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", e);
        }
    }

    private static void inspectZip(byte[] bytes) {
        try (var zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            long total = 0;
            int count = 0;
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (++count > 256 || entry.getName().startsWith("/") ||
                        entry.getName().contains("..") || entry.getName().contains("\\")) {
                    throw new IllegalArgumentException("CATALOG_XLSX_UNSAFE_ZIP");
                }
                int n;
                while ((n = zin.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_ZIP_BYTES) {
                        throw new IllegalArgumentException("CATALOG_XLSX_ZIP_BUDGET_EXCEEDED");
                    }
                }
            }
            if (count < 5) throw new IllegalArgumentException("CATALOG_XLSX_INVALID_ZIP");
        } catch (IOException e) {
            throw new IllegalArgumentException("CATALOG_XLSX_INVALID_ZIP", e);
        }
    }
}
