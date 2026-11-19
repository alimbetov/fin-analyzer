package kz.fin.formlib.calc.hybrid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R16.B/R7.B inputs and candidate arithmetic are NOT S2-qualified. Real PG16
 * checks numeric parity but cannot approve accounting semantics or methods.
 */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class R16F01F03CandidatePostgresqlTest {
  @Container
  static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fin_f01_f03_draft")
      .withUsername("fin").withPassword("test_only");

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired JdbcTemplate jdbc;
  private static BigDecimal decimal(String s){return new BigDecimal(s);}

  private BigDecimal f01Sql(FinancialReconciliationProposals.F01 f){
    return jdbc.queryForObject("""
        SELECT fin_calc.f01_candidate_balance_residual(?,?,?,?)
        """,BigDecimal.class,Date.valueOf(f.asOf()),f.assets(),
        f.liabilities(),f.equity());
  }
  private BigDecimal f03Sql(FinancialReconciliationProposals.F03 f){
    return jdbc.queryForObject("""
        SELECT fin_calc.f03_candidate_cash_residual(?,?,?,?,?,?,?)
        """,BigDecimal.class,Date.valueOf(f.periodStart()),
        Date.valueOf(f.periodEndExclusive()),
        f.openingCash(),f.cfo(),f.cfi(),f.cff(),f.closingCash());
  }
  private static void equalMoney(BigDecimal expected,BigDecimal actual){
    assertNotNull(actual);
    assertEquals(0,expected.compareTo(actual),
        "Java and SQL candidate residual differ");
  }

  @Test void r16F01F03_01CurrentRealFieldCodesAreDraftNotApproved(){
    for(String form:new String[]{"F01","F03"}){
      var fields=jdbc.query("""
          SELECT ff.field_code,ff.field_type,c.review_state
          FROM fin_lib.lib_form_definition f
          JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
          JOIN fin_lib.lib_form_field ff ON ff.form_version_id=v.id
          JOIN fin_lib.lib_field_binding_contract c ON c.form_code=f.form_code
            AND c.field_code=ff.field_code
          WHERE f.form_code=? ORDER BY ff.field_code
          """,(r,i)->new String[]{r.getString(1),r.getString(2),r.getString(3)},form);
      Map<String,String> codes=new HashMap<>();
      for(var row:fields){
        assertEquals("CANDIDATE_UNQUALIFIED",row[2]);
        codes.put(row[0],row[1]);
      }
      if(form.equals("F01")){
        assertEquals(Set.of("assets","liabilities","equity","asOf"),codes.keySet());
        assertEquals("DATE",codes.get("asOf"));
        for(String key:List.of("assets","liabilities","equity"))
          assertEquals("MONEY",codes.get(key));
      }else{
        assertEquals(Set.of("openingCash","cfo","cfi","cff","closingCash"),codes.keySet());
        for(String key:codes.keySet())assertEquals("MONEY",codes.get(key));
      }
    }
    assertEquals(FinancialReconciliationProposals.STATUS,
        "CANDIDATE_UNQUALIFIED_NOT_S2");
  }

  @Test void r16F01F03_02BalanceJavaSqlBoundaryParity(){
    LocalDate d=LocalDate.of(2026,11,19);
    for(String[] values:new String[][]{
        {"100.00","70.00","30.00"},
        {"100.00","70.01","30.00"},
        {"0","0","0"},
        {"0","10","-10"},
        {"100.005","20.004","80.001"},
        {"999999999999999999.999999999999","1.000000000001","100.000000000000"}
    }){
      var v=new FinancialReconciliationProposals.F01(d,
          decimal(values[0]),decimal(values[1]),decimal(values[2]));
      equalMoney(FinancialReconciliationProposals.f01Residual(v),f01Sql(v));
    }
    assertThrows(IllegalArgumentException.class,()->
        new FinancialReconciliationProposals.F01(null,
            BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ZERO));
    assertThrows(DataAccessException.class,()->jdbc.queryForObject("""
        SELECT fin_calc.f01_candidate_balance_residual(NULL,1,1,0)
        """,BigDecimal.class));
  }

  @Test void r16F01F03_03CashJavaSqlSignedFlowAndPeriodParity(){
    LocalDate start=LocalDate.of(2026,11,1),end=LocalDate.of(2026,12,1);
    for(String[] values:new String[][]{
        {"500","200","-100","-50","550"},
        {"500","200","-100","-50","549.99"},
        {"0","0","0","0","0"},
        {"1.015","-0.015","-1.000","0","0"},
        {"-100","200","-50","-60","-10"}
    }){
      var v=new FinancialReconciliationProposals.F03(start,end,
          decimal(values[0]),decimal(values[1]),decimal(values[2]),
          decimal(values[3]),decimal(values[4]));
      equalMoney(FinancialReconciliationProposals.f03Residual(v),f03Sql(v));
    }
    assertThrows(IllegalArgumentException.class,()->
        new FinancialReconciliationProposals.F03(end,start,
            BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,
            BigDecimal.ZERO,BigDecimal.ZERO));
    assertThrows(DataAccessException.class,()->jdbc.queryForObject("""
        SELECT fin_calc.f03_candidate_cash_residual(
          DATE '2026-12-01',DATE '2026-11-01',1,1,1,1,1)
        """,BigDecimal.class));
  }

  @Test void r16F01F03_04DeterministicRandomizedParity(){
    var random=new Random(20261119L);
    LocalDate start=LocalDate.of(2026,1,1),end=LocalDate.of(2027,1,1);
    for(int i=0;i<100;i++){
      BigDecimal a=BigDecimal.valueOf(random.nextInt(20_000_001)-10_000_000,2);
      BigDecimal b=BigDecimal.valueOf(random.nextInt(20_000_001)-10_000_000,2);
      BigDecimal c=BigDecimal.valueOf(random.nextInt(20_000_001)-10_000_000,2);
      BigDecimal d=BigDecimal.valueOf(random.nextInt(20_000_001)-10_000_000,2);
      BigDecimal e=BigDecimal.valueOf(random.nextInt(20_000_001)-10_000_000,2);
      var balance=new FinancialReconciliationProposals.F01(start,a,b,c);
      equalMoney(FinancialReconciliationProposals.f01Residual(balance),f01Sql(balance));
      var cash=new FinancialReconciliationProposals.F03(start,end,a,b,c,d,e);
      equalMoney(FinancialReconciliationProposals.f03Residual(cash),f03Sql(cash));
    }
  }

  @Test void r16F01F03_05CandidateFunctionsNeverFinanciallyApproveOrWrite(){
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.method_revision
        """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.calc_result
        """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_fact.fact_set
        """,Integer.class));
    var funcs=jdbc.query("""
        SELECT p.proname,p.provolatile,p.proparallel
        FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='fin_calc' AND
          p.proname IN ('f01_candidate_balance_residual',
            'f03_candidate_cash_residual')
        """,(r,i)->new String[]{r.getString(1),r.getString(2),r.getString(3)});
    assertEquals(2,funcs.size());
    for(var f:funcs){
      assertEquals("i",f[1]);
      assertEquals("s",f[2]);
    }
  }
}
