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
  public record ReportContext(String periodType,String periodCode,String periodStart,
       String periodEndExclusive,String currency,String reportingBasis,String coverage,
       String dataSpace,String formVersionLabel,String workflowStatus) {
    public ReportContext {
      for(String part:List.of(periodType,periodCode,periodStart,periodEndExclusive,
          currency,reportingBasis,coverage,dataSpace,formVersionLabel,workflowStatus))
        if(part==null||part.isBlank())throw invalid("XLSX_REPORT_CONTEXT_REQUIRED");
    }
  }
  public record Parsed(Envelope envelope,Map<String,String> values,String displayLocale,
                       ReportContext reportContext) {}
  private static final String[] CONTEXT_KEYS={
    "periodType","periodCode","periodStart","periodEndExclusive","currency",
    "reportingBasis","coverage","dataSpace","formVersionLabel","workflowStatus"};
  private static List<String> contextValues(ReportContext c){
    return List.of(c.periodType(),c.periodCode(),c.periodStart(),c.periodEndExclusive(),
      c.currency(),c.reportingBasis(),c.coverage(),c.dataSpace(),c.formVersionLabel(),
      c.workflowStatus());
  }
  private static String heading(String locale,String ru,String kk,String en,String zh){
    return switch(locale){
      case "ru" -> ru; case "kk" -> kk; case "en" -> en; case "zh-Hans" -> zh;
      default -> throw invalid("XLSX_LOCALE_UNSUPPORTED");
    };
  }
  private static String periodLabel(String locale,String type){
    String[] label=switch(type){
      case "D" -> new String[]{"День","Күн","Day","日"};
      case "W" -> new String[]{"Неделя","Апта","Week","周"};
      case "DEC" -> new String[]{"Декада","Онкүндік","Ten-day period","旬"};
      case "M" -> new String[]{"Месяц","Ай","Month","月"};
      case "Q" -> new String[]{"Квартал","Тоқсан","Quarter","季度"};
      case "H" -> new String[]{"Полугодие","Жартыжылдық","Half-year","半年"};
      case "Y" -> new String[]{"Год","Жыл","Year","年"};
      default -> new String[]{type,type,type,type};
    };
    return switch(locale){
      case "ru" -> label[0];case "kk" -> label[1];case "en" -> label[2];
      case "zh-Hans" -> label[3];default -> throw invalid("XLSX_LOCALE_UNSUPPORTED");
    };
  }
  private static String periodSummary(Display display,ReportContext context){
    return heading(display.locale(),"Период","Кезең","Period","期间")+": "+
      context.periodCode()+" ("+periodLabel(display.locale(),context.periodType())+")";
  }
  private static String currencySummary(Display display,ReportContext context){
    return heading(display.locale(),"Валюта","Валюта","Currency","币种")+": "+context.currency();
  }
  private static String datesSummary(Display display,ReportContext context){
    String end=java.time.LocalDate.parse(context.periodEndExclusive()).minusDays(1).toString();
    return heading(display.locale(),"Даты","Мерзімі","Dates","日期")+": "+
      context.periodStart()+" — "+end;
  }
  private static String basisSummary(Display display,ReportContext context){
    return heading(display.locale(),"Основа","Есеп негізі","Basis","编制基础")+": "+
      context.reportingBasis()+" | "+context.formVersionLabel()+" | "+context.workflowStatus();
  }
  private static String revisionSummary(Display display,Envelope envelope,ReportContext context){
    return heading(display.locale(),"Ревизия","Редакция","Revision","修订")+": "+
      envelope.revision()+" | "+context.formVersionLabel()+" | "+envelope.style();
  }


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
    return export(envelope,entries,display,null);
  }

  public static byte[] export(Envelope envelope,List<Entry> entries,Display display,
                              ReportContext context) {
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
      report.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(
        0,0,0,envelope.style()==Style.DASHBOARD?4:1));
      if(context==null){
        write(report,1,0,"Style: "+envelope.style()+", revision: "+envelope.revision(),null);
        write(report,2,0,"Input cells are highlighted; change values only. PARAMETERS is read-only.",null);
      }else{
        write(report,1,0,periodSummary(display,context),bold);
        write(report,1,1,currencySummary(display,context),bold);
        write(report,2,0,datesSummary(display,context),null);
        write(report,2,1,basisSummary(display,context),null);
        if(envelope.style()==Style.DASHBOARD){
          write(report,1,3,heading(display.locale(),"Тип","Түрі","Type","类型")+
            ": "+context.periodType(),bold);
          write(report,1,4,revisionSummary(display,envelope,context),bold);
          write(report,2,3,heading(display.locale(),"Охват","Қамту","Coverage","范围")+
            ": "+context.coverage(),null);
        }
      }
      if(envelope.style()==Style.EXCEL){
        write(report,3,0,heading(display.locale(),"Показатель","Көрсеткіш","Indicator","指标"),title);
        write(report,3,1,heading(display.locale(),"Значение","Мәні","Value","数值"),title);
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
      if(context!=null){
        List<String> attrs=contextValues(context);
        for(int i=0;i<CONTEXT_KEYS.length;i++){
          int row=11+i/5,col=(i%5)*2;
          write(params,row,col,CONTEXT_KEYS[i],bold);
          write(params,row,col+1,attrs.get(i),metadataStyle);
        }
      }
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
      report.getRow(0).setHeightInPoints(27);
      report.getRow(1).setHeightInPoints(27);
      report.getRow(2).setHeightInPoints(27);
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
      Display display=new Display(locale,read(params,10,1));
      ReportContext context=null;
      if(!read(params,11,0).isBlank()){
        List<String> attr=new ArrayList<>();
        for(int i=0;i<CONTEXT_KEYS.length;i++){
          int row=11+i/5,col=(i%5)*2;
          expect(read(params,row,col),CONTEXT_KEYS[i],"XLSX_CONTEXT_KEY_CHANGED");
          String value=read(params,row,col+1);
          if(value.isBlank())throw invalid("XLSX_REPORT_CONTEXT_REQUIRED");
          attr.add(value);
        }
        context=new ReportContext(attr.get(0),attr.get(1),attr.get(2),attr.get(3),
          attr.get(4),attr.get(5),attr.get(6),attr.get(7),attr.get(8),attr.get(9));
        expect(read(report,1,0),periodSummary(display,context),"XLSX_PERIOD_HEADER_CHANGED");
        expect(read(report,1,1),currencySummary(display,context),"XLSX_CURRENCY_HEADER_CHANGED");
        expect(read(report,2,0),datesSummary(display,context),"XLSX_DATES_HEADER_CHANGED");
        expect(read(report,2,1),basisSummary(display,context),"XLSX_BASIS_HEADER_CHANGED");
        if(style==Style.DASHBOARD){
          expect(read(report,1,3),heading(locale,"Тип","Түрі","Type","类型")+
            ": "+context.periodType(),"XLSX_PERIOD_TYPE_HEADER_CHANGED");
          expect(read(report,1,4),revisionSummary(display,new Envelope(instance,organization,
            version,code,revision,sha,style),context),"XLSX_REVISION_HEADER_CHANGED");
          expect(read(report,2,3),heading(locale,"Охват","Қамту","Coverage","范围")+
            ": "+context.coverage(),"XLSX_COVERAGE_HEADER_CHANGED");
        }
      }else{
        expect(read(report,1,0),"Style: "+style+", revision: "+revision,"XLSX_HEADER_MISMATCH");
      }
      if(style==Style.EXCEL){
        expect(read(report,3,0),heading(locale,"Показатель","Көрсеткіш","Indicator","指标"),
          "XLSX_HEADER_MISMATCH");
        expect(read(report,3,1),heading(locale,"Значение","Мәні","Value","数值"),
          "XLSX_HEADER_MISMATCH");
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
          boolean fixed=((r<keys.length || r==9 || r==10) && col<=1)
             || (context!=null && (r==11||r==12) && col<10);
          boolean header=r==FIRST_FIELD_ROW-1 && col<columns.length;
          boolean binding=allowedParams.contains(r+":"+col);
          if(c.getCellType()!=CellType.BLANK && !(fixed||header||binding))
            throw invalid("XLSX_UNBOUND_PARAMETER_CELL");
        }
      }
      return new Parsed(new Envelope(instance,organization,version,code,revision,sha,style),
          Collections.unmodifiableMap(values),locale,context);
    }catch(ResponseStatusException ex){throw ex;}
     catch(Exception ex){throw invalid("XLSX_INVALID_OR_CORRUPT");}
  }
}
