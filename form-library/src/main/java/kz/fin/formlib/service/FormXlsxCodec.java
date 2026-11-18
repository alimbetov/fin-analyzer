package kz.fin.formlib.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.*;
import java.util.*;

/**
 * FIN XLSX V2: REPORT (human-facing) + PARAMETERS (machine-readable).
 * Parameters are untrusted assertions and must equal server-pinned field metadata.
 * The workbook never grants authorization or replaces canonical validators.
 */
public final class FormXlsxCodec {
  private FormXlsxCodec() {}
  public static final int MAX_BYTES=2_000_000, MAX_CELLS=20_000;
  private static final String MAGIC="FIN-FORM-XLSX-V2";
  private static final int FIRST_FIELD_ROW=14;
  public enum Style { CLASSIC, EXCEL, DASHBOARD }

  public record Entry(String key,String value,String type,String unit,boolean required,String label) {
    public Entry(String key,String value) { this(key,value,"STRING","",false,key); }
    public Entry(String key,String value,String type,String unit,boolean required) {
      this(key,value,type,unit,required,key);
    }
  }
  public record Display(String locale,String reportTitle) {
    public Display {
      if(!Set.of("ru","kk","en","zh-Hans").contains(locale))
        throw invalid("XLSX_LOCALE_UNSUPPORTED");
      if(reportTitle==null||reportTitle.isBlank())throw invalid("XLSX_REPORT_TITLE_REQUIRED");
    }
  }
  public record Envelope(UUID instanceId,UUID organizationId,UUID formVersionId,
                         String formCode,int revision,String contentSha256,Style style) {}
  public record Parsed(Envelope envelope,Map<String,String> values,String displayLocale) {}

