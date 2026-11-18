package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.domain.LibCurrency;
import kz.fin.formlib.repository.FormDefinitionRepository;
import kz.fin.formlib.repository.LibCurrencyRepository;
import kz.fin.formlib.service.FormDraftService;
import kz.fin.formlib.service.PeriodResolver;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Deliberately opt-in end-to-end acceptance matrix, because 231 combinations
 * exercise many real HTTP requests and immutable database revisions.
 * Run: mvn -f form-library/pom.xml
 *   -Dtest=R19ImportMatrixIntegrationTest -Dfin.uat.matrix.enabled=true test
 *
 * Coverage: all 7 catalog period types x all 11 currencies x all 3 XLSX styles.
 * It creates only fresh synthetic F02 instances; existing user data is untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named="fin.uat.matrix.enabled",matches="true")
class R19ImportMatrixIntegrationTest {
  private static final Map<String,String> PERIODS=new LinkedHashMap<>();
  private static final List<String> STYLES=List.of("CLASSIC","EXCEL","DASHBOARD");
  private static final List<String> LOCALES=List.of("ru","kk","en","zh-Hans");
  static {
    PERIODS.put("D","2026-11-18");
    PERIODS.put("W","2026-W47");
    PERIODS.put("DEC","2026-11-2");
    PERIODS.put("M","2026-11");
    PERIODS.put("Q","2026-Q4");
    PERIODS.put("H","2026-H2");
    PERIODS.put("Y","2026");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired FormDefinitionRepository forms;
  @Autowired LibCurrencyRepository currencies;
  @Autowired JdbcTemplate sql;

  private record Case(String type,String code,String currency,int minorUnits,
                      String style,String locale) {}
  private record Created(UUID instanceId,UUID org,String initial,
                         String contentSha256) {}

  private JsonNode ok(RequestBuilder request)throws Exception {
    return mapper.readTree(mvc.perform(request).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString());
  }

  private long revisions(UUID instanceId) {
    Long n=sql.queryForObject(
      "SELECT COUNT(*) FROM fin_intake.lib_form_revision WHERE instance_id=?",
      Long.class,instanceId);
    return Objects.requireNonNull(n);
  }

  private JsonNode read(UUID instanceId,UUID org,Integer revision)throws Exception {
    String url="/api/v1/drafts/"+instanceId;
    if(revision!=null)url+="/revisions/"+revision;
    return ok(get(url).param("organizationId",org.toString()));
  }

  private Created create(Case c)throws Exception {
    UUID org=UUID.randomUUID();
    String initial=c.minorUnits()==0?"1000":"1000.25";
    var request=new FormDraftService.DraftRequest(null,org,UUID.randomUUID(),
      UUID.randomUUID(),UUID.randomUUID(),"F02",c.type(),c.code(),c.currency(),
      "PERIOD_ONLY","MANAGEMENT","SYNTHETIC",0,
      Map.of("netRevenue",initial,"cogs","450","operatingExpenses","100"),
      List.of(),"matrix-uat","seed");
    JsonNode receipt=ok(post("/api/v1/drafts")
      .contentType(MediaType.APPLICATION_JSON)
      .header("Idempotency-Key","r19-matrix-seed-"+org)
      .content(mapper.writeValueAsString(request)));
    assertEquals(1,receipt.path("revisionNo").asInt());
    return new Created(UUID.fromString(receipt.path("instanceId").asText()),
      org,initial,receipt.path("contentSha256").asText());
  }

  private byte[] export(Created created,Case c)throws Exception {
    return mvc.perform(get("/api/v1/drafts/"+created.instanceId()+"/export.xlsx")
      .param("organizationId",created.org().toString())
      .param("styleCode",c.style())
      .param("locale",c.locale()))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
  }

  private static byte[] edited(byte[] xlsx,String style,String value)throws Exception {
    try(Workbook workbook=WorkbookFactory.create(new ByteArrayInputStream(xlsx));
        ByteArrayOutputStream output=new ByteArrayOutputStream()){
      Sheet report=workbook.getSheet("REPORT");
      assertNotNull(report);assertNotNull(workbook.getSheet("PARAMETERS"));
      var number=report.getRow(4).getCell(0);
      assertEquals("1",number.getStringCellValue());
      assertEquals(HorizontalAlignment.CENTER,number.getCellStyle().getAlignment());
      assertEquals("FIN-FORM-XLSX-V3",workbook.getSheet("PARAMETERS")
        .getRow(0).getCell(1).getStringCellValue());
      assertEquals("C5",workbook.getSheet("PARAMETERS")
        .getRow(14).getCell(1).getStringCellValue());
      report.getRow(4).getCell(2).setCellValue(value);
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private static MockMultipartFile part(byte[] file){
    return new MockMultipartFile("file","matrix.xlsx",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",file);
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      previewRequest(UUID profile,Created created,byte[] file){
    return multipart("/api/v1/import-profiles/"+profile+"/preview")
      .file(part(file))
      .param("instanceId",created.instanceId().toString())
      .param("organizationId",created.org().toString())
      .param("actor","matrix-uat").param("reason","matrix preview");
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      commitRequest(UUID ticket,Created created,byte[] file){
    return multipart("/api/v1/import-profiles/previews/"+ticket+"/commit")
      .file(part(file))
      .param("instanceId",created.instanceId().toString())
      .param("organizationId",created.org().toString())
      .param("actor","matrix-uat").param("reason","matrix commit")
      .header("Idempotency-Key","r19-matrix-commit-"+created.org());
  }

  private void exercise(Case c,UUID profile)throws Exception {
    PeriodResolver.Range range=PeriodResolver.resolve(c.type(),c.code());
    Created created=create(c);
    assertEquals(1,revisions(created.instanceId()));
    JsonNode initial=read(created.instanceId(),created.org(),null);
    assertEquals(range.periodType(),initial.path("periodType").asText());
    assertEquals(range.periodCode(),initial.path("periodCode").asText());
    assertEquals(range.startInclusive().toString(),initial.path("periodStart").asText());
    assertEquals(range.endExclusive().toString(),
      initial.path("periodEndExclusive").asText());
    assertEquals(c.currency(),initial.path("currency").asText());
    assertEquals(created.initial(),initial.path("values").path("netRevenue").asText());

    byte[] original=export(created,c);
    String editedValue=c.minorUnits()==0?"1500":"1500,25";
    byte[] changed=edited(original,c.style(),editedValue);
    JsonNode preview=ok(previewRequest(profile,created,changed));
    assertEquals(1,preview.path("expectedRevision").asInt());
    String canonical=c.minorUnits()==0?"1500":"1500.25";
    assertEquals(canonical,preview.path("values").path("netRevenue").asText());
    assertEquals(1,revisions(created.instanceId()),
      "Preview must never write fin_intake");
    UUID ticket=UUID.fromString(preview.path("previewId").asText());
    JsonNode committed=ok(commitRequest(ticket,created,changed));
    assertEquals(2,committed.path("revisionNo").asInt());
    String newSha=committed.path("contentSha256").asText();
    assertEquals(64,newSha.length());
    assertNotEquals(created.contentSha256(),newSha);
    assertEquals(2,revisions(created.instanceId()));

    JsonNode latest=read(created.instanceId(),created.org(),null);
    JsonNode revision1=read(created.instanceId(),created.org(),1);
    assertEquals(2,latest.path("revisionNo").asInt());
    assertEquals(canonical,latest.path("values").path("netRevenue").asText());
    assertEquals("450",latest.path("values").path("cogs").asText());
    assertEquals("100",latest.path("values").path("operatingExpenses").asText());
    assertEquals(newSha,latest.path("sha256").asText());
    assertEquals(c.currency(),latest.path("currency").asText());
    assertEquals(range.periodCode(),latest.path("periodCode").asText());
    assertEquals(created.initial(),revision1.path("values").path("netRevenue").asText());
    assertEquals(created.contentSha256(),revision1.path("sha256").asText());
    assertEquals(1,revision1.path("revisionNo").asInt());

    // A second export must carry the newly committed revision and its SHA.
    byte[] reexport=export(created,c);
    try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(reexport))){
      Sheet params=book.getSheet("PARAMETERS");
      assertEquals("2",params.getRow(5).getCell(1).getStringCellValue());
      assertEquals(newSha,params.getRow(6).getCell(1).getStringCellValue());
      assertEquals(c.currency(),params.getRow(11).getCell(9).getStringCellValue());
      assertEquals(range.periodType(),params.getRow(11).getCell(1).getStringCellValue());
      assertEquals(range.periodCode(),params.getRow(11).getCell(3).getStringCellValue());
      assertEquals(c.locale(),params.getRow(9).getCell(1).getStringCellValue());
    }
    // Any old workbook is stale, even if numeric value is valid.
    mvc.perform(previewRequest(profile,created,changed))
      .andExpect(status().isConflict());
    assertEquals(2,revisions(created.instanceId()));
  }

  @Test
  void allPeriodCurrencyStyleCombinationsAndImmutableHistoryMatch() throws Exception {
    var active=currencies.findAllByOrderByCodeAsc().stream()
      .filter(c->"ACTIVE".equals(c.getStatus())).toList();
    assertEquals(11,active.size(),"Changes to supported currencies require matrix review");
    UUID formVersion=forms.findByFormCode("F02").orElseThrow().getCurrentVersionId();
    JsonNode profile=ok(post("/api/v1/import-profiles")
      .contentType(MediaType.APPLICATION_JSON)
      .content(mapper.writeValueAsString(Map.of("formVersionId",formVersion))));
    UUID profileId=UUID.fromString(profile.path("id").asText());

    List<String> outcomes=new ArrayList<>();
    outcomes.add("periodType,periodCode,currency,minorUnits,style,locale,result,error");
    int passed=0;
    for(var period:PERIODS.entrySet()){
      for(LibCurrency currency:active){
        for(String style:STYLES){
          int caseIndex=outcomes.size()-1;
          Case scenario=new Case(period.getKey(),period.getValue(),
            currency.getCode(),currency.getMinorUnits(),style,
            LOCALES.get(caseIndex%LOCALES.size()));
          try {
            exercise(scenario,profileId);
            passed++;
            outcomes.add(String.join(",",scenario.type(),scenario.code(),
              scenario.currency(),Integer.toString(scenario.minorUnits()),
              scenario.style(),scenario.locale(),"PASS",""));
          }catch(Exception|AssertionError e){
            String error=String.valueOf(e.getMessage()).replace('\n',' ').replace(',',';');
            outcomes.add(String.join(",",scenario.type(),scenario.code(),
              scenario.currency(),Integer.toString(scenario.minorUnits()),
              scenario.style(),scenario.locale(),"FAIL",error));
          }
        }
      }
    }
    Path result=Path.of("target","uat","r19-import-matrix.csv");
    Files.createDirectories(result.getParent());
    Files.write(result,outcomes,StandardCharsets.UTF_8);
    int total=PERIODS.size()*active.size()*STYLES.size();
    assertEquals(231,total);
    assertEquals(total,passed,"Matrix "+passed+"/"+total+"; inspect "+result);
  }
}
