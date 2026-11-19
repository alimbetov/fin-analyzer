package kz.fin.formlib.calc.fact;

import java.util.List;
import java.util.UUID;

/** Pinned test-only F02 policy; creation is NOT financial approval. */
public record F02MappingDescriptor(
    UUID formVersionId, String schemaSha256, String sourceMappingSha256,
    String descriptorVersion, String reviewStatus, String metricContractVersion,
    List<FieldBinding> fields, String policySha256, String mappingBundleSha256,
    String descriptorSha256) {

  public record FieldBinding(String sourceFieldCode, String metricCode,
      String fieldType, String valueState, String periodSemantics, int maxScale) {
    public FieldBinding {
      FactDigest.required(sourceFieldCode, "F02_FIELD_REQUIRED");
      FactDigest.required(metricCode, "F02_METRIC_REQUIRED");
      if (!"MONEY".equals(fieldType) || !"PRESENT".equals(valueState)
          || !"INTERVAL".equals(periodSemantics) || maxScale != 12) {
        throw new IllegalArgumentException("F02_FIELD_POLICY_INVALID");
      }
    }
    String canonical() {
      return FactDigest.sha("FIN-FACT-F02-BINDING-V1",
          sourceFieldCode, metricCode, fieldType, valueState,
          periodSemantics, Integer.toString(maxScale));
    }
  }

  public F02MappingDescriptor {
    if (formVersionId == null || !"F02_MAPPING_V1".equals(descriptorVersion)
        || !"TEST_ONLY_PILOT".equals(reviewStatus)
        || !"TEST_ONLY_F02_V1".equals(metricContractVersion)) {
      throw new IllegalArgumentException("F02_DESCRIPTOR_NOT_PINNED");
    }
    FactDigest.requireSha(schemaSha256, "F02_SCHEMA_SHA_INVALID");
    FactDigest.requireSha(sourceMappingSha256, "F02_SOURCE_MAPPING_SHA_INVALID");
    FactDigest.requireSha(policySha256, "F02_POLICY_SHA_INVALID");
    FactDigest.requireSha(mappingBundleSha256, "F02_MAPPING_BUNDLE_SHA_INVALID");
    FactDigest.requireSha(descriptorSha256, "F02_DESCRIPTOR_SHA_INVALID");
    if (fields == null) throw new IllegalArgumentException("F02_FIELDS_REQUIRED");
    fields = List.copyOf(fields);
    if (!fields.equals(approvedPilotFields())) {
      throw new IllegalArgumentException("F02_MAPPING_FIELDS_MISMATCH");
    }
    String expectedPolicy = policyDigest(fields);
    String expectedBundle = FactDigest.sha("FIN-FACT-F02-MAPPING-BUNDLE-V1",
        sourceMappingSha256, expectedPolicy);
    String expectedDescriptor = FactDigest.sha("FIN-FACT-F02-DESCRIPTOR-V1",
        formVersionId.toString(), schemaSha256, sourceMappingSha256,
        descriptorVersion, reviewStatus, metricContractVersion,
        expectedPolicy, expectedBundle);
    if (!expectedPolicy.equals(policySha256)
        || !expectedBundle.equals(mappingBundleSha256)
        || !expectedDescriptor.equals(descriptorSha256)) {
      throw new IllegalArgumentException("F02_DESCRIPTOR_SHA_MISMATCH");
    }
  }

  private static List<FieldBinding> approvedPilotFields() {
    return List.of(
        new FieldBinding("cogs","F02.cogs","MONEY","PRESENT","INTERVAL",12),
        new FieldBinding("netRevenue","F02.netRevenue","MONEY","PRESENT","INTERVAL",12),
        new FieldBinding("operatingExpenses","F02.operatingExpenses",
            "MONEY","PRESENT","INTERVAL",12));
  }

  private static String policyDigest(List<FieldBinding> fields) {
    return FactDigest.sha("FIN-FACT-F02-POLICY-V1",
        fields.stream().map(FieldBinding::canonical).toArray(String[]::new));
  }

  /**
   * Requires a pre-pinned formVersion/schema/mapping triple supplied by a reviewer.
   * No source-driven auto-selection and no trusted issuer are provided here.
   */
  public static F02MappingDescriptor testOnlyPilot(
      UUID formVersionId, String schemaSha256, String sourceMappingSha256) {
    if (formVersionId == null) throw new IllegalArgumentException("F02_VERSION_REQUIRED");
    var fields = approvedPilotFields();
    String policy = policyDigest(fields);
    String bundle = FactDigest.sha("FIN-FACT-F02-MAPPING-BUNDLE-V1",
        sourceMappingSha256, policy);
    String descriptor = FactDigest.sha("FIN-FACT-F02-DESCRIPTOR-V1",
        formVersionId.toString(), schemaSha256, sourceMappingSha256,
        "F02_MAPPING_V1", "TEST_ONLY_PILOT", "TEST_ONLY_F02_V1",
        policy, bundle);
    return new F02MappingDescriptor(formVersionId, schemaSha256, sourceMappingSha256,
        "F02_MAPPING_V1", "TEST_ONLY_PILOT", "TEST_ONLY_F02_V1",
        fields, policy, bundle, descriptor);
  }
}
