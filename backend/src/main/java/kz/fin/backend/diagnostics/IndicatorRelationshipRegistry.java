package kz.fin.backend.diagnostics;

import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.MetricKey;
import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.RelationType;
import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.Rule;
import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.TimeRole;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * R4.6 one immutable, versioned source of truth for semantic indicator links.
 *
 * Registers all 166 form dispositions; only explicitly mapped numeric source
 * bindings can contribute to a relationship. Registration is NOT qualification.
 * No automatic derivation from translated labels, form names, or correlation.
 */
public final class IndicatorRelationshipRegistry {
    public static final String VERSION="INDICATOR_RELATIONSHIPS_V1";
    private static final String CATALOG_HEADER =
        "formCode\tcatalog\tformKind\taggregateRoot\tgrain\tfieldContract\tfieldEvidence\tqualificationStatus";
    private static final String RELATION_HEADER =
        "relationCode\trelationVersion\trelationType\tmethodCode\tqualificationStatus\taccountingPolicy\tperiodPolicy\tsourceBindings";

    public enum FormDisposition {
        EVIDENCE_ONLY, PILOT_NUMERIC_SOURCE, NUMERIC_CANDIDATE_UNQUALIFIED
    }
    public enum Qualification { SYNTHETIC_PILOT_UNQUALIFIED }
    public record Form(String code,String kind,FormDisposition disposition,String qualification,
                       String catalog,String aggregateRoot,String grain,String fieldContract,
                       String fieldEvidence) {}
    public record Binding(MetricKey key,String formCode) {}
    public record Relationship(String code,String version,RelationType type,String methodCode,
                               Qualification qualification,String accountingPolicy,String periodPolicy,
                               List<Binding> inputs) {
        public Relationship { inputs=List.copyOf(inputs); }
        public Rule asRule(){
            return new Rule(code,type,inputs.stream().map(Binding::key).toList());
        }
    }
    private final Map<String,Form> forms;
    private final List<Relationship> relationships;
    private final Map<String,String> originByMetric;
    private final Map<String,Relationship> byCode;
    private final String definitionHash;

    private IndicatorRelationshipRegistry(Map<String,Form> forms,List<Relationship> relationships,
                                         Map<String,String> originByMetric){
        this.forms=Collections.unmodifiableMap(new TreeMap<>(forms));
        this.relationships=List.copyOf(relationships);
        this.originByMetric=Map.copyOf(originByMetric);
        Map<String,Relationship> lookup=new TreeMap<>();
        for(Relationship relation:relationships)
            if(lookup.putIfAbsent(relation.code(),relation)!=null)
                throw new IllegalStateException("DUPLICATE_RELATION_CODE");
        this.byCode=Map.copyOf(lookup);
        this.definitionHash=hash(this.forms,relationships);
    }
    public Map<String,Form> forms(){return forms;}
    public List<Relationship> relationships(){return relationships;}
    public Map<String,String> originByMetric(){return originByMetric;}
    public String definitionHash(){return definitionHash;}
    public Optional<Relationship> relation(String code){return Optional.ofNullable(byCode.get(code));}
    public List<Rule> executableSyntheticRules(){
        return relationships.stream()
            .filter(r->r.qualification()==Qualification.SYNTHETIC_PILOT_UNQUALIFIED)
            .map(Relationship::asRule).toList();
    }
    public Set<String> referencedForms(){
        Set<String> result=new TreeSet<>();
        relationships.forEach(r->r.inputs().forEach(b->result.add(b.formCode())));
        return Collections.unmodifiableSet(result);
    }

