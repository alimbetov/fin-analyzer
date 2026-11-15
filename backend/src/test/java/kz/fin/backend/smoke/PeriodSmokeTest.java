package kz.fin.backend.smoke;

import kz.fin.backend.domain.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class PeriodSmokeTest {
    record Case(String id, String op, LocalDate start, LocalDate end, String values,
                String status, String expected, String reason) {}

    private List<Case> load() throws IOException {
        InputStream stream=getClass().getResourceAsStream("/smoke/period-cases.tsv");
        assertNotNull(stream,"period fixtures missing");
        try (var r=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            assertEquals("id\top\tstart\tend\tobservations\tstatus\texpected\treason",r.readLine());
            List<Case> cases=new ArrayList<>();String line;
            while ((line=r.readLine())!=null) {
                if(line.isBlank()) continue;
                String[] a=line.split("\\t",-1);
                assertEquals(8,a.length,line);
                cases.add(new Case(a[0],a[1],LocalDate.parse(a[2]),LocalDate.parse(a[3]),a[4],a[5],a[6],a[7]));
            }
            return List.copyOf(cases);
        }
    }

    private List<PeriodAggregation.Observation> observations(String text) {
        List<PeriodAggregation.Observation> out=new ArrayList<>();
        for(String token:text.split(";")) {
            String[] p=token.split("=",2);
            assertEquals(2,p.length,"invalid observation: "+token);
            EconomicKernel.TemporalReference time;
            if(p[0].startsWith("@")) {
                time=EconomicKernel.TemporalReference.asOf(LocalDate.parse(p[0].substring(1)));
            } else {
                String[] d=p[0].split("\\.\\.",2);
                assertEquals(2,d.length);
                time=EconomicKernel.TemporalReference.interval(LocalDate.parse(d[0]),LocalDate.parse(d[1]));
            }
            var value=CalculationSmokeTest.inputs("VALUE="+p[1]).get("VALUE");
            out.add(new PeriodAggregation.Observation(time,value));
        }
        return List.copyOf(out);
    }

    @TestFactory Stream<DynamicTest> temporalCases() throws IOException {
        List<Case> cases=load();
        assertTrue(cases.size()>=16);
        assertEquals(cases.size(),cases.stream().map(Case::id).distinct().count());
        return cases.stream().map(c->DynamicTest.dynamicTest(c.id(),()->{
            var target=EconomicKernel.TemporalReference.interval(c.start(),c.end());
            var points=observations(c.values());
            if(c.status().equals("REJECTED")) {
                assertThrows(IllegalArgumentException.class,()->run(c.op(),target,points));
                return;
            }
            PeriodAggregation.Aggregate r=run(c.op(),target,points);
            assertEquals(PeriodAggregation.Status.valueOf(c.status()),r.status());
            if(c.expected().isBlank())assertNull(r.value());
            else assertEquals(0,r.value().compareTo(new BigDecimal(c.expected())));
            if(!c.reason().isBlank()) assertEquals(c.reason(),r.reason());
        }));
    }

    private PeriodAggregation.Aggregate run(String op,EconomicKernel.TemporalReference target,
                                            List<PeriodAggregation.Observation> points) {
        return switch(op) {
            case "FLOW" -> PeriodAggregation.flowSum(target,points);
            case "STOCK" -> PeriodAggregation.closingStock(target,points);
            default -> throw new IllegalArgumentException("unknown operation "+op);
        };
    }

    @Test void threeMonthlyGrossMarginsMustBeWeightedByRevenue() {
        LocalDate[] boundaries={LocalDate.of(2026,1,1),LocalDate.of(2026,2,1),
                LocalDate.of(2026,3,1),LocalDate.of(2026,4,1)};
        List<PeriodAggregation.Observation> rev=new ArrayList<>(),cogs=new ArrayList<>();
        String[] revValues={"100","900","0"},costValues={"90","450","0"};
        for(int i=0;i<3;i++){
            var time=EconomicKernel.TemporalReference.interval(boundaries[i],boundaries[i+1]);
            rev.add(new PeriodAggregation.Observation(time,EconomicKernel.EconomicValue.present(revValues[i])));
            cogs.add(new PeriodAggregation.Observation(time,EconomicKernel.EconomicValue.present(costValues[i])));
        }
        var q=EconomicKernel.TemporalReference.interval(boundaries[0],boundaries[3]);
        BigDecimal revenue=PeriodAggregation.flowSum(q,rev).value();
        BigDecimal costs=PeriodAggregation.flowSum(q,cogs).value();
        assertEquals(0,revenue.compareTo(new BigDecimal("1000")));
        assertEquals(0,costs.compareTo(new BigDecimal("540")));
        var dag=SmokeModels.build("RETAIL");
        var inputs=CalculationSmokeTest.inputs("QTY=1;UNIT_PRICE=1000;UNIT_COST=540;RETURNS=0;OPEX=0");
        assertEquals(0,dag.calculate("OPERATING_MARGIN_PCT",inputs).value()
                .compareTo(new BigDecimal("46")));
    }
}
