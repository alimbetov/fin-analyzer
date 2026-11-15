package kz.fin.backend.domain;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FormCompatibilityCatalogTest {
    @Test void all166CatalogFormsAreClassifiedWithoutPretendingImplemented() throws Exception {
        try (var stream = getClass().getResourceAsStream("/fin/form-compatibility.tsv")) {
            assertNotNull(stream);
            var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals("formCode\tcatalog\tformKind\taggregateRoot\tgrain\tfieldContract\tfieldEvidence\tqualificationStatus",reader.readLine());
            Map<String, Integer> count = new HashMap<>();
            Set<String> seen = new HashSet<>();
            String line;
            while ((line=reader.readLine())!=null) {
                String[] columns=line.split("\\t",-1);
                assertEquals(8,columns.length,line);
                for (String column:columns) assertFalse(column.isBlank(),line);
                assertTrue(seen.add(columns[0]), "duplicate form: "+columns[0]);
                assertTrue(Set.of("DESIGN_ONLY","PILOT_UNQUALIFIED").contains(columns[7]));
                count.merge(columns[1],1,Integer::sum);
            }
            assertEquals(166,seen.size());
            assertEquals(44,count.get("FCP_TD"));
            assertEquals(59,count.get("DF"));
            assertEquals(63,count.get("SF"));
            for (String code:List.of("F01","F08","C05","P04","T01","D08","DF-SAL-01",
                    "DF-CSH-06","DF-GOV-02","SF-A01","SF-G01","SF-C03","SF-U03")) assertTrue(seen.contains(code),code);
        }
    }
}
