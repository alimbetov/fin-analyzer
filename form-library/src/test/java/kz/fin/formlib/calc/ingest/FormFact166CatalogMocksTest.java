package kz.fin.formlib.calc.ingest;

import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIN FACT INGEST-1: 166 distinct synthetic source snapshots using the same
 * physical field names/types that CatalogBootstrap seeds for every form.
 * This verifies UNQUALIFIED projection only. Not real approval, S2, a source
 * SHA rehash, or PostgreSQL fin_fact materialization.
 */
class FormFact166CatalogMocksTest {
  private static final Set<String> NUMERIC = Set.of("MONEY", "DECIMAL", "INTEGER");
  private static final String SHA = "a".repeat(64);
  private static final String EVIDENCE_FIELDS =
      "documentRef:REF;documentType:CODE;documentDate:DATE;version:CODE;"
      + "issuerRef:REF;attachmentRef:REF;status:ENUM";
  private final FormFactIngestionRegistry registry=FormFactIngestionRegistry.loadDefault();
  private final FormFactCandidateExtractor extractor=new FormFactCandidateExtractor(registry);
  private final Map<String,FormProfile> profiles=loadProfiles();

  private record FormProfile(
      String formCode, FormFactIngestionRegistry.Mode mode,
      List<FormFactCandidateExtractor.Field> fields) {}

  private static List<String[]> tsv(String resource, int expectedColumns) {
    var url=FormFact166CatalogMocksTest.class.getResourceAsStream("/catalog/"+resource);
    if(url == null) throw new AssertionError("CATALOG_RESOURCE_MISSING:"+resource);
    try(var reader=new BufferedReader(new InputStreamReader(url, StandardCharsets.UTF_8))) {
      String header=reader.readLine();
      if(header==null) throw new AssertionError("CATALOG_EMPTY:"+resource);
      List<String[]> result=new ArrayList<>();
      String line;
      while((line=reader.readLine())!=null) {
        if(line.isBlank()) continue;
        String[] columns=line.split("\t",-1);
        if(columns.length!=expectedColumns)
          throw new AssertionError("CATALOG_COLUMN_COUNT:"+resource);
        result.add(columns);
      }
      return result;
    }catch(IOException ex){throw new AssertionError("CATALOG_READ_FAILED:"+resource,ex);}
  }

  private static String[] descriptor(String token) {
    String[] pair=token.split(":",-1);
    if(pair.length!=2 || pair[0].isBlank() || pair[1].isBlank())
      throw new AssertionError("CATALOG_FIELD_DESCRIPTOR_INVALID:"+token);
    return pair;
  }

  /** Derive the same source field inventory from seed sources used by CatalogBootstrap. */
  private static Map<String,FormProfile> loadProfiles(){
    Map<String,String> specialized=new HashMap<>();
    for(var row:tsv("specialized-schema-candidates-r6.tsv",5))
      if(specialized.putIfAbsent(row[0],row[2])!=null)
        throw new AssertionError("SPECIALIZED_FORM_DUPLICATE:"+row[0]);

    Map<String,String> pilotFields=new TreeMap<>();
    for(var row:tsv("intake-fields-v5.tsv",12))
      if(!"F02".equals(row[0]) ||
          pilotFields.putIfAbsent(row[3],"MONEY")!=null)
        throw new AssertionError("F02_PILOT_INVALID");
    assertEquals(3,pilotFields.size());

    Map<String,FormProfile> result=new TreeMap<>();
    for(var row:tsv("form-compatibility.tsv",8)){
      String code=row[0],kind=row[2],evidenceKind=row[6];
      boolean evidence=kind.equals("DOCUMENT") || kind.equals("EXTERNAL_TAX");
      FormFactIngestionRegistry.Mode mode=evidence
          ?FormFactIngestionRegistry.Mode.EVIDENCE
          :Set.of("F01","F02","F03").contains(code)
              ?FormFactIngestionRegistry.Mode.SINGLE_RECORD
              :FormFactIngestionRegistry.Mode.ROWSET;
      String fieldsSpec=code.equals("F02")
          ?String.join(";",pilotFields.entrySet().stream()
              .map(v->v.getKey()+":"+v.getValue()).toList())
          :evidenceKind.equals("TYPED_FIELD_HINTS")?row[5]
              :specialized.containsKey(code)?specialized.get(code)
                  :evidence?EVIDENCE_FIELDS:"";
      List<FormFactCandidateExtractor.Field> fields=new ArrayList<>();
      if(!fieldsSpec.isBlank()) for(String token:fieldsSpec.split(";")){
        String[] pair=descriptor(token);
        fields.add(new FormFactCandidateExtractor.Field(
            pair[0],pair[1],"MANUAL",code.equals("F02")));
      }
      if(result.putIfAbsent(code,new FormProfile(code,mode,List.copyOf(fields)))!=null)
        throw new AssertionError("FORM_DUPLICATE:"+code);
    }
    return Map.copyOf(result);
  }

