package kz.fin.backend.analytics;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static kz.fin.backend.analytics.AutoIndicatorEngine.*;

class SectorAutoIndicatorContractTest {
    private static List<String[]> tsv(String path, int columns, String header) throws Exception {
        var stream=SectorAutoIndicatorContractTest.class.getResourceAsStream("/fin/"+path);
        assertNotNull(stream,path);
        try(var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            assertEquals(header,reader.readLine(),path);
            List<String[]> lines=new ArrayList<>();
            String row;
            while((row=reader.readLine())!=null) {
                if(row.isBlank()) continue;
                String[] cells=row.split("\\t",-1);
                assertEquals(columns,cells.length,row);
                for (String value:cells) assertFalse(value.isBlank(),row);
                lines.add(cells);
            }
            return List.copyOf(lines);
        }
    }

    @Test void everySectorSectionHasExactlyOneCandidateWithRealSourceField() throws Exception {
        Map<String,String[]> forms=new HashMap<>();
        var found=tsv("form-compatibility.tsv",8,
                "formCode\tcatalog\tformKind\taggregateRoot\tgrain\tfieldContract\tfieldEvidence\tqualificationStatus");
        assertEquals(166,found.size());
        for(String[] fields:found)forms.put(fields[0],fields);

        var sectors=tsv("sector-auto-indicator-coverage.tsv",11,
                "section\tsourceForm\tsourceField\tsourceGrain\tmetricCode\tmeasureKind\tunitCode\toperatorRecommendation\tgroupDimension\tqualificationStatus\tmethodologyGap");
        assertEquals(21,sectors.size());
        assertEquals("ABCDEFGHIJKLMNOPQRSTU",sectors.stream().map(s->s[0]).collect(Collectors.joining()));
        assertEquals(21,sectors.stream().map(s->s[4]).distinct().count());

        for(String[] sector:sectors) {
            String code="SF-"+sector[0];
            assertTrue(sector[1].startsWith(code),"wrong sector "+Arrays.toString(sector));
            String[] source=forms.get(sector[1]);
            assertNotNull(source,"missing original form: "+sector[1]);
            assertEquals("SF",source[1]);
            assertEquals(sector[3],source[4],"grain mismatch for "+code);
            assertTrue(Arrays.stream(source[5].split(";"))
                    .anyMatch(field->field.startsWith(sector[2]+":")),
                    "field missing from form schema: "+Arrays.toString(sector));
            assertTrue(Arrays.stream(source[5].split(";"))
                    .anyMatch(field->field.startsWith(sector[8]+":")),
                    "dimension missing from form schema: "+Arrays.toString(sector));
            assertTrue(Set.of("FLOW","STOCK","LEVEL").contains(sector[5]));
            assertTrue(Set.of("FLOW_SUM","STOCK_CLOSING","REVIEW_REQUIRED").contains(sector[7]));
            assertEquals("DESIGN_ONLY",sector[9],"sector method was incorrectly marked approved");
        }

        assertEquals("REVIEW_REQUIRED",sectors.get(5)[7],"construction actualCost needs grain review");
        assertEquals("REVIEW_REQUIRED",sectors.get(14)[7],"public actualSpend needs grain review");
        assertEquals("REVIEW_REQUIRED",sectors.get(20)[7],"cumulative grant spend must not be summed");
    }

    @Test void allOperatorsAreEitherImplementedOrExplicitlyDesignOnly() throws Exception {
        var ops=tsv("auto-indicator-operators.tsv",5,
                "operatorCode\tmeasureKind\tcategory\trule\timplementationStatus");
        assertEquals(15,ops.size());
        Set<String> known=new HashSet<>();
        for(String[] op:ops) {
            assertTrue(known.add(op[0]),"duplicate "+op[0]);
            assertTrue(Set.of("IMPLEMENTED_PURE_JAVA","DESIGN_ONLY").contains(op[4]));
        }
        Set<String> implemented=ops.stream().filter(x->x[4].equals("IMPLEMENTED_PURE_JAVA"))
                .map(x->x[0]).collect(Collectors.toSet());
        assertEquals(Set.of("FLOW_SUM","MONTHLY_FLOW_MEAN","DAY_WEIGHTED_LEVEL_MEAN",
                "STOCK_CLOSING","ABS_CHANGE","PCT_CHANGE","PERCENT_OF",
                "SHARE_PCT","MOVING_MEAN"),implemented);
        assertEquals(4,Aggregation.values().length);
    }

    @Test void reportColumnsHaveStableDisplayOrderAndAreNotYetImplemented() throws Exception {
        var cols=tsv("auto-report-columns.tsv",7,
                "reportCode\tdisplayOrder\tmetricCode\toperatorExpression\twindowOrComparison\toutputUnit\tqualificationStatus");
        assertEquals(11,cols.size());
        Map<String,SortedSet<Integer>> byReport=new HashMap<>();
        for(String[] c:cols) {
            assertEquals("DESIGN_ONLY",c[6]);
            int order=Integer.parseInt(c[1]);
            assertTrue(order>0);
            SortedSet<Integer> set=byReport.computeIfAbsent(c[0],ignore->new TreeSet<>());
            assertTrue(set.add(order),"duplicate report order: "+c[0]+"/"+order);
            assertTrue(c[2].matches("[A-Z][A-Z0-9_]+"));
        }
        assertEquals(8,byReport.get("REV").size());
        assertEquals(100,byReport.get("REV").first());
        assertEquals(800,byReport.get("REV").last());
    }
}
