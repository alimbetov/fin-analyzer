package kz.fin.formlib.calc.fact;

import java.util.HashSet;
import java.util.List;

/** Unqualified deterministic candidate. Its SHA proves identity, NEVER S2 approval. */
public record CandidateFactSet(
    FactScope scope, List<PinnedSourceMember> sources,
    List<TypedObservation> observations, String mappingBundleSha256,
    String descriptorSha256, String sourceSetSha256,
    String candidateFactsetSha256, int canonicalizationVersion) {

  public CandidateFactSet {
    if (scope == null || sources == null || observations == null
        || sources.isEmpty() || observations.isEmpty() || canonicalizationVersion != 1) {
      throw new IllegalArgumentException("CANDIDATE_FACTSET_INVALID");
    }
    sources = List.copyOf(sources);
    observations = List.copyOf(observations);
    if (new HashSet<>(sources.stream().map(PinnedSourceMember::revisionId).toList()).size()
        != sources.size()
        || new HashSet<>(observations.stream()
            .map(TypedObservation::observationKeySha256).toList()).size()
            != observations.size()) {
      throw new IllegalArgumentException("CANDIDATE_FACTSET_DUPLICATE");
    }
    FactDigest.requireSha(mappingBundleSha256, "MAPPING_BUNDLE_SHA_INVALID");
    FactDigest.requireSha(descriptorSha256, "DESCRIPTOR_SHA_INVALID");
    FactDigest.requireSha(sourceSetSha256, "SOURCE_SET_SHA_INVALID");
    FactDigest.requireSha(candidateFactsetSha256, "CANDIDATE_SHA_INVALID");
  }
}
