package kz.fin.backend.analytics;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticsModelCatalogTest {
    @Test void allFortyTwoProposedModelsMapToExistingFormsAndAreNotMislabelledReady() throws Exception {
        Set<String> availableForms = new HashSet<>();
        try(var stream=getClass().getResourceAsStream("/fin/form-compatibility.tsv")) {
            assertNotNull(stream);
            var r=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8));
            assertNotNull(r.readLine());
            String line;
            while((line=r.readLine())!=null) if(!line.isBlank()) availableForms.add(line.split("\\t",-1)[0]);
        }
        assertEquals(166,availableForms.size());

        Map<String,Integer> counts = new HashMap<>();
        Set<String> unique = new HashSet<>();
        Set<String> pilots = new HashSet<>();
        try(var stream=getClass().getResourceAsStream("/fin/analytics-model-catalog.tsv")) {
            assertNotNull(stream);
            var r=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8));
            assertEquals("modelCode\tpack\ttitle\tinputFormCodes\toutputMetricCodes\tqualificationStatus",r.readLine());
            String line;
            while((line=r.readLine())!=null) {
                if(line.isBlank()) continue;
                String[] a=line.split("\\t",-1);
                assertEquals(6,a.length,line);
                assertTrue(unique.add(a[0]),"duplicate "+a[0]);
                assertFalse(a[2].isBlank());
                assertFalse(a[4].isBlank());
                for(String form:a[3].split(";")) assertTrue(availableForms.contains(form),"unknown form "+form);
                assertTrue(Set.of("DESIGN_ONLY","CALC_PILOT").contains(a[5]));
                if(a[5].equals("CALC_PILOT")) pilots.add(a[0]);
                counts.merge(a[1],1,Integer::sum);
            }
        }
        assertEquals(42,unique.size());
        assertEquals(Map.of("ACCOUNTING",7,"CFO",8,"INVESTMENT",9,"COSTING",10,"MANAGEMENT",8),counts);
        assertEquals(Set.of("CFO-01","INV-05","INV-06","CST-04","CST-05"),pilots);
    }
}