  private static ResponseStatusException invalid(String reason) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,reason);
  }
  public static Style style(String code) {
    try {return Style.valueOf(code.toUpperCase(Locale.ROOT));}
    catch(RuntimeException e){throw invalid("UNSUPPORTED_XLSX_STYLE");}
  }
  private static void write(Sheet sheet,int row,int column,String value,CellStyle style) {
    Row r=sheet.getRow(row);
    if(r==null)r=sheet.createRow(row);
    Cell c=r.createCell(column,CellType.STRING);
    c.setCellValue(value==null?"":value);
    if(style!=null)c.setCellStyle(style);
  }
  private static String read(Sheet sheet,int row,int col) {
    Row r=sheet.getRow(row);
    if(r==null)return "";
    Cell cell=r.getCell(col,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    if(cell==null)return "";
    if(cell.getCellType()!=CellType.STRING)throw invalid("XLSX_TEXT_CELL_REQUIRED");
    return cell.getStringCellValue();
  }
  private static int[] location(Style style,int index) {
    return switch(style){
      case CLASSIC -> new int[]{4+index,0,1};
      case EXCEL -> new int[]{4+index,0,1};
      case DASHBOARD -> new int[]{4+index/2,(index%2)*3,(index%2)*3+1};
    };
  }
  private static void expect(String actual,String expected,String reason) {
    if(!Objects.equals(actual,expected))throw invalid(reason);
  }
  private static String cellRef(int[] position) {
    return new org.apache.poi.ss.util.CellReference(position[0],position[2]).formatAsString();
  }
  private static List<String> metadata(Envelope e,int count) {
    return List.of(MAGIC,e.instanceId().toString(),e.organizationId().toString(),
      e.formVersionId().toString(),e.formCode(),Integer.toString(e.revision()),
      e.contentSha256(),e.style().name(),Integer.toString(count));
  }

  public static byte[] export(Envelope envelope,List<Entry> entries) {
    return export(envelope,entries,new Display("ru",envelope.formCode()));
  }

  public static byte[] export(Envelope envelope,List<Entry> entries,Display display) {
    if(envelope==null||entries==null||entries.isEmpty()||entries.size()>MAX_CELLS)
      throw invalid("XLSX_FIELD_COUNT_INVALID");
    Set<String> names=new HashSet<>();
    for(Entry entry:entries)
      if(entry==null||entry.key()==null||entry.key().isBlank()||!names.add(entry.key()))
        throw invalid("XLSX_DUPLICATE_BINDING");
    try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream output=new ByteArrayOutputStream()){
      Sheet report=book.createSheet("REPORT");
      Sheet params=book.createSheet("PARAMETERS");
      CellStyle text=book.createCellStyle();
      text.setDataFormat(book.createDataFormat().getFormat("@"));
      Font font=book.createFont();font.setBold(true);
      CellStyle bold=book.createCellStyle();bold.setFont(font);
      CellStyle title=book.createCellStyle();
      title.setFont(font);
      title.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
      title.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      Font white=book.createFont();white.setBold(true);white.setColor(IndexedColors.WHITE.getIndex());
      title.setFont(white);
      CellStyle mandatory=book.createCellStyle();
      mandatory.cloneStyleFrom(text);
      mandatory.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
      mandatory.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      mandatory.setBorderBottom(BorderStyle.THIN);
      mandatory.setBottomBorderColor(IndexedColors.GOLD.getIndex());
      CellStyle optional=book.createCellStyle();
      optional.cloneStyleFrom(text);
      optional.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
      optional.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      optional.setBorderBottom(BorderStyle.THIN);
      optional.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
      CellStyle keyStyle=book.createCellStyle();
      keyStyle.setFont(font);
      keyStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      keyStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      CellStyle metadataStyle=book.createCellStyle();
      metadataStyle.cloneStyleFrom(text);
      metadataStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
      metadataStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

      write(report,0,0,"FIN Form Library — "+display.reportTitle(),title);
      write(report,1,0,"Style: "+envelope.style()+", revision: "+envelope.revision(),null);
      write(report,2,0,"Input cells are highlighted; change values only. PARAMETERS is read-only.",null);
      if(envelope.style()==Style.EXCEL){
        write(report,3,0,"Field key",title);write(report,3,1,"Value",title);
      }
      List<String> metadata=metadata(envelope,entries.size());
      String[] metaKeys={"contractFormat","instanceId","organizationId","formVersionId",
        "formCode","expectedRevision","contentSha256","styleCode","fieldCount"};
      for(int i=0;i<metaKeys.length;i++){
        write(params,i,0,metaKeys[i],bold);
        write(params,i,1,metadata.get(i),metadataStyle);
      }
      write(params,9,0,"displayLocale",bold);
      write(params,9,1,display.locale(),metadataStyle);
      write(params,10,0,"reportTitle",bold);
      write(params,10,1,display.reportTitle(),metadataStyle);
      String[] columns={"fieldKey","valueCell","fieldType","unitCode","required","parser"};
      for(int i=0;i<columns.length;i++)write(params,FIRST_FIELD_ROW-1,i,columns[i],title);
      for(int i=0;i<entries.size();i++){
        Entry e=entries.get(i);
        int[] p=location(envelope.style(),i);
        write(report,p[0],p[1],e.label(),keyStyle);
        write(report,p[0],p[2],e.value(),e.required()?mandatory:optional);
        int row=FIRST_FIELD_ROW+i;
        write(params,row,0,e.key(),text);
        write(params,row,1,cellRef(p),text);
        write(params,row,2,e.type(),text);
        write(params,row,3,e.unit(),text);
        write(params,row,4,Boolean.toString(e.required()),text);
        write(params,row,5,"CANONICAL_TEXT",text);
      }
      report.setColumnWidth(0,12000);report.setColumnWidth(1,11500);
      if(envelope.style()==Style.DASHBOARD){
        report.setColumnWidth(2,1600);
        report.setColumnWidth(3,12000);
        report.setColumnWidth(4,11500);
      }
      report.createFreezePane(1,4);
      report.setDisplayGridlines(false);
      params.setColumnWidth(0,11000);params.setColumnWidth(1,13000);
      params.setColumnWidth(2,7500);params.setColumnWidth(3,7500);
      book.write(output);
      if(output.size()>MAX_BYTES)throw invalid("XLSX_EXPORT_TOO_LARGE");
      return output.toByteArray();
    }catch(IOException e){throw invalid("XLSX_EXPORT_FAILED");}
  }

  public static Parsed parse(byte[] bytes,List<Entry> expected) {
    if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)
      throw invalid("XLSX_FILE_SIZE_INVALID");
    if(expected==null||expected.isEmpty()||expected.size()>MAX_CELLS)
      throw invalid("XLSX_FIELD_COUNT_INVALID");
    try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
      if(book.getNumberOfSheets()!=2||book.getSheet("REPORT")==null||
          book.getSheet("PARAMETERS")==null)throw invalid("XLSX_SHEETS_INVALID");
      Sheet params=book.getSheet("PARAMETERS");
      Sheet report=book.getSheet("REPORT");
      String[] keys={"contractFormat","instanceId","organizationId","formVersionId",
          "formCode","expectedRevision","contentSha256","styleCode","fieldCount"};
      for(int i=0;i<keys.length;i++)expect(read(params,i,0),keys[i],"XLSX_PARAMETER_KEY_CHANGED");
      expect(read(params,0,1),MAGIC,"XLSX_MAGIC_INVALID");
      UUID instance=UUID.fromString(read(params,1,1));
      UUID organization=UUID.fromString(read(params,2,1));
      UUID version=UUID.fromString(read(params,3,1));
      String code=read(params,4,1);
      int revision=Integer.parseInt(read(params,5,1));
      String sha=read(params,6,1);
      Style style=style(read(params,7,1));
      int count=Integer.parseInt(read(params,8,1));
      if(count!=expected.size())throw invalid("XLSX_FIELD_COUNT_MISMATCH");
      expect(read(params,9,0),"displayLocale","XLSX_PARAMETER_KEY_CHANGED");
      expect(read(params,10,0),"reportTitle","XLSX_PARAMETER_KEY_CHANGED");
      String locale=read(params,9,1);
      new Display(locale,read(params,10,1));
      expect(read(report,1,0),"Style: "+style+", revision: "+revision,"XLSX_HEADER_MISMATCH");
      if(style==Style.EXCEL){
        expect(read(report,3,0),"Field key","XLSX_HEADER_MISMATCH");
        expect(read(report,3,1),"Value","XLSX_HEADER_MISMATCH");
      }
      String[] columns={"fieldKey","valueCell","fieldType","unitCode","required","parser"};
      for(int i=0;i<columns.length;i++)
        expect(read(params,FIRST_FIELD_ROW-1,i),columns[i],"XLSX_PARAMETER_COLUMNS_CHANGED");

      Map<String,String> values=new LinkedHashMap<>();
      Set<String> allowed=new HashSet<>();
      Set<String> allowedParams=new HashSet<>();
      for(int i=0;i<expected.size();i++){
        Entry e=expected.get(i);
        int[] p=location(style,i);
        int r=FIRST_FIELD_ROW+i;
        expect(read(params,r,0),e.key(),"XLSX_FIELD_BINDING_CHANGED");
        expect(read(params,r,1),cellRef(p),"XLSX_CELL_MAPPING_CHANGED");
        expect(read(params,r,2),e.type(),"XLSX_FIELD_TYPE_CHANGED");
        expect(read(params,r,3),e.unit(),"XLSX_UNIT_CHANGED");
        expect(read(params,r,4),Boolean.toString(e.required()),"XLSX_REQUIRED_CHANGED");
        expect(read(params,r,5),"CANONICAL_TEXT","XLSX_PARSER_CHANGED");
        // Display labels are presentation only: use server-pinned fieldKey and
        // valueCell from PARAMETERS for identity, not translated human captions.
        if(read(report,p[0],p[1]).isBlank())throw invalid("XLSX_DISPLAY_LABEL_REQUIRED");
        allowed.add(p[0]+":"+p[1]);allowed.add(p[0]+":"+p[2]);
        values.put(e.key(),read(report,p[0],p[2]));
        for(int c=0;c<columns.length;c++)allowedParams.add(r+":"+c);
      }
      // Reject cells outside the server-generated value/label positions.
      for(Row row:report){
        if(row.getRowNum()<4)continue;
        for(Cell c:row){
          if(c.getCellType()!=CellType.BLANK &&
              !allowed.contains(row.getRowNum()+":"+c.getColumnIndex()))
            throw invalid("XLSX_UNBOUND_REPORT_CELL");
        }
      }
      for(Row row:params){
        for(Cell c:row){
          int r=row.getRowNum(),col=c.getColumnIndex();
          boolean fixed=(r<keys.length || r==9 || r==10) && col<=1;
          boolean header=r==FIRST_FIELD_ROW-1 && col<columns.length;
          boolean binding=allowedParams.contains(r+":"+col);
          if(c.getCellType()!=CellType.BLANK && !(fixed||header||binding))
            throw invalid("XLSX_UNBOUND_PARAMETER_CELL");
        }
      }
      return new Parsed(new Envelope(instance,organization,version,code,revision,sha,style),
          Collections.unmodifiableMap(values),locale);
    }catch(ResponseStatusException ex){throw ex;}
     catch(Exception ex){throw invalid("XLSX_INVALID_OR_CORRUPT");}
  }
}
