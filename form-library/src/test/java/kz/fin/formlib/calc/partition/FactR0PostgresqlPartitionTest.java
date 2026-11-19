package kz.fin.formlib.calc.partition;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FACT-0-only physical acceptance. Direct synthetic SQL fixtures test DDL
 * constraints, NOT a real qualification gateway (FACT-1 remains unimplemented).
 */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class FactR0PostgresqlPartitionTest {
  @Container
  static final PostgreSQLContainer<?> db=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fin_fact_r0_physical_test")
      .withUsername("fin").withPassword("test_only_pg");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",db::getJdbcUrl);
    r.add("spring.datasource.username",db::getUsername);
    r.add("spring.datasource.password",db::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired FactMonthlyPartitionService partitions;
  @Autowired FormDraftService drafts;
  @Autowired PlatformTransactionManager txManager;

  private NamedParameterJdbcTemplate named() { return new NamedParameterJdbcTemplate(jdbc); }

  record Fixture(UUID organizationId,UUID economicEntityId,UUID businessScopeId,
                 UUID projectId,UUID revisionId,UUID formVersionId,UUID factsetId,
                 String sourceSha,String scopeKey,YearMonth month) {}

  private static String sha(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception ex){throw new IllegalStateException(ex);}
  }

  private Fixture newFixture(YearMonth month) {
    var org=UUID.randomUUID();var entity=UUID.randomUUID();
    var business=UUID.randomUUID();var project=UUID.randomUUID();
    var request=new FormDraftService.DraftRequest(null,org,entity,business,project,
        "F02","M",month.toString(),"KZT","PERIOD_ONLY","MANAGEMENT",
        "SYNTHETIC",0,
        Map.of("netRevenue","125","cogs","50","operatingExpenses","10"),
        List.of(),"fact0-ci","synthetic storage fixture");
    var saved=drafts.save(request,"fact0-"+UUID.randomUUID());
    UUID version=jdbc.queryForObject(
        "SELECT form_version_id FROM fin_intake.lib_form_revision WHERE id=?",
        UUID.class,saved.revisionId());
    return new Fixture(org,entity,business,project,saved.revisionId(),
        version,UUID.randomUUID(),saved.contentSha256(),
        sha("FACT0:"+org+":"+month),month);
  }

  private void insertSet(Fixture f) {
    var params=new MapSqlParameterSource()
        .addValue("id",f.factsetId()).addValue("org",f.organizationId())
        .addValue("entity",f.economicEntityId())
        .addValue("business",f.businessScopeId()).addValue("project",f.projectId())
        .addValue("scope",f.scopeKey()).addValue("code",f.month().toString())
        .addValue("start",f.month().atDay(1))
        .addValue("end",f.month().plusMonths(1).atDay(1))
        .addValue("sources","[{\"revisionId\":\""+f.revisionId()+"\"}]")
        .addValue("sourceSha",f.sourceSha())
        .addValue("mapSha",sha("FACT0_TEST_ONLY_MAPPING"))
        .addValue("factSha",sha("FACT0_TEST_FACT:"+f.factsetId()))
        .addValue("bindingSha",sha("FACT0_TEST_BINDING:"+f.factsetId()));
    named().update("""
        INSERT INTO fin_fact.fact_set (
          id, organization_id, economic_entity_id, business_scope_id, project_id,
          dataset_code, scope_key, data_space, coverage, reporting_basis,
          currency_code, target_period_type, target_period_code,
          target_period_start, target_period_end_exclusive, source_revisions_jsonb,
          source_set_sha256, mapping_bundle_sha256, canonicalization_version,
          observation_count, factset_sha256, qualification_binding_sha256,
          qualification_epoch, qualification_mode, issuer)
        VALUES (
          :id,:org,:entity,:business,:project,'F02_TEST_ONLY',
          :scope,'SYNTHETIC','PERIOD_ONLY','MANAGEMENT','KZT',
          'M',:code,:start,:end,CAST(:sources AS jsonb),
          :sourceSha,:mapSha,1,1,:factSha,:bindingSha,
          1,'TEST_QUALIFIED','FACT0_SQL_TEST_FIXTURE')
        """,params);
  }

  private UUID insertObservation(Fixture f,String key) {
    UUID id=UUID.randomUUID();
    var params=new MapSqlParameterSource()
        .addValue("start",f.month().atDay(1))
        .addValue("end",f.month().plusMonths(1).atDay(1))
        .addValue("id",id).addValue("factset",f.factsetId())
        .addValue("revision",f.revisionId()).addValue("version",f.formVersionId())
        .addValue("contentSha",f.sourceSha())
        .addValue("mapSha",sha("FACT0_TEST_ONLY_MAPPING"))
        .addValue("dimensionSha",sha("NO_DIMENSIONS"))
        .addValue("key",key)
        .addValue("valueSha",sha("TEST_VALUE_125"));
    named().update("""
        INSERT INTO fin_fact.fact_observation (
          period_start,observation_id,factset_id,source_revision_id,
          source_form_code,source_form_version_id,source_row_key,source_field_code,
          source_content_sha256,mapping_sha256,metric_code,metric_contract_version,
          period_type,period_end_exclusive,period_semantics,dimension_digest,
          dimensions_jsonb,observation_key_sha256,value_kind,value_state,
          numeric_value,unit_code,currency_code,canonical_value_sha256)
        VALUES (
          :start,:id,:factset,:revision,'F02',:version,'SINGLE','netRevenue',
          :contentSha,:mapSha,'F02.netRevenue','TEST_ONLY_V1',
          'M',:end,'INTERVAL',:dimensionSha,CAST('{}' AS jsonb),:key,
          'NUMERIC','PRESENT',125,'KZT','KZT',:valueSha)
        """,params);
    return id;
  }

  @Test void partitionedSchemaIsPresentWithCompositePrimaryKeyAndNoDefault() {
    assertEquals(3,jdbc.queryForObject("""
        SELECT count(*) FROM information_schema.tables
        WHERE table_schema='fin_fact'
          AND table_name IN ('fact_set','fact_observation','factset_control')
        """,Integer.class));
    assertEquals(5,jdbc.queryForObject("""
        SELECT count(*) FROM information_schema.tables
        WHERE table_schema='fin_calc'
          AND table_name IN ('calc_outbox_event','calc_plan',
                             'calc_job','calc_result','calc_current_result')
        """,Integer.class));
    assertEquals("RANGE (period_start)",jdbc.queryForObject(
        "SELECT pg_get_partkeydef('fin_fact.fact_observation'::regclass)",
        String.class));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM pg_inherits i
        JOIN pg_class c ON c.oid=i.inhrelid
        WHERE i.inhparent='fin_fact.fact_observation'::regclass
          AND c.relname LIKE '%default%'
        """,Integer.class));
    String pk=jdbc.queryForObject("""
        SELECT pg_get_constraintdef(oid) FROM pg_constraint
        WHERE conrelid='fin_fact.fact_observation'::regclass AND contype='p'
        """,String.class);
    assertTrue(pk.contains("period_start")&&pk.contains("observation_id"));
  }

  @Test void dryRunDoesNotWriteAndCreationIsIdempotent() {
    var target=YearMonth.of(2026,10);
    var baseline=partitions.inspectRequiredCoverage(target,target.plusMonths(1));
    assertEquals(2,baseline.months().size());
    assertEquals(0,partitions.ensureMonthlyRange(target,target.plusMonths(1),true).created());
    assertEquals(2,partitions.ensureMonthlyRange(target,target.plusMonths(1),false).months().size());
    assertTrue(partitions.inspectRequiredCoverage(target,target.plusMonths(1)).complete());
    assertEquals(2,partitions.ensureMonthlyRange(target,target.plusMonths(1),false).existed());
    assertThrows(IllegalArgumentException.class,
        ()->partitions.ensureMonthlyRange(target,target.plusMonths(61),false));
    assertThrows(IllegalArgumentException.class,
        ()->partitions.ensureMonthlyRange(target.plusMonths(1),target,false));
  }

  @Test void octoberAndNovemberRowsRouteToSeparatePartitionsAndCannotChange() {
    var oct=YearMonth.of(2026,10);var nov=oct.plusMonths(1);
    partitions.ensureMonthlyRange(oct,nov,false);
    var first=newFixture(oct);var second=newFixture(nov);
    insertSet(first);insertSet(second);
    UUID x=insertObservation(first,sha("key-oct")),y=insertObservation(second,sha("key-nov"));
    assertEquals(1,jdbc.queryForObject(
        "SELECT count(*) FROM fin_fact.fact_observation_2026_10 WHERE observation_id=?",
        Integer.class,x));
    assertEquals(1,jdbc.queryForObject(
        "SELECT count(*) FROM fin_fact.fact_observation_2026_11 WHERE observation_id=?",
        Integer.class,y));
    assertThrows(DataAccessException.class,()->jdbc.update("""
        UPDATE fin_fact.fact_observation SET numeric_value=999
        WHERE period_start=? AND observation_id=?
        """,oct.atDay(1),x));
    assertThrows(DataAccessException.class,()->jdbc.update(
        "DELETE FROM fin_fact.fact_observation WHERE period_start=? AND observation_id=?",
        oct.atDay(1),x));
    assertThrows(DataAccessException.class,()->jdbc.update(
        "UPDATE fin_fact.fact_set SET observation_count=9 WHERE id=?",
        first.factsetId()));
    assertThrows(DataAccessException.class,()->jdbc.update(
        "DELETE FROM fin_fact.fact_set WHERE id=?",first.factsetId()));
    assertThrows(DataAccessException.class,
        ()->insertObservation(first,sha("key-oct"))); // immutable business uniqueness
  }

  @Test void missingHistoricalMonthHasNoSilentFallback() {
    var missing=YearMonth.of(2094,2);
    assertEquals(FactMonthlyPartitionService.State.MISSING,
        partitions.inspectRequiredCoverage(missing,missing).months().get(0).state());
    var f=newFixture(missing);
    insertSet(f);
    assertThrows(DataAccessException.class,()->insertObservation(f,sha("missing-part")));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_fact.fact_observation WHERE factset_id=?
        """,Integer.class,f.factsetId()));
    // A controlled maintenance action can cover historical backfill, not
    // a business/qualification transaction's implicit DDL.
    partitions.ensureMonthlyRange(missing,missing,false);
    insertObservation(f,sha("missing-part"));
    assertEquals(1,jdbc.queryForObject("""
        SELECT count(*) FROM fin_fact.fact_observation
        WHERE factset_id=? AND period_start=?
        """,Integer.class,f.factsetId(),missing.atDay(1)));
  }

  @Test void activePointerCannotLinkToForeignTenantOrScope() {
    var m=YearMonth.of(2026,10);
    partitions.ensureMonthlyRange(m,m,false);
    var f=newFixture(m);
    insertSet(f);
    assertThrows(DataAccessException.class,()->jdbc.update("""
        INSERT INTO fin_fact.factset_control
         (id,stable_scope_key,organization_id,active_factset_id,qualification_epoch)
        VALUES (?,?,?,?,?)
        """,UUID.randomUUID(),sha("WRONG_SCOPE"),f.organizationId(),f.factsetId(),1));
    assertThrows(DataAccessException.class,()->jdbc.update("""
        INSERT INTO fin_fact.factset_control
         (id,stable_scope_key,organization_id,active_factset_id,qualification_epoch)
        VALUES (?,?,?,?,?)
        """,UUID.randomUUID(),f.scopeKey(),UUID.randomUUID(),f.factsetId(),1));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_fact.factset_control
        WHERE active_factset_id=?
        """,Integer.class,f.factsetId()));
  }

  @Test void transactionRollbackRemovesHeaderObservationAndOutbox() {
    var m=YearMonth.of(2026,11);partitions.ensureMonthlyRange(m,m,false);
    var f=newFixture(m);
    new TransactionTemplate(txManager).execute(status->{
      insertSet(f);
      insertObservation(f,sha("rollback"));
      jdbc.update("""
          INSERT INTO fin_calc.calc_outbox_event
           (id,organization_id,scope_key,producer,producer_event_id,
            event_type,payload_jsonb)
          VALUES (?,?,?,?,?,'FACTSET_QUALIFIED','{}'::jsonb)
          """,UUID.randomUUID(),f.organizationId(),f.scopeKey(),"FACT0_TEST",
          f.factsetId().toString());
      status.setRollbackOnly();
      return null;
    });
    assertEquals(0,jdbc.queryForObject(
        "SELECT count(*) FROM fin_fact.fact_set WHERE id=?",Integer.class,f.factsetId()));
    assertEquals(0,jdbc.queryForObject(
        "SELECT count(*) FROM fin_calc.calc_outbox_event WHERE producer_event_id=?",
        Integer.class,f.factsetId().toString()));
    assertEquals(0,jdbc.queryForObject(
        "SELECT count(*) FROM fin_fact.fact_observation WHERE factset_id=?",
        Integer.class,f.factsetId()));
  }

  @Test void malformedSameNamedChildIsRejectedInsteadOfSkipped() {
    var month=YearMonth.of(2093,1);
    String table="fin_fact.fact_observation_2093_01";
    jdbc.execute("CREATE TABLE "+table+" (id UUID PRIMARY KEY)");
    try{
      assertEquals(FactMonthlyPartitionService.State.INVALID,
          partitions.inspectRequiredCoverage(month,month).months().get(0).state());
      var error=assertThrows(IllegalStateException.class,
          ()->partitions.ensureMonthlyRange(month,month,false));
      assertTrue(error.getMessage().contains("FACT_PARTITION_CORRUPT"));
    }finally{
      // TEST-ONLY cleanup of an intentionally malformed unrelated table.
      jdbc.execute("DROP TABLE "+table);
    }
  }

  @Test void simultaneousPartitionMaintenanceCreatesOneValidChild() throws Exception {
    var m=YearMonth.of(2095,3);
    var pool=Executors.newFixedThreadPool(2);
    var start=new CountDownLatch(1);
    try{
      var first=pool.submit(()->{
        try{start.await();return partitions.ensureMonthlyRange(m,m,false);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
      });
      var second=pool.submit(()->{
        try{start.await();return partitions.ensureMonthlyRange(m,m,false);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
      });
      start.countDown();
      var a=first.get(30,TimeUnit.SECONDS);
      var b=second.get(30,TimeUnit.SECONDS);
      assertEquals(1,a.created()+b.created());
      assertEquals(1,a.existed()+b.existed());
      assertTrue(partitions.inspectRequiredCoverage(m,m).complete());
    }finally{pool.shutdownNow();}
  }
}
