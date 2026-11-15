package kz.fin.backend.diagnostics;

import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.MetricKey;
import kz.fin.backend.diagnostics.CrossFormDiagnosticEngine.TimeRole;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Approved-for-exploration business questions and their metric anchors.
 * Loaded from the same versioned FIN resources used in the React study planner.
 * An anchor is a query route, never evidence of a financial fact.
 */
public final class DiagnosticProblemCatalog {
    public static final String VERSION="DIAGNOSTIC_PROBLEM_ROUTES_V1";
    private static final String HEADER="problemCode\tqualificationStatus\tanchorBindings";
    private final Map<String,List<MetricKey>> routes;
    private DiagnosticProblemCatalog(Map<String,List<MetricKey>> routes){
        this.routes=Collections.unmodifiableMap(new TreeMap<>(routes));
    }
    public Map<String,List<MetricKey>> routes(){return routes;}
    public List<MetricKey> anchors(String code){return routes.getOrDefault(code,List.of());}

    public static DiagnosticProblemCatalog bundled(){
        try(var input=DiagnosticProblemCatalog.class.getResourceAsStream(
                "/fin/diagnostic-problem-routes-v1.tsv")){
            if(input==null)throw new IllegalStateException("PROBLEM_ROUTES_UNAVAILABLE");
            return parse(new String(input.readAllBytes(),StandardCharsets.UTF_8),
                    IndicatorRelationshipRegistry.bundled());
        }catch(IOException e){throw new IllegalStateException("PROBLEM_ROUTES_UNREADABLE",e);}
    }
    public static DiagnosticProblemCatalog parse(String source,IndicatorRelationshipRegistry registry){
        Objects.requireNonNull(source);Objects.requireNonNull(registry);
        String[] lines=source.stripTrailing().split("\\R");
        if(lines.length!=5 || !lines[0].equals(HEADER))
            throw new IllegalArgumentException("INVALID_PROBLEM_ROUTE_MANIFEST");
        Map<String,List<MetricKey>> routes=new TreeMap<>();
        Set<MetricKey> allowed=new HashSet<>();
        registry.relationships().forEach(r->r.inputs().forEach(b->allowed.add(b.key())));
        for(int i=1;i<lines.length;i++){
            String[] fields=lines[i].split("\\t",-1);
            if(fields.length!=3 || !fields[0].matches("[A-Z][A-Z0-9_]{1,127}") ||
                    !fields[1].equals("SYNTHETIC_PILOT_UNQUALIFIED"))
                throw new IllegalArgumentException("UNKNOWN_OR_UNQUALIFIED_PROBLEM_ROUTE");
            List<MetricKey> anchors=new ArrayList<>();
            for(String item:fields[2].split(";")){
                String[] metric=item.split(":",-1);
                if(metric.length!=2)
                    throw new IllegalArgumentException("INVALID_PROBLEM_ANCHOR");
                TimeRole role;
                try{role=TimeRole.valueOf(metric[1]);}
                catch(IllegalArgumentException e){throw new IllegalArgumentException("UNREVIEWED_PROBLEM_TIME_ROLE",e);}
                MetricKey key=new MetricKey(metric[0],role);
                if(!allowed.contains(key))
                    throw new IllegalArgumentException("PROBLEM_ANCHOR_NOT_IN_RELATIONSHIP_GRAPH");
                anchors.add(key);
            }
            if(anchors.size()<1 || new HashSet<>(anchors).size()!=anchors.size() ||
                    routes.putIfAbsent(fields[0],List.copyOf(anchors))!=null)
                throw new IllegalArgumentException("DUPLICATE_OR_EMPTY_PROBLEM_ROUTE");
        }
        if(routes.size()!=4)throw new IllegalArgumentException("PROBLEM_ROUTE_COUNT_DRIFT");
        return new DiagnosticProblemCatalog(routes);
    }
}
