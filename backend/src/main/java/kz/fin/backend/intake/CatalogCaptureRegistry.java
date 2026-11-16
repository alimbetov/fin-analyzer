package kz.fin.backend.intake;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Read-only contract covering ALL 166 catalog codes. The 122 typed-hint entries
 * allow an unqualified draft; NO catalog row is proof of approved methodology.
 * This is NOT an HTTP upload controller, source publication or diagnostic.
 */
public final class CatalogCaptureRegistry {
    public static final String VERSION="FIN-CATALOG-CAPTURE-1";
    public static final int MAX_ROWS=200;
    private static final String RESOURCE="/fin/form-compatibility.tsv";
    private static final String HEADER="formCode\tcatalog\tformKind\taggregateRoot\tgrain"
            +"\tfieldContract\tfieldEvidence\tqualificationStatus";
    private static final Set<String> KINDS=Set.of(
        "STRUCTURED","STRUCTURED_INPUT","COMPOSITE","SECTOR_INPUT","DOCUMENT","EXTERNAL_TAX");
    private static final Set<String> LOCALES=Set.of("ru","kk","en");

    public enum Mode {TYPED_DRAFT,EVIDENCE_ONLY,SCHEMA_NOT_APPROVED}
    public enum FieldType {REF,CODE,DATE,MONEY,DECIMAL,PERIOD,ENUM,INTEGER,TEXT,BOOLEAN}
    public record Field(String code,FieldType type) {}
    public record Definition(String code,String catalog,String kind,String aggregateRoot,
                             String grain,String qualification,Mode captureMode,
                             List<Field> fields) {
        public Definition {fields=List.copyOf(fields);}
    }
    public record EntryRow(String rowId,Map<String,String> cells) {}
    public record Context(String formCode,String projectCode,String locale,
                          ReportingPeriodV5.Type periodType,String periodCode,
                          ReportingPeriodV5.Coverage coverage,String currency,int amountScale) {}
    public record Finding(String code,String rowId,String fieldCode) {}
    public record Preview(Context context,List<EntryRow> rows,List<Finding> findings,
                          boolean valid,String status,boolean publishable) {
        public Preview {rows=List.copyOf(rows);findings=List.copyOf(findings);}
    }

    private static final List<Definition> DEFINITIONS=load();
    private static final Map<String,Definition> BY_CODE=index(DEFINITIONS);
    private CatalogCaptureRegistry(){}

