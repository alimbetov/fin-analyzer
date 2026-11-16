package kz.fin.backend.persistence;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Bounded, server-side XLSX v1 parser and issuer for five pilot form families.
 * XLSX metadata is a schema contract, NOT a digital signature or source approval.
 * A SHA-256 of original bytes is stored separately by intake service / PostgreSQL.
 */
@Service
public final class PilotXlsxTemplateService {
    public static final String VERSION="FIN-PILOT-V1";
    private static final String FORMAT="FIN-PILOT-XLSX-1";
    private static final int MAX_BYTES=2_000_000,MAX_ROWS=512,MAX_ZIP_BYTES=16_000_000;
    private static final String DATA="FIN_Data",META="_FIN_Metadata";
    private static final List<String> KEYS=List.of(
            "FIN_FORMAT","FORM_CODE","FORM_VERSION","ORGANIZATION_ID",
            "ENTITY_ID","BUSINESS_SCOPE_ID","REPORTING_BASIS","CURRENCY","SCALE");
    private static final Map<String,List<String>> FIELDS=Map.of(
            "F01",List.of("assets","liabilities","equity","cash","receivables"),
            "F02",List.of("netRevenue","cogs","operatingExpenses"),
            "F03",List.of("operatingCash","investingCash","financingCash"),
            "DF-CSH-01",List.of("receipt"),
            "DF-CSH-02",List.of("payment"));
    private static final Map<String,String> ROW_IDS=Map.of(
            "F01","balance","F02","pnl","F03","cf");
    private static final String LIQUIDITY_VERSION="FIN-LIQUIDITY-V1";
    private static final List<String> F01_LIQUIDITY_FIELDS=List.of(
            "assets","liabilities","equity","cash","receivables",
            "currentAssets","currentLiabilities");

