package kz.fin.formlib.calc.fact;

import java.util.UUID;

/** Exact immutable revision, not latest, with verified content and row-order identity. */
public record PinnedSourceMember(
    UUID revisionId, UUID instanceId, UUID formVersionId, String formCode,
    String canonicalContentSha256, String rowOrderSha256,
    String schemaSha256, String mappingSha256, String sourceRole) {

  public PinnedSourceMember {
    if (revisionId == null || instanceId == null || formVersionId == null) {
      throw new IllegalArgumentException("PINNED_SOURCE_IDENTITY_REQUIRED");
    }
    FactDigest.required(formCode, "PINNED_FORM_REQUIRED");
    FactDigest.required(sourceRole, "PINNED_SOURCE_ROLE_REQUIRED");
    FactDigest.requireSha(canonicalContentSha256, "PINNED_CONTENT_SHA_INVALID");
    FactDigest.requireSha(rowOrderSha256, "PINNED_ROW_ORDER_SHA_INVALID");
    FactDigest.requireSha(schemaSha256, "PINNED_SCHEMA_SHA_INVALID");
    FactDigest.requireSha(mappingSha256, "PINNED_MAPPING_SHA_INVALID");
  }

  public String memberDigest() {
    return FactDigest.sha("FIN-FACT-SOURCE-MEMBER-V1",
        revisionId.toString(), instanceId.toString(), formVersionId.toString(),
        formCode, canonicalContentSha256, rowOrderSha256, schemaSha256,
        mappingSha256, sourceRole);
  }
}