    private static Map<String,Definition> index(List<Definition> rows){
        Map<String,Definition> map=new HashMap<>();
        for(var row:rows)map.put(row.code(),row);
        return Map.copyOf(map);
    }
    private static List<Definition> load(){
        var forms=new ArrayList<Definition>();
        Set<String> codes=new HashSet<>();
        try(var input=CatalogCaptureRegistry.class.getResourceAsStream(RESOURCE)){
            if(input==null)throw new IllegalStateException("CATALOG_MISSING");
            try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){
                if(!HEADER.equals(reader.readLine()))throw new IllegalStateException("CATALOG_HEADER_DRIFT");
                String line;
                while((line=reader.readLine())!=null){
                    String[] row=line.split("\t",-1);
                    if(row.length!=8||Arrays.stream(row).anyMatch(String::isBlank)||
                       !row[0].matches("[A-Z][A-Z0-9-]{0,127}")||
                       !codes.add(row[0])||!KINDS.contains(row[2])||
                       !Set.of("FCP_TD","DF","SF").contains(row[1])||
                       !Set.of("CATALOG_SUMMARY","TYPED_FIELD_HINTS").contains(row[6])||
                       !Set.of("DESIGN_ONLY","PILOT_UNQUALIFIED").contains(row[7]))
                        throw new IllegalStateException("CATALOG_UNAPPROVED_OR_DUPLICATE_FORM");
                    List<Field> fields=new ArrayList<>();
                    Mode mode;
                    if("TYPED_FIELD_HINTS".equals(row[6])){
                        if(!Set.of("STRUCTURED_INPUT","SECTOR_INPUT").contains(row[2]))
                            throw new IllegalStateException("CATALOG_TYPED_KIND_MISMATCH");
                        Set<String> fieldNames=new HashSet<>();
                        for(String token:row[5].split(";")){
                            String[] pair=token.split(":",-1);
                            if(pair.length!=2||!pair[0].matches("[A-Za-z][A-Za-z0-9_]*")||
                               !fieldNames.add(pair[0]))
                                throw new IllegalStateException("CATALOG_INVALID_TYPED_FIELD");
                            try{fields.add(new Field(pair[0],FieldType.valueOf(pair[1])));}
                            catch(IllegalArgumentException e){
                                throw new IllegalStateException("CATALOG_INVALID_TYPED_FIELD",e);
                            }
                        }
                        if(fields.isEmpty()||fields.size()>20)
                            throw new IllegalStateException("CATALOG_FIELD_BUDGET");
                        mode=Mode.TYPED_DRAFT;
                    }else if(Set.of("DOCUMENT","EXTERNAL_TAX").contains(row[2])){
                        mode=Mode.EVIDENCE_ONLY;
                    }else mode=Mode.SCHEMA_NOT_APPROVED;
                    forms.add(new Definition(row[0],row[1],row[2],row[3],
                            row[4],row[7],mode,fields));
                }
            }
        }catch(java.io.IOException e){throw new IllegalStateException("CATALOG_READ_FAILURE",e);}
        if(forms.size()!=166||codes.size()!=166||
           forms.stream().filter(x->x.captureMode()==Mode.TYPED_DRAFT).count()!=122||
           forms.stream().filter(x->x.captureMode()==Mode.EVIDENCE_ONLY).count()!=12||
           forms.stream().filter(x->x.captureMode()==Mode.SCHEMA_NOT_APPROVED).count()!=32)
            throw new IllegalStateException("CATALOG_READINESS_DRIFT");
        return List.copyOf(forms);
    }
    public static List<Definition> all(){return DEFINITIONS;}
    public static Definition require(String code){
        Definition form=BY_CODE.get(code);
        if(form==null)throw new IllegalArgumentException("CATALOG_UNKNOWN_FORM");
        return form;
    }
    public static String canonical(FieldType type,String raw,String locale){
        if(!LOCALES.contains(locale))throw new IllegalArgumentException("TRANSLATION_NOT_READY");
        if(raw==null)throw new IllegalArgumentException("INVALID_VALUE_TYPE");
        String value=raw.trim();
        if(value.isEmpty())return "";
        if(type==FieldType.MONEY||type==FieldType.DECIMAL){
            try{return FormSchemaV5.canonicalUserDecimal(value,locale);}
            catch(IllegalArgumentException e){throw new IllegalArgumentException("INVALID_DECIMAL");}
        }
        if(type==FieldType.INTEGER){
            if(!value.matches("-?[0-9]{1,26}"))throw new IllegalArgumentException("INVALID_INTEGER");
            return new BigInteger(value).toString();
        }
        if(type==FieldType.DATE){
            if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                throw new IllegalArgumentException("INVALID_DATE");
            try{
                var d=LocalDate.parse(value);
                if(d.getYear()<1900||d.getYear()>9998)throw new IllegalArgumentException("INVALID_DATE");
                return value;
            }catch(DateTimeParseException e){throw new IllegalArgumentException("INVALID_DATE",e);}
        }
        if(type==FieldType.PERIOD){
            String code=value.matches("[0-9]{4}-(0[1-9]|1[0-2])")?value.replace('-','.'):value;
            ReportingPeriodV5.Type granularity;
            if(code.matches("[0-9]{4}\\.(0[1-9]|1[0-2])"))granularity=ReportingPeriodV5.Type.MONTH;
            else if(code.matches("[0-9]{4}-W[0-9]{2}"))granularity=ReportingPeriodV5.Type.WEEK;
            else if(code.matches("[0-9]{4}-Q[1-4]"))granularity=ReportingPeriodV5.Type.QUARTER;
            else if(code.matches("[0-9]{4}-H[12]"))granularity=ReportingPeriodV5.Type.HALF_YEAR;
            else if(code.matches("[0-9]{4}"))granularity=ReportingPeriodV5.Type.YEAR;
            else throw new IllegalArgumentException("INVALID_PERIOD");
            try{ReportingPeriodV5.resolve(granularity,code,ReportingPeriodV5.Coverage.PERIOD_ONLY);}
            catch(RuntimeException e){throw new IllegalArgumentException("INVALID_PERIOD",e);}
            return code;
        }
        if(type==FieldType.BOOLEAN){
            if(!Set.of("true","false").contains(value))
                throw new IllegalArgumentException("INVALID_BOOLEAN");
            return value;
        }
        if(type==FieldType.TEXT){
            if(value.length()>2000||Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]")
                 .matcher(value).find())throw new IllegalArgumentException("INVALID_TEXT");
            return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFC);
        }
        if(value.length()>120||!value.matches("[\\p{L}\\p{N}_./:-]+")||
           value.matches("^[=+@-].*"))
            throw new IllegalArgumentException("INVALID_REFERENCE");
        return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFC);
    }

    /** No default requiredness is invented from TYPED_FIELD_HINTS. */
    public static Preview validate(Context context,List<EntryRow> submitted){
        Objects.requireNonNull(context);
        Definition form=require(context.formCode());
        if(form.captureMode()!=Mode.TYPED_DRAFT)
            throw new IllegalArgumentException("CATALOG_SCHEMA_NOT_QUALIFIED");
        List<Finding> findings=new ArrayList<>();
        if(!LOCALES.contains(context.locale()))findings.add(new Finding("TRANSLATION_NOT_READY",null,null));
        if(context.projectCode()==null||!context.projectCode().matches("[\\p{L}\\p{N}_-]{1,50}"))
            findings.add(new Finding("PROJECT_CODE_REQUIRED",null,null));
        if(!"KZT".equals(context.currency())||!Set.of(1,1000,1000000).contains(context.amountScale()))
            findings.add(new Finding("MONEY_CONTEXT_UNSUPPORTED",null,null));
        try{ReportingPeriodV5.resolve(context.periodType(),context.periodCode(),context.coverage());}
        catch(RuntimeException e){findings.add(new Finding("INVALID_REPORTING_PERIOD",null,null));}
        if(submitted==null||submitted.isEmpty()||submitted.size()>MAX_ROWS)
            findings.add(new Finding("CATALOG_ROW_COUNT_INVALID",null,null));
        Set<String> seen=new HashSet<>();
        List<EntryRow> normalized=new ArrayList<>();
        if(submitted!=null)for(var row:submitted.subList(0,Math.min(submitted.size(),MAX_ROWS))){
            if(row==null||row.rowId()==null||!row.rowId().matches("[A-Za-z0-9_-]{1,60}")||
                !seen.add(row.rowId())){
                findings.add(new Finding("CATALOG_DUPLICATE_OR_INVALID_ROW_ID",
                    row==null?null:row.rowId(),null));continue;
            }
            if(row.cells()==null){
                findings.add(new Finding("CATALOG_INVALID_CELLS",row.rowId(),null));continue;
            }
            Set<String> allowed=new HashSet<>();
            for(var f:form.fields())allowed.add(f.code());
            for(String key:row.cells().keySet()){
                if(!allowed.contains(key))findings.add(new Finding("CATALOG_UNKNOWN_FIELD",row.rowId(),key));
            }
            Map<String,String> cells=new LinkedHashMap<>();
            for(var field:form.fields()){
                String raw=row.cells().getOrDefault(field.code(),"");
                try{cells.put(field.code(),canonical(field.type(),raw,context.locale()));}
                catch(IllegalArgumentException e){
                    findings.add(new Finding(e.getMessage(),row.rowId(),field.code()));
                    cells.put(field.code(),raw);
                }
            }
            if(cells.values().stream().allMatch(String::isBlank))
                findings.add(new Finding("CATALOG_EMPTY_ROW",row.rowId(),null));
            normalized.add(new EntryRow(row.rowId(),Map.copyOf(cells)));
        }
        return new Preview(context,normalized,findings,findings.isEmpty(),"UNQUALIFIED_DRAFT",false);
    }
}
