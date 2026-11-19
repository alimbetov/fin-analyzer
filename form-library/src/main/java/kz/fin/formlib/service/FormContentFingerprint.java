package kz.fin.formlib.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The SINGLE FIN-LIB-CONTENT-V1 / FIN-LIB-ROW-ORDER-V1 encoder used by both
 * FormDraftService.save() and INGEST-2 read-back verification.
 *
 * Historical format, including nested JSON string for attachment metadata,
 * List.toString() for display order, key sorting, and row-id ordering, MUST
 * NOT be changed. A future encoding must use a different contract version.
 */
public final class FormContentFingerprint {
  private static final String CONTENT_PREFIX = "FIN-LIB-CONTENT-V1|";
  private static final String ORDER_PREFIX = "FIN-LIB-ROW-ORDER-V1|";

  public record Context(UUID organizationId, UUID economicEntityId,
      UUID businessScopeId, UUID projectId, String formCode,
      String formVersionLabel, String periodType, String periodCode,
      String coverage, String currency, String reportingBasis, String dataSpace) {}

  public record Row(UUID rowId, int rowPosition, Map<String,String> values) {}
  public record Attachment(String fileName, String mediaType, String sha256) {}
  public record Hashes(String contentSha256, String rowOrderSha256) {}

  private final ObjectMapper mapper;
  public FormContentFingerprint(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "OBJECT_MAPPER_REQUIRED");
  }

  public Hashes compute(Context ctx, Map<String,String> normalized,
      List<Row> rows, Attachment attachment) {
    Objects.requireNonNull(ctx, "SOURCE_CONTEXT_REQUIRED");
    Objects.requireNonNull(normalized, "SOURCE_VALUES_REQUIRED");
    Objects.requireNonNull(rows, "SOURCE_ROWS_REQUIRED");
    if (rows.stream().anyMatch(r -> r == null || r.rowId() == null
        || r.rowPosition() <= 0 || r.values() == null))
      throw new IllegalArgumentException("SOURCE_ROW_INVALID");
    if (normalized.containsValue(null)
        || rows.stream().anyMatch(r -> r.values().containsValue(null)))
      throw new IllegalArgumentException("SOURCE_NULL_VALUE");

    // The former FormDraftService JSON List used these 12 context fields,
    // a sorted String map and a row-id-sorted list of row-id+value pairs.
    String content = json(List.of(
        ctx.organizationId().toString(), ctx.economicEntityId().toString(),
        ctx.businessScopeId().toString(), ctx.projectId().toString(),
        ctx.formCode(), ctx.formVersionLabel(), ctx.periodType(), ctx.periodCode(),
        ctx.coverage(), ctx.currency(), ctx.reportingBasis(), ctx.dataSpace(),
        new TreeMap<>(normalized),
        rows.stream().sorted(Comparator.comparing(Row::rowId))
            .map(row -> List.of(row.rowId().toString(), new TreeMap<>(row.values())))
            .toList()));
    if (attachment != null) {
      // Historical encoding deliberately hashes JSON containing a *JSON
      // string*, NOT a flattened object. Keep it byte-for-byte compatible.
      content = json(List.of(content, attachment.fileName(),
          attachment.mediaType(), attachment.sha256()));
    }
    String order = rows.stream().sorted(Comparator.comparingInt(Row::rowPosition)
        .thenComparing(Row::rowId))
        .map(row -> row.rowPosition()+":"+row.rowId()).toList().toString();
    return new Hashes(sha256(CONTENT_PREFIX + content),
        sha256(ORDER_PREFIX + order));
  }

  private String json(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (JsonProcessingException ex) {
      throw new IllegalStateException("FIN_CONTENT_SERIALIZATION_FAILED", ex);
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA256_UNAVAILABLE", ex);
    }
  }
}
