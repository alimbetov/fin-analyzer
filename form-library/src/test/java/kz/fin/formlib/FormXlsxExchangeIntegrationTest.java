package kz.fin.formlib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.service.FormDraftService;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FormXlsxExchangeIntegrationTest {
  @Autowired MockMvc http;
  @Autowired ObjectMapper json;

  private record Saved(UUID id,UUID org,String sha) {}

  private Saved create(String code,Map<String,String> values,
                       List<FormDraftService.RowInput> rows) throws Exception {
    UUID org=UUID.randomUUID();
    var req=new FormDraftService.DraftRequest(null,org,
      UUID.fromString("00000000-0000-0000-0000-000000000002"),
      UUID.fromString("00000000-0000-0000-0000-000000000003"),
      UUID.randomUUID(),code,"M","2026-11","KZT","PERIOD_ONLY",
      "MANAGEMENT","SYNTHETIC",0,values,rows,"qa","initial");
    var response=http.perform(post("/api/v1/drafts")
      .header("Idempotency-Key","xlsx-seed-"+org)
      .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(req)))
      .andExpect(status().isOk()).andReturn();
    JsonNode saved=json.readTree(response.getResponse().getContentAsString());
    return new Saved(UUID.fromString(saved.get("instanceId").asText()),org,
      saved.get("contentSha256").asText());
  }

  private byte[] export(Saved saved,String style) throws Exception {
    return http.perform(get("/api/v1/drafts/"+saved.id()+"/export.xlsx")
      .param("organizationId",saved.org().toString()).param("styleCode",style))
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsByteArray();
  }

  private byte[] changeCell(byte[] bytes,int row,int column,String text) throws Exception {
    try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(bytes));
        ByteArrayOutputStream output=new ByteArrayOutputStream()){
      book.getSheet("REPORT").getRow(row).getCell(column).setCellValue(text);
      book.write(output);
      return output.toByteArray();
    }
  }

  private JsonNode upload(Saved saved,byte[] bytes,String key) throws Exception {
    var file=new MockMultipartFile("file","form.xlsx",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes);
    var response=http.perform(multipart("/api/v1/drafts/"+saved.id()+"/import.xlsx")
      .file(file).param("organizationId",saved.org().toString())
      .param("actor","qa").param("reason","xlsx-import")
      .header("Idempotency-Key",key))
      .andExpect(status().isOk()).andReturn();
    return json.readTree(response.getResponse().getContentAsString());
  }

  private JsonNode revision(Saved saved,int no) throws Exception {
    var response=http.perform(get("/api/v1/drafts/"+saved.id()+"/revisions/"+no)
      .param("organizationId",saved.org().toString()))
      .andExpect(status().isOk()).andReturn();
    return json.readTree(response.getResponse().getContentAsString());
  }

  @Test
  void exportedFilenameIncludesActualPeriodCurrencyStyleLocaleVersionAndRevision()
      throws Exception {
    Saved saved=create("F02",Map.of("netRevenue","100",
      "cogs","40","operatingExpenses","10"),List.of());
    for(String style:List.of("CLASSIC","EXCEL","DASHBOARD")){
      for(String locale:List.of("ru","kk","en","zh-Hans")){
        var response=http.perform(get("/api/v1/drafts/"+saved.id()+"/export.xlsx")
          .param("organizationId",saved.org().toString())
          .param("styleCode",style).param("locale",locale))
          .andExpect(status().isOk()).andReturn().getResponse();
        String header=response.getHeader("Content-Disposition");
        assertNotNull(header);
        assertTrue(header.startsWith("attachment; filename=\\"FIN_F02_M-2026-11_KZT_"+
          style+"_"+locale+"_FIN-FORM-V2_r0001_"),header);
        assertTrue(header.endsWith(".xlsx\\""),header);
        assertTrue(header.contains(saved.sha().substring(0,12)),header);
        assertFalse(header.contains(saved.id().toString()));
        assertFalse(header.contains(saved.org().toString()));
      }
    }
    JsonNode committed=upload(saved,changeCell(export(saved,"EXCEL"),4,3,"200"),
      "filename-revision-"+saved.org());
    var newer=http.perform(get("/api/v1/drafts/"+saved.id()+"/export.xlsx")
      .param("organizationId",saved.org().toString())
      .param("styleCode","EXCEL").param("locale","ru"))
      .andExpect(status().isOk()).andReturn().getResponse();
    String updated=newer.getHeader("Content-Disposition");
    assertNotNull(updated);
    assertTrue(updated.contains("_r0002_"),updated);
    assertTrue(updated.contains(committed.get("contentSha256").asText().substring(0,12)),updated);
    assertFalse(updated.contains("_r0001_"));
  }

  @Test
  void threeStylesExportEditAndReimportIntoImmutableFinIntakeRevisions() throws Exception {
    for(String style:List.of("CLASSIC","EXCEL","DASHBOARD")){
      Saved saved=create("F02",Map.of("netRevenue","125000.50",
          "cogs","70000","operatingExpenses","1000"),List.of());
      byte[] file=export(saved,style);
      try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(file))){
        assertEquals(2,book.getNumberOfSheets());
        assertEquals("MONEY",book.getSheet("PARAMETERS").getRow(14).getCell(2).getStringCellValue());
      }
      int column=3;
      JsonNode receipt=upload(saved,changeCell(file,4,column,"150000.25"),
         "xlsx-import-"+style+"-"+saved.org());
      assertEquals(2,receipt.get("revisionNo").asInt());
      assertNotEquals(saved.sha(),receipt.get("contentSha256").asText());
      assertEquals(receipt.get("contentSha256").asText(),revision(saved,2).get("sha256").asText());
      assertEquals("150000.25",revision(saved,2).get("values").get("netRevenue").asText());
      assertEquals("70000",revision(saved,2).get("values").get("cogs").asText());
      assertEquals("1000",revision(saved,2).get("values").get("operatingExpenses").asText());
      assertEquals("125000.5",revision(saved,1).get("values").get("netRevenue").asText());
      assertEquals(saved.sha(),revision(saved,1).get("sha256").asText());
    }
  }

  @Test
  void unchangedXlsxDoesNotCreateNewRevision() throws Exception {
    Saved saved=create("F02",Map.of("netRevenue","100",
       "cogs","40","operatingExpenses","10"),List.of());
    byte[] unchanged=export(saved,"EXCEL");
    var file=new MockMultipartFile("file","unchanged.xlsx",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",unchanged);
    http.perform(multipart("/api/v1/drafts/"+saved.id()+"/import.xlsx")
      .file(file).param("organizationId",saved.org().toString())
      .param("actor","qa").param("reason","no changes")
      .header("Idempotency-Key","unchanged-"+saved.org()))
      .andExpect(status().isUnprocessableEntity());
    assertEquals(1,revision(saved,1).get("revisionNo").asInt());
  }

  @Test
  void localizedExportKeepsCanonicalFieldCodesInParameters() throws Exception {
    Saved saved=create("F02",Map.of("netRevenue","100",
       "cogs","40","operatingExpenses","10"),List.of());
    for(String locale:List.of("ru","kk","en","zh-Hans")) {
      byte[] bytes=http.perform(get("/api/v1/drafts/"+saved.id()+"/export.xlsx")
        .param("organizationId",saved.org().toString())
        .param("styleCode","EXCEL").param("locale",locale))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsByteArray();
      try(Workbook workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
        assertEquals(locale,workbook.getSheet("PARAMETERS").getRow(9)
          .getCell(1).getStringCellValue());
        assertEquals("netRevenue",workbook.getSheet("PARAMETERS")
          .getRow(14).getCell(0).getStringCellValue());
      }
    }
  }

  @Test
  void exportedPeriodAndCurrencyRemainPinnedToFinIntake()throws Exception {
    Saved saved=create("F02",Map.of("netRevenue","500",
        "cogs","200","operatingExpenses","100"),List.of());
    byte[] original=export(saved,"EXCEL");
    try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(original))) {
      var report=book.getSheet("REPORT");
      assertTrue(report.getRow(1).getCell(0).getStringCellValue().contains("2026-11"));
      assertTrue(report.getRow(1).getCell(2).getStringCellValue().contains("KZT"));
      assertTrue(report.getRow(2).getCell(0).getStringCellValue().contains("2026-11-30"));
    }
    // Even changing both the visible header and PARAMETERS cannot change
    // the already persisted reporting currency or accounting period.
    byte[] changed=changeCell(original,4,3,"600");
    try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(changed));
        ByteArrayOutputStream out=new ByteArrayOutputStream()) {
      book.getSheet("REPORT").getRow(1).getCell(2).setCellValue("Валюта: USD");
      book.getSheet("PARAMETERS").getRow(11).getCell(9).setCellValue("USD");
      book.write(out);
      changed=out.toByteArray();
    }
    var file=new MockMultipartFile("file","wrong-currency.xlsx",
       "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",changed);
    http.perform(multipart("/api/v1/drafts/"+saved.id()+"/import.xlsx")
      .file(file).param("organizationId",saved.org().toString())
      .param("actor","qa").param("reason","invalid context")
      .header("Idempotency-Key","currency-context-"+saved.org()))
      .andExpect(status().isConflict());
    assertEquals(1,revision(saved,1).get("revisionNo").asInt());
  }

  @Test
  void staleWorkbookIsRejectedWithoutCreatingNewRevision()throws Exception{
    Saved saved=create("F02",Map.of("netRevenue","500",
        "cogs","200","operatingExpenses","100"),List.of());
    byte[] original=export(saved,"EXCEL");
    upload(saved,changeCell(original,4,3,"700"),"first-"+saved.org());
    var file=new MockMultipartFile("file","old.xlsx",
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",original);
    http.perform(multipart("/api/v1/drafts/"+saved.id()+"/import.xlsx")
      .file(file).param("organizationId",saved.org().toString())
      .param("actor","qa").param("reason","stale")
      .header("Idempotency-Key","stale-"+saved.org()))
      .andExpect(status().isConflict());
    assertEquals(2,revision(saved,2).get("revisionNo").asInt());
  }
}