  @Test void registryMatchesBootstrapped166FormsAndAll877PhysicalFields() {
    assertEquals(166,profiles.size());
    assertEquals(registry.profiles().keySet(),profiles.keySet());
    long physical=0,source=0,evidence=0;
    Map<String,Map<String,String>> actual=new TreeMap<>();
    for(var p:profiles.values()){
      assertEquals(p.mode(),registry.require(p.formCode(),p.mode().name()).mode());
      var typed=new TreeMap<String,String>();
      for(var field:p.fields()){
        if(typed.putIfAbsent(field.fieldCode(),field.fieldType())!=null)
          fail("DUPLICATE_PHYSICAL_FIELD:"+p.formCode()+"."+field.fieldCode());
      }
      actual.put(p.formCode(),typed);
      physical+=p.fields().size();
      if(p.mode()==FormFactIngestionRegistry.Mode.EVIDENCE)evidence+=p.fields().size();
      else source+=p.fields().size();
    }
    assertEquals(877,physical);
    assertEquals(793,source);
    assertEquals(84,evidence);

    int candidateCount=0,sourceCandidateCount=0,derivedCount=0;
    Set<String> seen=new HashSet<>();
    for(var row:tsv("form-field-presentation-review-r6.tsv",10)){
      candidateCount++;
      String form=row[0],field=row[1],type=row[2];
      if(!seen.add(form+"."+field))fail("R6_DUPLICATE_FIELD:"+form+"."+field);
      String physicalType=actual.get(form)==null?null:actual.get(form).get(field);
      if(form.equals("F02")&&field.equals("netProfit")){
        assertNull(physicalType,"F02.netProfit must not be writable SOURCE_FIELD");
        derivedCount++;
      }else{
        assertEquals(type,physicalType,"CATALOG_FIELD_DRIFT:"+form+"."+field);
        sourceCandidateCount++;
      }
    }
    assertEquals(794,candidateCount);
    assertEquals(793,sourceCandidateCount);
    assertEquals(1,derivedCount);
    // Reverse containment prevents the mock suite from silently omitting actual fields.
    for(var entry:actual.entrySet())
      if(profiles.get(entry.getKey()).mode()!=FormFactIngestionRegistry.Mode.EVIDENCE)
        for(String field:entry.getValue().keySet())
          assertTrue(seen.contains(entry.getKey()+"."+field),
              "BOOTSTRAP_FIELD_MISSING_IN_R6:"+entry.getKey()+"."+field);
  }

  @TestFactory
  Stream<DynamicNode> exactFieldMocksForEvery166Form(){
    return profiles.values().stream()
        .sorted(Comparator.comparing(FormProfile::formCode))
        .map(p->DynamicContainer.dynamicContainer(p.formCode(),Stream.of(
            DynamicTest.dynamicTest("all actual fields typed and lineage preserved",
                ()->checkValidProjection(p)),
            DynamicTest.dynamicTest("wrong declared content mode rejected",
                ()->checkWrongMode(p)),
            DynamicTest.dynamicTest("unknown source field rejected",
                ()->checkUnknownField(p)),
            DynamicTest.dynamicTest("malformed numeric/date/evidence or duplicate row rejected",
                ()->checkBadData(p)))));
  }

  private static UUID stable(String text){
    return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
  }

  private static String valueFor(String type, boolean secondRow){
    return switch(type){
      case "MONEY" -> secondRow?"0":"123.45";
      case "DECIMAL" -> secondRow?"0":"12.5";
      case "INTEGER" -> secondRow?"0":"42";
      case "DATE" -> secondRow?"2026-10-16":"2026-10-15";
      case "BOOLEAN" -> secondRow?"false":"true";
      case "PERIOD" -> "2026-10";
      case "CODE","REF","TEXT","ENUM" -> secondRow?"TEST_B":"TEST_A";
      default -> throw new AssertionError("UNSUPPORTED_PHYSICAL_FIELD_TYPE:"+type);
    };
  }

  private static Map<String,String> allValues(FormProfile profile,boolean secondRow){
    Map<String,String> result=new TreeMap<>();
    for(var f:profile.fields())
      result.put(f.fieldCode(),valueFor(f.fieldType(),secondRow));
    return result;
  }

