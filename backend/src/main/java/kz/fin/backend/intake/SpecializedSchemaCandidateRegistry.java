package kz.fin.backend.intake;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Independent Java read/validation model for 32 R6.1 proposed field contracts.
 * Not used as a trusted FIN-PILOT approval method or authoritative publication.
 */
public final class SpecializedSchemaCandidateRegistry {
    public static final String RESOURCE="/fin/specialized-schema-candidates-r6.tsv";
    private static final String HEADER="formCode\tlayout\tfields\tpreliminaryInvariant\tqualification";
    public record Candidate(String code,String layout,List<CatalogCaptureRegistry.Field> fields,
                            String invariant,String qualification){
        public Candidate{fields=List.copyOf(fields);}
    }
    public record Finding(String code,String rowId,String fieldCode,String expected,String actual){}
    public record Preview(String code,List<CatalogCaptureRegistry.EntryRow> rows,List<Finding> findings,
                          boolean validDraft,boolean methodologyApproved,boolean publishable){
        public Preview{rows=List.copyOf(rows);findings=List.copyOf(findings);}
    }
    private record Rule(String result,List<String> fields,List<Integer> signs){
        public Rule{fields=List.copyOf(fields);signs=List.copyOf(signs);}
    }
    private static final Map<String,Rule> RULES=Map.of(
      "BALANCE_IDENTITY",new Rule("assets",List.of("liabilities","equity"),List.of(1,1)),
      "CASH_ROLLFORWARD",new Rule("closingCash",List.of("openingCash","cfo","cfi","cff"),List.of(1,1,1,1)),
      "EQUITY_ROLLFORWARD",new Rule("closingEquity",List.of("openingEquity","contributions","dividends","otherChanges"),List.of(1,1,-1,1)),
      "FORECAST_CASH_ROLL",new Rule("closingCash",List.of("openingCash","inflows","outflows"),List.of(1,1,-1)),
      "DEBT_PRINCIPAL_ROLL",new Rule("closingPrincipal",List.of("openingPrincipal","drawdown","principalRepayment"),List.of(1,1,-1))
    );
    private static final Set<String> OTHER=Set.of(
      "NONE","NONNEGATIVE_LEDGER","TRIAL_BALANCE_ROW","REFERENCE_ONLY");
    private static final List<Candidate> CATALOG=load();
    private static final Map<String,Candidate> INDEX=index(CATALOG);
    private SpecializedSchemaCandidateRegistry(){}
    private static Map<String,Candidate> index(List<Candidate> all){
        Map<String,Candidate> idx=new HashMap<>();
        for(var item:all)idx.put(item.code(),item);
        return Map.copyOf(idx);
    }
    private static List<Candidate> load(){
        var out=new ArrayList<Candidate>();
        Set<String> seen=new HashSet<>();
        try(var stream=SpecializedSchemaCandidateRegistry.class.getResourceAsStream(RESOURCE)){
            if(stream==null)throw new IllegalStateException("SPECIALIZED_RESOURCE_NOT_FOUND");
            try(var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
                if(!HEADER.equals(reader.readLine()))throw new IllegalStateException("SPECIALIZED_HEADER_DRIFT");
                String line;
                while((line=reader.readLine())!=null){
                    String[] row=line.split("\t",-1);
                    if(row.length!=5||!row[0].matches("F0[1-8]|C(0[1-9]|1[0-4])|P(0[1-9]|10)")||
                       !seen.add(row[0])||!row[1].matches("[A-Z][A-Z0-9_]+")||
                       !(RULES.containsKey(row[3])||OTHER.contains(row[3]))||
                       !row[4].equals("SCHEMA_CANDIDATE_NOT_APPROVED"))
                        throw new IllegalStateException("SPECIALIZED_UNQUALIFIED_OR_DUPLICATE");
                    var fields=new ArrayList<CatalogCaptureRegistry.Field>();
                    Set<String> fieldNames=new HashSet<>();
                    for(String f:row[2].split(";")){
                        String[] pair=f.split(":",-1);
                        if(pair.length!=2||!pair[0].matches("[A-Za-z][A-Za-z0-9_]*")||
                            !fieldNames.add(pair[0]))
                            throw new IllegalStateException("SPECIALIZED_INVALID_FIELDS");
                        try{
                            fields.add(new CatalogCaptureRegistry.Field(
                                pair[0],CatalogCaptureRegistry.FieldType.valueOf(pair[1])));
                        }catch(IllegalArgumentException e){
                            throw new IllegalStateException("SPECIALIZED_INVALID_FIELDS",e);
                        }
                    }
                    if(fields.size()<2||fields.size()>18||
                       CatalogCaptureRegistry.require(row[0]).captureMode()!=
                         CatalogCaptureRegistry.Mode.SCHEMA_NOT_APPROVED)
                        throw new IllegalStateException("SPECIALIZED_SCHEMA_NOT_APPROVED");
                    if(row[3].equals("REFERENCE_ONLY")&&fields.stream().anyMatch(
                        f->f.type()==CatalogCaptureRegistry.FieldType.MONEY))
                        throw new IllegalStateException("SPECIALIZED_COMPOSITE_CANNOT_COPY_MONEY");
                    out.add(new Candidate(row[0],row[1],fields,row[3],row[4]));
                }
            }
        }catch(java.io.IOException e){throw new IllegalStateException("SPECIALIZED_READ_FAILED",e);}
        if(out.size()!=32||seen.size()!=32)throw new IllegalStateException("SPECIALIZED_COVERAGE_DRIFT");
        return List.copyOf(out);
    }
    public static List<Candidate> all(){return CATALOG;}
    public static Candidate require(String formCode){
        Candidate c=INDEX.get(formCode);
        if(c==null)throw new IllegalArgumentException("SPECIALIZED_UNKNOWN_FORM");
        return c;
    }
    private static void add(List<Finding> findings,String code,String row,String field){
        findings.add(new Finding(code,row,field,null,null));
    }
    public static Preview validate(String formCode,List<CatalogCaptureRegistry.EntryRow> rows,String locale){
        var form=require(formCode);
        var findings=new ArrayList<Finding>();
        if(!Set.of("ru","kk","en").contains(locale))
            add(findings,"TRANSLATION_NOT_READY",null,null);
        if(rows==null||rows.isEmpty()||rows.size()>CatalogCaptureRegistry.MAX_ROWS)
            add(findings,"SPECIALIZED_ROW_COUNT_INVALID",null,null);
        var result=new ArrayList<CatalogCaptureRegistry.EntryRow>();
        Set<String> ids=new HashSet<>();
        if(rows!=null)for(var row:rows.subList(0,Math.min(rows.size(),CatalogCaptureRegistry.MAX_ROWS))){
            if(row==null||row.rowId()==null||!row.rowId().matches("[A-Za-z0-9_-]{1,60}")||
               !ids.add(row.rowId())){
                add(findings,"SPECIALIZED_INVALID_OR_DUPLICATE_ROW",row==null?null:row.rowId(),null);
                continue;
            }
            if(row.cells()==null){
                add(findings,"SPECIALIZED_INVALID_CELLS",row.rowId(),null);continue;
            }
            var known=new HashSet<String>();
            for(var field:form.fields())known.add(field.code());
            for(var field:row.cells().keySet())if(!known.contains(field))
                add(findings,"SPECIALIZED_UNKNOWN_FIELD",row.rowId(),field);
            Map<String,String> canonical=new LinkedHashMap<>();
            for(var field:form.fields()){
                try{canonical.put(field.code(),CatalogCaptureRegistry.canonical(
                    field.type(),row.cells().getOrDefault(field.code(),""),locale));}
                catch(IllegalArgumentException e){
                    add(findings,e.getMessage(),row.rowId(),field.code());
                    canonical.put(field.code(),row.cells().getOrDefault(field.code(),""));
                }
            }
            if(canonical.values().stream().allMatch(String::isBlank))
                add(findings,"SPECIALIZED_EMPTY_ROW",row.rowId(),null);
            Rule rule=RULES.get(form.invariant());
            if(rule!=null){
                List<String> codes=new ArrayList<>(rule.fields());
                codes.add(rule.result());
                if(codes.stream().allMatch(k->canonical.get(k)!=null&&!canonical.get(k).isBlank())){
                    try{
                        BigDecimal expected=BigDecimal.ZERO;
                        for(int i=0;i<rule.fields().size();i++)
                            expected=expected.add(new BigDecimal(canonical.get(rule.fields().get(i)))
                              .multiply(BigDecimal.valueOf(rule.signs().get(i))));
                        BigDecimal actual=new BigDecimal(canonical.get(rule.result()));
                        if(expected.compareTo(actual)!=0)
                            findings.add(new Finding("SPECIALIZED_PROVISIONAL_INVARIANT_MISMATCH",
                                row.rowId(),rule.result(),expected.stripTrailingZeros().toPlainString(),
                                actual.stripTrailingZeros().toPlainString()));
                    }catch(NumberFormatException ignored){
                        // Value errors were reported at the typed-field boundary.
                    }
                }else add(findings,"SPECIALIZED_CHECK_NOT_EVALUATED",row.rowId(),null);
            }
            if(form.invariant().equals("TRIAL_BALANCE_ROW"))
                add(findings,"SPECIALIZED_METHOD_REVIEW_REQUIRED",row.rowId(),null);
            result.add(new CatalogCaptureRegistry.EntryRow(row.rowId(),Map.copyOf(canonical)));
        }
        boolean valid=findings.stream().noneMatch(x->!Set.of(
            "SPECIALIZED_CHECK_NOT_EVALUATED","SPECIALIZED_METHOD_REVIEW_REQUIRED").contains(x.code()));
        return new Preview(formCode,result,findings,valid,false,false);
    }
}
