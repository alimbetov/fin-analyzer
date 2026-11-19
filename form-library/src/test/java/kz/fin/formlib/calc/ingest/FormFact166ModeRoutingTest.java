package kz.fin.formlib.calc.ingest;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Synthetic ROUTING qualification only. Does not prove per-form financial
 * mappings, real production JSON samples, qualified metrics or fin_fact writes.
 */
class FormFact166ModeRoutingTest {
  private static final String SHA="d".repeat(64);
  private final FormFactCandidateExtractor extractor=new FormFactCandidateExtractor();

  @Test void everyRegisteredFormUsesItsDeclaredExtractionStrategy() {
    var registry=FormFactIngestionRegistry.loadDefault();
    assertEquals(166,registry.size());
    int single=0, rowset=0, evidence=0;

    for(var entry:registry.profiles().values()) {
      String code=entry.formCode();
      String mode=entry.mode().name();
      var scope=new FormFactCandidateExtractor.Scope(
          UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
          "M","2026-10", LocalDate.of(2026,10,1),LocalDate.of(2026,11,1),
          "KZT","ACTUAL","IFRS","PROJECT");
      UUID rowId=UUID.randomUUID();
      var numericField=new FormFactCandidateExtractor.Field(
          "fixtureAmount","MONEY","MANUAL",true);
      var textField=new FormFactCandidateExtractor.Field(
          "fixtureDocumentRef","REF","MANUAL",true);
      var fields=entry.mode()==FormFactIngestionRegistry.Mode.EVIDENCE
          ?List.of(textField):List.of(numericField);
      var values=entry.mode()==FormFactIngestionRegistry.Mode.SINGLE_RECORD
          ?Map.of("fixtureAmount","123"):entry.mode()==FormFactIngestionRegistry.Mode.EVIDENCE
              ?Map.of("fixtureDocumentRef","synthetic-ref"):Map.<String,String>of();
      var rows=entry.mode()==FormFactIngestionRegistry.Mode.ROWSET
          ?List.of(new FormFactCandidateExtractor.Row(rowId,1,Map.of("fixtureAmount","123")))
          :List.<FormFactCandidateExtractor.Row>of();
      var attachments=entry.mode()==FormFactIngestionRegistry.Mode.EVIDENCE
          ?List.of(new FormFactCandidateExtractor.Evidence(
              UUID.randomUUID(),"synthetic-document",SHA,"application/pdf",42))
          :List.<FormFactCandidateExtractor.Evidence>of();
      var snapshot=new FormFactCandidateExtractor.SourceSnapshot(
          UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
          code,mode,SHA,SHA,SHA,scope,fields,values,rows,attachments);
      var p=extractor.extract(snapshot);
      assertEquals(code,p.formCode());
      assertEquals("UNQUALIFIED_SOURCE_PROJECTION",p.qualificationState());
      assertEquals("SOURCE_SHA_NOT_REVERIFIED",p.sourceIntegrityState());
      switch(entry.mode()) {
        case SINGLE_RECORD -> {
          single++;
          assertEquals(1,p.numeric().size(),code);
          assertNull(p.numeric().get(0).rowId());
        }
        case ROWSET -> {
          rowset++;
          assertEquals(1,p.numeric().size(),code);
          assertEquals(rowId,p.numeric().get(0).rowId(),code);
        }
        case EVIDENCE -> {
          evidence++;
          assertTrue(p.numeric().isEmpty(),code);
          assertEquals(1,p.evidence().size(),code);
        }
      }
    }
    assertEquals(3,single);
    assertEquals(151,rowset);
    assertEquals(12,evidence);
  }
}
