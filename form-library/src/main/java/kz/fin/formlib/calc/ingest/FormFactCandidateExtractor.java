package kz.fin.formlib.calc.ingest;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Read-only, mapping-UNQUALIFIED source projection for 166 form profiles.
 * Does not assign financial metric identity, publish facts, perform sums or grant S2.
 * The saved FIN-LIB-CONTENT-V1 SHA is a CLAIM here; INGEST-2 must recompute it.
 */
@Component
public final class FormFactCandidateExtractor {
  private static final Pattern DECIMAL = Pattern.compile("-?[0-9]{1,26}(\\.[0-9]{1,12})?");
  private static final Pattern INTEGER = Pattern.compile("-?[0-9]{1,26}");
  private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

  public record Scope(
      UUID organizationId, UUID economicEntityId, UUID businessScopeId, UUID projectId,
      String periodType, String periodCode, LocalDate periodStart,
      LocalDate periodEndExclusive, String currencyCode, String coverage,
      String reportingBasis, String dataSpace) {}

  public record Field(String fieldCode, String fieldType, String inputMode, boolean required) {}
  public record Row(UUID rowId, int rowPosition, Map<String,String> values) {}
  public record Evidence(UUID evidenceId, String documentRef, String sha256,
                         String mediaType, long sizeBytes) {}

  public record SourceSnapshot(
      UUID revisionId, UUID instanceId, UUID formVersionId,
      String formCode, String contentMode,
      String claimedCanonicalContentSha256, String sourceSchemaSha256,
      String sourceMappingSha256, Scope scope,
      List<Field> fields, Map<String,String> values,
      List<Row> rows, List<Evidence> evidence) {}

  /**
   * Nullable number only when state=BLANK; never represent BLANK as zero.
   * No metricCode: converting a source field into a financial metric needs S2 mapping.
   */
  public record NumericCandidate(
      UUID rowId, String sourceFieldCode, String fieldType,
      String valueState, BigDecimal numericValue) {}
  public record AttributeCandidate(
      UUID rowId, String sourceFieldCode, String fieldType,
      String valueState, String sourceValue) {}
  public record Projection(
      UUID revisionId, UUID instanceId, UUID formVersionId, String formCode,
      String contentMode, String rowOrderSha256,
      Scope scope, String sourceSha256Claim, String sourceSchemaSha256,
      String sourceMappingSha256, String ingestionRegistrySha256,
      String sourceIntegrityState, String qualificationState,
      List<NumericCandidate> numeric, List<AttributeCandidate> attributes,
      List<Evidence> evidence) {}

  private final FormFactIngestionRegistry registry;

  public FormFactCandidateExtractor() {
    this(FormFactIngestionRegistry.loadDefault());
  }

  public FormFactCandidateExtractor(FormFactIngestionRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
  }

