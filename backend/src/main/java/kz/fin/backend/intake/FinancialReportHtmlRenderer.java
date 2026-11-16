package kz.fin.backend.intake;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * X3: offline, self-contained, passive HTML from an already-selected snapshot.
 * Never queries the database or evaluates HTML, script, URL, Markdown or CSS
 * supplied by the uploaded workbook. No client-side framework is required.
 */
@Service
public final class FinancialReportHtmlRenderer {
    public static final String VERSION = "FIN-BE-R1-X3-HTML-V1";
    public static final int MAX_HTML_BYTES = 4_000_000;
    public static final String STYLE = """
        :root{color-scheme:light;font-family:system-ui,'Arial Unicode MS',sans-serif}
        body{margin:0 auto;padding:24px;max-width:1400px;color:#1b2532;background:#fff}
        h1{font-size:1.45rem;margin:0 0 12px}
        h2{font-size:1.1rem;margin:24px 0 10px}
        .warning{border:2px solid #8a3b10;background:#fff6ec;padding:12px;
          font-weight:700}
        .passport{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));
          gap:8px 20px;margin:16px 0}
        .passport div{overflow-wrap:anywhere}
        .passport dt{font-size:.77rem;color:#465769}
        .passport dd{margin:0;font-weight:600}
        .table-wrap{overflow-x:auto;max-width:100%}
        table{border-collapse:collapse;width:max-content;min-width:100%;font-size:.85rem}
        th,td{border:1px solid #a6b3c1;padding:7px 9px;vertical-align:top;
          text-align:left;white-space:pre-wrap;overflow-wrap:anywhere}
        th{background:#eef2f7;position:sticky;top:0}
        td.value{font-variant-numeric:tabular-nums}
        .secondary{font-size:.76rem;color:#4d5f73}
        .blank{color:#657488}
        .foot{border-top:1px solid #a6b3c1;padding-top:10px;margin-top:20px;font-size:.8rem}
        @media print{@page{size:landscape;margin:12mm}body{padding:0}
          .table-wrap{overflow:visible}table{width:100%;min-width:0}
          thead{display:table-header-group}tr{break-inside:avoid}
          th{position:static}td,th{overflow-wrap:anywhere}}
        """;
    public static final String CSP = "default-src 'none'; base-uri 'none'; "
            + "script-src 'none'; connect-src 'none'; img-src 'none'; font-src 'none'; "
            + "object-src 'none'; form-action 'none'; frame-ancestors 'none'; "
            + "style-src 'sha256-" + Base64.getEncoder().encodeToString(
                    digest(STYLE.getBytes(StandardCharsets.UTF_8))) + "'";

    private final FinancialReportRenderModel model;

    public FinancialReportHtmlRenderer(FinancialReportRenderModel model) {
        this.model = Objects.requireNonNull(model);
    }

    public record Rendered(UUID sourceRevisionId, String displayLocale,
                           String html, String sha256,
                           String contentFingerprintV2) {
        public Rendered { Objects.requireNonNull(html); }
        public byte[] utf8() { return html.getBytes(StandardCharsets.UTF_8); }
    }

    public Rendered render(FinancialReportSnapshotService.Snapshot snapshot,
                           String displayLocale, Instant generatedAt) {
        return render(model.prepare(snapshot, displayLocale, generatedAt));
    }