  private FormFactCandidateExtractor.SourceSnapshot build(FormProfile p){
    String code=p.formCode();
    var scope=new FormFactCandidateExtractor.Scope(
        stable(code+"-org"),stable(code+"-entity"),
        stable(code+"-scope"),stable(code+"-project"),
        "M","2026-10",LocalDate.of(2026,10,1),LocalDate.of(2026,11,1),
        "KZT","ACTUAL","IFRS","PROJECT");
    List<FormFactCandidateExtractor.Row> rows=List.of();
    Map<String,String> top=Map.of();
    List<FormFactCandidateExtractor.Evidence> evidence=List.of();
    if(p.mode()==FormFactIngestionRegistry.Mode.ROWSET){
      rows=List.of(
          new FormFactCandidateExtractor.Row(stable(code+"-row-1"),1,allValues(p,false)),
          new FormFactCandidateExtractor.Row(stable(code+"-row-2"),2,allValues(p,true)));
    }else{
      top=allValues(p,false);
      if(p.mode()==FormFactIngestionRegistry.Mode.EVIDENCE)
        evidence=List.of(new FormFactCandidateExtractor.Evidence(
            stable(code+"-doc"),code+"-document","b".repeat(64),
            "application/pdf",1024));
    }
    return new FormFactCandidateExtractor.SourceSnapshot(
        stable(code+"-revision"),stable(code+"-instance"),
        stable(code+"-version"),code,p.mode().name(),
        SHA,"c".repeat(64),"d".repeat(64),scope,p.fields(),
        top,rows,evidence);
  }

  private void checkValidProjection(FormProfile p){
    var source=build(p);
    var result=extractor.extract(source);
    assertEquals(source.revisionId(),result.revisionId());
    assertEquals(source.formVersionId(),result.formVersionId());
    assertEquals(source.scope(),result.scope());
    assertEquals(SHA,result.sourceSha256Claim());
    assertEquals("UNQUALIFIED_SOURCE_PROJECTION",result.qualificationState());
    assertEquals("SOURCE_SHA_NOT_REVERIFIED",result.sourceIntegrityState());

    long numericFields=p.fields().stream().filter(f->NUMERIC.contains(f.fieldType())).count();
    long attributeFields=p.fields().size()-numericFields;
    int rowMultiplier=p.mode()==FormFactIngestionRegistry.Mode.ROWSET?2:1;
    if(p.mode()==FormFactIngestionRegistry.Mode.EVIDENCE){
      assertEquals(0,result.numeric().size(),p.formCode());
      assertEquals(7,result.attributes().size(),p.formCode());
      assertEquals(1,result.evidence().size(),p.formCode());
    }else{
      assertEquals(rowMultiplier*numericFields,result.numeric().size(),p.formCode());
      assertEquals(rowMultiplier*attributeFields,result.attributes().size(),p.formCode());
      assertTrue(result.evidence().isEmpty(),p.formCode());
    }

    Set<String> declared=p.fields().stream().map(
        FormFactCandidateExtractor.Field::fieldCode).collect(
            java.util.stream.Collectors.toSet());
    Set<String> projected=new HashSet<>();
    result.numeric().forEach(x->{
      assertNotNull(x.numericValue(),p.formCode()+"."+x.sourceFieldCode());
      assertEquals("PRESENT",x.valueState());
      assertTrue(declared.contains(x.sourceFieldCode()),p.formCode());
      if(p.mode()==FormFactIngestionRegistry.Mode.ROWSET)
        assertTrue(source.rows().stream().anyMatch(row->row.rowId().equals(x.rowId())));
      else assertNull(x.rowId());
      projected.add(x.sourceFieldCode());
    });
    result.attributes().forEach(x->{
      assertNotNull(x.sourceValue());
      assertTrue(declared.contains(x.sourceFieldCode()),p.formCode());
      if(p.mode()==FormFactIngestionRegistry.Mode.ROWSET)
        assertTrue(source.rows().stream().anyMatch(row->row.rowId().equals(x.rowId())));
      else assertNull(x.rowId());
      projected.add(x.sourceFieldCode());
    });
    assertEquals(declared,projected,p.formCode()+" actual source field coverage");

    if(p.mode()==FormFactIngestionRegistry.Mode.ROWSET && numericFields>0){
      for(var field:p.fields())
        if(NUMERIC.contains(field.fieldType())){
          var fieldOutputs=result.numeric().stream()
              .filter(x->x.sourceFieldCode().equals(field.fieldCode())).toList();
          assertEquals(2,fieldOutputs.size(),p.formCode());
          assertEquals(stable(p.formCode()+"-row-1"),fieldOutputs.get(0).rowId());
          assertEquals(stable(p.formCode()+"-row-2"),fieldOutputs.get(1).rowId());
          assertTrue(fieldOutputs.get(0).numericValue().signum()>0);
          assertEquals(0,fieldOutputs.get(1).numericValue().signum());
        }
    }
  }