    /** Also used by negative tests on tampered TSV contents. */
    public static IndicatorRelationshipRegistry parse(String catalog,String policy){
        List<String[]> formRows=rows(catalog,CATALOG_HEADER,8);
        Map<String,Form> base=new TreeMap<>();
        for(String[] r:formRows){
            if(!r[0].matches("[A-Z][A-Z0-9-]{0,127}") ||
               !Set.of("STRUCTURED","STRUCTURED_INPUT","COMPOSITE","SECTOR_INPUT",
                       "DOCUMENT","EXTERNAL_TAX").contains(r[2]) ||
               !r[7].equals("DESIGN_ONLY") && !r[7].equals("PILOT_UNQUALIFIED"))
                throw new IllegalArgumentException("INVALID_CATALOG_FORM_OR_QUALIFICATION");
            FormDisposition disposition=Set.of("DOCUMENT","EXTERNAL_TAX").contains(r[2])
                ?FormDisposition.EVIDENCE_ONLY:FormDisposition.NUMERIC_CANDIDATE_UNQUALIFIED;
            if(base.putIfAbsent(r[0],new Form(r[0],r[2],disposition,r[7],r[1],r[3],r[4],r[5],r[6]))!=null)
                throw new IllegalArgumentException("DUPLICATE_FORM");
        }
        if(base.size()!=166)throw new IllegalArgumentException("CATALOG_NOT_166_FORMS");
        List<Relationship> relationships=new ArrayList<>();
        Map<String,String> origins=new HashMap<>();
        Set<String> codes=new HashSet<>();
        for(String[] r:rows(policy,RELATION_HEADER,8)){
            if(!r[0].matches("[A-Z][A-Z0-9_]{1,127}") ||
                !r[1].matches("[0-9]+\\.[0-9]+\\.[0-9]+") ||
                !r[0].equals(r[3]) ||
                !r[5].equals("SAME_PUBLISHED_SCOPE") ||
                !r[6].equals("MONTH_AND_PRIOR_CLOSE"))
                throw new IllegalArgumentException("INVALID_RELATION_SEMANTICS");
            if(!codes.add(r[0]))throw new IllegalArgumentException("DUPLICATE_RELATION_CODE");
            RelationType type;
            Qualification qualification;
            try{
                type=RelationType.valueOf(r[2]);
                qualification=Qualification.valueOf(r[4]);
            }catch(IllegalArgumentException e){throw new IllegalArgumentException("UNKNOWN_RELATION_TYPE_OR_QUALIFICATION",e);}
            String[] parts=r[7].split(";",-1);
            List<Binding> inputs=new ArrayList<>();
            Set<MetricKey> unique=new HashSet<>();
            if(parts.length<2)throw new IllegalArgumentException("RELATION_NEEDS_MULTIPLE_INPUTS");
            for(String part:parts){
                String[] f=part.split(":",-1);
                if(f.length!=3 || f[0].isBlank() || f[1].isBlank() || f[2].isBlank())
                    throw new IllegalArgumentException("INVALID_RELATION_BINDING");
                TimeRole role;
                try{role=TimeRole.valueOf(f[1]);}
                catch(IllegalArgumentException e){throw new IllegalArgumentException("UNKNOWN_TIME_ROLE",e);}
                MetricKey key=new MetricKey(f[0],role);
                Form form=base.get(f[2]);
                if(form==null || form.disposition()==FormDisposition.EVIDENCE_ONLY)
                    throw new IllegalArgumentException("NON_NUMERIC_OR_UNKNOWN_RELATION_FORM");
                if(!unique.add(key))throw new IllegalArgumentException("DUPLICATE_RELATION_INPUT");
                String previous=origins.putIfAbsent(f[0],f[2]);
                if(previous!=null && !previous.equals(f[2]))
                    throw new IllegalArgumentException("AMBIGUOUS_CANONICAL_METRIC_SOURCE");
                if(role==TimeRole.CURRENT_FLOW && !Set.of("F02","F03","DF-CSH-01","DF-CSH-02").contains(f[2])
                    || role!=TimeRole.CURRENT_FLOW && !f[2].equals("F01"))
                    throw new IllegalArgumentException("UNQUALIFIED_FORM_TIME_ROLE");
                inputs.add(new Binding(key,f[2]));
            }
            relationships.add(new Relationship(r[0],r[1],type,r[3],
                    qualification,r[5],r[6],inputs));
        }
        if(relationships.size()!=6)
            throw new IllegalArgumentException("UNREVIEWED_EXECUTABLE_RELATION_COUNT");
        // The executable pilots are explicit: do NOT accidentally promote
        // the remaining 161 catalog forms merely because a field resembles a metric.
        for(String formCode:new HashSet<>(origins.values())){
            Form form=base.get(formCode);
            base.put(formCode,new Form(form.code(),form.kind(),
                    FormDisposition.PILOT_NUMERIC_SOURCE,form.qualification(),
                    form.catalog(),form.aggregateRoot(),form.grain(),
                    form.fieldContract(),form.fieldEvidence()));
        }
        return new IndicatorRelationshipRegistry(base,relationships,origins);
    }

    public static IndicatorRelationshipRegistry bundled(){
        return parse(resource("form-compatibility.tsv"),resource("indicator-relationships-v1.tsv"));
    }
    private static String resource(String name){
        try(var input=IndicatorRelationshipRegistry.class.getResourceAsStream("/fin/"+name)){
            if(input==null)throw new IllegalStateException("RELATION_RESOURCE_MISSING_"+name);
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }catch(IOException e){throw new IllegalStateException("RELATION_RESOURCE_UNREADABLE_"+name,e);}
    }
    private static List<String[]> rows(String text,String header,int count){
        if(text==null)throw new IllegalArgumentException("REGISTRY_NULL");
        String[] lines=text.stripTrailing().split("\\R");
        if(lines.length<2 || !lines[0].equals(header))
            throw new IllegalArgumentException("REGISTRY_HEADER_INVALID");
        List<String[]> parsed=new ArrayList<>();
        for(int i=1;i<lines.length;i++){
            if(lines[i].isBlank())throw new IllegalArgumentException("REGISTRY_BLANK_ROW");
            String[] columns=lines[i].split("\\t",-1);
            if(columns.length!=count || Arrays.stream(columns).anyMatch(String::isBlank))
                throw new IllegalArgumentException("REGISTRY_BAD_ROW_"+i);
            parsed.add(columns);
        }
        return parsed;
    }
    private static String hash(Map<String,Form> forms,List<Relationship> relations){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update(VERSION.getBytes(StandardCharsets.UTF_8));
            // Re-qualifying a form changes the meaning of a relationship even
            // if its metric bindings have not changed. Include every form disposition.
            for(Form f:forms.values()){
                byte[] canonical=(f.code()+"|"+f.kind()+"|"+f.disposition()+"|"+
                        f.qualification()+"|"+f.catalog()+"|"+f.aggregateRoot()+"|"+
                        f.grain()+"|"+f.fieldContract()+"|"+f.fieldEvidence())
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(new byte[]{(byte)(canonical.length>>>24),
                        (byte)(canonical.length>>>16),(byte)(canonical.length>>>8),
                        (byte)canonical.length});
                digest.update(canonical);
            }
            for(Relationship r:relations){
                String serialized=r.code()+"|"+r.version()+"|"+r.type()+"|"+
                    r.qualification()+"|"+r.accountingPolicy()+"|"+r.periodPolicy()+"|"+
                    r.inputs().stream().map(b->b.key().code()+":"+b.key().role()+":"+b.formCode())
                        .reduce((a,b)->a+";"+b).orElse("");
                byte[] bytes=serialized.getBytes(StandardCharsets.UTF_8);
                digest.update(new byte[]{(byte)(bytes.length>>>24),(byte)(bytes.length>>>16),
                        (byte)(bytes.length>>>8),(byte)bytes.length});
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA256_UNAVAILABLE",e);}
    }
}
