package kz.fin.formlib.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * XLSX UI transport only. The server generates coordinate bindings from the pinned
 * field contract; imported workbook cells never invent field/metric identifiers.
 * Data remains text: Excel NUMERIC cells are rejected to avoid 15-digit truncation.
 */
public final class FormXlsxCodec {
  private FormXlsxCodec() {}
  public static final int MAX_BYTES=2_000_000, MAX_CELLS=20_000;
  public enum Style { CLASSIC, EXCEL, DASHBOARD }
  public record Entry(String key,String value) {}
  public record Envelope(UUID instanceId,UUID organizationId,UUID formVersionId,
                         String formCode,int revision,String contentSha256,Style style) {}
  public record Parsed(Envelope envelope,Map<String,String> values) {}

  private static ResponseStatusException invalid(String reason) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,reason);
  }
  public static Style style(String code) {
    try {return Style.valueOf(code.toUpperCase(Locale.ROOT));}
    catch(RuntimeException e){throw invalid("UNSUPPORTED_XLSX_STYLE");}
  }
  private static void write(Sheet sheet,int row,int column,String value,CellStyle format) {
    Row r=sheet.getRow(row);
    if(r==null) r=sheet.createRow(row);
    Cell c=r.createCell(column,CellType.STRING);
    c.setCellValue(value==null?"":value);
    if(format!=null)c.setCellStyle(format);
  }
  private static String cell(Sheet sheet,int row,int column) {
    Row r=sheet.getRow(row);
    if(r==null)return "";
    Cell c=r.getCell(column,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    if(c==null)return "";
    if(c.getCellType()!=CellType.STRING)throw invalid("XLSX_TEXT_CELL_REQUIRED");
    return c.getStringCellValue();
  }
  private static int[] position(Style style,int index) {
    return switch(style) {
      case CLASSIC -> new int[]{4+index,0,1};
      case EXCEL -> new int[]{4+index,0,2};
      case DASHBOARD -> new int[]{4+index/2,(index%2)*3,(index%2)*3+1};
    };
  }

  public static byte[] export(Envelope envelope,List<Entry> entries) {
    if(entries.isEmpty()||entries.size()>MAX_CELLS)throw invalid("XLSX_FIELD_COUNT_INVALID");
    Set<String> keys=new HashSet<>();
    for(Entry entry:entries)
      if(entry.key()==null||entry.key().isBlank()||!keys.add(entry.key()))
        throw invalid("XLSX_DUPLICATE_BINDING");
    try(XSSFWorkbook workbook=new XSSFWorkbook();
        ByteArrayOutputStream buffer=new ByteArrayOutputStream()) {
      Sheet report=workbook.createSheet("Report");
      Sheet meta=workbook.createSheet("_FIN_META");
      CellStyle text=workbook.createCellStyle();
      text.setDataFormat(workbook.createDataFormat().getFormat("@"));
      Font bold=workbook.createFont();bold.setBold(true);
      CellStyle heading=workbook.createCellStyle();heading.setFont(bold);
      write(report,0,0,"FIN Form Library — "+envelope.formCode(),heading);
      write(report,1,0,"Style: "+envelope.style()+", revision: "+envelope.revision(),null);
      write(report,2,0,"Editable text values. Do not move rows, rename keys or enter formulas.",null);
      if(envelope.style()==Style.EXCEL) {
        write(report,3,0,"Field key",heading);
        write(report,3,2,"Value",heading);
      }
      for(int i=0;i<entries.size();i++){
        Entry entry=entries.get(i);
        int[] p=position(envelope.style(),i);
        write(report,p[0],p[1],entry.key(),heading);
        write(report,p[0],p[2],entry.value(),text);
      }
      report.setColumnWidth(0,12000);
      report.setColumnWidth(1,9500);
      if(envelope.style()==Style.EXCEL)report.setColumnWidth(2,11000);
      if(envelope.style()==Style.DASHBOARD){
        report.setColumnWidth(3,12000);
        report.setColumnWidth(4,9500);
      }
      List<String> attributes=List.of("FIN-FORM-XLSX-V1",envelope.instanceId().toString(),
        envelope.organizationId().toString(),envelope.formVersionId().toString(),
        envelope.formCode(),Integer.toString(envelope.revision()),
        envelope.contentSha256(),envelope.style().name(),
        Integer.toString(entries.size()));
      for(int i=0;i<attributes.size();i++)write(meta,i,0,attributes.get(i),null);
      workbook.setSheetHidden(workbook.getSheetIndex(meta),true);
      workbook.write(buffer);
      if(buffer.size()>MAX_BYTES)throw invalid("XLSX_EXPORT_TOO_LARGE");
      return buffer.toByteArray();
    }catch(IOException e){throw invalid("XLSX_EXPORT_FAILED");}
  }

  public static Parsed parse(byte[] content,List<Entry> expected) {
    if(content==null||content.length==0||content.length>MAX_BYTES)throw invalid("XLSX_FILE_SIZE_INVALID");
    if(expected.isEmpty()||expected.size()>MAX_CELLS)throw invalid("XLSX_FIELD_COUNT_INVALID");
    try(Workbook workbook=WorkbookFactory.create(new ByteArrayInputStream(content))){
      if(workbook.getNumberOfSheets()!=2||workbook.getSheet("Report")==null
          ||workbook.getSheet("_FIN_META")==null)throw invalid("XLSX_SHEETS_INVALID");
      Sheet meta=workbook.getSheet("_FIN_META"), report=workbook.getSheet("Report");
      if(!workbook.isSheetHidden(workbook.getSheetIndex(meta)))throw invalid("XLSX_METADATA_NOT_HIDDEN");
      if(!"FIN-FORM-XLSX-V1".equals(cell(meta,0,0)))throw invalid("XLSX_MAGIC_INVALID");
      UUID instance=UUID.fromString(cell(meta,1,0));
      UUID organization=UUID.fromString(cell(meta,2,0));
      UUID version=UUID.fromString(cell(meta,3,0));
      String formCode=cell(meta,4,0);
      int revision=Integer.parseInt(cell(meta,5,0));
      String contentSha=cell(meta,6,0);
      Style style=style(cell(meta,7,0));
      int count=Integer.parseInt(cell(meta,8,0));
      if(count!=expected.size())throw invalid("XLSX_FIELD_COUNT_MISMATCH");
      if(!cell(report,0,0).equals("FIN Form Library — "+formCode))
        throw invalid("XLSX_HEADER_MISMATCH");
      if(!cell(report,1,0).equals("Style: "+style+", revision: "+revision))
        throw invalid("XLSX_HEADER_MISMATCH");
      if(style==Style.EXCEL&&(!"Field key".equals(cell(report,3,0))
            ||!"Value".equals(cell(report,3,2))))throw invalid("XLSX_HEADER_MISMATCH");
      Map<String,String> values=new LinkedHashMap<>();
      Set<String> validCoords=new HashSet<>();
      for(int i=0;i<expected.size();i++) {
        Entry binding=expected.get(i);
        int[] p=position(style,i);
        if(!binding.key().equals(cell(report,p[0],p[1])))
          throw invalid("XLSX_BINDING_CHANGED");
        String address=p[0]+":"+p[2];
        if(!validCoords.add(address))throw invalid("XLSX_BINDING_OVERLAP");
        values.put(binding.key(),cell(report,p[0],p[2]));
      }
      // Imported cells may only occupy the exact deterministic template coordinates.
      // No unnoticed extra values, formulas, hidden rows or injected columns.
      for(Row row:report){
        if(row.getRowNum()<4)continue;
        for(Cell c:row){
          if(c.getCellType()==CellType.BLANK)continue;
          String key=row.getRowNum()+":"+c.getColumnIndex();
          boolean label=false;
          for(int i=0;i<expected.size();i++){
            int[] p=position(style,i);
            if(p[0]==row.getRowNum()&&p[1]==c.getColumnIndex()){label=true;break;}
          }
          if(!validCoords.contains(key)&&!label)throw invalid("XLSX_UNBOUND_CELL");
        }
      }
      // Meta is NOT a trust boundary; service compares these values to fin_intake.
      return new Parsed(new Envelope(instance,organization,version,formCode,
        revision,contentSha,style),Collections.unmodifiableMap(values));
    }catch(ResponseStatusException e){throw e;}
     catch(Exception e){throw invalid("XLSX_INVALID_OR_CORRUPT");}
  }
}
