package kz.fin.formlib.calc.hybrid;

import kz.fin.formlib.calc.partition.FactMonthlyPartitionService;
import kz.fin.formlib.service.FormDraftService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** CALC-166: SQL-only candidate SUM of pinned TEST_QUALIFIED sources.
 * No financial approval, no result publication, no production usage.
 */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc166IntervalAggregatePostgresqlTest {
  @Container
  static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("calc166_aggregate").withUsername("fin").withPassword("test_only");

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired FactMonthlyPartitionService partitions;
  @Autowired FormDraftService drafts;

  private static String sha(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(s.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception ex){throw new IllegalStateException(ex);}
  }

  record Fixture(UUID org,UUID factset,String dimension,LocalDate start,LocalDate end){}

  private Fixture source() { return source(false); }

  private Fixture source(boolean asOf) {
    YearMonth month=YearMonth.of(2026,11);
    partitions.ensureMonthlyRange(month,month,false);
    UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),
      business=UUID.randomUUID(),project=UUID.randomUUID(),factset=UUID.randomUUID();
    LocalDate start=month.atDay(1),end=month.plusMonths(1).atDay(1);
    var saved=drafts.save(new FormDraftService.DraftRequest(
      null,org,entity,business,project,"F02","M",month.toString(),"KZT",
      "PERIOD_ONLY","MANAGEMENT","SYNTHETIC",0,
      Map.of("netRevenue","125","cogs","50","operatingExpenses","10"),
      List.of(),"calc166-ci","synthetic read-only aggregation fixture"),
      "calc166-"+UUID.randomUUID());
    UUID version=jdbc.queryForObject("""
      SELECT form_version_id FROM fin_intake.lib_form_revision WHERE id=?
      """,UUID.class,saved.revisionId());
    String dim=sha("NO_DIMENSIONS"),scope=sha("SCOPE:"+org);
    var p=new MapSqlParameterSource()
      .addValue("id",factset).addValue("org",org).addValue("entity",entity)
      .addValue("business",business).addValue("project",project)
      .addValue("scope",scope).addValue("start",start).addValue("end",end)
      .addValue("source",saved.revisionId()).addValue("version",version)
      .addValue("sourceSha",saved.contentSha256()).addValue("dim",dim)
      .addValue("mapSha",sha("MAP:"+factset))
      .addValue("factSha",sha("FACT:"+factset))
      .addValue("bindingSha",sha("BINDING:"+factset));
    new NamedParameterJdbcTemplate(jdbc).update("""
      INSERT INTO fin_fact.fact_set(
        id,organization_id,economic_entity_id,business_scope_id,project_id,
        dataset_code,scope_key,data_space,coverage,reporting_basis,currency_code,
        target_period_type,target_period_code,target_period_start,
        target_period_end_exclusive,source_revisions_jsonb,
        source_set_sha256,mapping_bundle_sha256,canonicalization_version,
        observation_count,factset_sha256,qualification_binding_sha256,
        qualification_epoch,qualification_mode,issuer)
      VALUES(:id,:org,:entity,:business,:project,'CALC166_TEST',
        :scope,'SYNTHETIC','PERIOD_ONLY','MANAGEMENT','KZT',
        'M','2026-11',:start,:end,
        CAST('[]' AS jsonb),:sourceSha,:mapSha,1,2,:factSha,
        :bindingSha,1,'TEST_QUALIFIED','CALC166_TEST')
      """,p);
    if(asOf) partitions.ensureMonthlyRange(month,month,false);
    for(int i=0;i<2;i++){
      var row=new MapSqlParameterSource().addValues(p.getValues())
          .addValue("observation",UUID.randomUUID())
          .addValue("rowKey","ROW-"+i)
          .addValue("key",sha("KEY:"+factset+":"+i))
          .addValue("value",new BigDecimal(i==0?"125.25":"74.75"))
          .addValue("observationStart",asOf?start.plusDays(i):start)
          .addValue("observationEnd",asOf?start.plusDays(i+1):end)
          .addValue("semantics",asOf?"AS_OF":"INTERVAL")
          .addValue("valueSha",sha("VALUE:"+i));
      new NamedParameterJdbcTemplate(jdbc).update("""
        INSERT INTO fin_fact.fact_observation(
          period_start,observation_id,factset_id,source_revision_id,
          source_form_code,source_form_version_id,source_row_key,source_field_code,
          source_content_sha256,mapping_sha256,metric_code,metric_contract_version,
          period_type,period_end_exclusive,period_semantics,dimension_digest,
          dimensions_jsonb,observation_key_sha256,value_kind,value_state,
          numeric_value,unit_code,currency_code,canonical_value_sha256)
        VALUES(:observationStart,:observation,:id,:source,'F02',:version,:rowKey,'netRevenue',
          :sourceSha,:mapSha,'F02.netRevenue','TEST_ONLY_V1',
          'M',:observationEnd,:semantics,:dim,'{}'::jsonb,:key,
          'NUMERIC','PRESENT',:value,'KZT','KZT',:valueSha)
        """,row);
    }
    return new Fixture(org,factset,dim,start,end);
  }

  private Object[] arguments(Fixture f) {
    return new Object[]{f.org(),f.factset(),"F02","F02.netRevenue",
        f.start(),f.end(),f.dimension(),"KZT","KZT"};
  }

  @Test void exactPinnedIntervalSumAndNoResultWrite() {
    var f=source();
    var rows=jdbc.query("""
      SELECT observation_count,total_value FROM fin_calc.candidate_interval_sum(
        ?::uuid,?::uuid,?::varchar,?::varchar,?::date,?::date,?::char(64),
        ?::varchar,?::char(3))
      """,(rs,i)->new Object[]{rs.getLong(1),rs.getBigDecimal(2)},arguments(f));
    assertEquals(1,rows.size());
    assertEquals(2L,rows.getFirst()[0]);
    assertEquals(0,new BigDecimal("200.00").compareTo((BigDecimal)rows.getFirst()[1]));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));
  }

  @Test void wrongTenantPeriodUnitAndMissingMetricFailClosed(){
    var f=source();
    String call="""
      SELECT total_value FROM fin_calc.candidate_interval_sum(
        ?::uuid,?::uuid,?::varchar,?::varchar,?::date,?::date,?::char(64),
        ?::varchar,?::char(3))
      """;
    Object[] org=arguments(f);org[0]=UUID.randomUUID();
    assertThrows(DataAccessException.class,()->jdbc.queryForObject(call,BigDecimal.class,org));
    Object[] period=arguments(f);period[4]=f.start().minusDays(1);
    assertThrows(DataAccessException.class,()->jdbc.queryForObject(call,BigDecimal.class,period));
    Object[] unit=arguments(f);unit[7]="USD";
    assertThrows(DataAccessException.class,()->jdbc.queryForObject(call,BigDecimal.class,unit));
    Object[] metric=arguments(f);metric[3]="F02.unknown";
    assertThrows(DataAccessException.class,()->jdbc.queryForObject(call,BigDecimal.class,metric));
  }
  @Test void lastAsOfChoosesLatestSnapshotNotSum() {
    var f=source(true);
    String call="""
      SELECT observation_date,numeric_value
      FROM fin_calc.candidate_last_asof(
        ?::uuid,?::uuid,?::varchar,?::varchar,?::date,?::char(64),
        ?::varchar,?::char(3))
      """;
    var rows=jdbc.query(call,(rs,i)->new Object[]{
      rs.getDate(1).toLocalDate(),rs.getBigDecimal(2)},
      f.org(),f.factset(),"F02","F02.netRevenue",
      f.start().plusDays(2),f.dimension(),"KZT","KZT");
    assertEquals(1,rows.size());
    assertEquals(f.start().plusDays(1),rows.getFirst()[0]);
    assertEquals(0,new BigDecimal("74.75").compareTo((BigDecimal)rows.getFirst()[1]));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));
  }

  @Test void lastAsOfRejectsMissingFutureWrongTenantAndIntervalSource() {
    var f=source(true);
    String call="""
      SELECT numeric_value FROM fin_calc.candidate_last_asof(
        ?::uuid,?::uuid,?::varchar,?::varchar,?::date,?::char(64),
        ?::varchar,?::char(3))
      """;
    Object[] missing={f.org(),f.factset(),"F02","F02.netRevenue",
      f.start().minusDays(1),f.dimension(),"KZT","KZT"};
    assertThrows(DataAccessException.class,()->jdbc.queryForObject(call,BigDecimal.class,missing));
    Object[] future=missing.clone();future[4]=f.end();
    assertThrows(DataAccessException.class,()->jdbc.queryForObject(call,BigDecimal.class,future));
    Object[] tenant=missing.clone();tenant[0]=UUID.randomUUID();tenant[4]=f.start();
    assertThrows(DataAccessException.class,()->jdbc.queryForObject(call,BigDecimal.class,tenant));
    Object[] wrongUnit=missing.clone();wrongUnit[4]=f.start();wrongUnit[6]="USD";
    assertThrows(DataAccessException.class,()->jdbc.queryForObject(call,BigDecimal.class,wrongUnit));
    var interval=source(false);
    Object[] wrongGrain={interval.org(),interval.factset(),"F02","F02.netRevenue",
      interval.start(),interval.dimension(),"KZT","KZT"};
    assertThrows(DataAccessException.class,()->jdbc.queryForObject(call,BigDecimal.class,wrongGrain));
  }

  @Test void groupByUsesExactFactsetAndOneDimension() {
    var f=source();
    var rows=jdbc.query("""
      SELECT dimension_digest,observation_count,total_value
      FROM fin_calc.candidate_interval_group_by(
        ?::uuid,?::uuid,?::varchar,?::varchar,?::date,?::date,
        ?::varchar,?::char(3))
      """,(rs,i)->new Object[]{rs.getString(1),rs.getLong(2),rs.getBigDecimal(3)},
      f.org(),f.factset(),"F02","F02.netRevenue",f.start(),f.end(),"KZT","KZT");
    assertEquals(1,rows.size());
    assertEquals(f.dimension(),rows.getFirst()[0].toString().trim());
    assertEquals(2L,rows.getFirst()[1]);
    assertEquals(0,new BigDecimal("200.00").compareTo((BigDecimal)rows.getFirst()[2]));
  }

  @Test void groupByFailsOnWrongUnitAndMissingMetric() {
    var f=source();
    String call="""
      SELECT total_value FROM fin_calc.candidate_interval_group_by(
        ?::uuid,?::uuid,?::varchar,?::varchar,?::date,?::date,
        ?::varchar,?::char(3))
      """;
    assertThrows(DataAccessException.class,()->jdbc.queryForList(call,
      f.org(),f.factset(),"F02","F02.netRevenue",f.start(),f.end(),"USD","KZT"));
    assertThrows(DataAccessException.class,()->jdbc.queryForList(call,
      f.org(),f.factset(),"F02","F02.noSuchMetric",f.start(),f.end(),"KZT","KZT"));
  }

  @Test void ratioOfSumsRejectsMissingDenominatorAndSameMetric() {
    var f=source();
    String call="""
      SELECT ratio FROM fin_calc.candidate_ratio_of_sums(
        ?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,
        ?::date,?::date,?::char(64),?::varchar,?::char(3))
      """;
    assertThrows(DataAccessException.class,()->jdbc.queryForObject(call,BigDecimal.class,
      f.org(),f.factset(),"F02","F02.netRevenue","F02.cogs",f.start(),f.end(),
      f.dimension(),"KZT","KZT"));
    assertThrows(DataAccessException.class,()->jdbc.queryForObject(call,BigDecimal.class,
      f.org(),f.factset(),"F02","F02.netRevenue","F02.netRevenue",f.start(),f.end(),
      f.dimension(),"KZT","KZT"));
  }

  @Test void ratioOfSumsDividesTwoPinnedMetricTotals() {
    var f=source();
    // Synthetic COGS observation, on the same pinned source revision and
    // exact dimensional grain as the two netRevenue observations.
    jdbc.update("""
      INSERT INTO fin_fact.fact_observation(
        period_start,observation_id,factset_id,source_revision_id,
        source_form_code,source_form_version_id,source_row_key,source_field_code,
        source_content_sha256,mapping_sha256,metric_code,metric_contract_version,
        period_type,period_end_exclusive,period_semantics,dimension_digest,
        dimensions_jsonb,observation_key_sha256,value_kind,value_state,
        numeric_value,unit_code,currency_code,canonical_value_sha256)
      SELECT period_start,?,factset_id,source_revision_id,
        source_form_code,source_form_version_id,'COST-ROW','cogs',
        source_content_sha256,mapping_sha256,'F02.cogs',metric_contract_version,
        period_type,period_end_exclusive,period_semantics,dimension_digest,
        dimensions_jsonb,?,'NUMERIC','PRESENT',100.00,unit_code,currency_code,?
      FROM fin_fact.fact_observation
      WHERE factset_id=? AND metric_code='F02.netRevenue' LIMIT 1
      """,UUID.randomUUID(),sha("COST-KEY:"+f.factset()),sha("COST-VALUE:100.00"),
      f.factset());
    var ratio=jdbc.queryForObject("""
      SELECT ratio FROM fin_calc.candidate_ratio_of_sums(
        ?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,
        ?::date,?::date,?::char(64),?::varchar,?::char(3))
      """,BigDecimal.class,f.org(),f.factset(),"F02",
      "F02.netRevenue","F02.cogs",f.start(),f.end(),f.dimension(),"KZT","KZT");
    assertNotNull(ratio);
    assertEquals(0,new BigDecimal("2").compareTo(ratio));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));
  }

}