  public Projection extract(SourceSnapshot source) {
    Objects.requireNonNull(source, "SOURCE_SNAPSHOT_REQUIRED");
    requireNonNull(source.revisionId(), "REVISION_ID_REQUIRED");
    requireNonNull(source.instanceId(), "INSTANCE_ID_REQUIRED");
    requireNonNull(source.formVersionId(), "FORM_VERSION_ID_REQUIRED");
    requireSha(source.claimedCanonicalContentSha256(), "SOURCE_SHA_INVALID");
    requireSha(source.sourceSchemaSha256(), "SCHEMA_SHA_INVALID");
    requireSha(source.sourceMappingSha256(), "MAPPING_SHA_INVALID");
    validateScope(source.scope());
    var profile = registry.require(source.formCode(), source.contentMode());
    if (source.fields() == null || source.values() == null
        || source.rows() == null || source.evidence() == null) {
      throw new IllegalArgumentException("SOURCE_COLLECTION_MISSING");
    }

    Map<String,Field> definitions = new HashMap<>();
    for (Field f : source.fields()) {
      if (f == null || f.fieldCode() == null || f.fieldCode().isBlank()
          || f.fieldType() == null || f.fieldType().isBlank()
          || f.inputMode() == null || f.inputMode().isBlank()
          || definitions.putIfAbsent(f.fieldCode(), f) != null) {
        throw new IllegalArgumentException("FIELD_DEFINITION_INVALID_OR_DUPLICATE");
      }
    }

    List<NumericCandidate> numbers = new ArrayList<>();
    List<AttributeCandidate> attributes = new ArrayList<>();
    List<Evidence> evidence = new ArrayList<>();

    switch (profile.mode()) {
      case SINGLE_RECORD -> {
        if (!source.rows().isEmpty() || !source.evidence().isEmpty()) {
          throw new IllegalArgumentException("SINGLE_RECORD_MIXED_SOURCE");
        }
        extractFields(null, source.values(), definitions, false, numbers, attributes);
      }
      case ROWSET -> {
        if (!source.values().isEmpty() || source.rows().isEmpty()
            || source.rows().size() > 200 || !source.evidence().isEmpty()) {
          throw new IllegalArgumentException("ROWSET_SHAPE_INVALID");
        }
        Set<UUID> rowIds = new HashSet<>();
        Set<Integer> positions = new HashSet<>();
        for (Row row : source.rows()) {
          if (row == null || row.rowId() == null || row.rowPosition() < 1
              || !rowIds.add(row.rowId()) || !positions.add(row.rowPosition())
              || row.values() == null) {
            throw new IllegalArgumentException("ROWSET_ROW_IDENTITY_INVALID");
          }
          extractFields(row.rowId(), row.values(), definitions, false, numbers, attributes);
        }
      }
      case EVIDENCE -> {
        if (!source.rows().isEmpty()) {
          throw new IllegalArgumentException("EVIDENCE_ROWSET_FORBIDDEN");
        }
        extractFields(null, source.values(), definitions, true, numbers, attributes);
        Set<UUID> evidenceIds = new HashSet<>();
        for (Evidence e : source.evidence()) {
          if (e == null || e.evidenceId() == null || !evidenceIds.add(e.evidenceId())
              || e.documentRef() == null || e.documentRef().isBlank()
              || e.mediaType() == null || e.mediaType().isBlank()
              || e.sizeBytes() < 0) {
            throw new IllegalArgumentException("EVIDENCE_REFERENCE_INVALID");
          }
          requireSha(e.sha256(), "EVIDENCE_SHA_INVALID");
          evidence.add(e);
        }
      }
    }
    numbers.sort(Comparator.comparing((NumericCandidate n) ->
        n.rowId() == null ? "" : n.rowId().toString()).thenComparing(NumericCandidate::sourceFieldCode));
    attributes.sort(Comparator.comparing((AttributeCandidate n) ->
        n.rowId() == null ? "" : n.rowId().toString()).thenComparing(AttributeCandidate::sourceFieldCode));
    evidence.sort(Comparator.comparing(e -> e.evidenceId().toString()));

    return new Projection(source.revisionId(), source.instanceId(),
        source.formVersionId(), source.formCode(), source.contentMode(), null,
        source.scope(), source.claimedCanonicalContentSha256(), source.sourceSchemaSha256(),
        source.sourceMappingSha256(), registry.manifestSha256(),
        "SOURCE_SHA_NOT_REVERIFIED", "UNQUALIFIED_SOURCE_PROJECTION",
        List.copyOf(numbers), List.copyOf(attributes), List.copyOf(evidence));
  }

