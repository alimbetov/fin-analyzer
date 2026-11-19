package kz.fin.formlib.calc.fact;

import kz.fin.formlib.calc.ingest.FormFactCandidateExtractor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FACT-1.A1 pure-Java negative matrix. Fixtures are synthetic, never financial
 * qualification or a substitute for source SHA rehash in a PostgreSQL TX-Q.
 */
class F02CandidateMappingTest {
  private static final UUID ORG = id(1);
  private static final UUID ENTITY = id(2);
  private static final UUID BUSINESS = id(3);
  private static final UUID PROJECT = id(4);
  private static final UUID REVISION = id(5);
  private static final UUID INSTANCE = id(6);
  private static final UUID FORM_VERSION = id(7);
  private static final String SOURCE_SHA = "a".repeat(64);
  private static final String SCHEMA_SHA = "b".repeat(64);
  private static final String MAPPING_SHA = "c".repeat(64);
  private static final String ROW_ORDER_SHA = "d".repeat(64);

  private static UUID id(long n) {
    return new UUID(0, n);
  }

  private static FormFactCandidateExtractor.Scope scope() {
    return new FormFactCandidateExtractor.Scope(ORG, ENTITY, BUSINESS, PROJECT,
        "M", "2026-11", LocalDate.of(2026, 11, 1),
        LocalDate.of(2026, 12, 1), "KZT", "PERIOD_ONLY",
        "MANAGEMENT", "SYNTHETIC");
  }

  private static List<FormFactCandidateExtractor.Field> fields() {
    return List.of(
        new FormFactCandidateExtractor.Field("netRevenue","MONEY","MANUAL",true),
        new FormFactCandidateExtractor.Field("cogs","MONEY","MANUAL",true),
        new FormFactCandidateExtractor.Field("operatingExpenses","MONEY","MANUAL",true),
        new FormFactCandidateExtractor.Field("netProfit","MONEY","DERIVED",false));
  }

  private static Map<String, String> values() {
    return Map.of("netRevenue","12500000","cogs","7400000",
        "operatingExpenses","2100000");
  }

  private static FormFactCandidateExtractor.SourceSnapshot snapshot(
      Map<String,String> values, String mode) {
    return new FormFactCandidateExtractor.SourceSnapshot(
        REVISION,INSTANCE,FORM_VERSION,"F02",mode,
        SOURCE_SHA,SCHEMA_SHA,MAPPING_SHA,scope(),fields(),values,
        List.of(),List.of());
  }

  /** Local fixture marks previously extracted source reverified; no actual issuer. */
  private static FormFactCandidateExtractor.Projection verified() {
    return markVerified(new FormFactCandidateExtractor().extract(
        snapshot(values(),"SINGLE_RECORD")));
  }

  private static FormFactCandidateExtractor.Projection markVerified(
      FormFactCandidateExtractor.Projection p) {
    return new FormFactCandidateExtractor.Projection(
        p.revisionId(),p.instanceId(),p.formVersionId(),p.formCode(),
        p.contentMode(),ROW_ORDER_SHA,p.scope(),p.sourceSha256Claim(),
        p.sourceSchemaSha256(),p.sourceMappingSha256(),
        p.ingestionRegistrySha256(),"SOURCE_SHA_REVERIFIED",
        "UNQUALIFIED_SOURCE_PROJECTION",
        p.numeric(),p.attributes(),p.evidence());
  }

  private static FormFactCandidateExtractor.Projection change(
      FormFactCandidateExtractor.Projection p,
      FormFactCandidateExtractor.Scope scope,
      String mode, String rowSha, String sourceSha, String schemaSha,
      String mappingSha, UUID version,
      List<FormFactCandidateExtractor.NumericCandidate> numbers) {
    return new FormFactCandidateExtractor.Projection(
        p.revisionId(),p.instanceId(),version,p.formCode(),
        mode,rowSha,scope,sourceSha,schemaSha,mappingSha,
        p.ingestionRegistrySha256(),p.sourceIntegrityState(),
        p.qualificationState(),numbers,p.attributes(),p.evidence());
  }

