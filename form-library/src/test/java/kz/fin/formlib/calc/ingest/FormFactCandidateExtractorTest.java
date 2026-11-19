package kz.fin.formlib.calc.ingest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FormFactCandidateExtractorTest {
  private static final String SOURCE_SHA = "a".repeat(64);
  private static final String SCHEMA_SHA = "b".repeat(64);
  private static final String MAPPING_SHA = "c".repeat(64);
  private final FormFactCandidateExtractor extractor = new FormFactCandidateExtractor();

  private static FormFactCandidateExtractor.Scope scope() {
    return new FormFactCandidateExtractor.Scope(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        "M", "2026-10", LocalDate.of(2026,10,1), LocalDate.of(2026,11,1),
        "KZT", "ACTUAL", "IFRS", "PROJECT");
  }
  private static FormFactCandidateExtractor.Field field(
      String code, String type, boolean required) {
    return new FormFactCandidateExtractor.Field(code, type, "MANUAL", required);
  }
  private static FormFactCandidateExtractor.SourceSnapshot snapshot(
      String form, String mode, List<FormFactCandidateExtractor.Field> fields,
      Map<String,String> values, List<FormFactCandidateExtractor.Row> rows,
      List<FormFactCandidateExtractor.Evidence> evidence) {
    return new FormFactCandidateExtractor.SourceSnapshot(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), form, mode,
        SOURCE_SHA, SCHEMA_SHA, MAPPING_SHA, scope(), fields, values, rows, evidence);
  }
  private static List<FormFactCandidateExtractor.Field> f02() {
    return List.of(
        field("netRevenue","MONEY",true),
        field("cogs","MONEY",true),
        field("operatingExpenses","MONEY",true),
        new FormFactCandidateExtractor.Field("netProfit","MONEY","DERIVED",false));
  }

  @Test void manifestClassifiesAll166ExactlyOnce() {
    var registry = FormFactIngestionRegistry.loadDefault();
    assertEquals(166,registry.size());
    assertEquals(3,registry.profiles().values().stream()
        .filter(v->v.mode()==FormFactIngestionRegistry.Mode.SINGLE_RECORD).count());
    assertEquals(151,registry.profiles().values().stream()
        .filter(v->v.mode()==FormFactIngestionRegistry.Mode.ROWSET).count());
    assertEquals(12,registry.profiles().values().stream()
        .filter(v->v.mode()==FormFactIngestionRegistry.Mode.EVIDENCE).count());
    assertEquals(64,registry.manifestSha256().length());
    assertEquals("TEST_ONLY_PILOT_PENDING",registry.require("F02","SINGLE_RECORD").reviewState());
    assertThrows(IllegalArgumentException.class,()->registry.require("F02","ROWSET"));
    assertThrows(IllegalArgumentException.class,()->registry.require("UNKNOWN","ROWSET"));
  }

  @Test void f02GeneratesThreeUnqualifiedTypedInputsWithoutDerivedMetric() {
    var src = snapshot("F02","SINGLE_RECORD",f02(),
        Map.of("netRevenue","12500000", "cogs","7400000", "operatingExpenses","2100000"),
        List.of(),List.of());
    var projection = extractor.extract(src);
    assertEquals("UNQUALIFIED_SOURCE_PROJECTION",projection.qualificationState());
    assertEquals("SOURCE_SHA_NOT_REVERIFIED",projection.sourceIntegrityState());
    assertEquals(3,projection.numeric().size());
    assertTrue(projection.numeric().stream().noneMatch(x->x.sourceFieldCode().equals("netProfit")));
    assertEquals(new BigDecimal("7400000"),projection.numeric().get(0).numericValue());
    assertEquals(src.revisionId(),projection.revisionId());
    assertEquals(src.formVersionId(),projection.formVersionId());
    assertEquals(SOURCE_SHA,projection.sourceSha256Claim());
  }

  @Test void optionalBlankAndAbsentRemainDistinctFromZero() {
    var defs = List.of(field("netRevenue","MONEY",true),field("cogs","MONEY",false));
    var projection = extractor.extract(snapshot("F02","SINGLE_RECORD",defs,
        Map.of("netRevenue","0","cogs",""),List.of(),List.of()));
    assertEquals("BLANK",projection.numeric().get(0).valueState());
    assertNull(projection.numeric().get(0).numericValue());
    assertEquals("PRESENT",projection.numeric().get(1).valueState());
    assertEquals(BigDecimal.ZERO,projection.numeric().get(1).numericValue());
    var withoutCogs=extractor.extract(snapshot("F02","SINGLE_RECORD",defs,
        Map.of("netRevenue","0"),List.of(),List.of()));
    assertEquals(1,withoutCogs.numeric().size());
  }

  @Test void rowsetKeepsRowIdsAndNeverAddsOrGroupsFinancialValues() {
    UUID rowA=UUID.randomUUID(), rowB=UUID.randomUUID();
    var defs=List.of(field("gross","MONEY",true),
        field("overdue","MONEY",false),field("counterparty","REF",true));
    var src=snapshot("C01","ROWSET",defs,Map.of(),
        List.of(new FormFactCandidateExtractor.Row(rowB,2,
                    Map.of("gross","200","counterparty","client-b")),
                new FormFactCandidateExtractor.Row(rowA,1,
                    Map.of("gross","100","overdue","0","counterparty","client-a"))),
        List.of());
    var result=extractor.extract(src);
    assertEquals(3,result.numeric().size());
    assertEquals(2,result.numeric().stream().filter(x->x.sourceFieldCode().equals("gross")).count());
    assertEquals(2,result.attributes().size());
    assertEquals(2,result.numeric().stream().map(x->x.rowId()).distinct().count());
    assertTrue(result.numeric().stream().anyMatch(x->x.numericValue().equals(new BigDecimal("200"))));
    assertEquals("UNQUALIFIED_SOURCE_PROJECTION",result.qualificationState());
  }

  @Test void evidenceOnlyKeepsReferencesAndNeverEmitsNumericFacts() {
    UUID id=UUID.randomUUID();
    var src=snapshot("D01","EVIDENCE",
        List.of(field("documentRef","REF",true),field("amount","MONEY",false)),
        Map.of("documentRef","proof-1","amount","2500"),List.of(),
        List.of(new FormFactCandidateExtractor.Evidence(
            id,"document-ref","d".repeat(64),"application/pdf",1024)));
    var projected=extractor.extract(src);
    assertTrue(projected.numeric().isEmpty());
    assertEquals(2,projected.attributes().size());
    assertEquals(id,projected.evidence().get(0).evidenceId());
  }

  @Test void unknownDerivedAndReadOnlyInputAreRejected() {
    var derived=snapshot("F02","SINGLE_RECORD",f02(),
        Map.of("netRevenue","1","cogs","1","operatingExpenses","1","netProfit","999"),
        List.of(),List.of());
    assertThrows(IllegalArgumentException.class,()->extractor.extract(derived));
    var unknown=snapshot("F02","SINGLE_RECORD",f02(),
        Map.of("netRevenue","1","cogs","1","operatingExpenses","1","unknown","9"),
        List.of(),List.of());
    assertThrows(IllegalArgumentException.class,()->extractor.extract(unknown));
  }

  @Test void malformedDecimalAndSilentNormalizationAreRejected() {
    var defs=List.of(field("netRevenue","MONEY",true));
    for(String invalid:List.of("1,200", "NaN", "1e4", "001", "0.00", "1.2345678901234")) {
      var src=snapshot("F02","SINGLE_RECORD",defs,
          Map.of("netRevenue",invalid),List.of(),List.of());
      assertThrows(IllegalArgumentException.class,()->extractor.extract(src),invalid);
    }
    assertEquals(new BigDecimal("1.25"),extractor.extract(
        snapshot("F02","SINGLE_RECORD",defs,
            Map.of("netRevenue","1.25"),List.of(),List.of()))
        .numeric().get(0).numericValue());
  }

  @Test void wrongModeAndDuplicateRowIdentityAreRejected() {
    var badMode=snapshot("F02","ROWSET",List.of(field("gross","MONEY",true)),
        Map.of(),List.of(new FormFactCandidateExtractor.Row(UUID.randomUUID(),1,
            Map.of("gross","1"))),List.of());
    assertThrows(IllegalArgumentException.class,()->extractor.extract(badMode));
    UUID sameId=UUID.randomUUID();
    var duplicates=snapshot("C01","ROWSET",List.of(field("gross","MONEY",true)),
        Map.of(),List.of(
            new FormFactCandidateExtractor.Row(sameId,1,Map.of("gross","1")),
            new FormFactCandidateExtractor.Row(sameId,2,Map.of("gross","2"))),
        List.of());
    assertThrows(IllegalArgumentException.class,()->extractor.extract(duplicates));
  }

  @Test void missingRequiredAndWrongShaAreRejected() {
    assertThrows(IllegalArgumentException.class,()->extractor.extract(
        snapshot("F02","SINGLE_RECORD",f02(),
            Map.of("netRevenue","1"),List.of(),List.of())));
    var base=snapshot("F02","SINGLE_RECORD",f02(),
        Map.of("netRevenue","1","cogs","1","operatingExpenses","1"),List.of(),List.of());
    var corrupt=new FormFactCandidateExtractor.SourceSnapshot(
        base.revisionId(),base.instanceId(),base.formVersionId(),
        base.formCode(),base.contentMode(),"WRONG-SHA",base.sourceSchemaSha256(),
        base.sourceMappingSha256(),base.scope(),base.fields(),base.values(),base.rows(),base.evidence());
    assertThrows(IllegalArgumentException.class,()->extractor.extract(corrupt));
  }
}