  private static void extractFields(UUID rowId, Map<String,String> input,
      Map<String,Field> defs, boolean evidenceOnly,
      List<NumericCandidate> numbers, List<AttributeCandidate> attributes) {
    // Fail closed: unknown/derived/read-only source data cannot enter candidate projection.
    for (var e : input.entrySet()) {
      Field f = defs.get(e.getKey());
      if (f == null) throw new IllegalArgumentException("UNKNOWN_SOURCE_FIELD");
      if (!"MANUAL".equals(f.inputMode())) {
        throw new IllegalArgumentException("DERIVED_OR_READ_ONLY_AS_SOURCE");
      }
      if (e.getValue() == null) throw new IllegalArgumentException("NULL_SOURCE_VALUE");
    }
    for (Field f : defs.values()) {
      if (f.required() && "MANUAL".equals(f.inputMode())
          && (input.get(f.fieldCode()) == null || input.get(f.fieldCode()).isBlank())) {
        throw new IllegalArgumentException("REQUIRED_SOURCE_FIELD_MISSING");
      }
    }
    for (String code : input.keySet().stream().sorted().toList()) {
      Field f = defs.get(code);
      String raw = input.get(code);
      String state = raw.isEmpty() ? "BLANK" : "PRESENT";
      if (!evidenceOnly && Set.of("MONEY","DECIMAL","INTEGER").contains(f.fieldType())) {
        BigDecimal value = raw.isEmpty() ? null : parseCanonicalNumber(f.fieldType(), raw);
        numbers.add(new NumericCandidate(rowId, code, f.fieldType(), state, value));
      } else {
        // EVIDENCE document metadata is not a numeric financial fact, even if
        // its physical field type is numeric. Do not expose as an approved metric.
        validateAttribute(f.fieldType(), raw);
        attributes.add(new AttributeCandidate(rowId, code, f.fieldType(), state, raw));
      }
    }
  }

  private static BigDecimal parseCanonicalNumber(String type, String raw) {
    if (type.equals("INTEGER")) {
      if (!INTEGER.matcher(raw).matches()) throw new IllegalArgumentException("INTEGER_VALUE_INVALID");
      String canonical = new BigInteger(raw).toString();
      if (!canonical.equals(raw)) throw new IllegalArgumentException("NON_CANONICAL_NUMERIC_SOURCE");
      return new BigDecimal(canonical);
    }
    if (!DECIMAL.matcher(raw).matches()) throw new IllegalArgumentException("DECIMAL_VALUE_INVALID");
    BigDecimal value = new BigDecimal(raw);
    if (value.precision() > 38 || value.scale() > 12
        || !raw.equals(value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString())) {
      throw new IllegalArgumentException("NON_CANONICAL_NUMERIC_SOURCE");
    }
    return value;
  }

  private static void validateAttribute(String type, String raw) {
    if (raw.isEmpty()) return;
    if ("DATE".equals(type)) {
      try {
        if (!LocalDate.parse(raw).toString().equals(raw)) throw new IllegalArgumentException("DATE_NOT_CANONICAL");
      } catch (DateTimeParseException ex) {
        throw new IllegalArgumentException("DATE_VALUE_INVALID", ex);
      }
    }
    if ("BOOLEAN".equals(type) && !Set.of("true","false").contains(raw)) {
      throw new IllegalArgumentException("BOOLEAN_VALUE_INVALID");
    }
    if (raw.length() > 1000) throw new IllegalArgumentException("ATTRIBUTE_TOO_LONG");
  }

  private static void validateScope(Scope s) {
    if (s == null || s.organizationId() == null || s.economicEntityId() == null
        || s.businessScopeId() == null || s.projectId() == null
        || s.periodStart() == null || s.periodEndExclusive() == null
        || !s.periodStart().isBefore(s.periodEndExclusive())
        || s.periodType() == null || s.periodCode() == null
        || s.currencyCode() == null || !s.currencyCode().matches("[A-Z]{3}")
        || s.coverage() == null || s.reportingBasis() == null || s.dataSpace() == null) {
      throw new IllegalArgumentException("SOURCE_SCOPE_INVALID");
    }
  }

  private static void requireSha(String sha, String error) {
    if (sha == null || !SHA.matcher(sha).matches()) {
      throw new IllegalArgumentException(error);
    }
  }

  private static void requireNonNull(Object value, String message) {
    if (value == null) throw new IllegalArgumentException(message);
  }
}
