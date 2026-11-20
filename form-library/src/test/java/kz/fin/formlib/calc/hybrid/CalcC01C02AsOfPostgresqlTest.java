package kz.fin.formlib.calc.hybrid;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real saved source revision -> verified FactSet -> SQL/JDBC -> Java -> immutable UAT result. */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class CalcC01C02AsOfPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("calc_c01_c02_e2e")
        .withUsername("fin").withPassword("test_only");
  @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired FormDraftService drafts;
  @Autowired FactMonthlyPartitionService partitions;
  @Autowired ObjectMapper json;
  @Autowired CalcC01C02AsOfJdbcService service;

  private static String sha(String text) {
    try {
      return java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("ASOF_TEST_SHA256_UNAVAILABLE",ex);
    }
  }
  private record Fixture(UUID org, UUID factset) {}
  private Fixture fixture(String form) throws Exception {
    var month=YearMonth.of(2026,11);
    var start=month.atDay(1);
    var end=month.plusMonths(1).atDay(1);
    partitions.ensureMonthlyRange(month,month,false);
    UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),business=UUID.randomUUID();
    UUID project=UUID.randomUUID(),factset=UUID.randomUUID();
    UUID row1=UUID.randomUUID(),row2=UUID.randomUUID();
    Map<String,String> values1,values2;
    if("C01".equals(form)){
      values1=Map.of("counterparty","customer-a","gross","100",
        "allowance","10","overdue","20","dueAt","2026-11-15",
        "relatedParty","false");
      values2=Map.of("counterparty","customer-b","gross","60",
        "allowance","5","overdue","12","dueAt","2026-11-18",
        "relatedParty","false");
    } else {
      values1=Map.of("counterparty","vendor-a","outstanding","60",
        "overdue","10","dueAt","2026-11-15","debtCategory","TRADE");
      values2=Map.of("counterparty","vendor-b","outstanding","40",
        "overdue","10","dueAt","2026-11-18","debtCategory","TRADE");
    }
    var rows=List.of(new FormDraftService.RowInput(row1,1,values1),
        new FormDraftService.RowInput(row2,2,values2));
    var saved=drafts.save(new FormDraftService.DraftRequest(
      null,org,entity,business,project,form,"M","2026-11","KZT",
      "PERIOD_ONLY","MANAGEMENT","SYNTHETIC",0,
      Map.of(),rows,"calc-asof-e2e","synthetic debt calculation"),
      form+"-"+UUID.randomUUID());
    UUID version=jdbc.queryForObject("""
      SELECT form_version_id FROM fin_intake.lib_form_revision WHERE id=?
      """,UUID.class,saved.revisionId());
    String schema=jdbc.queryForObject("""
      SELECT schema_sha256 FROM fin_lib.lib_form_version WHERE id=?
      """,String.class,version);
    String sourceJson=json.writeValueAsString(List.of(Map.of(
      "revisionId",saved.revisionId().toString(),
      "formVersionId",version.toString(),
      "formCode",form,
      "canonicalContentSha256",saved.contentSha256(),
      "schemaSha256",schema)));
    String scope=sha("ASOF_SCOPE|"+factset);
    String factSha=sha("ASOF_FACT|"+factset);
    String mapSha=sha("ASOF_MAP|"+factset);
    var named=new NamedParameterJdbcTemplate(jdbc);
    var args=new MapSqlParameterSource()
        .addValue("id",factset).addValue("org",org)
        .addValue("entity",entity).addValue("business",business)
        .addValue("project",project).addValue("scope",scope)
        .addValue("start",start).addValue("end",end)
        .addValue("rev",saved.revisionId()).addValue("version",version)
        .addValue("sourceSha",saved.contentSha256())
        .addValue("manifest",sourceJson)
        .addValue("mapSha",mapSha).addValue("factSha",factSha)
        .addValue("qualification",sha("ASOF_QUAL|"+factset))
        .addValue("count",form.equals("C01")?6:4);
    named.update("""
      INSERT INTO fin_fact.fact_set(
        id,organization_id,economic_entity_id,business_scope_id,project_id,
        dataset_code,scope_key,data_space,coverage,reporting_basis,currency_code,
        target_period_type,target_period_code,target_period_start,
        target_period_end_exclusive,source_revisions_jsonb,source_set_sha256,
        mapping_bundle_sha256,canonicalization_version,observation_count,
        factset_sha256,qualification_binding_sha256,
        qualification_epoch,qualification_mode,issuer)
      VALUES(:id,:org,:entity,:business,:project,'CALC_ASOF_CANDIDATE',
        :scope,'SYNTHETIC','PERIOD_ONLY','MANAGEMENT','KZT',
        'M','2026-11',:start,:end,CAST(:manifest AS jsonb),:sourceSha,
        :mapSha,1,:count,:factSha,:qualification,1,'TEST_QUALIFIED','ASOF_UAT')
      """,args);
    jdbc.update("""
      INSERT INTO fin_fact.factset_control(id,stable_scope_key,organization_id,
         active_factset_id,qualification_epoch,revoked,row_version)
      VALUES(?,?,?,?,1,false,0)
      """,UUID.randomUUID(),scope,org,factset);
    for(var row:rows) {
      for(var f:form.equals("C01")?List.of("gross","allowance","overdue")
                                  :List.of("outstanding","overdue")) {
        String value=row.values().get(f);
        var p=new MapSqlParameterSource().addValues(args.getValues())
          .addValue("form",form).addValue("row",row.rowId().toString())
          .addValue("field",f).addValue("metric",form+"."+f)
          .addValue("observation",UUID.randomUUID())
          .addValue("dim",sha("ASOF_DIM|"+factset))
          .addValue("keySha",sha(
              "ASOF_KEY|"+factset+"|"+row.rowId()+"|"+f))
          .addValue("val",new BigDecimal(value))
          .addValue("valSha",sha(
              "ASOF_VAL|"+row.rowId()+"|"+f+"|"+value));
        named.update("""
          INSERT INTO fin_fact.fact_observation(
            period_start,observation_id,factset_id,source_revision_id,
            source_form_code,source_form_version_id,source_row_key,source_field_code,
            source_content_sha256,mapping_sha256,metric_code,metric_contract_version,
            period_type,period_end_exclusive,period_semantics,dimension_digest,
            dimensions_jsonb,observation_key_sha256,value_kind,value_state,
            numeric_value,unit_code,currency_code,canonical_value_sha256)
          VALUES(:start,:observation,:id,:rev,:form,:version,:row,
            :field,:sourceSha,:mapSha,:metric,'ASOF_UAT_V1','M',:end,
            'AS_OF',:dim,'{}'::jsonb,:keySha,'NUMERIC','PRESENT',
            :val,'KZT','KZT',:valSha)
          """,p);
      }
    }
    return new Fixture(org,factset);
  }

  @Test void c01ExactE2eAndImmutableReplay() throws Exception {
    var f=fixture("C01");
    var first=service.executeUat(f.org(),f.factset(),"C01");
    var replay=service.executeUat(f.org(),f.factset(),"C01");
    assertFalse(first.replayed());
    assertTrue(replay.replayed());
    assertEquals(first.resultId(),replay.resultId());
    assertEquals(LocalDate.of(2026,11,30),first.asOf());
    assertEquals(0,first.primaryValue().compareTo(new BigDecimal("145.00")));
    assertEquals(0,first.overdueValue().compareTo(new BigDecimal("32.00")));
    assertEquals(0,first.overdueShare().compareTo(new BigDecimal("0.2")));
    assertTrue(first.sqlJavaParityPassed());
    assertFalse(first.financiallyApproved());
    assertFalse(first.productionAdmitted());
    assertThrows(DataAccessException.class,()->jdbc.update("""
      UPDATE fin_calc.calc154_asof_uat_result SET production_admitted=true WHERE id=?
      """,first.resultId()));
  }

  @Test void c02ExactE2eAndRevocationBlocksReplay() throws Exception {
    var f=fixture("C02");
    var first=service.executeUat(f.org(),f.factset(),"C02");
    assertEquals(0,first.primaryValue().compareTo(new BigDecimal("100.00")));
    assertEquals(0,first.overdueShare().compareTo(new BigDecimal("0.2")));
    assertThrows(IllegalStateException.class,()->
      service.executeUat(UUID.randomUUID(),f.factset(),"C02"));
    jdbc.update("""
      UPDATE fin_fact.factset_control SET revoked=true,
         qualification_epoch=qualification_epoch+1,row_version=row_version+1
      WHERE active_factset_id=?
      """,f.factset());
    assertThrows(IllegalStateException.class,()->
      service.executeUat(f.org(),f.factset(),"C02"));
  }
}