    public record Context(UUID organizationId,UUID entityId,UUID businessScopeId,
                          String formCode,String reportingBasis,String currency,int scale,
                          String formVersion){
        // Preserve every legacy call site and the old template's exact identity.
        public Context(UUID org,UUID entity,UUID scope,String form,String basis,
                       String currency,int scale){
            this(org,entity,scope,form,basis,currency,scale,VERSION);
        }
        public Context {
            Objects.requireNonNull(organizationId);Objects.requireNonNull(entityId);
            Objects.requireNonNull(businessScopeId);
            if(!FIELDS.containsKey(formCode)||!Set.of("MANAGEMENT","SEPARATE","CONSOLIDATED").contains(reportingBasis)
                    ||currency==null||!currency.matches("[A-Z]{3}")
                    ||scale!=1&&scale!=1000
                    ||!(VERSION.equals(formVersion)
                         || ("F01".equals(formCode)&&LIQUIDITY_VERSION.equals(formVersion))))
                throw new IllegalArgumentException("XLSX_UNSUPPORTED_CONTEXT");
        }
    }
    private static List<String> fields(Context c){
        return LIQUIDITY_VERSION.equals(c.formVersion())
                ? F01_LIQUIDITY_FIELDS : FIELDS.get(c.formCode());
    }
    private static String[] metadata(Context c){
        return new String[]{FORMAT,c.formCode(),c.formVersion(),c.organizationId().toString(),
                c.entityId().toString(),c.businessScopeId().toString(),
                c.reportingBasis(),c.currency(),Integer.toString(c.scale())};
    }
    public byte[] template(Context context){
        Objects.requireNonNull(context);
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            Sheet data=book.createSheet(DATA);
            Row header=data.createRow(0);
            String[] names={"SOURCE_ROW","SOURCE_FIELD","PERIOD_YYYY_MM","AMOUNT"};
            for(int i=0;i<4;i++)header.createCell(i).setCellValue(names[i]);
            int index=1;
            for(String field:fields(context)){
                Row row=data.createRow(index++);
                row.createCell(0).setCellValue(ROW_IDS.getOrDefault(
                        context.formCode(),context.formCode().equals("DF-CSH-01")?"receipt1":"payment1"));
                row.createCell(1).setCellValue(field);
                row.createCell(2).setCellValue("");
                row.createCell(3).setBlank();
            }
            for(int i=0;i<4;i++)data.setColumnWidth(i,new int[]{28,29,25,25}[i]*256);
            data.createFreezePane(0,1);
            Sheet meta=book.createSheet(META);
            String[] values=metadata(context);
            for(int i=0;i<KEYS.size();i++){
                Row row=meta.createRow(i);
                row.createCell(0).setCellValue(KEYS.get(i));
                row.createCell(1).setCellValue(values[i]);
            }
            book.setSheetVisibility(1,SheetVisibility.VERY_HIDDEN);
            book.write(bytes);
            if(bytes.size()>MAX_BYTES)throw new IllegalArgumentException("XLSX_GENERATED_TOO_LARGE");
            return bytes.toByteArray();
        }catch(IOException e){throw new IllegalStateException("XLSX_TEMPLATE_WRITE_FAILED",e);}
    }

    public List<TrustedFinancialFormMapper.Cell> parse(Context context,byte[] bytes){
        Objects.requireNonNull(context);
        if(bytes==null || bytes.length<100 || bytes.length>MAX_BYTES)
            throw new IllegalArgumentException("XLSX_SIZE_LIMIT");
        inspectZip(bytes);
        try(ByteArrayInputStream input=new ByteArrayInputStream(bytes);
            XSSFWorkbook workbook=new XSSFWorkbook(input)){
            if(workbook.getNumberOfSheets()!=2 || !DATA.equals(workbook.getSheetName(0))
                    || !META.equals(workbook.getSheetName(1)) ||
                    workbook.getSheetVisibility(1)!=SheetVisibility.VERY_HIDDEN)
                throw new IllegalArgumentException("XLSX_TEMPLATE_STRUCTURE_CHANGED");
            for(Sheet sheet:workbook)
                for(Row row:sheet)
                    for(Cell cell:row)
                        if(cell.getCellType()==CellType.FORMULA || cell.getCellType()==CellType.ERROR)
                            throw new IllegalArgumentException("XLSX_FORMULAS_OR_ERRORS_FORBIDDEN");
            Sheet meta=workbook.getSheet(META);
            String[] expected=metadata(context);
            if(meta.getLastRowNum()!=KEYS.size()-1)
                throw new IllegalArgumentException("XLSX_METADATA_CHANGED");
            for(int i=0;i<KEYS.size();i++){
                Row row=meta.getRow(i);
                if(row==null || !KEYS.get(i).equals(text(row.getCell(0)))
                        || !expected[i].equals(text(row.getCell(1)))
                        || row.getLastCellNum()>2)
                    throw new IllegalArgumentException("XLSX_METADATA_MISMATCH");
            }
            Sheet data=workbook.getSheet(DATA);
            Row header=data.getRow(0);
            if(header==null || header.getLastCellNum()>4)
                throw new IllegalArgumentException("XLSX_COLUMNS_CHANGED");
            String[] expectedHeader={"SOURCE_ROW","SOURCE_FIELD","PERIOD_YYYY_MM","AMOUNT"};
            for(int i=0;i<4;i++)
                if(!expectedHeader[i].equals(text(header.getCell(i))))
                    throw new IllegalArgumentException("XLSX_COLUMNS_CHANGED");
            if(data.getLastRowNum()>MAX_ROWS)
                throw new IllegalArgumentException("XLSX_EXCESS_ROWS");
            List<TrustedFinancialFormMapper.Cell> cells=new ArrayList<>();
            for(int i=1;i<=data.getLastRowNum();i++){
                Row row=data.getRow(i);
                if(row==null)continue;
                if(row.getLastCellNum()>4)throw new IllegalArgumentException("XLSX_EXTRA_COLUMNS");
                String rowId=text(row.getCell(0));
                String field=text(row.getCell(1));
                String period=text(row.getCell(2));
                BigDecimal value=amount(row.getCell(3));
                // Unfilled preformatted template rows are harmless, not zero.
                if(period.isBlank() && value==null)continue;
                if(rowId.isBlank()||field.isBlank()||period.isBlank()||value==null)
                    throw new IllegalArgumentException("XLSX_PARTIAL_SOURCE_ROW");
                if(!fields(context).contains(field))
                    throw new IllegalArgumentException("XLSX_UNKNOWN_SOURCE_FIELD");
                String fixed=ROW_IDS.get(context.formCode());
                if(fixed!=null && !fixed.equals(rowId))
                    throw new IllegalArgumentException("XLSX_WRONG_SOURCE_ROW");
                YearMonth m;
                try{m=YearMonth.parse(period);}
                catch(RuntimeException e){throw new IllegalArgumentException("XLSX_INVALID_MONTH",e);}
                cells.add(new TrustedFinancialFormMapper.Cell(rowId,field,m,value));
            }
            if(cells.isEmpty())throw new IllegalArgumentException("XLSX_NO_FINANCIAL_CELLS");
            // Input duplicate row coordinates also reject in mapper fingerprint.
            TrustedFinancialFormMapper.fingerprint(cells);
            return List.copyOf(cells);
        }catch(IOException|RuntimeException e){
            if(e instanceof IllegalArgumentException iae)throw iae;
            throw new IllegalArgumentException("XLSX_SERVER_PARSER_REJECTED_FILE",e);
        }
    }
    private static String text(Cell cell){
        if(cell==null || cell.getCellType()==CellType.BLANK)return "";
        if(cell.getCellType()!=CellType.STRING)
            throw new IllegalArgumentException("XLSX_EXPECTED_LITERAL_STRING");
        return cell.getStringCellValue();
    }
    private static BigDecimal amount(Cell cell){
        if(cell==null || cell.getCellType()==CellType.BLANK)return null;
        if(cell.getCellType()==CellType.STRING){
            String literal=cell.getStringCellValue().trim();
            if(literal.isEmpty())return null;
            if(!literal.matches("-?[0-9]{1,26}(\\.[0-9]{1,12})?"))
                throw new IllegalArgumentException("XLSX_INVALID_DECIMAL_LITERAL");
            return new BigDecimal(literal);
        }
        if(cell.getCellType()==CellType.NUMERIC){
            double numeric=cell.getNumericCellValue();
            if(!Double.isFinite(numeric) || BigDecimal.valueOf(numeric).precision()>15)
                throw new IllegalArgumentException("XLSX_EXCEL_NUMERIC_PRECISION_UNSAFE");
            return BigDecimal.valueOf(numeric).stripTrailingZeros();
        }
        throw new IllegalArgumentException("XLSX_INVALID_AMOUNT_TYPE");
    }
    private static void inspectZip(byte[] bytes){
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
            int count=0;long total=0;
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                count++;
                String name=entry.getName().toLowerCase(Locale.ROOT);
                if(count>100 || name.startsWith("/")||name.contains("..")||
                        name.contains("vbaproject")||name.contains("externallinks")||
                        name.contains("embeddings")||name.contains("connections")||
                        name.contains("oleobject")||name.contains("dde"))
                    throw new IllegalArgumentException("XLSX_UNSAFE_ZIP_ENTRY");
                byte[] buffer=new byte[8192];int size;
                while((size=zip.read(buffer))!=-1){
                    total+=size;
                    if(total>MAX_ZIP_BYTES)throw new IllegalArgumentException("XLSX_UNCOMPRESSED_SIZE_LIMIT");
                }
                zip.closeEntry();
            }
            if(count<5)throw new IllegalArgumentException("XLSX_NOT_A_VALID_WORKBOOK");
        }catch(IOException e){throw new IllegalArgumentException("XLSX_ZIP_INVALID",e);}
    }
}
