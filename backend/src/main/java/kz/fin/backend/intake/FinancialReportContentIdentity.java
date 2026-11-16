package kz.fin.backend.intake;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Versioned canonical content identity for export consumers.
 *
 * The legacy normalizedFingerprint sorts row IDs; this V2 digest preserves
 * source row order, context, source-input locale and original document digest.
 * It intentionally excludes the revision UUID, XLSX bytes, display locale,
 * rendering time and report format so all renderers see the same identity.
 * This is engineering provenance, not a financial authenticity signature.
 */
public final class FinancialReportContentIdentity {
    public static final String VERSION = "FIN-SNAPSHOT-CONTENT-V2";

    private FinancialReportContentIdentity() {}

    public static String digest(FinancialReportSnapshotService.Snapshot source) {
        Objects.requireNonNull(source, "source");
        try {
            var bytes = new ByteArrayOutputStream();
            var encoded = new DataOutputStream(bytes);
            value(encoded, VERSION);
            value(encoded, source.organizationId().toString());
            value(encoded, source.projectId().toString());
            value(encoded, source.economicEntityId().toString());
            value(encoded, source.businessScopeId().toString());
            value(encoded, source.projectCode());
            value(encoded, source.formCode());
            value(encoded, source.captureMode());
            value(encoded, source.reportingBasis());
            value(encoded, source.periodType().name());
            value(encoded, source.periodCode());
            value(encoded, source.coverage().name());
            value(encoded, source.currency());
            encoded.writeInt(source.amountScale());
            value(encoded, source.sourceInputLocale());
            value(encoded, source.schemaFingerprint());
            // Document identity matters for evidence-only forms even if the
            // seven XLSX register fields are unchanged.
            value(encoded, source.documentSha256());
            encoded.writeInt(source.fields().size());
            for (var field : source.fields()) {
                value(encoded, field.code());
                value(encoded, field.type().name());
            }
            encoded.writeInt(source.rows().size());
            for (var row : source.rows()) {
                value(encoded, row.rowId());
                if (row.cells().size() != source.fields().size())
                    throw new IllegalArgumentException("FIN_CONTENT_V2_FIELD_COUNT_MISMATCH");
                for (var field : source.fields()) {
                    String cell = row.cells().get(field.code());
                    if (cell == null)
                        throw new IllegalArgumentException("FIN_CONTENT_V2_FIELD_MISSING");
                    value(encoded, cell);
                }
            }
            encoded.flush();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("FIN_CONTENT_V2_HASH_FAILED", e);
        }
    }

    private static void value(DataOutputStream output, String raw) throws IOException {
        if (raw == null) {
            output.writeInt(-1);
        } else {
            byte[] utf8 = raw.getBytes(StandardCharsets.UTF_8);
            output.writeInt(utf8.length);
            output.write(utf8);
        }
    }
}
