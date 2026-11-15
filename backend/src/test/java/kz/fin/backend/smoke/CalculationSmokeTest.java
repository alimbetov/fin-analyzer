package kz.fin.backend.smoke;

import kz.fin.backend.domain.CalculationDag;
import kz.fin.backend.domain.EconomicKernel.EconomicValue;
import org.junit.jupiter.api.*;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class CalculationSmokeTest {
    record Case(String id, String model, String mode, String metric, String inputs,
                String status, String expected, String reason) {}

    private List<Case> load() throws IOException {
        InputStream resource=getClass().getResourceAsStream("/smoke/calculation-cases.tsv");
        assertNotNull(resource,"calculation smoke fixtures missing");
        try (var reader=new BufferedReader(new InputStreamReader(resource,StandardCharsets.UTF_8))) {
            assertEquals("id\tmodel\tmode\tmetric\tinputs\tstatus\texpected\treason",reader.readLine());
            List<Case> rows=new ArrayList<>();String line;
            while ((line=reader.readLine())!=null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] a=line.split("\\t",-1);
                assertEquals(8,a.length,line);
                rows.add(new Case(a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7]));
            }
            return List.copyOf(rows);
        }
    }

    static Map<String,EconomicValue> inputs(String text) {
        Map<String,EconomicValue> values=new HashMap<>();
        for(String part:text.split(";")) {
            String[] kv=part.split("=",2);
            if(kv.length!=2) throw new IllegalArgumentException("bad fixture: "+part);
            String v=kv[1];
            EconomicValue value;
            if(v.equals("-")) value=EconomicValue.missing();
            else if(v.equals("NA")) value=EconomicValue.notApplicable();
            else if(v.equals("0!")) value=EconomicValue.explicitZero();
            else if(v.startsWith("~")) {
                String[] estimate=v.substring(1).split("@",2);
                if(estimate.length!=2) throw new IllegalArgumentException("bad estimate: "+v);
                value=EconomicValue.estimated(estimate[0],estimate[1]);
            } else value=EconomicValue.present(v);
            if(values.putIfAbsent(kv[0],value)!=null)
                throw new IllegalArgumentException("duplicate input: "+kv[0]);
        }
        return Map.copyOf(values);
    }

    @TestFactory
    Stream<DynamicTest> smokeMatrix() throws IOException {
        List<Case> rows=load();
        assertTrue(rows.size()>=25,"insufficient smoke cases");
        assertEquals(rows.size(),rows.stream().map(Case::id).distinct().count());
        assertTrue(rows.stream().map(Case::model).distinct().count()>=8);
        assertTrue(rows.stream().map(Case::mode).collect(Collectors.toSet())
                .containsAll(Set.of("ACTUAL","PLAN","FORECAST","SCENARIO")));
        return rows.stream().map(c->DynamicTest.dynamicTest(c.id(),()->{
            CalculationDag dag=SmokeModels.build(c.model());
            var result=dag.calculate(c.metric(),inputs(c.inputs()));
            assertEquals(CalculationDag.Status.valueOf(c.status()),result.status(),c.id());
            if(c.expected().isBlank()) assertNull(result.value(),c.id());
            else {
                assertNotNull(result.value(),c.id());
                assertEquals(0,result.value().setScale(6,RoundingMode.HALF_UP)
                        .compareTo(new BigDecimal(c.expected()).setScale(6,RoundingMode.HALF_UP)),c.id());
            }
            if(!c.reason().isBlank()) assertEquals(c.reason(),result.reason(),c.id());
        }));
    }

    @Test void scenarioAndPlanReuseModelWithoutChangingActual() {
        var actual=inputs("BASE_REVENUE=12000000;GROWTH_RATE=0");
        var scenario=inputs("BASE_REVENUE=12000000;GROWTH_RATE=-0.20");
        CalculationDag dag=SmokeModels.build("FORECAST");
        assertEquals(0,dag.calculate("FORECAST_REVENUE",actual).value()
                .compareTo(new BigDecimal("12000000")));
        assertEquals(0,dag.calculate("FORECAST_REVENUE",scenario).value()
                .compareTo(new BigDecimal("9600000")));
        assertEquals(0,dag.calculate("FORECAST_REVENUE",actual).value()
                .compareTo(new BigDecimal("12000000")));
    }

    @Test void sharedDependenciesProduceSameAnswerIndependentOfCallOrder() {
        var dag=SmokeModels.build("RETAIL");
        var data=inputs("QTY=1000;UNIT_PRICE=15000;UNIT_COST=9000;RETURNS=0;OPEX=2000000");
        BigDecimal p=dag.calculate("OPERATING_PROFIT",data).value();
        dag.calculate("GROSS_PROFIT",data);
        dag.calculate("OPERATING_MARGIN_PCT",data);
        assertEquals(0,p.compareTo(dag.calculate("OPERATING_PROFIT",data).value()));
    }

    @Test void fixtureScopeNeverBecomesApprovalEvidence() throws IOException {
        for(Case c:load()) {
            assertTrue(Set.of("ACTUAL","PLAN","FORECAST","SCENARIO").contains(c.mode()));
            assertFalse(c.inputs().isBlank());
        }
    }
}
