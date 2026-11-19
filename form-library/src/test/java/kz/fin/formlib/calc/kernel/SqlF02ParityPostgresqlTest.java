package kz.fin.formlib.calc.kernel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Real PG16 native SQL versus Java oracle. No financial S2, no fact promotion. */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class SqlF02ParityPostgresqlTest {
  @Container
  static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fin_sql_f02_parity")
      .withUsername("fin").withPassword("pg_qa_only");

  @DynamicPropertySource static void postgres(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired SqlF02CalculationKernel nativeKernel;
  @Autowired JdbcTemplate sql;
  private final F02CalculationKernel javaKernel=new F02CalculationKernel();

  private static Map<String,BigDecimal> inputs(String revenue,String costs,String expenses){
    return Map.of("F02.netRevenue",new BigDecimal(revenue),
        "F02.cogs",new BigDecimal(costs),
        "F02.operatingExpenses",new BigDecimal(expenses));
  }

  private static Stream<Arguments> vectors(){
    return Stream.of(
        Arguments.of("normal","12500000","7400000","2100000"),
        Arguments.of("all-zero","0","0","0"),
        Arguments.of("zero-and-loss","0","1","2"),
        Arguments.of("rounded-zero-denominator","0.004","0","0"),
        Arguments.of("bankers-tie-even-0","1.005","0","0.005"),
        Arguments.of("bankers-tie-odd-1","1.015","0","0.015"),
        Arguments.of("bankers-tie-even-2","1.025","0","0.025"),
        Arguments.of("bankers-tie-odd-3","1.035","0","0.035"),
        Arguments.of("ratio-tie-even","2000000","1999999.99","0"),
        Arguments.of("ratio-tie-odd","2000000","1999999.97","0"),
        Arguments.of("negative-ratio-tie","2000000","2000000.01","0"),
        Arguments.of("negative-profit","100.00","150.00","1.01"),
        Arguments.of("small-revenue","0.01","0.00","0.01"),
        Arguments.of("precision-near-limit","99999999999999999999.99","11.22","9.95"),
        Arguments.of("fraction-to-round","12.999999999999","0.005","0.005"),
        Arguments.of("noncanonical-trailing-zero","12.500000000000","4.000000000000","1.000000000000")
    );
  }

  @ParameterizedTest(name="SQL parity {0}")
  @MethodSource("vectors")
  void sql01ReferenceVectorsMatchAllValuesStatesAndSha(
      String label,String revenue,String cost,String expense){
    var sources=inputs(revenue,cost,expense);
    var javaValue=javaKernel.evaluate(sources);
    var sqlValue=nativeKernel.evaluate(sources);
    assertEquals(javaValue,sqlValue,"mismatch vector="+label);
    assertEquals(javaValue.outputValuesSha256(),sqlValue.outputValuesSha256());
  }

  @Test void sql02DeterministicRandomizedDecimalParity(){
    Random random=new Random(20261119L);
    for(int i=0;i<256;i++){
      var sources=Map.of(
          "F02.netRevenue",new BigDecimal(random.nextInt(10_000_000))
              .movePointLeft(random.nextInt(5)),
          "F02.cogs",new BigDecimal(random.nextInt(10_000_000))
              .movePointLeft(random.nextInt(5)),
          "F02.operatingExpenses",new BigDecimal(random.nextInt(10_000_000))
              .movePointLeft(random.nextInt(5)));
      assertEquals(javaKernel.evaluate(sources),nativeKernel.evaluate(sources),
          "randomized SQL/Java parity at index="+i);
    }
  }

  @Test void sql03NativeRoundHalfEvenDiffersFromPostgresDefaultRound(){
    BigDecimal reference=sql.queryForObject("""
        SELECT fin_calc.f02_round_half_even(1.005::numeric,2)
        """,BigDecimal.class);
    BigDecimal pgDefault=sql.queryForObject("""
        SELECT round(1.005::numeric,2)
        """,BigDecimal.class);
    assertEquals(0,reference.compareTo(new BigDecimal("1.00")));
    assertEquals(0,pgDefault.compareTo(new BigDecimal("1.01")));
    assertEquals(0,sql.queryForObject("""
        SELECT fin_calc.f02_round_half_even(-1.025::numeric,2)
        """,BigDecimal.class).compareTo(new BigDecimal("-1.02")));
  }

  @Test void sql04RejectMalformedInputWithoutSilentlyApproving(){
    assertThrows(IllegalArgumentException.class,()->nativeKernel.evaluate(
        Map.of("F02.netRevenue",BigDecimal.ONE)));
    assertThrows(IllegalArgumentException.class,()->nativeKernel.evaluate(
        inputs("1","-1","1")));
    assertThrows(IllegalArgumentException.class,()->nativeKernel.evaluate(
        inputs("1.0000000000001","1","1")));
    assertThrows(DataAccessException.class,()->sql.queryForList("""
        SELECT * FROM fin_calc.f02_native_calculate(-1,1,1)
        """));
    assertThrows(DataAccessException.class,()->sql.queryForList("""
        SELECT * FROM fin_calc.f02_native_calculate(NULL,1,1)
        """));
  }

  @Test void sql05PureFunctionsAreImmutableAndParallelSafe(){
    var functions=sql.queryForList("""
        SELECT p.proname,p.provolatile,p.proparallel
        FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='fin_calc' AND p.proname IN (
          'f02_round_half_even','f02_ratio_half_even','f02_native_calculate')
        """);
    assertEquals(3,functions.size());
    for(var function:functions){
      assertEquals("i",function.get("provolatile"));
      assertEquals("s",function.get("proparallel"));
    }
    assertEquals(0,sql.queryForObject("""
        SELECT count(*) FROM fin_calc.calc_result
        """,Integer.class),"SQL arithmetic must not publish a result");
    assertEquals(0,sql.queryForObject("""
        SELECT count(*) FROM fin_fact.fact_set
        """,Integer.class),"SQL arithmetic must not qualify a FactSet");
  }
}
