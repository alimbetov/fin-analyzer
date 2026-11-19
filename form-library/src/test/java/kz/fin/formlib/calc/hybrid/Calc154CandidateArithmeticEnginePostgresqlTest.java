package kz.fin.formlib.calc.hybrid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PG16 pilot arithmetic DAG. No S2, no CalcPlan execution, no writes. */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc154CandidateArithmeticEnginePostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
      new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("calc154_arithmetic").withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void db(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired Calc154CandidateArithmeticJdbcEngine engine;
  @Autowired JdbcTemplate sql;

  private UUID version(String code){
    return sql.queryForObject("""
      SELECT v.id FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      WHERE f.form_code=?
      """,UUID.class,code);
  }
  private static BigDecimal d(String s){return new BigDecimal(s);}
  private static Map<String,BigDecimal> inputs(String... kv){
    var m=new HashMap<String,BigDecimal>();
    for(int i=0;i<kv.length;i+=2)m.put(kv[i],d(kv[i+1]));
    return m;
  }
  private static BigDecimal result(Calc154CandidateArithmeticJdbcEngine.CandidatePreview p,
      String code){
    return p.outputs().stream().filter(o->o.metricCode().equals(code)).findFirst()
      .orElseThrow().value();
  }
  private static void eq(String expected,BigDecimal actual){
    assertNotNull(actual);
    assertEquals(0,d(expected).compareTo(actual));
  }
  private void noWrites(){
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_fact.fact_set",Integer.class));
  }

  @Test void f02ThreeOutputsAreComputedFromOneDAGWithNativeSql(){
    UUID v=version("F02");
    var supplied=inputs("F02.netRevenue","200.00","F02.cogs","100.00",
      "F02.operatingExpenses","50.00");
    var compiled=engine.compile("F02",v);
    assertEquals(3,compiled.outputCodes().size());
    assertEquals(3,compiled.physicalInputCodes().size());
    assertTrue(compiled.sql().startsWith("WITH src AS (SELECT ?::numeric"));
    assertFalse(compiled.sql().contains("netRevenue"),
      "metric codes are data/metadata, never interpolated as SQL identifiers");
    var result=engine.preview("F02",v,supplied);
    assertEquals(3,result.outputs().size());
    eq("100.00",result(result,"F02.grossProfit"));
    eq("0.5",result(result,"F02.grossMargin"));
    eq("50.00",result(result,"F02.operatingResult"));
    assertFalse(result.financiallyApproved());
    assertFalse(compiled.financiallyApproved());
    assertEquals(64,result.compilationSha256().length());
    assertEquals(64,result.inputSha256().length());
    noWrites();
  }

  @Test void f01CandidateBalanceReconcilesToZeroAndSignedResidual(){
    UUID v=version("F01");
    var a=engine.preview("F01",v,inputs("F01.assets","100.00",
      "F01.liabilities","70.00","F01.equity","30.00"));
    eq("0",result(a,"F01.balanceResidual"));
    var b=engine.preview("F01",v,inputs("F01.assets","100.00",
      "F01.liabilities","70.01","F01.equity","30.00"));
    eq("-0.01",result(b,"F01.balanceResidual"));
    assertEquals(a.compilationSha256(),b.compilationSha256());
    assertNotEquals(a.inputSha256(),b.inputSha256());
    noWrites();
  }

  @Test void f03SignedCashRollForwardWorksWithoutAssumingFinancialApproval(){
    UUID v=version("F03");
    var result=engine.preview("F03",v,inputs("F03.openingCash","500",
      "F03.cfo","200","F03.cfi","-100","F03.cff","-50",
      "F03.closingCash","550"));
    eq("0",result(result,"F03.cashResidual"));
    assertFalse(result.financiallyApproved());
  }

  @Test void zeroDenominatorProducesInvalidNotFabricatedZero(){
    var output=engine.preview("F02",version("F02"),
      inputs("F02.netRevenue","0","F02.cogs","25",
        "F02.operatingExpenses","5"));
    var margin=output.outputs().stream()
      .filter(x->x.metricCode().equals("F02.grossMargin")).findFirst().orElseThrow();
    assertEquals("INVALID",margin.status());
    assertNull(margin.value());
    eq("-25",result(output,"F02.grossProfit"));
    noWrites();
  }

  @Test void deterministicCompilationAndIndependentPostgresqlF02Parity(){
    UUID v=version("F02");
    var a=engine.compile("F02",v);
    var b=engine.compile("F02",v);
    assertEquals(a.sql(),b.sql());
    assertEquals(a.compilationSha256(),b.compilationSha256());
    var actual=engine.preview("F02",v,
      inputs("F02.netRevenue","200","F02.cogs","100",
        "F02.operatingExpenses","50"));
    var independent=sql.queryForMap("""
      SELECT gross_profit,gross_margin,operating_result
      FROM fin_calc.f02_native_calculate(200,100,50)
      """);
    eq(((BigDecimal)independent.get("gross_profit")).toPlainString(),
      result(actual,"F02.grossProfit"));
    eq(((BigDecimal)independent.get("gross_margin")).toPlainString(),
      result(actual,"F02.grossMargin"));
    eq(((BigDecimal)independent.get("operating_result")).toPlainString(),
      result(actual,"F02.operatingResult"));
  }

  @Test void injectionExtraAndMissingInputAndWrongRevisionAreRejected(){
    UUID v=version("F02");
    assertThrows(IllegalArgumentException.class,()->engine.compile("C07",version("C07")));
    assertThrows(IllegalStateException.class,()->engine.compile("F02",UUID.randomUUID()));
    assertThrows(IllegalArgumentException.class,()->engine.preview("F02",v,
      inputs("F02.netRevenue","200","F02.cogs","100")));
    var input=inputs("F02.netRevenue","200","F02.cogs","100",
      "F02.operatingExpenses","50");
    input.put("F02.netRevenue'); DROP TABLE fin_fact.fact_set; --",BigDecimal.ONE);
    assertThrows(IllegalArgumentException.class,()->engine.preview("F02",v,input));
    noWrites();
  }

  @Test void missingValueAndTooManyDecimalsFailClosed(){
    var input=inputs("F02.netRevenue","200","F02.cogs","100",
      "F02.operatingExpenses","50");
    input.put("F02.cogs",null);
    assertThrows(IllegalArgumentException.class,()->engine.preview("F02",version("F02"),input));
    input.put("F02.cogs",d("0.1234567890123"));
    assertThrows(IllegalArgumentException.class,()->engine.preview("F02",version("F02"),input));
    noWrites();
  }
}