  private static F02MappingDescriptor descriptor() {
    return F02MappingDescriptor.testOnlyPilot(
        FORM_VERSION,SCHEMA_SHA,MAPPING_SHA);
  }

  private static CandidateFactSet assemble(
      FormFactCandidateExtractor.Projection p) {
    return new DefaultF02SourceFactAssembler().assemble(p,descriptor());
  }

  @Test void qf01GoldenVectorIncludesExactScopeSourceAndThreeSortedMetrics() {
    var result=assemble(verified());
    assertEquals("91e47effa4b1a0a2d8ef735a9ff2c5e4259513456fec8a8f167bf002a5a9c267",
        result.scope().stableScopeKey());
    assertEquals("72cbe4ac7124e8aa2434433881d2226270f11808504d3745f79030d7e110ecf3",
        result.sourceSetSha256());
    assertEquals("f4b4533a05367e497a1ad70d003c38b80a389802156fe4ae37db25720f5beadb",
        result.candidateFactsetSha256());
    assertEquals(List.of("F02.cogs","F02.netRevenue","F02.operatingExpenses"),
        result.observations().stream().map(TypedObservation::metricCode).toList());
    assertEquals(INSTANCE,result.sources().getFirst().instanceId());
    assertEquals(ROW_ORDER_SHA,result.sources().getFirst().rowOrderSha256());
    assertEquals(1,result.canonicalizationVersion());
    assertEquals(3,result.observations().size());
    assertThrows(UnsupportedOperationException.class,()->result.sources().clear());
    assertThrows(UnsupportedOperationException.class,()->result.observations().clear());
  }

  @Test void goldenDescriptorHasPinnedIndependentPolicyAndBundleDigest() {
    var d=descriptor();
    assertEquals("fec018b662ec16b0519c582951c4e339d912ed42b35af86bf5fed29f3cbbde70",
        d.policySha256());
    assertEquals("be2294309d7f56b0bd7eaf298e9cfe1c6124bf1cecd395ca4c4313a1a903dc6d",
        d.mappingBundleSha256());
    assertEquals("39221679bd7f4edc66b498040404de6c30102b41b85eb773d19dd71902c474a1",
        d.descriptorSha256());
    assertEquals("TEST_ONLY_PILOT",d.reviewStatus());
    assertNotEquals(d.descriptorSha256(),F02MappingDescriptor.testOnlyPilot(
        id(9),SCHEMA_SHA,MAPPING_SHA).descriptorSha256());
  }

  @Test void sourceOrderAndMapIterationCannotChangeCandidateIdentity() {
    var a=verified();
    var reversed=new java.util.ArrayList<>(a.numeric());
    java.util.Collections.reverse(reversed);
    assertEquals(assemble(a).candidateFactsetSha256(),
        assemble(change(a,a.scope(),"SINGLE_RECORD",ROW_ORDER_SHA,
            SOURCE_SHA,SCHEMA_SHA,MAPPING_SHA,FORM_VERSION,reversed))
            .candidateFactsetSha256());
  }

  @Test void qf02ZeroIsPresentButBlankOrMissingCannotBeMapped() {
    var extractor=new FormFactCandidateExtractor();
    var zero=extractor.extract(snapshot(Map.of(
        "netRevenue","0","cogs","0","operatingExpenses","0"),"SINGLE_RECORD"));
    var candidate=assemble(markVerified(zero));
    assertEquals(3,candidate.observations().size());
    assertTrue(candidate.observations().stream()
        .allMatch(o->o.value().compareTo(BigDecimal.ZERO)==0));
    for (var bad:List.of(
        Map.of("netRevenue","0","cogs","","operatingExpenses","0"),
        Map.of("netRevenue","0","operatingExpenses","0"))) {
      assertThrows(IllegalArgumentException.class,
          ()->extractor.extract(snapshot(bad,"SINGLE_RECORD")));
    }
  }

  @Test void qf03DerivedOrFourthUnknownInputIsRejected() {
    var extractor=new FormFactCandidateExtractor();
    for (var extra:List.of("netProfit","unreviewedField")) {
      var input=new java.util.HashMap<>(values());
      input.put(extra,"1");
      assertThrows(IllegalArgumentException.class,
          ()->extractor.extract(snapshot(input,"SINGLE_RECORD")));
    }
  }