  private static FormFactCandidateExtractor.SourceSnapshot altered(
      FormFactCandidateExtractor.SourceSnapshot s,String mode,
      Map<String,String> top,List<FormFactCandidateExtractor.Row> rows,
      List<FormFactCandidateExtractor.Evidence> evidence){
    return new FormFactCandidateExtractor.SourceSnapshot(
        s.revisionId(),s.instanceId(),s.formVersionId(),
        s.formCode(),mode,s.claimedCanonicalContentSha256(),
        s.sourceSchemaSha256(),s.sourceMappingSha256(),
        s.scope(),s.fields(),top,rows,evidence);
  }

  private void checkWrongMode(FormProfile p){
    var s=build(p);
    String wrong=p.mode()==FormFactIngestionRegistry.Mode.ROWSET?"SINGLE_RECORD":"ROWSET";
    var injected=altered(s,wrong,s.values(),s.rows(),s.evidence());
    var error=assertThrows(IllegalArgumentException.class,()->extractor.extract(injected));
    assertEquals("SOURCE_MODE_CONTRACT_MISMATCH",error.getMessage());
  }

  private void checkUnknownField(FormProfile p){
    var s=build(p);
    if(p.mode()==FormFactIngestionRegistry.Mode.ROWSET){
      var rows=new ArrayList<>(s.rows());
      Map<String,String> bad=new HashMap<>(rows.get(0).values());
      bad.put("__unknown_source_field__","1");
      rows.set(0,new FormFactCandidateExtractor.Row(
          rows.get(0).rowId(),1,bad));
      var injected=altered(s,s.contentMode(),s.values(),rows,s.evidence());
      var error=assertThrows(IllegalArgumentException.class,()->extractor.extract(injected));
      assertEquals("UNKNOWN_SOURCE_FIELD",error.getMessage());
    } else {
      Map<String,String> bad=new HashMap<>(s.values());
      bad.put("__unknown_source_field__","1");
      var injected=altered(s,s.contentMode(),bad,s.rows(),s.evidence());
      var error=assertThrows(IllegalArgumentException.class,()->extractor.extract(injected));
      assertEquals("UNKNOWN_SOURCE_FIELD",error.getMessage());
    }
  }

  private void checkBadData(FormProfile p){
    var s=build(p);
    if(p.mode()==FormFactIngestionRegistry.Mode.EVIDENCE){
      var invalid=new FormFactCandidateExtractor.Evidence(
          stable(p.formCode()+"-bad"),"document","not-a-sha","application/pdf",1024);
      var injected=altered(s,s.contentMode(),s.values(),s.rows(),List.of(invalid));
      assertEquals("EVIDENCE_SHA_INVALID",
          assertThrows(IllegalArgumentException.class,()->extractor.extract(injected)).getMessage());
      return;
    }
    var numericField=p.fields().stream().filter(f->NUMERIC.contains(f.fieldType()))
        .findFirst();
    if(numericField.isPresent()){
      String code=numericField.get().fieldCode();
      if(p.mode()==FormFactIngestionRegistry.Mode.ROWSET){
        var rows=new ArrayList<>(s.rows());
        var bad=new HashMap<>(rows.get(0).values());
        bad.put(code,"1e5");
        rows.set(0,new FormFactCandidateExtractor.Row(rows.get(0).rowId(),1,bad));
        assertThrows(IllegalArgumentException.class,()->extractor.extract(
            altered(s,s.contentMode(),s.values(),rows,s.evidence())));
      }else{
        var bad=new HashMap<>(s.values());
        bad.put(code,"1e5");
        assertThrows(IllegalArgumentException.class,()->extractor.extract(
            altered(s,s.contentMode(),bad,s.rows(),s.evidence())));
      }
    }else if(p.mode()==FormFactIngestionRegistry.Mode.ROWSET){
      var rows=new ArrayList<>(s.rows());
      rows.set(1,new FormFactCandidateExtractor.Row(rows.get(0).rowId(),2,rows.get(1).values()));
      assertEquals("ROWSET_ROW_IDENTITY_INVALID",
          assertThrows(IllegalArgumentException.class,()->extractor.extract(
              altered(s,s.contentMode(),s.values(),rows,s.evidence()))).getMessage());
    }else{
      // F05/F08 etc. are legitimate nonnumeric forms; corrupt a real date
      // if present, otherwise use a null source value (never coerced to zero).
      String dateField=p.fields().stream().filter(f->f.fieldType().equals("DATE"))
          .map(FormFactCandidateExtractor.Field::fieldCode).findFirst().orElse(null);
      var bad=new HashMap<>(s.values());
      if(dateField!=null)bad.put(dateField,"INVALID-DATE");
      else bad.put(p.fields().get(0).fieldCode(),null);
      assertThrows(IllegalArgumentException.class,()->extractor.extract(
          altered(s,s.contentMode(),bad,s.rows(),s.evidence())));
    }
  }
}
