package kz.fin.backend.intake;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * P0-V5 executable F02 contract. Same UTF-8 source as React (?raw TSV).
 * Does not publish ACTUAL facts or bypass the existing API authorization layer.
 */
public final class FormSchemaV5 {
    public static final String VERSION="FIN-FORM-V2";
    private static final String RESOURCE="/fin/intake-fields-v5.tsv";
    private static final String HEADER="formCode\tformVersion\trowId\tfieldCode\tordinal\trequired\t"
            +"titleRu\ttitleKk\ttitleEn\tlabelRu\tlabelKk\tlabelEn";
    public static final List<String> ACTIVE_LOCALES=List.of("ru","kk","en");
    public record Field(String rowId,String code,int order,boolean required,Map<String,String> labels) {
        public Field{labels=Map.copyOf(labels);}
    }
    public record Form(String code,String version,Map<String,String> titles,List<Field> fields) {
        public Form {titles=Map.copyOf(titles);fields=List.copyOf(fields);}
        public String title(String locale){requireLocale(locale);return titles.get(locale);}
        public List<String> fieldCodes(){return fields.stream().map(Field::code).toList();}
    }
    public record Input(String rowId,String fieldCode,String decimalText) {}
    public record Normalized(List<Input> cells,List<String> errors,boolean valid) {
        public Normalized{cells=List.copyOf(cells);errors=List.copyOf(errors);}
    }
    private FormSchemaV5(){}
    public static void requireLocale(String locale){
        if(!ACTIVE_LOCALES.contains(locale))throw new IllegalArgumentException("TRANSLATION_NOT_READY");
    }
    public static Form pilotF02(){
        List<Field> fields=new ArrayList<>();
        Map<String,String> title=null;
        try(var input=FormSchemaV5.class.getResourceAsStream(RESOURCE)){
            if(input==null)throw new IllegalStateException("V5_REGISTRY_MISSING");
            try(var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))){
                if(!HEADER.equals(reader.readLine()))throw new IllegalStateException("V5_REGISTRY_HEADER_CHANGED");
                String line;
                while((line=reader.readLine())!=null){
                    if(line.isBlank())continue;
                    String[] r=line.split("\t",-1);
                    if(r.length!=12||Arrays.stream(r).anyMatch(String::isBlank)
                       ||!"F02".equals(r[0])||!VERSION.equals(r[1])||!"pnl".equals(r[2]))
                        throw new IllegalStateException("V5_REGISTRY_BAD_ROW");
                    Map<String,String> titles=Map.of("ru",r[6],"kk",r[7],"en",r[8]);
                    if(title!=null&&!title.equals(titles))throw new IllegalStateException("V5_REGISTRY_TITLE_DRIFT");
                    title=titles;
                    fields.add(new Field(r[2],r[3],Integer.parseInt(r[4]),Boolean.parseBoolean(r[5]),
                        Map.of("ru",r[9],"kk",r[10],"en",r[11])));
                }
            }
        }catch(java.io.IOException e){throw new IllegalStateException("V5_REGISTRY_READ_FAILED",e);}
        fields.sort(Comparator.comparingInt(Field::order));
        if(fields.size()!=3||title==null||!fields.stream().map(Field::code).toList()
                .equals(List.of("netRevenue","cogs","operatingExpenses")))
            throw new IllegalStateException("V5_REGISTRY_INCOMPLETE_F02");
        for(int i=0;i<fields.size();i++)if(fields.get(i).order()!=i+1)
            throw new IllegalStateException("V5_REGISTRY_ORDER_DRIFT");
        return new Form("F02",VERSION,title,fields);
    }
    public static String canonicalDecimal(String raw){
        if(raw==null||!raw.trim().matches("-?[0-9]{1,26}(\\.[0-9]{1,12})?"))
            throw new IllegalArgumentException("INVALID_DECIMAL");
        BigDecimal decimal=new BigDecimal(raw.trim());
        if(decimal.precision()>38||decimal.scale()>12)throw new IllegalArgumentException("INVALID_DECIMAL");
        if(decimal.signum()==0)return "0";
        return decimal.stripTrailingZeros().toPlainString();
    }
    /** Source-language parsing agrees with the React F02 normalizer.
     *  A comma is a decimal separator only for ru/kk, never a thousands separator. */
    public static String canonicalUserDecimal(String raw,String sourceLocale){
        requireLocale(sourceLocale);
        if(raw==null)throw new IllegalArgumentException("INVALID_DECIMAL");
        String text=raw.trim();
        if(("ru".equals(sourceLocale)||"kk".equals(sourceLocale))
          &&text.matches("-?[0-9]{1,26},[0-9]{1,12}"))
            return canonicalDecimal(text.replace(',','.'));
        return canonicalDecimal(text);
    }
    public static Normalized normalizeF02(List<Input> submitted){
        return normalizeF02(submitted,"en");
    }
    public static Normalized normalizeF02(List<Input> submitted,String sourceLocale){
        requireLocale(sourceLocale);
        if(submitted==null)throw new IllegalArgumentException("FIN_V5_CELLS_REQUIRED");
        Form form=pilotF02();
        List<String> errors=new ArrayList<>();
        Map<String,Input> byCode=new HashMap<>();
        for(Input cell:submitted){
            if(cell==null ||form.fields().stream().noneMatch(f->f.code().equals(cell.fieldCode())
                    &&f.rowId().equals(cell.rowId()))){
                errors.add("UNKNOWN_SOURCE_COORDINATE");continue;
            }
            if(byCode.putIfAbsent(cell.fieldCode(),cell)!=null)errors.add("DUPLICATE_SOURCE_FIELD:"+cell.fieldCode());
        }
        List<Input> cells=new ArrayList<>();
        for(Field f:form.fields()){
            Input cell=byCode.get(f.code());
            if(cell==null||cell.decimalText()==null||cell.decimalText().isBlank()){
                if(f.required())errors.add("REQUIRED_FIELD_MISSING:"+f.code());
                cells.add(new Input(f.rowId(),f.code(),""));
            }else{
                try{cells.add(new Input(f.rowId(),f.code(),canonicalUserDecimal(cell.decimalText(),sourceLocale)));}
                catch(IllegalArgumentException e){
                    errors.add("INVALID_DECIMAL:"+f.code());
                    cells.add(new Input(f.rowId(),f.code(),cell.decimalText()));
                }
            }
        }
        return new Normalized(cells,errors,errors.isEmpty());
    }
}