  @Test void qf04InvalidCanonicalNumericSignOverflowOrExponentRejected() {
    var extractor=new FormFactCandidateExtractor();
    for (var invalid:List.of("-1","1e4","123456789012345678901234567890",
        "0.00","01","1.1234567890123")) {
      var input=new java.util.HashMap<>(values());
      input.put("cogs",invalid);
      if ("-1".equals(invalid)) {
        // Generic source extractor admits negative MONEY; F02 pilot policy does not.
        assertThrows(IllegalArgumentException.class,
            ()->assemble(markVerified(extractor.extract(
                snapshot(input,"SINGLE_RECORD")))));
      } else {
        assertThrows(IllegalArgumentException.class,
            ()->extractor.extract(snapshot(input,"SINGLE_RECORD")));
      }
    }
    var p=verified();
    var modified=new java.util.ArrayList<>(p.numeric());
    modified.set(0,new FormFactCandidateExtractor.NumericCandidate(
        null,"cogs","MONEY","PRESENT",new BigDecimal("1e4")));
    assertThrows(IllegalArgumentException.class,()->assemble(change(p,p.scope(),
        p.contentMode(),ROW_ORDER_SHA,SOURCE_SHA,SCHEMA_SHA,MAPPING_SHA,FORM_VERSION,
        modified)));
  }

  @Test void qf05WrongModeAndUnverifiedSourceCannotEnterAssembler() {
    var p=verified();
    assertThrows(IllegalArgumentException.class,()->assemble(change(p,p.scope(),
        "ROWSET",ROW_ORDER_SHA,SOURCE_SHA,SCHEMA_SHA,MAPPING_SHA,FORM_VERSION,
        p.numeric())));
    var unverified=new FormFactCandidateExtractor().extract(
        snapshot(values(),"SINGLE_RECORD"));
    assertThrows(IllegalArgumentException.class,()->assemble(unverified));
    assertThrows(IllegalArgumentException.class,()->assemble(change(p,p.scope(),
        "EVIDENCE",ROW_ORDER_SHA,SOURCE_SHA,SCHEMA_SHA,MAPPING_SHA,FORM_VERSION,
        p.numeric())));
  }

  @Test void qf06PinnedVersionSchemaAndMappingMustMatchExactly() {
    var p=verified();
    for (var altered:List.of(
        change(p,p.scope(),"SINGLE_RECORD",ROW_ORDER_SHA,SOURCE_SHA,
            "e".repeat(64),MAPPING_SHA,FORM_VERSION,p.numeric()),
        change(p,p.scope(),"SINGLE_RECORD",ROW_ORDER_SHA,SOURCE_SHA,
            SCHEMA_SHA,"e".repeat(64),FORM_VERSION,p.numeric()),
        change(p,p.scope(),"SINGLE_RECORD",ROW_ORDER_SHA,SOURCE_SHA,
            SCHEMA_SHA,MAPPING_SHA,id(9),p.numeric()))) {
      assertThrows(IllegalArgumentException.class,()->assemble(altered));
    }
    assertThrows(IllegalArgumentException.class,()->new F02MappingDescriptor(
        descriptor().formVersionId(),SCHEMA_SHA,MAPPING_SHA,
        descriptor().descriptorVersion(),descriptor().reviewStatus(),
        descriptor().metricContractVersion(),descriptor().fields(),
        descriptor().policySha256(),descriptor().mappingBundleSha256(),
        "f".repeat(64)));
  }

