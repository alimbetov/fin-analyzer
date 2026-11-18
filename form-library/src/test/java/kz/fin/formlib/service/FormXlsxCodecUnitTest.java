package kz.fin.formlib.service;

import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** XLSX V2 file-contract tests, no Spring or DB. */
class FormXlsxCodecUnitTest {
  private static final UUID INSTANCE=UUID.randomUUID();
  private static final UUID ORG=UUID.randomUUID();
  private static final UUID VERSION=UUID.randomUUID();
  private static final String SHA="a".repeat(64);

  private static FormXlsxCodec.Envelope envelope(FormXlsxCodec.Style style){
    return new FormXlsxCodec.Envelope(INSTANCE,ORG,VERSION,"F02",1,SHA,style);
  }
  private static List<FormXlsxCodec.Entry> fields(){
    return List.of(
      new FormXlsxCodec.Entry("netRevenue","125000.50","MONEY","KZT",true),
      new FormXlsxCodec.Entry("cogs","70000","MONEY","KZT",true),
      new FormXlsxCodec.Entry("description","","STRING","",false));
  }

  private static byte[] mutate(byte[] input,String sheetName,int row,int col,
                               String newValue, boolean formula) throws Exception {
    try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(input));
        ByteArrayOutputStream output=new ByteArrayOutputStream()){
      Sheet sheet=book.getSheet(sheetName);
      assertNotNull(sheet);
      Row target=sheet.getRow(row);
      if(target==null)target=sheet.createRow(row);
      Cell cell=target.getCell(col);
      if(cell==null)cell=target.createCell(col);
      if(formula)cell.setCellFormula(newValue);
      else cell.setCellValue(newValue);
      book.write(output);
      return output.toByteArray();
    }
  }
  private static void rejected(byte[] file,String code){
    var failure=assertThrows(ResponseStatusException.class,
      ()->FormXlsxCodec.parse(file,fields()));
    assertTrue(failure.getReason().contains(code),failure.getReason());
  }

  @Test
  void twoSheetsRoundTripAndThreeStylesKeepExactCanonicalValues() throws Exception {
    for(var style:FormXlsxCodec.Style.values()){
      byte[] file=FormXlsxCodec.export(envelope(style),fields());
      try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(file))){
        assertEquals(2,book.getNumberOfSheets());
        assertNotNull(book.getSheet("REPORT"));
        assertNotNull(book.getSheet("PARAMETERS"));
        assertEquals("fieldKey",book.getSheet("PARAMETERS").getRow(13).getCell(0).getStringCellValue());
        assertEquals("MONEY",book.getSheet("PARAMETERS").getRow(14).getCell(2).getStringCellValue());
        assertEquals("KZT",book.getSheet("PARAMETERS").getRow(14).getCell(3).getStringCellValue());
      }
      var parsed=FormXlsxCodec.parse(file,fields());
      assertEquals(style,parsed.envelope().style());
      assertEquals(SHA,parsed.envelope().contentSha256());
      assertEquals(INSTANCE,parsed.envelope().instanceId());
      assertEquals(Map.of("netRevenue","125000.50","cogs","70000","description",""),
        parsed.values());
    }
  }

  @Test
  void legitimateValueEditRetainsBindings() throws Exception {
    byte[] file=FormXlsxCodec.export(envelope(FormXlsxCodec.Style.EXCEL),fields());
    var parsed=FormXlsxCodec.parse(mutate(file,"REPORT",4,1,"150000.25",false),fields());
    assertEquals("150000.25",parsed.values().get("netRevenue"));
    assertEquals("70000",parsed.values().get("cogs"));
  }

  @Test
  void changingFieldTypeUnitOrParserInParametersIsRejected() throws Exception {
    byte[] file=FormXlsxCodec.export(envelope(FormXlsxCodec.Style.EXCEL),fields());
    rejected(mutate(file,"PARAMETERS",14,2,"STRING",false),"XLSX_FIELD_TYPE_CHANGED");
    rejected(mutate(file,"PARAMETERS",14,3,"USD",false),"XLSX_UNIT_CHANGED");
    rejected(mutate(file,"PARAMETERS",14,5,"IGNORE_VALIDATION",false),"XLSX_PARSER_CHANGED");
    rejected(mutate(file,"PARAMETERS",14,4,"false",false),"XLSX_REQUIRED_CHANGED");
    rejected(mutate(file,"PARAMETERS",14,1,"B999",false),"XLSX_CELL_MAPPING_CHANGED");
  }

  @Test
  void changingFieldCodeOrInsertingUnboundCellIsRejected() throws Exception {
    byte[] file=FormXlsxCodec.export(envelope(FormXlsxCodec.Style.CLASSIC),fields());
    // Human labels are intentionally cosmetic and do not define a binding.
    var renamed=FormXlsxCodec.parse(mutate(file,"REPORT",4,0,"Другой заголовок",false),fields());
    assertEquals("125000.50",renamed.values().get("netRevenue"));
    rejected(mutate(file,"PARAMETERS",14,0,"otherMetric",false),"XLSX_FIELD_BINDING_CHANGED");
    rejected(mutate(file,"REPORT",12,3,"hidden financial value",false),"XLSX_UNBOUND_REPORT_CELL");
    rejected(mutate(file,"PARAMETERS",24,5,"rogue metadata",false),"XLSX_UNBOUND_PARAMETER_CELL");
  }

  @Test
  void localizedHeadingsAndHighlightedEditableCellsDoNotChangeInputMappings() throws Exception {
    var translated=List.of(
      new FormXlsxCodec.Entry("netRevenue","125000.50","MONEY","KZT",true,
          "Выручка"),
      new FormXlsxCodec.Entry("cogs","70000","MONEY","KZT",true,
          "Себестоимость"),
      new FormXlsxCodec.Entry("description","","STRING","",false,
          "Примечание"));
    byte[] file=FormXlsxCodec.export(envelope(FormXlsxCodec.Style.EXCEL),translated,
      new FormXlsxCodec.Display("ru","Отчёт о прибылях и убытках"));
    try(Workbook workbook=WorkbookFactory.create(new ByteArrayInputStream(file))){
      var report=workbook.getSheet("REPORT");
      assertEquals("FIN Form Library — Отчёт о прибылях и убытках",
          report.getRow(0).getCell(0).getStringCellValue());
      assertEquals("Выручка",report.getRow(4).getCell(0).getStringCellValue());
      assertEquals("125000.50",report.getRow(4).getCell(1).getStringCellValue());
      assertNull(report.getRow(4).getCell(2));
      assertEquals("B5",workbook.getSheet("PARAMETERS")
          .getRow(14).getCell(1).getStringCellValue());
      assertEquals("ru",workbook.getSheet("PARAMETERS")
          .getRow(9).getCell(1).getStringCellValue());
      assertNotEquals(report.getRow(4).getCell(1).getCellStyle().getFillForegroundColor(),
          report.getRow(6).getCell(1).getCellStyle().getFillForegroundColor());
    }
    assertEquals("125000.50",FormXlsxCodec.parse(file,fields()).values().get("netRevenue"));
  }

  @Test
  void translatedLabelsInAllLocalesRemainDisplayOnly() {
    for(String locale:List.of("ru","kk","en","zh-Hans")){
      var display=new FormXlsxCodec.Display(locale,"Report title "+locale);
      byte[] output=FormXlsxCodec.export(envelope(FormXlsxCodec.Style.CLASSIC),fields(),display);
      assertEquals(SHA,FormXlsxCodec.parse(output,fields()).envelope().contentSha256());
    }
  }

  @Test
  void rejectsExcelFormulasInsteadOfEvaluatingThem() throws Exception {
    byte[] file=FormXlsxCodec.export(envelope(FormXlsxCodec.Style.EXCEL),fields());
    rejected(mutate(file,"REPORT",4,1,"SUM(1,2)",true),"XLSX_TEXT_CELL_REQUIRED");
  }

  @Test
  void wrongServerFieldContractCannotBeUsedForParsing(){
    byte[] file=FormXlsxCodec.export(envelope(FormXlsxCodec.Style.CLASSIC),fields());
    var altered=List.of(
      new FormXlsxCodec.Entry("netRevenue","125000.50","DECIMAL","KZT",true),
      fields().get(1),fields().get(2));
    var error=assertThrows(ResponseStatusException.class,
      ()->FormXlsxCodec.parse(file,altered));
    assertEquals("XLSX_FIELD_TYPE_CHANGED",error.getReason());
  }

  @Test
  void rejectsOversizedOrMissingInput(){
    var error=assertThrows(ResponseStatusException.class,
      ()->FormXlsxCodec.parse(new byte[FormXlsxCodec.MAX_BYTES+1],fields()));
    assertEquals("XLSX_FILE_SIZE_INVALID",error.getReason());
  }
}
