package kz.fin.formlib.bootstrap;

import kz.fin.formlib.domain.*;
import kz.fin.formlib.repository.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Component
public class CatalogBootstrap implements ApplicationRunner {
  private final FormDefinitionRepository forms;
  private final FormVersionRepository versions;
  private final FormSectionRepository sections;
  private final FormFieldRepository fields;
  private final FormFieldTranslationRepository translations;

  public CatalogBootstrap(FormDefinitionRepository forms, FormVersionRepository versions,
                          FormSectionRepository sections, FormFieldRepository fields,
                          FormFieldTranslationRepository translations) {
    this.forms=forms; this.versions=versions; this.sections=sections;
    this.fields=fields; this.translations=translations;
  }

  private static List<String[]> rows(String resource, int columns) throws IOException {
    List<String[]> out=new ArrayList<>();
    try (var input=new BufferedReader(new InputStreamReader(
          new ClassPathResource(resource).getInputStream(), StandardCharsets.UTF_8))) {
      String header=input.readLine();
      if(header==null) throw new IllegalStateException("EMPTY_CATALOG: "+resource);
      String line;
      while((line=input.readLine())!=null) {
        if(line.isBlank()) continue;
        String[] parts=line.split("\\t",-1);
        if(parts.length!=columns) throw new IllegalStateException("INVALID_CATALOG_ROW: "+resource);
        out.add(parts);
      }
    }
    return out;
  }
  private static String sha(String canonical) {
    try {
      byte[] bytes=MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (Exception ex) { throw new IllegalStateException(ex); }
  }
  private static List<String[]> parseFields(String s) {
    if(s==null||s.isBlank()) return List.of();
    List<String[]> parsed=new ArrayList<>();
    for(String token:s.split(";")) {
      String[] p=token.split(":",-1);
      if(p.length!=2) throw new IllegalStateException("INVALID_FIELD: "+token);
      parsed.add(p);
    }
    return parsed;
  }
  @Transactional
  @Override
  public void run(ApplicationArguments args) throws Exception {
    if(forms.count()!=0) {
      if(forms.count()!=166) throw new IllegalStateException("PARTIAL_CATALOG_SEED");
      return;
    }
    Map<String,String> candidates=new HashMap<>();
    for(String[] row:rows("catalog/specialized-schema-candidates-r6.tsv",5))
      candidates.put(row[0],row[2]);
    Map<String,String[]> pilot=new LinkedHashMap<>();
    for(String[] row:rows("catalog/intake-fields-v5.tsv",12)) pilot.put(row[3],row);
    if(pilot.size()!=3) throw new IllegalStateException("F02_PILOT_INCOMPLETE");

    int count=0,typed=0,evidence=0,unapproved=0;
    Set<String> codes=new HashSet<>();
    for(String[] row:rows("catalog/form-compatibility.tsv",8)) {
      String code=row[0], category=row[1], kind=row[2], grain=row[4], evidenceKind=row[6];
      if(!codes.add(code)) throw new IllegalStateException("DUPLICATE_FORM_CODE");
      boolean evidenceOnly=kind.equals("DOCUMENT")||kind.equals("EXTERNAL_TAX");
      boolean typedFields=evidenceKind.equals("TYPED_FIELD_HINTS");
      String capture=evidenceOnly?"EVIDENCE_ONLY":typedFields?"TYPED_DRAFT":"SCHEMA_NOT_APPROVED";
      if(evidenceOnly) evidence++; else if(typedFields) typed++; else unapproved++;
      String contentMode=evidenceOnly?"EVIDENCE":
           Set.of("F01","F02","F03").contains(code)?"SINGLE_RECORD":"ROWSET";
      List<String[]> desc;
      if(code.equals("F02")) {
        desc=new ArrayList<>();
        for(String[] p:pilot.values()) desc.add(new String[]{p[3],"MONEY"});
      } else if(typedFields) desc=parseFields(row[5]);
      else if(candidates.containsKey(code)) desc=parseFields(candidates.get(code));
      else if(evidenceOnly) desc=parseFields("documentRef:REF;documentType:CODE;documentDate:DATE;version:CODE;issuerRef:REF;attachmentRef:REF;status:ENUM");
      else desc=List.of();

      var def=new FormDefinition();
      def.setId(UUID.randomUUID()); def.setFormCode(code);
      def.setCatalog(category); def.setLegacyCaptureMode(capture);
      forms.save(def);
      var version=new FormVersion();version.setId(UUID.randomUUID());version.setFormId(def.getId());
      version.setVersionLabel(code.equals("F02")?"FIN-FORM-V2":"CATALOG-V1");
      version.setContentMode(contentMode); version.setGrain(grain);
      version.setQualificationState("CANDIDATE_UNQUALIFIED");
      String signature=code+"|"+capture+"|"+contentMode+"|"+row[4]+"|"+
        desc.stream().map(p->p[0]+":"+p[1]).reduce("",(a,b)->a+"|"+b);
      version.setSchemaSha256(sha("FIN-LIB-SCHEMA-V1|"+signature));
      version.setPresentationSha256(sha("FIN-LIB-PRESENTATION-V1|"+code+"|"+signature));
      version.setMappingSha256(sha("FIN-LIB-NOT-APPROVED-MAPPING-V1|"+code));
      version.setCreatedAt(Instant.now()); versions.save(version);
      def.setCurrentVersionId(version.getId()); forms.save(def);
      var sec=new FormSection();sec.setId(UUID.randomUUID());sec.setFormVersionId(version.getId());
      sec.setSectionCode("MAIN");sec.setSectionOrder(10);sec.setLayoutKind(contentMode);
      sections.save(sec);
      int order=0;
      for(String[] d:desc) {
        var field=new FormField();field.setId(UUID.randomUUID());field.setFormVersionId(version.getId());
        field.setSectionId(sec.getId());field.setFieldCode(d[0]);
        field.setFieldType(d[1]);field.setInputMode("MANUAL");
        field.setDisplayOrder((++order)*10);
        field.setRequired(code.equals("F02")); // Only pilot F02 has reviewed structural requiredness.
        fields.save(field);
        if(code.equals("F02")) {
          String[] p=pilot.get(d[0]);
          for(int j=0;j<3;j++) {
            var t=new FormFieldTranslation(); t.setId(UUID.randomUUID());
            t.setFormVersionId(version.getId()); t.setSubjectType("FIELD");
            t.setSubjectCode(d[0]);t.setLocale(new String[]{"ru","kk","en"}[j]);
            t.setTitle(p[9+j]);translations.save(t);
          }
        }
      }
      count++;
    }
    if(count!=166||typed!=122||evidence!=12||unapproved!=32)
      throw new IllegalStateException("CATALOG_166_DRIFT: "+count+"/"+typed+"/"+evidence+"/"+unapproved);
  }
}
