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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real source ROWSET revisions for C07 and C09, real synthetic fin_fact
 * observations, and real PostgreSQL candidate GROUP_BY / RATIO_OF_SUMS.
 * This is not an approved methodology or a financial FactSet.
 */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc154AggregateFormulaJdbcPreviewPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("calc154_aggregate_rowset")
        .withUsername("fin").withPassword("test_only");
  @DynamicPropertySource static void db(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired FormDraftService drafts;
  @Autowired FactMonthlyPartitionService partitions;
  @Autowired Calc154AggregateFormulaJdbcPreview preview;

  private static String sha(String input){
    try{
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(input.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception e){throw new IllegalStateException(e);}
  }
  private static void eq(String expected,BigDecimal actual){
    assertNotNull(actual);
    assertEquals(0,new BigDecimal(expected).compareTo(actual));
  }
  private UUID formula(String name){
    return jdbc.queryForObject("""
      SELECT id FROM fin_calc.formula_contract_revision
      WHERE output_metric_code=? AND formula_version='CALC154_CANDIDATE_V1'
      """,UUID.class,name);
  }

  private record Source(UUID org,UUID factset,String firstDimension,
      String secondDimension) {}

  private Source syntheticRowset(String form) {
    if(!List.of("C07","C09").contains(form))
      throw new IllegalArgumentException("TEST_ONLY_FORM");
    YearMonth month=YearMonth.of(2026,11);
    LocalDate start=month.atDay(1),end=month.plusMonths(1).atDay(1);
    partitions.ensureMonthlyRange(month,month,false);
    UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),
      business=UUID.randomUUID(),project=UUID.randomUUID(),factset=UUID.randomUUID();
    UUID row1=UUID.randomUUID(),row2=UUID.randomUUID();
    var entries=new ArrayList<FormDraftService.RowInput>();
    if("C07".equals(form)){
      entries.add(new FormDraftService.RowInput(row1,1,Map.of(
        "month","2026-11","clientRef","CLIENT-A","direction","SALE",
        "grossSales","125.00","returns","5.00","currency","KZT")));
      entries.add(new FormDraftService.RowInput(row2,2,Map.of(
        "month","2026-11","clientRef","CLIENT-B","direction","SALE",
        "grossSales","75.00","returns","10.00","currency","KZT")));
    }else{
      entries.add(new FormDraftService.RowInput(row1,1,Map.of(
        "expenseItem","RENT","period","2026-11","costCenter","A","amount","40.00")));
      entries.add(new FormDraftService.RowInput(row2,2,Map.of(
        "expenseItem","UTILITIES","period","2026-11","costCenter","B","amount","60.00")));
    }
    var draft=drafts.save(new FormDraftService.DraftRequest(
      null,org,entity,business,project,form,"M","2026-11","KZT",
      "PERIOD_ONLY","MANAGEMENT","SYNTHETIC",0,Map.of(),entries,
      "calc154-rowset-ci","test-only actual rowset source"),"calc154-"+UUID.randomUUID());
    UUID version=jdbc.queryForObject(
      "SELECT form_version_id FROM fin_intake.lib_form_revision WHERE id=?",
      UUID.class,draft.revisionId());
    String scope=sha("SCOPE:"+org),dimA=sha("DIM-A:"+factset),
      dimB=sha("DIM-B:"+factset),mapSha=sha("MAP:"+factset),
      factSha=sha("FACT:"+factset),bindingSha=sha("BIND:"+factset);
    var values=new MapSqlParameterSource()
      .addValue("id",factset).addValue("org",org).addValue("entity",entity)
      .addValue("business",business).addValue("project",project)
      .addValue("scope",scope).addValue("start",start).addValue("end",end)
      .addValue("source",draft.revisionId()).addValue("version",version)
      .addValue("sourceSha",draft.contentSha256()).addValue("mapSha",mapSha)
      .addValue("factSha",factSha).addValue("bindingSha",bindingSha)
      .addValue("count","C07".equals(form)?4:2);
    var named=new NamedParameterJdbcTemplate(jdbc);
    named.update("""
      INSERT INTO fin_fact.fact_set(
        id,organization_id,economic_entity_id,business_scope_id,project_id,
        dataset_code,scope_key,data_space,coverage,reporting_basis,currency_code,
        target_period_type,target_period_code,target_period_start,
        target_period_end_exclusive,source_revisions_jsonb,
        source_set_sha256,mapping_bundle_sha256,canonicalization_version,
        observation_count,factset_sha256,qualification_binding_sha256,
        qualification_epoch,qualification_mode,issuer)
      VALUES(:id,:org,:entity,:business,:project,'CALC154_ROWSET_TEST',
        :scope,'SYNTHETIC','PERIOD_ONLY','MANAGEMENT','KZT',
        'M','2026-11',:start,:end,
        CAST('[]' AS jsonb),:sourceSha,:mapSha,1,:count,:factSha,
        :bindingSha,1,'TEST_QUALIFIED','CALC154_ROWSET_TEST')
      """,values);
    jdbc.update("""
      INSERT INTO fin_fact.factset_control(
        id,stable_scope_key,organization_id,active_factset_id,
        qualification_epoch,revoked,row_version)
      VALUES (?,?,?,?,1,false,0)
      """,UUID.randomUUID(),scope,org,factset);

    for(int i=0;i<2;i++){
      var row= i==0?row1:row2;
      String dim=i==0?dimA:dimB;
      String json=i==0?"{\"segment\":\"A\"}":"{\"segment\":\"B\"}";
      if("C07".equals(form)){
        insertObservation(named,values,form,row,"grossSales",
          i==0?"125.00":"75.00",dim,json,start,end,i);
        insertObservation(named,values,form,row,"returns",
          i==0?"5.00":"10.00",dim,json,start,end,i);
      }else{
        insertObservation(named,values,form,row,"amount",
          i==0?"40.00":"60.00",dim,json,start,end,i);
      }
    }
    return new Source(org,factset,dimA,dimB);
  }

  private void insertObservation(NamedParameterJdbcTemplate named,
      MapSqlParameterSource base,String form,UUID row,String field,String amount,
      String dim,String dimensions,LocalDate start,LocalDate end,int ordinal){
    var values=new MapSqlParameterSource().addValues(base.getValues())
      .addValue("observation",UUID.randomUUID())
      .addValue("rowKey",row.toString())
      .addValue("field",field).addValue("metric",form+"."+field)
      .addValue("amount",new BigDecimal(amount))
      .addValue("dim",dim).addValue("dimJson",dimensions)
      .addValue("key",sha("KEY:"+base.getValue("id")+":"+field+":"+ordinal))
      .addValue("valueSha",sha("VALUE:"+amount+":"+field+":"+ordinal));
    named.update("""
      INSERT INTO fin_fact.fact_observation(
        period_start,observation_id,factset_id,source_revision_id,
        source_form_code,source_form_version_id,source_row_key,source_field_code,
        source_content_sha256,mapping_sha256,metric_code,metric_contract_version,
        period_type,period_end_exclusive,period_semantics,dimension_digest,
        dimensions_jsonb,observation_key_sha256,value_kind,value_state,
        numeric_value,unit_code,currency_code,canonical_value_sha256)
      VALUES(:start,:observation,:id,:source,
        :form,:version,:rowKey,:field,:sourceSha,:mapSha,:metric,'TEST_ONLY_V1',
        'M',:end,'INTERVAL',:dim,CAST(:dimJson AS jsonb),:key,
        'NUMERIC','PRESENT',:amount,'KZT','KZT',:valueSha)
      """,new MapSqlParameterSource().addValues(values.getValues())
          .addValue("form",form));
  }

  @Test void c07GrossSalesGroupByTwoRealRowsetDimensions(){
    var source=syntheticRowset("C07");
    var output=preview.preview(formula("C07.grossSalesGroupedCandidate"),
      source.org(),source.factset(),"KZT",null);
    assertEquals("GROUP_BY",output.operation());
    assertEquals(2,output.groups().size());
    eq("125.00",output.groups().get(0).dimensionDigest().equals(source.firstDimension())
      ?output.groups().get(0).totalValue():output.groups().get(1).totalValue());
    eq("75.00",output.groups().get(0).dimensionDigest().equals(source.secondDimension())
      ?output.groups().get(0).totalValue():output.groups().get(1).totalValue());
    assertFalse(output.financiallyApproved());
  }

  @Test void c07RatioOfSumsUsesCorrectDimensionAndNotAverageOfRatios(){
    var source=syntheticRowset("C07");
    var output=preview.preview(formula("C07.returnsShareCandidate"),
      source.org(),source.factset(),"KZT",source.firstDimension());
    assertEquals("RATIO_OF_SUMS",output.operation());
    eq("5.00",output.ratio().numeratorTotal());
    eq("125.00",output.ratio().denominatorTotal());
    eq("0.04",output.ratio().value());
    assertFalse(output.financiallyApproved());
  }

  @Test void c09ExpenseAmountIsGroupedAcrossItsActualRowset(){
    var source=syntheticRowset("C09");
    var output=preview.preview(formula("C09.expenseAmountGroupedCandidate"),
      source.org(),source.factset(),"KZT",null);
    assertEquals("C09.expenseAmountGroupedCandidate",output.outputMetric());
    assertEquals(2,output.groups().size());
    BigDecimal total=output.groups().stream()
        .map(Calc154AggregateFormulaJdbcPreview.Group::totalValue)
        .reduce(BigDecimal.ZERO,BigDecimal::add);
    eq("100.00",total);
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.calc_result",Integer.class));
  }

  @Test void wrongTenantOrUnitOrOperationCannotBeUsed(){
    var source=syntheticRowset("C09");
    var contract=formula("C09.expenseAmountGroupedCandidate");
    assertThrows(IllegalStateException.class,()->preview.preview(
      contract,UUID.randomUUID(),source.factset(),"KZT",null));
    assertThrows(DataAccessException.class,()->preview.preview(
      contract,source.org(),source.factset(),"USD",null));
    assertThrows(IllegalArgumentException.class,()->preview.preview(
      contract,source.org(),source.factset(),"KZT",source.firstDimension()));
    assertThrows(IllegalStateException.class,()->preview.preview(
      UUID.randomUUID(),source.org(),source.factset(),"KZT",null));
  }

  @Test void revokedSourceCannotCalculateAndNoResultsArePublished(){
    var source=syntheticRowset("C07");
    jdbc.update("""
      UPDATE fin_fact.factset_control
      SET revoked=true,qualification_epoch=2,row_version=row_version+1
      WHERE active_factset_id=?
      """,source.factset());
    assertThrows(IllegalStateException.class,()->preview.preview(
      formula("C07.returnsGroupedCandidate"),
      source.org(),source.factset(),"KZT",null));
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.calc_result",Integer.class));
  }
}
