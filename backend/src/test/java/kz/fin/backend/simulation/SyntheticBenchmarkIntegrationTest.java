package kz.fin.backend.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import kz.fin.backend.analytics.AnalysisContext;
import kz.fin.backend.domain.EconomicKernel;
import kz.fin.backend.persistence.FactSetPublicationService;
import kz.fin.backend.persistence.TrustedBusinessDiagnosticService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SyntheticBenchmarkIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired FactSetPublicationService factPublication;
    @Autowired TrustedBusinessDiagnosticService diagnosis;

    private static final String DATASET_VERSION="SIM-R1-20261115";
    private static final YearMonth EVALUATION=YearMonth.of(2026,10);
    private static final List<String> DIAGNOSTIC_METRICS=List.of(
            "REV_NET","COGS","CASH_END","AR_END");
    private static final EconomicKernel.ReportingBasis BASIS=
            EconomicKernel.ReportingBasis.MANAGEMENT;
    private static final String ZERO_HASH="0".repeat(64);

    private record Profile(String code,String sector,String type,String expectedOutcome) {}
    private record DataPoint(String scenario,int revision,String metric,String metricVersion,
                             LocalDate month,String timeKind,String measureKind,String unit,
                             String currency,String form,String field,String state,
                             BigDecimal amount,String method) {}
    private record Ledger(UUID org,UUID entity,UUID scope,UUID factSetId) {}
    private record FactSeed(UUID id,DataPoint point,UUID sourceId) {}
    private record Expected(String scenario,String rule,String kind,int count) {}

    private static List<Profile> profiles;
    private static List<DataPoint> points;
    private static List<Expected> oracle;

    private static List<String[]> readTsv(String filename,int columns) throws Exception {
        return readResourceTsv("/simulation/"+filename,columns);
    }

    private static List<String[]> readResourceTsv(String path,int columns) throws Exception {
        try(var stream=Objects.requireNonNull(SyntheticBenchmarkIntegrationTest.class
                        .getResourceAsStream(path),path);
            var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
            List<String[]> all=new ArrayList<>();
            String row=reader.readLine();
            assertNotNull(row,filename);
            while((row=reader.readLine())!=null){
                if(row.isBlank())continue;
                String[] fields=row.split("\\t",-1);
                assertEquals(columns,fields.length,filename+": "+row);
                all.add(fields);
            }
            return List.copyOf(all);
        }
    }

    @BeforeEach
    synchronized void loadSeparateSimulationTables() throws Exception {
        if(profiles!=null) return;
        var manifest=readTsv("profiles.tsv",9);
        var facts=readTsv("monthly-financial-facts.tsv",14);
        var expectations=readTsv("golden-findings.tsv",5);
        profiles=manifest.stream().map(f->new Profile(f[0],f[1],f[2],f[7])).toList();
        points=facts.stream().map(f->new DataPoint(
                f[0],Integer.parseInt(f[1]),f[2],f[3],LocalDate.parse(f[4]),
                f[5],f[6],f[7],f[8],f[9],f[10],f[11],
                f[12].isEmpty()?null:new BigDecimal(f[12]),f[13].isEmpty()?null:f[13]))
                .toList();
        oracle=expectations.stream().map(f->new Expected(
                f[0],f[2],f[3],Integer.parseInt(f[4]))).toList();

        jdbc.batchUpdate("""
            INSERT INTO fin_sim.dataset(scenario_code,sector_code,profile_code,generated_version,
                   first_month,last_month,currency_code,expected_outcome,note)
            VALUES(?,?,?,?,?,?,?,?,?)
            """,new BatchPreparedStatementSetter(){
                @Override public int getBatchSize(){return manifest.size();}
                @Override public void setValues(PreparedStatement p,int i)throws SQLException{
                    String[] row=manifest.get(i);
                    for(int j=0;j<row.length;j++){
                        if(j==4||j==5)p.setObject(j+1,LocalDate.parse(row[j]));
                        else p.setString(j+1,row[j]);
                    }
                }
            });
        jdbc.batchUpdate("""
            INSERT INTO fin_sim.observation(
              scenario_code,source_revision,metric_code,metric_version,period_month,
              time_kind,measure_kind,unit_code,currency_code,source_form_code,
              source_field_code,value_state,numeric_value,estimate_method_ref)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,new BatchPreparedStatementSetter(){
                @Override public int getBatchSize(){return points.size();}
                @Override public void setValues(PreparedStatement p,int i)throws SQLException{
                    DataPoint row=points.get(i);
                    p.setString(1,row.scenario());p.setInt(2,row.revision());
                    p.setString(3,row.metric());p.setString(4,row.metricVersion());
                    p.setObject(5,row.month());
                    p.setString(6,row.timeKind());p.setString(7,row.measureKind());
                    p.setString(8,row.unit());p.setString(9,row.currency().isBlank()?null:row.currency());
                    p.setString(10,row.form());p.setString(11,row.field());
                    p.setString(12,row.state());
                    if(row.amount()==null)p.setNull(13,Types.NUMERIC);
                    else p.setBigDecimal(13,row.amount());
                    p.setString(14,row.method());
                }
            });
        jdbc.batchUpdate("""
            INSERT INTO fin_sim.expected_finding(
                scenario_code,source_revision,rule_code,finding_kind,expected_count)
            VALUES(?,1,?,?,?)
            """,new BatchPreparedStatementSetter(){
                @Override public int getBatchSize(){return oracle.size();}
                @Override public void setValues(PreparedStatement p,int i)throws SQLException{
                    Expected e=oracle.get(i);
                    p.setString(1,e.scenario());p.setString(2,e.rule());
                    p.setString(3,e.kind());p.setInt(4,e.count());
                }
            });
    }

    @Test
    @Order(1)
    void all166FormsAccountedForButOnlySampledFormsAreClaimedAsGenerated() throws Exception {
        var coverage=readTsv("form-coverage.tsv",4);
        assertEquals(166,coverage.size());
        assertEquals(166,coverage.stream().map(x->x[0]).distinct().count());
        var currentCatalog=readResourceTsv("/fin/form-compatibility.tsv",8);
        var catalogByCode=new HashMap<String,String[]>();
        for(String[] row:currentCatalog)
            assertNull(catalogByCode.put(row[0],row),"Duplicate form contract "+row[0]);
        assertEquals(catalogByCode.keySet(),coverage.stream()
                .map(x->x[0]).collect(Collectors.toSet()),
                "Stale synthetic form coverage; must match all live registry form codes");
        for(String[] row:coverage)
            assertEquals(catalogByCode.get(row[0])[1],row[1],
                    "Catalog group drift for "+row[0]);
        var used=points.stream().map(DataPoint::form).collect(Collectors.toSet());
        assertTrue(used.size()>=20);
        assertEquals(used,coverage.stream().filter(x->x[2].equals("SYNTHETIC_AGGREGATE_SAMPLE"))
                .map(x->x[0]).collect(Collectors.toSet()));
        assertEquals(166-used.size(),coverage.stream().filter(x->x[2].equals("CONTRACT_ONLY")).count());
        assertTrue(points.stream().noneMatch(x->x.state().equals("ESTIMATED")));
        assertTrue(points.stream().noneMatch(x->x.amount()!=null
                && x.amount().scale()>12));
    }

    @Test
    @Order(2)
    void generatedRowsObeyFinancialReconciliationsAndNeverTurnMissingIntoZero(){
        assertEquals(9,profiles.size());
        assertEquals(9*36*19,points.size());
        assertEquals(9*3*3,oracle.size());
        assertEquals(9L,jdbc.queryForObject("SELECT count(*) FROM fin_sim.dataset",Long.class));
        assertEquals(6156L,jdbc.queryForObject("SELECT count(*) FROM fin_sim.observation",Long.class));
        assertEquals(81L,jdbc.queryForObject("SELECT count(*) FROM fin_sim.expected_finding",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM fin_fact.fact_assertion",Long.class));
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM fin_diag.assessment_run",Long.class));

        for(Profile profile:profiles){
            List<DataPoint> scenario=points.stream().filter(p->p.scenario().equals(profile.code())).toList();
            Map<LocalDate,Map<String,DataPoint>> months=new TreeMap<>();
            scenario.forEach(v->months.computeIfAbsent(v.month(),d->new HashMap<>()).put(v.metric(),v));
            assertEquals(36,months.size(),profile.code());
            BigDecimal oldAr=BigDecimal.ZERO,oldAp=BigDecimal.ZERO,
                    oldInventory=BigDecimal.ZERO,oldCash=new BigDecimal("130000000"),
                    oldDebt=new BigDecimal("100000000");
            for(var e:months.entrySet()){
                Map<String,DataPoint> m=e.getValue();
                assertEquals(19,m.size(),profile.code()+"/"+e.getKey());
                BigDecimal cogs=val(m,"COGS"),rev=val(m,"REV_NET");
                BigDecimal expense=val(m,"OPEX"),tax=val(m,"TAX_PAYMENT");
                BigDecimal cashIn=val(m,"CASH_IN_OP"),cashOut=val(m,"CASH_OUT_OP");
                BigDecimal purchases=val(m,"PURCHASES"),capex=val(m,"CAPEX");
                BigDecimal draw=val(m,"DEBT_DRAW"),repay=val(m,"DEBT_REPAY");
                BigDecimal ar=val(m,"AR_END"),ap=val(m,"AP_END");
                BigDecimal inventory=val(m,"INVENTORY_END"),cash=val(m,"CASH_END");
                BigDecimal debt=val(m,"DEBT_END"),cashDelta=val(m,"NET_CASH_CHANGE");
                eq(oldCash.add(cashDelta),cash,"Cash rollforward",profile,e.getKey());
                eq(cashIn.subtract(cashOut).subtract(capex).add(draw).subtract(repay),
                        cashDelta,"Cash flow reconciliation",profile,e.getKey());
                eq(oldDebt.add(draw).subtract(repay),debt,"Debt rollforward",profile,e.getKey());
                if(cogs!=null) {
                    eq(oldInventory.add(purchases).subtract(cogs),inventory,
                            "Inventory rollforward",profile,e.getKey());
                    eq(oldAp.add(purchases).subtract(cashOut.subtract(expense).subtract(tax)),ap,
                            "Payables rollforward",profile,e.getKey());
                }
                if(rev!=null) {
                    eq(oldAr.add(rev).subtract(cashIn),ar,
                            "Receivables rollforward",profile,e.getKey());
                    if(cogs!=null) eq(rev.subtract(cogs).subtract(expense).subtract(tax),
                            val(m,"NET_PROFIT"),"Simplified net result",profile,e.getKey());
                }else assertNull(val(m,"NET_PROFIT"),"Missing revenue cannot produce net profit");
                oldAr=ar;oldAp=ap;oldInventory=inventory;oldCash=cash;oldDebt=debt;
            }
        }
    }

    private static BigDecimal val(Map<String,DataPoint> m,String key){
        DataPoint point=Objects.requireNonNull(m.get(key),key);
        return point.amount();
    }
    private static void eq(BigDecimal expected,BigDecimal actual,String dimension,
                           Profile profile,LocalDate date){
        assertEquals(0,expected.compareTo(Objects.requireNonNull(actual)),
                profile.code()+"/"+date+": "+dimension+" expected="+expected+" actual="+actual);
    }

    @Test
    @Order(3)
    void sqlDashboardIndependentlyChecksTrendReadinessAndNeverMixesCurrencies(){
        assertEquals(324L,jdbc.queryForObject(
                "SELECT count(*) FROM fin_sim.monthly_dashboard",Long.class));
        assertEquals(9L,jdbc.queryForObject("""
                SELECT count(*) FROM fin_sim.monthly_dashboard WHERE period_month='2026-10-01'
                """,Long.class));

        var healthy=dashboard("HEALTHY_RETAIL");
        assertTrue(((BigDecimal)healthy.get("revenue_yoy_percent")).signum()>0);
        assertTrue(((BigDecimal)healthy.get("trailing_12m_revenue")).signum()>0);
        assertTrue(((BigDecimal)healthy.get("moving_3m_revenue")).signum()>0);
        assertEquals(Boolean.TRUE,healthy.get("consistent_currency"));

        var stress=dashboard("WORKING_CAPITAL_STRESS");
        assertTrue(((BigDecimal)stress.get("revenue_mom_percent")).signum()>0);
        assertTrue(((BigDecimal)stress.get("cash_mom_change")).signum()<0);
        assertTrue(((BigDecimal)stress.get("ar_mom_change")).signum()>0);

        var manufacturing=dashboard("MARGIN_SHOCK_MANUFACTURING");
        assertTrue(((BigDecimal)manufacturing.get("gross_margin_yoy_points")).signum()<0);
        var seasonal=dashboard("SEASONAL_SERVICES");
        assertTrue(((BigDecimal)seasonal.get("revenue_mom_percent")).signum()<0);
        assertTrue(((BigDecimal)seasonal.get("revenue_yoy_percent")).signum()>0);
        var decline=dashboard("REVENUE_DECLINE_CONSTRUCTION");
        assertTrue(((BigDecimal)decline.get("revenue_yoy_percent")).signum()<0);

        var missing=dashboard("MISSING_COGS_RETAIL");
        assertNull(missing.get("gross_profit"));
        assertNull(missing.get("gross_margin_percent"));
        var zero=dashboard("ZERO_REVENUE_BASE_SERVICES");
        assertNull(zero.get("revenue_yoy_percent"));
        assertNull(zero.get("gross_margin_yoy_points"));
        var yearMissing=dashboard("MISSING_PRIOR_YEAR_RETAIL");
        assertNull(yearMissing.get("revenue_yoy_percent"));
        assertNull(yearMissing.get("gross_margin_yoy_points"));

        var mixed=dashboard("INVALID_MIXED_CURRENCY");
        assertEquals(Boolean.FALSE,mixed.get("consistent_currency"));
        assertNull(mixed.get("revenue_mom_percent"));
        assertNull(mixed.get("gross_margin_percent"));
        assertNull(mixed.get("trailing_12m_revenue"));
        assertNull(mixed.get("moving_3m_revenue"));
    }

    private Map<String,Object> dashboard(String scenario) {
        return jdbc.queryForMap("""
            SELECT * FROM fin_sim.monthly_dashboard
            WHERE scenario_code=? AND source_revision=1 AND period_month='2026-10-01'
            """,scenario);
    }

    @Test
    @Order(4)
    void replayAllNineCasesFromSeparateTablesThroughPublishedFactsAndGoldenOracle(){
        for(String metric:DIAGNOSTIC_METRICS) {
            boolean stock=metric.endsWith("_END");
            jdbc.update("""
                INSERT INTO fin_calc.metric_contract(
                    metric_code,metric_version,measure_kind,unit_code,qualification,method_ref)
                VALUES (?,'v1',?,'MONEY','PILOT','SIM_FIN_DIAG_V1') ON CONFLICT DO NOTHING
                """,metric,stock?"STOCK":"FLOW");
        }
        for(Profile profile:profiles) {
            Ledger ledger=replayToTrustedSyntheticFactSet(profile);
            boolean rejected=profile.expectedOutcome().equals("REJECT_CURRENCY");
            UUID assessmentId=null;
            JsonNode findings=null;
            if(rejected){
                var exception=assertThrows(IllegalArgumentException.class,()->
                     diagnosis.assessAndPublish(ledger.org(),ledger.factSetId(),EVALUATION),
                     profile.code());
                assertEquals("DIAGNOSTIC_MIXED_METRIC_VERSION_OR_CURRENCY",
                        exception.getMessage(),profile.code());
            }else{
                var receipt=diagnosis.assessAndPublish(ledger.org(),ledger.factSetId(),EVALUATION);
                assessmentId=receipt.id();
                var response=diagnosis.loadPublished(ledger.org(),assessmentId);
                findings=response.snapshot().path("findings");
                assertEquals(TrustedBusinessDiagnosticService.SourceFreshness.CURRENT_SOURCE,
                        response.sourceFreshness());
                assertEquals("SYNTHETIC",response.snapshot().path("dataSpace").asText());
                assertEquals(64,receipt.inputFingerprint().length());
                assertEquals(64,receipt.snapshotHash().length());
            }
            int expectedCount=oracle.stream().filter(e->e.scenario().equals(profile.code()))
                    .mapToInt(Expected::count).sum();
            Map<String,Integer> actualCounts=new HashMap<>();
            if(findings!=null)for(JsonNode finding:findings) {
                String key=finding.path("ruleCode").asText()+"/"+finding.path("kind").asText();
                actualCounts.merge(key,1,Integer::sum);
                assertTrue(finding.path("explanationKey").isTextual());
                if(!finding.path("evidence").isEmpty())
                    assertTrue(finding.path("evidence").get(0).path("metricCode").isTextual());
            }
            for(Expected item:oracle){
                if(!item.scenario().equals(profile.code()))continue;
                assertEquals(item.count(),actualCounts.getOrDefault(item.rule()+"/"+item.kind(),0),
                        "GOLDEN_ORACLE_MISMATCH "+profile.code()+"/"+item.rule()+"/"+item.kind());
            }
            assertEquals(expectedCount,actualCounts.values().stream().mapToInt(i->i).sum(),
                    "UNEXPECTED_EXTRA_FINDINGS "+profile.code());
            jdbc.update("""
                INSERT INTO fin_sim.evaluation_run(
                    id,scenario_code,source_revision,organization_id,fact_set_revision_id,
                    assessment_id,engine_version,passed,expected_finding_total,actual_finding_total)
                VALUES(?,?,1,?,?,?,?,true,?,?)
                """,UUID.randomUUID(),profile.code(),ledger.org(),ledger.factSetId(),
                    assessmentId,"BUSINESS_DIAGNOSTIC_V1",expectedCount,
                    actualCounts.values().stream().mapToInt(i->i).sum());
        }
        assertEquals(9L,jdbc.queryForObject(
                "SELECT count(*) FROM fin_sim.evaluation_run WHERE passed=true",Long.class));
        assertEquals(8L,jdbc.queryForObject(
                "SELECT count(*) FROM fin_sim.evaluation_run WHERE assessment_id IS NOT NULL",Long.class));
        assertEquals(0L,jdbc.queryForObject(
                "SELECT count(*) FROM fin_sim.evaluation_run WHERE passed=false",Long.class));
    }

    private Ledger replayToTrustedSyntheticFactSet(Profile profile){
        UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),
                scope=UUID.randomUUID(),revision=UUID.randomUUID();
        var samples=points.stream()
                .filter(p->p.scenario().equals(profile.code())&&p.revision()==1
                        && DIAGNOSTIC_METRICS.contains(p.metric())).toList();
        assertEquals(4*36,samples.size(),profile.code());
        assertEquals(144L,jdbc.queryForObject("""
                SELECT count(*) FROM fin_sim.observation WHERE scenario_code=?
                AND metric_code IN ('REV_NET','COGS','CASH_END','AR_END')
                """,Long.class,profile.code()));

        Map<String,UUID> sources=new HashMap<>();
        for(String form:samples.stream().map(DataPoint::form).distinct().toList()){
            UUID source=UUID.randomUUID();
            sources.put(form,source);
            jdbc.update("""
                INSERT INTO fin_fact.approved_source_revision(
                    organization_id,source_revision_id,form_code,form_version,approved_at)
                VALUES (?,?,?,'SIM1',now())
                """,org,source,form);
        }
        jdbc.update("""
            INSERT INTO fin_fact.fact_set_revision(id,organization_id,status,approved_at)
            VALUES (?,?,'APPROVED',now())
            """,revision,org);
        List<FactSeed> seeds=samples.stream().map(p->
                new FactSeed(UUID.randomUUID(),p,sources.get(p.form()))).toList();
        jdbc.batchUpdate("""
            INSERT INTO fin_fact.fact_assertion(
                id,organization_id,economic_entity_id,business_scope_id,
                metric_code,metric_version,time_kind,start_inclusive,end_exclusive,
                reporting_basis,unit_code,currency_code,dimension_hash,dimensions,
                value_state,numeric_value,estimate_method_ref,source_revision_id,recorded_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'{}'::jsonb,?,?,?,?,now())
            """,new BatchPreparedStatementSetter(){
                @Override public int getBatchSize(){return seeds.size();}
                @Override public void setValues(PreparedStatement stmt,int i)throws SQLException{
                    FactSeed s=seeds.get(i);DataPoint p=s.point();
                    LocalDate start=p.timeKind().equals("AS_OF")?
                            YearMonth.from(p.month()).atEndOfMonth():p.month();
                    LocalDate end=p.timeKind().equals("AS_OF")?
                            start.plusDays(1):p.month().plusMonths(1);
                    stmt.setObject(1,s.id());stmt.setObject(2,org);stmt.setObject(3,entity);
                    stmt.setObject(4,scope);stmt.setString(5,p.metric());
                    stmt.setString(6,p.metricVersion());stmt.setString(7,p.timeKind());
                    stmt.setObject(8,start);stmt.setObject(9,end);
                    stmt.setString(10,BASIS.name());stmt.setString(11,p.unit());
                    stmt.setString(12,p.currency());stmt.setString(13,ZERO_HASH);
                    stmt.setString(14,p.state());
                    if(p.amount()==null)stmt.setNull(15,Types.NUMERIC);
                    else stmt.setBigDecimal(15,p.amount());
                    stmt.setString(16,p.method());stmt.setObject(17,s.sourceId());
                }
            });
        jdbc.batchUpdate("""
            INSERT INTO fin_fact.fact_selection(
                organization_id,fact_set_revision_id,fact_key_hash,fact_assertion_id)
            VALUES(?,?,fin_fact.fact_identity_hash(?),?)
            """,new BatchPreparedStatementSetter(){
                @Override public int getBatchSize(){return seeds.size();}
                @Override public void setValues(PreparedStatement p,int i)throws SQLException{
                    UUID fact=seeds.get(i).id();
                    p.setObject(1,org);p.setObject(2,revision);
                    p.setObject(3,fact);p.setObject(4,fact);
                }
            });
        var pub=factPublication.publish(new FactSetPublicationService.Request(
                org,entity,scope,BASIS,AnalysisContext.DataSpace.SYNTHETIC,revision,null));
        assertEquals(revision,pub.revisionId());
        return new Ledger(org,entity,scope,revision);
    }
}