    public Rendered render(FinancialReportRenderModel.View view) {
        Objects.requireNonNull(view, "view");
        var source = view.snapshot();
        var html = new StringBuilder(12000);
        html.append("<!doctype html><html lang=\"").append(escape(view.displayLocale()))
                .append("\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta name=\"referrer\" content=\"no-referrer\">")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"")
                .append(escape(CSP.replace("frame-ancestors 'none'; ", ""))).append("\">")
                .append("<title>FIN ").append(escape(source.formCode())).append("</title>")
                .append("<style>").append(STYLE).append("</style></head><body>");
        html.append("<main><h1>FIN ").append(escape(source.formCode()))
                .append(" — ").append(escape(VERSION)).append("</h1>")
                .append("<div class=\"warning\">UNQUALIFIED / ")
                .append(escape(source.sourceStatus()))
                .append(" — NOT APPROVED FINANCIAL ACTUAL</div>");
        html.append("<dl class=\"passport\">");
        passport(html, "Organization", source.organizationId().toString());
        passport(html, "Economic entity", source.economicEntityId().toString());
        passport(html, "Business scope", source.businessScopeId().toString());
        passport(html, "Project", source.projectId().toString());
        passport(html, "Project code", source.projectCode());
        passport(html, "Form / title status",
                source.formCode() + " / " + view.labels().formTitle().status());
        passport(html, "Period", source.periodType() + " " + source.periodCode());
        passport(html, "Coverage", source.coverage().name());
        passport(html, "Reporting basis", source.reportingBasis());
        passport(html, "Currency / amount scale", source.currency() + " / " + source.amountScale());
        passport(html, "Source revision", source.sourceRevisionId().toString());
        passport(html, "Revision number", Integer.toString(source.revisionNumber()));
        passport(html, "Source draft", source.sourceDraftId().toString());
        passport(html, "Original XLSX SHA-256", source.originalXlsxSha256());
        if (source.documentSha256() != null)
            passport(html, "Original document SHA-256", source.documentSha256());
        passport(html, "Schema fingerprint", source.schemaFingerprint());
        passport(html, "Normalized fingerprint", source.normalizedFingerprint());
        passport(html, "Content fingerprint V2", source.contentFingerprintV2());
        passport(html, "Source input locale", source.sourceInputLocale());
        passport(html, "Display locale", view.displayLocale());
        passport(html, "Generated at UTC", view.generatedAt().toString());
        passport(html, "Human translations",
                view.provisionalLabels() + " PROVISIONAL; " + view.missingLabels()
                        + " MISSING; 0 APPROVED");
        html.append("</dl><h2>")
                .append(view.evidenceOnly() ? "Document evidence register (not numeric facts)"
                        : "Source financial data (canonical strings)")
                .append("</h2><div class=\"table-wrap\"><table><thead><tr><th scope=\"col\">ROW_ID</th>");
        for (var f : view.labels().fields()) {
            html.append("<th scope=\"col\">").append(escape(f.label()))
                    .append("<div class=\"secondary\">").append(escape(f.fieldCode()))
                    .append(" / ").append(escape(f.status().name()))
                    .append(" / ").append(escape(f.unitContext()))
                    .append("</div></th>");
        }
        html.append("</tr></thead><tbody>");
        for (var row : source.rows()) {
            html.append("<tr><th scope=\"row\">").append(escape(row.rowId())).append("</th>");
            for (var f : view.labels().fields()) {
                String raw = row.cells().get(f.fieldCode());
                if (raw == null)
                    throw new IllegalArgumentException("FIN_HTML_FIELD_ABSENT_FROM_SNAPSHOT");
                html.append("<td class=\"value\" data-field=\"")
                        .append(escape(f.fieldCode())).append("\">");
                if (raw.isEmpty()) {
                    html.append("<span class=\"blank\" data-empty=\"true\"></span>");
                } else {
                    html.append(escape(raw));
                }
                html.append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table></div><p class=\"foot\">")
                .append("Source revision is fixed. Missing translations fall back to machine field codes. ")
                .append("Evidence originals are not embedded. No source approval, recalculation or FX.")
                .append("</p></main></body></html>");
        byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_HTML_BYTES) throw new IllegalArgumentException("FIN_HTML_SIZE_BUDGET");
        return new Rendered(source.sourceRevisionId(), view.displayLocale(),
                html.toString(), HexFormat.of().formatHex(digest(bytes)),
                source.contentFingerprintV2());
    }

    private static void passport(StringBuilder out, String label, String value) {
        out.append("<div><dt>").append(escape(label)).append("</dt><dd>")
                .append(escape(value)).append("</dd></div>");
    }

    /** Context-sensitive for HTML text and quoted attributes; rejects spoofing
     * controls rather than rendering invisible bidi overrides into a report. */
    public static String escape(String input) {
        Objects.requireNonNull(input, "text");
        if (input.length() > 25000) throw new IllegalArgumentException("FIN_HTML_CELL_BUDGET");
        var out = new StringBuilder(input.length() + 20);
        for (int i = 0; i < input.length(); ) {
            int cp = input.codePointAt(i);
            if ((cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069)
                    || cp == 0x200E || cp == 0x200F || cp == 0x061C
                    || (cp < 32 && cp != '\n' && cp != '\r' && cp != '\t')
                    || cp == 127 || cp == 0xFFFE || cp == 0xFFFF
                    || Character.isSurrogate(input.charAt(i)) && cp < 0x10000)
                throw new IllegalArgumentException("FIN_HTML_UNSAFE_UNICODE");
            switch (cp) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    private static byte[] digest(byte[] source) {
        try { return MessageDigest.getInstance("SHA-256").digest(source); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
