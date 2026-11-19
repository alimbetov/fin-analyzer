package kz.fin.formlib.calc.kernel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Synthetic set-based benchmark, opt-in only. The release gate does not use
 * timing ratios as correctness checks: CI host speed/cache may fluctuate.
 *
 * Three equivalent E2E scopes, starting with the same PG16 10k/100k/1m rows:
 * JAVA_ONLY : stream 3N NUMERIC values over JDBC, SUM in Java, Java F02 SHA.
 * SQL_ONLY  : one SQL SELECT with SUM+native F02, JDBC 3 scalars, canonical SHA.
 * HYBRID    : one SQL SELECT of 3 SUMs, JDBC 3 scalars, Java F02 and SHA.
 *
 * Data creation, migration and Java/Spring boot are excluded from times.
 * Driver/network/transfer/result construction are included in all modes.
 * No signed financial S2, no immutable FactSet publication and no prod data.
 */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.sql.benchmark.enabled",matches="true")
class SqlF02BenchmarkPostgresqlTest {
  @Container
  static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fin_sql_f02_benchmark")
      .withUsername("fin").withPassword("synthetic_only");

  @DynamicPropertySource
  static void postgres(DynamicPropertyRegistry registry){
    registry.add("spring.datasource.url",pg::getJdbcUrl);
    registry.add("spring.datasource.username",pg::getUsername);
    registry.add("spring.datasource.password",pg::getPassword);
    registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired ObjectMapper mapper;
  private final F02CalculationKernel kernel=new F02CalculationKernel();
  private volatile String sink;
  private enum Mode{JAVA_ONLY,SQL_ONLY,HYBRID}
  private record Observation(BigDecimal revenue,BigDecimal cogs,BigDecimal opex){
    Map<String,BigDecimal> asMap(){
      return Map.of("F02.netRevenue",revenue,"F02.cogs",cogs,
          "F02.operatingExpenses",opex);
    }
  }
  private record Sample(Mode mode,long durationNanos,
      F02CalculationKernel.Result result) {}
  private record Timing(String mode,double medianMs,List<Double> samplesMs) {}

  private TransactionTemplate readOnlyTx(){
    var tx=new TransactionTemplate(manager);
    tx.setReadOnly(true);
    tx.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    return tx;
  }

  private Observation pgTotals(int rows){
    return readOnlyTx().execute(status->jdbc.queryForObject("""
        SELECT SUM(net_revenue) net_revenue,SUM(cogs) cogs,
          SUM(operating_expenses) operating_expenses
        FROM fin_calc.f02_bench_rows WHERE id<=?
        """,(r,i)->new Observation(
            r.getBigDecimal("net_revenue"),r.getBigDecimal("cogs"),
            r.getBigDecimal("operating_expenses")),rows));
  }

  private F02CalculationKernel.Result javaOnly(int count){
    return readOnlyTx().execute(status->{
      BigDecimal[] sum={BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO};
      // Streaming server-side cursor: no ResultSet materialization in heap.
      jdbc.query(conn->{
        var ps=conn.prepareStatement("""
            SELECT net_revenue,cogs,operating_expenses
            FROM fin_calc.f02_bench_rows WHERE id<=?
            """);
        ps.setFetchSize(2000);
        ps.setInt(1,count);
        return ps;
      },(RowCallbackHandler)rs->{
        sum[0]=sum[0].add(rs.getBigDecimal(1));
        sum[1]=sum[1].add(rs.getBigDecimal(2));
        sum[2]=sum[2].add(rs.getBigDecimal(3));
      });
      return kernel.evaluate(new Observation(sum[0],sum[1],sum[2]).asMap());
    });
  }

  private F02CalculationKernel.Result hybrid(int count){
    return kernel.evaluate(pgTotals(count).asMap());
  }

  private F02CalculationKernel.Result sqlOnly(int count){
    return readOnlyTx().execute(status->{
      var result=jdbc.query("""
          WITH totals AS MATERIALIZED (
            SELECT SUM(net_revenue) net_revenue,SUM(cogs) cogs,
              SUM(operating_expenses) operating_expenses
            FROM fin_calc.f02_bench_rows WHERE id<=?
          )
          SELECT f.gross_profit,f.gross_margin,f.operating_result,
            f.margin_state
          FROM totals t
          CROSS JOIN LATERAL fin_calc.f02_native_calculate(
            t.net_revenue,t.cogs,t.operating_expenses) f
          """,(r,i)->{
            var state=r.getString("margin_state");
            var margin=r.getBigDecimal("gross_margin");
            if(!("INVALID".equals(state)&&margin==null
                ||"PRESENT".equals(state)&&margin!=null))
              throw new IllegalStateException("CALC_SQL_BENCH_INVALID_MARGIN");
            return F02CalculationKernel.fromCalculated(
                r.getBigDecimal("gross_profit"),margin,
                r.getBigDecimal("operating_result"));
          },count);
      if(result.size()!=1)
        throw new IllegalStateException("CALC_SQL_BENCH_SHAPE_MISMATCH");
      return result.getFirst();
    });
  }

  private F02CalculationKernel.Result execute(Mode mode,int count){
    return switch(mode){
      case JAVA_ONLY -> javaOnly(count);
      case SQL_ONLY -> sqlOnly(count);
      case HYBRID -> hybrid(count);
    };
  }

  private Sample measure(Mode mode,int count){
    long start=System.nanoTime();
    var result=execute(mode,count);
    return new Sample(mode,System.nanoTime()-start,result);
  }

  private static double millis(long nanos){
    return Math.round(nanos/1000.0)/1000.0;
  }
  private static Timing median(Mode mode,List<Long> samples){
    var sorted=samples.stream().sorted().toList();
    return new Timing(mode.name(),millis(sorted.get(sorted.size()/2)),
        samples.stream().map(SqlF02BenchmarkPostgresqlTest::millis).toList());
  }

  @Test void benchmarkJavaSqlHybridIdenticalOutputAcrossThreeVolumes() throws Exception{
    jdbc.execute("""
        CREATE TABLE fin_calc.f02_bench_rows(
          id BIGINT PRIMARY KEY,
          net_revenue NUMERIC(38,12) NOT NULL,
          cogs NUMERIC(38,12) NOT NULL,
          operating_expenses NUMERIC(38,12) NOT NULL)
        """);
    jdbc.update("""
        INSERT INTO fin_calc.f02_bench_rows(
          id,net_revenue,cogs,operating_expenses)
        SELECT g,
          ((g % 10000)+10000)::numeric/100,
          ((g % 7000)+5000)::numeric/100,
          ((g % 3000)+1000)::numeric/100
        FROM generate_series(1,1000000) g
        """);
    jdbc.execute("ANALYZE fin_calc.f02_bench_rows");
    assertEquals(1_000_000,jdbc.queryForObject(
        "SELECT count(*) FROM fin_calc.f02_bench_rows",Integer.class));

    // Warm up JDBC driver, prepared statements, PG buffer cache and JVM
    // before timed samples. Never compare an initial cold call to warm calls.
    for(var mode:Mode.values()){
      for(int i=0;i<2;i++)sink=execute(mode,10_000).outputValuesSha256();
    }
    List<Map<String,Object>> scenarios=new ArrayList<>();
    for(var count:new int[]{10_000,100_000,1_000_000}){
      var oracle=kernel.evaluate(pgTotals(count).asMap());
      Map<Mode,List<Long>> samples=new LinkedHashMap<>();
      for(var mode:Mode.values())samples.put(mode,new ArrayList<>());
      // Rotating order across three rounds reduces stable cache/JIT bias.
      for(int round=0;round<3;round++){
        for(int offset=0;offset<3;offset++){
          var mode=Mode.values()[(round+offset)%3];
          var sample=measure(mode,count);
          assertEquals(oracle,sample.result(),
              "F02 SHA mismatch mode="+mode+" rows="+count);
          samples.get(mode).add(sample.durationNanos());
          sink=sample.result().outputValuesSha256();
        }
      }
      var timings=new ArrayList<Timing>();
      for(var mode:Mode.values())timings.add(median(mode,samples.get(mode)));
      var winner=timings.stream().min(Comparator.comparingDouble(
          Timing::medianMs)).orElseThrow();
      var scenario=new LinkedHashMap<String,Object>();
      scenario.put("rows",count);
      scenario.put("runsPerMode",3);
      scenario.put("measurements",timings);
      scenario.put("fastestObservedMode",winner.mode);
      scenario.put("outputValuesSha256",oracle.outputValuesSha256());
      scenarios.add(scenario);
      System.out.printf(Locale.ROOT,
          "[BENCH] rows=%d JAVA_ONLY=%.3fms SQL_ONLY=%.3fms "
              +"HYBRID=%.3fms winner=%s sha=%s%n",
          count,timings.get(0).medianMs,timings.get(1).medianMs,
          timings.get(2).medianMs,winner.mode,oracle.outputValuesSha256());
    }

    // Also measure scalar per-call JDBC latency separately. It must never
    // be confused with 10k/100k/1m set aggregation.
    Map<String,BigDecimal> single=Map.of(
        "F02.netRevenue",new BigDecimal("12500000"),
        "F02.cogs",new BigDecimal("7400000"),
        "F02.operatingExpenses",new BigDecimal("2100000"));
    SqlF02CalculationKernel scalarNative=new SqlF02CalculationKernel(jdbc);
    for(int i=0;i<20;i++){
      assertEquals(kernel.evaluate(single),scalarNative.evaluate(single));
    }
    long javaStart=System.nanoTime();
    for(int i=0;i<200;i++)sink=kernel.evaluate(single).outputValuesSha256();
    long javaNanos=System.nanoTime()-javaStart;
    long sqlStart=System.nanoTime();
    for(int i=0;i<200;i++)sink=scalarNative.evaluate(single).outputValuesSha256();
    long sqlNanos=System.nanoTime()-sqlStart;
    System.out.printf(Locale.ROOT,
        "[BENCH] scalarCalls=200 javaTotal=%.3fms sqlJDBCtotal=%.3fms%n",
        millis(javaNanos),millis(sqlNanos));

    var report=new LinkedHashMap<String,Object>();
    report.put("contract","FIN-CALC-SQL-01-JAVA-SQL-HYBRID-PG16-V1");
    report.put("mode","SYNTHETIC_TEST_ONLY_NOT_FINANCIAL_S2");
    report.put("db","PostgreSQL 16 Testcontainers, isolated dedicated database");
    report.put("measurement","Wall time including query/transfer/canonical hashing");
    report.put("exclusions","Fixture generation, DDL, Liquibase startup, production concurrency");
    report.put("warning","CI runner CPU/cache vary; use repeatable dedicated hardware before production selection");
    report.put("scenarios",scenarios);
    report.put("scalar",Map.of("calls",200,"javaTotalMs",millis(javaNanos),
        "sqlJdbcTotalMs",millis(sqlNanos)));
    Path dir=Path.of("target/fin-calc-sql-benchmark");
    Files.createDirectories(dir);
    mapper.writerWithDefaultPrettyPrinter().writeValue(
        dir.resolve("comparative-benchmark.json").toFile(),report);
  }
}