  @Test void qf07FullScopeAndPeriodAreBoundToDigestAndPilotAllowlist() {
    var p=verified();
    var s=p.scope();
    var differentOrg=new FormFactCandidateExtractor.Scope(id(90),
        s.economicEntityId(),s.businessScopeId(),s.projectId(),s.periodType(),
        s.periodCode(),s.periodStart(),s.periodEndExclusive(),s.currencyCode(),
        s.coverage(),s.reportingBasis(),s.dataSpace());
    assertNotEquals(assemble(p).candidateFactsetSha256(),
        assemble(change(p,differentOrg,"SINGLE_RECORD",ROW_ORDER_SHA,
            SOURCE_SHA,SCHEMA_SHA,MAPPING_SHA,FORM_VERSION,p.numeric()))
            .candidateFactsetSha256());
    var currency=new FormFactCandidateExtractor.Scope(
        s.organizationId(),s.economicEntityId(),s.businessScopeId(),s.projectId(),
        s.periodType(),s.periodCode(),s.periodStart(),s.periodEndExclusive(),
        "USD",s.coverage(),s.reportingBasis(),s.dataSpace());
    assertThrows(IllegalArgumentException.class,()->assemble(change(p,currency,
        "SINGLE_RECORD",ROW_ORDER_SHA,SOURCE_SHA,SCHEMA_SHA,MAPPING_SHA,
        FORM_VERSION,p.numeric())));
    var falsePeriod=new FormFactCandidateExtractor.Scope(
        s.organizationId(),s.economicEntityId(),s.businessScopeId(),s.projectId(),
        s.periodType(),s.periodCode(),s.periodStart(),s.periodEndExclusive().plusDays(1),
        s.currencyCode(),s.coverage(),s.reportingBasis(),s.dataSpace());
    assertThrows(IllegalArgumentException.class,()->assemble(change(p,falsePeriod,
        "SINGLE_RECORD",ROW_ORDER_SHA,SOURCE_SHA,SCHEMA_SHA,MAPPING_SHA,
        FORM_VERSION,p.numeric())));
  }

  @Test void sourceShaAndRowOrderArePartOfCandidateSourceIdentity() {
    var p=verified();
    var original=assemble(p);
    assertNotEquals(original.sourceSetSha256(),assemble(change(p,p.scope(),
        "SINGLE_RECORD","e".repeat(64),SOURCE_SHA,SCHEMA_SHA,MAPPING_SHA,
        FORM_VERSION,p.numeric())).sourceSetSha256());
    assertNotEquals(original.candidateFactsetSha256(),assemble(change(p,p.scope(),
        "SINGLE_RECORD",ROW_ORDER_SHA,"e".repeat(64),SCHEMA_SHA,MAPPING_SHA,
        FORM_VERSION,p.numeric())).candidateFactsetSha256());
    assertThrows(IllegalArgumentException.class,()->assemble(change(p,p.scope(),
        "SINGLE_RECORD","BAD",SOURCE_SHA,SCHEMA_SHA,MAPPING_SHA,
        FORM_VERSION,p.numeric())));
  }

  @Test void changingOneValueChangesOnlyValueIdentityNotObservationKey() {
    var p=verified();
    var changed=new java.util.ArrayList<>(p.numeric());
    int index=java.util.stream.IntStream.range(0,changed.size())
        .filter(i->changed.get(i).sourceFieldCode().equals("cogs"))
        .findFirst().orElseThrow();
    changed.set(index,new FormFactCandidateExtractor.NumericCandidate(
        null,"cogs","MONEY","PRESENT",new BigDecimal("7400001")));
    var result=assemble(change(p,p.scope(),"SINGLE_RECORD",ROW_ORDER_SHA,
        SOURCE_SHA,SCHEMA_SHA,MAPPING_SHA,FORM_VERSION,changed));
    var original=assemble(p);
    assertNotEquals(original.candidateFactsetSha256(),result.candidateFactsetSha256());
    assertEquals(original.observations().getFirst().observationKeySha256(),
        result.observations().getFirst().observationKeySha256());
    assertNotEquals(original.observations().getFirst().valueSha256(),
        result.observations().getFirst().valueSha256());
  }

  @Test void digestLengthDelimitersAndDomainSeparationPreventAmbiguity() {
    assertNotEquals(FactDigest.sha("FIN-FACT-SCOPE-V1","ab","c"),
        FactDigest.sha("FIN-FACT-SCOPE-V1","a","bc"));
    assertNotEquals(FactDigest.sha("FIN-FACT-SCOPE-V1","a"),
        FactDigest.sha("FIN-FACT-CANDIDATE-V1","a"));
    assertThrows(IllegalArgumentException.class,
        ()->FactDigest.sha("FIN-FACT-SCOPE-V1",(String)null));
  }
}
