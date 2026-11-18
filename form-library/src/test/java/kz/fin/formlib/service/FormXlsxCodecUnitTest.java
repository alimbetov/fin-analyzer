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
    var parsed=FormXlsxCodec.parse(mutate(file,"REPORT",4,3,"150000.25",false),fields());
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
    var renamed=FormXlsxCodec.parse(mutate(file,"REPORT",4,2,"Другой заголовок",false),fields());
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
      assertEquals("Выручка",report.getRow(4).getCell(2).getStringCellValue());
      assertEquals("125000.50",report.getRow(4).getCell(3).getStringCellValue());
      assertEquals("1",report.getRow(4).getCell(1).getStringCellValue());
      assertEquals("D5",workbook.getSheet("PARAMETERS")
          .getRow(14).getCell(1).getStringCellValue());
      assertEquals("ru",workbook.getSheet("PARAMETERS")
          .getRow(9).getCell(1).getStringCellValue());
      assertNotEquals(report.getRow(4).getCell(3).getCellStyle().getFillForegroundColor(),
          report.getRow(6).getCell(3).getCellStyle().getFillForegroundColor());
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
  void accountingPeriodCurrencyAndBasisAreVisibleAndVerifiedForEveryStyle()
      throws Exception {
    var context=new FormXlsxCodec.ReportContext("M","2026-11","2026-11-01",
      "2026-12-01","KZT","MANAGEMENT","PERIOD_ONLY","SYNTHETIC",
      "F02-V1","DRAFT");
    for(var style:FormXlsxCodec.Style.values()){
      var display=new FormXlsxCodec.Display("kk","Пайда мен залал туралы есеп");
      byte[] file=FormXlsxCodec.export(envelope(style),fields(),display,context);
      try(var book=WorkbookFactory.create(new ByteArrayInputStream(file))){
        var report=book.getSheet("REPORT");
        assertTrue(report.getRow(1).getCell(0).getStringCellValue().contains("2026-11"));
        assertTrue(report.getRow(1).getCell(0).getStringCellValue().contains("Ай"));
        assertTrue(report.getRow(1).getCell(2).getStringCellValue().contains("KZT"));
        assertTrue(report.getRow(2).getCell(0).getStringCellValue().contains("2026-11-30"));
        assertTrue(report.getRow(2).getCell(2).getStringCellValue().contains("MANAGEMENT"));
        assertTrue(report.getRow(2).getCell(2).getStringCellValue().contains("F02-V1"));
        if(style==FormXlsxCodec.Style.EXCEL){
          assertEquals("№",report.getRow(3).getCell(1).getStringCellValue());
          assertEquals("Көрсеткіш",report.getRow(3).getCell(2).getStringCellValue());
          assertEquals("Мәні",report.getRow(3).getCell(3).getStringCellValue());
        }
        var params=book.getSheet("PARAMETERS");
        assertEquals("periodType",params.getRow(11).getCell(0).getStringCellValue());
        assertEquals("M",params.getRow(11).getCell(1).getStringCellValue());
        assertEquals("currency",params.getRow(11).getCell(8).getStringCellValue());
        assertEquals("KZT",params.getRow(11).getCell(9).getStringCellValue());
      }
      var parsed=FormXlsxCodec.parse(file,fields());
      assertEquals(context,parsed.reportContext());
      assertEquals("125000.50",parsed.values().get("netRevenue"));
      rejected(mutate(file,"PARAMETERS",11,9,"USD",false),
          "XLSX_CURRENCY_HEADER_CHANGED");
      rejected(mutate(file,"REPORT",1,2,"USD",false),
          "XLSX_CURRENCY_HEADER_CHANGED");
      rejected(mutate(file,"PARAMETERS",11,3,"2026-10",false),
          "XLSX_PERIOD_HEADER_CHANGED");
    }
  }

  @Test
  void sequentialMetricNumbersAreCenteredAndProtectedInAllStyles() throws Exception {
    for(var style:FormXlsxCodec.Style.values()){
      byte[] file=FormXlsxCodec.export(envelope(style),fields());
      try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(file))){
        var report=book.getSheet("REPORT");
        int[][] cellPositions=style==FormXlsxCodec.Style.DASHBOARD
            ?new int[][]{{4,1,2,3},{4,5,6,7},{5,1,2,3}}
            :new int[][]{{4,1,2,3},{5,1,2,3},{6,1,2,3}};
        for(int i=0;i<cellPositions.length;i++){
          int[] pos=cellPositions[i];
          var number=report.getRow(pos[0]).getCell(pos[1]);
          assertEquals(Integer.toString(i+1),number.getStringCellValue());
          assertEquals(HorizontalAlignment.CENTER,number.getCellStyle().getAlignment());
          assertEquals(fields().get(i).key(),
            report.getRow(pos[0]).getCell(pos[2]).getStringCellValue());
          assertEquals(fields().get(i).value(),
            report.getRow(pos[0]).getCell(pos[3]).getStringCellValue());
        }
        assertEquals("FIN-FORM-XLSX-V4",book.getSheet("PARAMETERS")
          .getRow(0).getCell(1).getStringCellValue());
      }
      assertEquals(fields().size(),FormXlsxCodec.parse(file,fields()).values().size());
      rejected(mutate(file,"REPORT",4,1,"99",false),"XLSX_ROW_NUMBER_CHANGED");
    }
  }

  @Test
  void oldV2ExportsRemainImportableWithTheirOriginalCellCoordinates() throws Exception {
    byte[] newFile=FormXlsxCodec.export(envelope(FormXlsxCodec.Style.EXCEL),fields());
    try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(newFile));
        ByteArrayOutputStream output=new ByteArrayOutputStream()){
      var report=book.getSheet("REPORT");
      for(int index=0;index<fields().size();index++){
        var row=report.getRow(index+4);
        String label=row.getCell(2).getStringCellValue();
        String value=row.getCell(3).getStringCellValue();
        row.removeCell(row.getCell(3));
        row.removeCell(row.getCell(2));
        row.getCell(1).setCellValue(value);
        row.createCell(0,CellType.STRING).setCellValue(label);
      }
      report.getRow(3).createCell(0,CellType.STRING).setCellValue("Field key");
      report.getRow(3).getCell(1).setCellValue("Value");
      report.getRow(3).removeCell(report.getRow(3).getCell(2));
      report.getRow(3).removeCell(report.getRow(3).getCell(3));
      // Recreate V2 metadata positioning (A/B) from current V4 (A/C).
      report.getRow(1).createCell(1,CellType.STRING).setCellValue(
        report.getRow(1).getCell(2).getStringCellValue());
      report.getRow(1).removeCell(report.getRow(1).getCell(2));
      report.getRow(2).createCell(1,CellType.STRING).setCellValue(
        report.getRow(2).getCell(2).getStringCellValue());
      report.getRow(2).removeCell(report.getRow(2).getCell(2));
      var params=book.getSheet("PARAMETERS");
      params.getRow(0).getCell(1).setCellValue("FIN-FORM-XLSX-V2");
      for(int i=0;i<fields().size();i++)
        params.getRow(14+i).getCell(1).setCellValue("B"+(i+5));
      book.write(output);
      assertEquals(fields().size(),FormXlsxCodec.parse(output.toByteArray(),fields()).values().size());
    }
  }

  @Test
  void v4ReportKeepsReadableThirtyCharacterMetadataColumnAndCenteredTable()
      throws Exception {
    for(var style:FormXlsxCodec.Style.values()){
      var context=new FormXlsxCodec.ReportContext("M","2026-11","2026-11-01",
        "2026-12-01","KZT","MANAGEMENT","PERIOD_ONLY","SYNTHETIC",
        "FIN-FORM-V2","DRAFT");
      byte[] bytes=FormXlsxCodec.export(envelope(style),fields(),
        new FormXlsxCodec.Display("ru","Отчёт о прибылях и убытках"),context);
      try(Workbook workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
        Sheet sheet=workbook.getSheet("REPORT");
        assertEquals(30*256,sheet.getColumnWidth(0));
        assertEquals("№",sheet.getRow(3).getCell(1).getStringCellValue());
        assertEquals("Показатель",sheet.getRow(3).getCell(2).getStringCellValue());
        assertEquals("Значение",sheet.getRow(3).getCell(3).getStringCellValue());
        assertEquals("1",sheet.getRow(4).getCell(1).getStringCellValue());
        assertEquals("125000.50",sheet.getRow(4).getCell(3).getStringCellValue());
        assertEquals("D5",workbook.getSheet("PARAMETERS").getRow(14)
          .getCell(1).getStringCellValue());
        assertTrue(sheet.getRow(1).getCell(0).getStringCellValue().contains("2026-11"));
        assertTrue(sheet.getRow(1).getCell(2).getStringCellValue().contains("KZT"));
        assertEquals("FIN-FORM-XLSX-V4",workbook.getSheet("PARAMETERS")
          .getRow(0).getCell(1).getStringCellValue());
        if(style==FormXlsxCodec.Style.DASHBOARD) {
          assertEquals("№",sheet.getRow(3).getCell(5).getStringCellValue());
          assertEquals("2",sheet.getRow(4).getCell(5).getStringCellValue());
          assertEquals("70000",sheet.getRow(4).getCell(7).getStringCellValue());
        }
      }
      assertEquals(3,FormXlsxCodec.parse(bytes,fields()).values().size());
    }
  }

  @Test
  void previousV3NumberedLayoutRemainsReadable() throws Exception {
    byte[] v4=FormXlsxCodec.export(envelope(FormXlsxCodec.Style.EXCEL),fields());
    try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(v4));
        ByteArrayOutputStream out=new ByteArrayOutputStream()){
      Sheet sheet=book.getSheet("REPORT"), params=book.getSheet("PARAMETERS");
      for(int i=0;i<fields().size();i++){
        Row row=sheet.getRow(4+i);
        String value=row.getCell(3).getStringCellValue();
        String label=row.getCell(2).getStringCellValue();
        row.removeCell(row.getCell(3));
        row.getCell(2).setCellValue(value);
        row.getCell(1).setCellValue(label);
        row.createCell(0,CellType.STRING).setCellValue(Integer.toString(i+1));
        params.getRow(14+i).getCell(1).setCellValue("C"+(5+i));
      }
      sheet.getRow(3).getCell(3).setCellValue("");
      sheet.getRow(3).getCell(2).setCellValue("Значение");
      sheet.getRow(3).getCell(1).setCellValue("Показатель");
      sheet.getRow(3).createCell(0,CellType.STRING).setCellValue("№");
      params.getRow(0).getCell(1).setCellValue("FIN-FORM-XLSX-V3");
      book.write(out);
      var parsed=FormXlsxCodec.parse(out.toByteArray(),fields());
      assertEquals("125000.50",parsed.values().get("netRevenue"));
      assertEquals("70000",parsed.values().get("cogs"));
    }
  }

  @Test
  void rejectsExcelFormulasInsteadOfEvaluatingThem() throws Exception {
    byte[] file=FormXlsxCodec.export(envelope(FormXlsxCodec.Style.EXCEL),fields());
    rejected(mutate(file,"REPORT",4,3,"SUM(1,2)",true),"XLSX_TEXT_CELL_REQUIRED");
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
