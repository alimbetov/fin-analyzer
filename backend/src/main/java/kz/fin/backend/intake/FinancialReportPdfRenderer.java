package kz.fin.backend.intake;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * FIN-BE-R1-X4 PDFBox offline renderer. No HTML fetch, URI resolver, external
 * images, JavaScript, document attachments or network resources. It consumes
 * exactly the X1 snapshot (not an independent query or approved ACTUAL).
 *
 * Fonts are provisioned at deployment by the operator. They are not downloaded
 * at request time, and failure to load a required glyph is fatal (no invisible
 * Chinese, Kazakh Cyrillic, or truncated numeric values).
 */
@Service
public final class FinancialReportPdfRenderer {
    public static final String VERSION = "FIN-BE-R1-X4-PDF-V1";
    public static final int MAX_PDF_BYTES = 8_000_000;
    public static final int MAX_PAGES = 120;
    private static final PDRectangle LANDSCAPE = new PDRectangle(
            PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float PAGE_WIDTH = LANDSCAPE.getWidth();
    private static final float PAGE_HEIGHT = LANDSCAPE.getHeight();
    private static final float LEFT = 30f;
    private static final float RIGHT = 30f;
    private static final float FOOTER = 35f;
    private static final float LINE_HEIGHT = 10f;
    private static final float FONT_SIZE = 7.8f;
    private static final int FIELDS_PER_GROUP = 4;
    private final FinancialReportRenderModel model;

    public FinancialReportPdfRenderer(FinancialReportRenderModel model) {
        this.model = Objects.requireNonNull(model);
    }

    public record Rendered(UUID sourceRevisionId, String displayLocale,
                           int pageCount, String sha256, byte[] bytes,
                           String contentFingerprintV2) {
        public Rendered { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public Rendered render(FinancialReportSnapshotService.Snapshot snapshot,
                           String displayLocale, java.time.Instant generatedAt) {
        return render(model.prepare(snapshot, displayLocale, generatedAt));
    }

    public Rendered render(FinancialReportRenderModel.View view) {
        Objects.requireNonNull(view);
        var snapshot = view.snapshot();
        if (snapshot.fields().size() > 80 || snapshot.rows().size() > 200) {
            throw new IllegalArgumentException("FIN_PDF_SOURCE_BUDGET_EXCEEDED");
        }
        Path latinPath = font("FIN_PDF_LATIN_TTF",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
        Path cjkPath = font("FIN_PDF_CJK_TTF",
                "/usr/share/fonts/truetype/arphic-gbsn00lp/gbsn00lp.ttf");
        try (PDDocument document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            PDType0Font latin = PDType0Font.load(document, latinPath.toFile());
            PDType0Font cjk = PDType0Font.load(document, cjkPath.toFile());
            var fontSet = new Fonts(latin, cjk);
            document.getDocumentInformation().setTitle(
                    "FIN " + snapshot.formCode() + " " + snapshot.sourceRevisionId());
            document.getDocumentInformation().setCreator(VERSION);
            document.getDocumentInformation().setSubject(
                    "UNQUALIFIED source; no independent financial approval");

            int groups = (view.labels().fields().size() + FIELDS_PER_GROUP - 1)
                    / FIELDS_PER_GROUP;
            if (groups == 0) groups = 1;
            for (int group = 0; group < groups; group++) {
                int start = group * FIELDS_PER_GROUP;
                int end = Math.min(start + FIELDS_PER_GROUP, view.labels().fields().size());
                var included = view.labels().fields().subList(start, end);
                try (Pages pages = new Pages(document, fontSet, view, start, end)) {
                    pages.next();
                    for (var row : snapshot.rows()) {
                        List<List<String>> cells = new ArrayList<>();
                        cells.add(wrap(row.rowId(), pages.idWidth - 10f, FONT_SIZE, fontSet));
                        for (var f : included) {
                            String raw = row.cells().get(f.fieldCode());
                            if (raw == null)
                                throw new IllegalArgumentException("FIN_PDF_FIELD_ABSENT");
                            cells.add(wrap(raw, pages.fieldWidth - 10f, FONT_SIZE, fontSet));
                        }
                        int lines = cells.stream().mapToInt(List::size).max().orElse(1);
                        float height = 8f + LINE_HEIGHT * lines;
                        if (height > PAGE_HEIGHT - 280f)
                            throw new IllegalArgumentException("FIN_PDF_UNPRINTABLE_CELL");
                        if (pages.y - height < FOOTER) pages.next();
                        pages.drawRow(cells, height);
                    }
                }
            }
            if (document.getNumberOfPages() < 1 ||
                    document.getNumberOfPages() > MAX_PAGES)
                throw new IllegalArgumentException("FIN_PDF_PAGE_BUDGET");
            document.save(out);
            if (out.size() > MAX_PDF_BYTES)
                throw new IllegalArgumentException("FIN_PDF_SIZE_BUDGET");
            byte[] bytes = out.toByteArray();
            return new Rendered(snapshot.sourceRevisionId(), view.displayLocale(),
                    document.getNumberOfPages(),
                    HexFormat.of().formatHex(hash(bytes)), bytes,
                    snapshot.contentFingerprintV2());
        } catch (IOException | IllegalStateException e) {
            throw new IllegalArgumentException("FIN_PDF_RENDER_FAILED", e);
        }
    }

    private static Path font(String environment, String defaultFile) {
        String configured = System.getenv(environment);
        Path path = Path.of(configured == null || configured.isBlank()
                ? defaultFile : configured);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)
                || !path.toString().endsWith(".ttf")) {
            throw new IllegalStateException("FIN_PDF_REQUIRED_LOCAL_TTF_NOT_PROVISIONED: "
                    + environment);
        }
        return path;
    }

    private record Fonts(PDType0Font latin, PDType0Font cjk) {
        PDType0Font forPoint(int cp) {
            String s = new String(Character.toChars(cp));
            try {
                latin.getStringWidth(s);
                return latin;
            } catch (IllegalArgumentException | IOException ignored) {
                try {
                    cjk.getStringWidth(s);
                    return cjk;
                } catch (IllegalArgumentException | IOException impossible) {
                    throw new IllegalArgumentException("FIN_PDF_MISSING_GLYPH_U+"
                            + Integer.toHexString(cp), impossible);
                }
            }
        }
        float charWidth(int cp, float size) {
            try {
                return forPoint(cp).getStringWidth(
                        new String(Character.toChars(cp))) * size / 1000f;
            } catch (IOException e) {
                throw new IllegalArgumentException("FIN_PDF_FONT_METRICS", e);
            }
        }
    }

    /** No clipping or ellipsis: return all code points in reading order.
     * Mixed scripts use separate embedded font runs during PDF text drawing. */
    private static List<String> wrap(String input, float width, float fontSize, Fonts fonts) {
        Objects.requireNonNull(input);
        if (input.length() > 25000 || width <= 3)
            throw new IllegalArgumentException("FIN_PDF_CELL_BUDGET");
        var result = new ArrayList<String>();
        var line = new StringBuilder();
        float current = 0f;
        for (int offset = 0; offset < input.length(); ) {
            int cp = input.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == '\n' || cp == '\r') {
                result.add(line.toString());
                line.setLength(0); current = 0;
                continue;
            }
            if (cp == '\t') cp = ' ';
            if (Character.isISOControl(cp) ||
                    (cp >= 0x202A && cp <= 0x202E) ||
                    (cp >= 0x2066 && cp <= 0x2069) ||
                    cp == 0x200E || cp == 0x200F || cp == 0x061C) {
                throw new IllegalArgumentException("FIN_PDF_UNSAFE_CONTROL");
            }
            float charWidth = fonts.charWidth(cp, fontSize);
            if (charWidth > width)
                throw new IllegalArgumentException("FIN_PDF_GLYPH_WIDER_THAN_CELL");
            if (current + charWidth > width && !line.isEmpty()) {
                result.add(line.toString());
                line.setLength(0); current = 0;
            }
            line.appendCodePoint(cp);
            current += charWidth;
            if (result.size() > 128)
                throw new IllegalArgumentException("FIN_PDF_CELL_LINE_BUDGET");
        }
        result.add(line.toString());
        return List.copyOf(result);
    }

    private static final class Pages implements AutoCloseable {
        private final PDDocument doc;
        private final Fonts fonts;
        private final FinancialReportRenderModel.View view;
        private final int start;
        private final int end;
        private PDPageContentStream stream;
        private float y;
        private final float idWidth = 82f;
        private final float fieldWidth;

        Pages(PDDocument doc, Fonts fonts, FinancialReportRenderModel.View view,
              int start, int end) {
            this.doc = doc; this.fonts = fonts; this.view = view;
            this.start = start; this.end = end;
            this.fieldWidth = (PAGE_WIDTH - LEFT - RIGHT - idWidth)
                    / Math.max(1, end - start);
        }

        void next() throws IOException {
            if (stream != null) stream.close();
            if (doc.getNumberOfPages() >= MAX_PAGES)
                throw new IllegalArgumentException("FIN_PDF_PAGE_BUDGET");
            var page = new PDPage(LANDSCAPE);
            doc.addPage(page);
            stream = new PDPageContentStream(doc, page);
            y = PAGE_HEIGHT - 30f;
            var source = view.snapshot();
            text("FIN " + source.formCode() + " | " + VERSION, LEFT, y, 12f);
            y -= 19f;
            text("UNQUALIFIED / " + source.sourceStatus()
                    + " / NOT APPROVED ACTUAL", LEFT, y, 9f);
            y -= 15f;
            passport("Organization", source.organizationId().toString());
            passport("Economic entity", source.economicEntityId().toString());
            passport("Business scope", source.businessScopeId().toString());
            passport("Project / project code", source.projectId()
                    + " / " + source.projectCode());
            passport("Period / basis / coverage", source.periodType() + " "
                    + source.periodCode() + " / " + source.reportingBasis()
                    + " / " + source.coverage());
            passport("Currency / scale / input / display", source.currency() + " / "
                    + source.amountScale() + " / " + source.sourceInputLocale()
                    + " / " + view.displayLocale());
            passport("Source revision", source.sourceRevisionId().toString()
                    + " / " + source.revisionNumber());
            passport("Source document", source.sourceDraftId().toString());
            passport("Content fingerprint V2", source.contentFingerprintV2());
            passport("Generated at UTC", view.generatedAt().toString());
            passport("Translations", view.provisionalLabels() + " PROVISIONAL / "
                    + view.missingLabels() + " MISSING / 0 APPROVED");
            if (source.documentSha256() != null)
                passport("Evidence SHA256", source.documentSha256());
            y -= 10f;
            text(view.evidenceOnly() ? "DOCUMENT REGISTER (no numeric approval)"
                    : "CANONICAL SOURCE VALUES", LEFT, y, 8.5f);
            y -= 16f;
            text("Columns " + (start + 1) + " - " + end + " of "
                    + view.labels().fields().size(), LEFT, y, 8f);
            y -= 17f;
            drawHeader();
            text("Page " + doc.getNumberOfPages() + " / Source revision "
                    + source.sourceRevisionId(), LEFT, 18f, 7f);
        }

        private void passport(String k, String v) throws IOException {
            text(k + ": " + v, LEFT, y, 7.6f);
            y -= 11f;
        }

        private void drawHeader() throws IOException {
            float height = 35f;
            float x = LEFT;
            rect(x, y - height, idWidth, height);
            text("ROW_ID", x + 5, y - 15, FONT_SIZE);
            x += idWidth;
            for (int col = start; col < end; col++) {
                var field = view.labels().fields().get(col);
                rect(x, y - height, fieldWidth, height);
                var heading = wrap(field.label(), fieldWidth - 10f, FONT_SIZE, fonts);
                if (heading.size() > 2)
                    throw new IllegalArgumentException("FIN_PDF_UNPRINTABLE_HEADING");
                for (int i = 0; i < heading.size(); i++)
                    text(heading.get(i), x + 5f, y - 11f - LINE_HEIGHT * i, FONT_SIZE);
                text(field.fieldCode() + " / " + field.status(), x + 5, y - 31, 6.5f);
                x += fieldWidth;
            }
            y -= height;
        }

        void drawRow(List<List<String>> cells, float rowHeight) throws IOException {
            float x = LEFT;
            for (int i = 0; i < cells.size(); i++) {
                float w = i == 0 ? idWidth : fieldWidth;
                rect(x, y - rowHeight, w, rowHeight);
                for (int line = 0; line < cells.get(i).size(); line++) {
                    text(cells.get(i).get(line), x + 5f,
                            y - 11f - LINE_HEIGHT * line, FONT_SIZE);
                }
                x += w;
            }
            y -= rowHeight;
        }

        private void rect(float x, float y, float w, float h) throws IOException {
            stream.setLineWidth(0.5f);
            stream.addRect(x, y, w, h);
            stream.stroke();
        }

        private void text(String source, float x, float y, float size) throws IOException {
            if (source == null || source.isEmpty()) return;
            var characters = source.codePoints().toArray();
            // Use explicit embedded font runs, rather than substituting '??'
            // when an individual glyph is absent in the primary font.
            PDType0Font active = null;
            var run = new StringBuilder();
            float cursor = x;
            for (int cp : characters) {
                PDType0Font selected = fonts.forPoint(cp);
                if (active != selected && !run.isEmpty()) {
                    cursor += flush(run.toString(), active, cursor, y, size);
                    run.setLength(0);
                }
                run.appendCodePoint(cp);
                active = selected;
            }
            if (!run.isEmpty()) flush(run.toString(), active, cursor, y, size);
        }

        private float flush(String run, PDType0Font font, float x,
                            float y, float size) throws IOException {
            stream.beginText();
            stream.setFont(font, size);
            stream.setTextMatrix(Matrix.getTranslateInstance(x, y));
            stream.showText(run);
            stream.endText();
            return font.getStringWidth(run) * size / 1000f;
        }

        @Override public void close() throws IOException {
            if (stream != null) stream.close();
        }
    }

    private static byte[] hash(byte[] source) {
        try { return MessageDigest.getInstance("SHA-256").digest(source); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
