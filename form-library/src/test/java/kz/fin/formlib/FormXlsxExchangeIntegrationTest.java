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
  void threeStylesExportEditAndReimportIntoImmutableFinIntakeRevisions() throws Exception {
    for(String style:List.of("CLASSIC","EXCEL","DASHBOARD")){
      Saved saved=create("F02",Map.of("netRevenue","125000.50",
          "cogs","70000","operatingExpenses","1000"),List.of());
      byte[] file=export(saved,style);
      try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(file))){
        assertEquals(2,book.getNumberOfSheets());
        assertEquals("MONEY",book.getSheet("PARAMETERS").getRow(14).getCell(2).getStringCellValue());
      }
      int column=1;
      JsonNode receipt=upload(saved,changeCell(file,4,column,"150000.25"),
         "xlsx-import-"+style+"-"+saved.org());
      assertEquals(2,receipt.get("revisionNo").asInt());
      assertEquals("150000.25",revision(saved,2).get("values").get("netRevenue").asText());
      assertEquals("70000",revision(saved,2).get("values").get("cogs").asText());
      assertEquals("1000",revision(saved,2).get("values").get("operatingExpenses").asText());
      assertEquals("125000.5",revision(saved,1).get("values").get("netRevenue").asText());
      assertEquals(saved.sha(),revision(saved,1).get("sha256").asText());
    }
  }

  @Test
  void staleWorkbookIsRejectedWithoutCreatingNewRevision()throws Exception{
    Saved saved=create("F02",Map.of("netRevenue","500",
        "cogs","200","operatingExpenses","100"),List.of());
    byte[] original=export(saved,"EXCEL");
    upload(saved,changeCell(original,4,1,"700"),"first-"+saved.org());
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
