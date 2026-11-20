package kz.fin.formlib.calc.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.partition.FactMonthlyPartitionService;
import kz.fin.formlib.service.FormDraftService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CALC-4D.0/1/2 real PostgreSQL 16 tests. Synthetic source revisions are
 * rehashed by INGEST-2, exact observations are compared with raw values.
 * No production S2, CalcPlan admission, jobs, or financial results.
 */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc4DTypedPlanPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
      new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("calc4d_typed_plan")
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
  @Autowired Calc4DTypedDagCompiler dag;
  @Autowired Calc4DExactFactSetInputResolver resolver;
  @Autowired Calc4DImmutablePlanCompiler plans;
  @Autowired ObjectMapper mapper;

  private UUID formula(String metric){
    return jdbc.queryForObject("""
      SELECT id FROM fin_calc.formula_contract_revision WHERE output_metric_code=?
      AND formula_version=CASE WHEN ? LIKE 'F02.%' THEN 'F02_TEST_ONLY_V1'
        WHEN ? LIKE 'C07.%' OR ? LIKE 'C09.%'
          THEN 'CALC154_CANDIDATE_V1'
        WHEN ? LIKE 'F01.%' THEN 'F01_CANDIDATE_V1'
        ELSE 'F03_CANDIDATE_V1' END
      """,UUID.class,metric,metric,metric,metric,metric);
  }
  private List<UUID> f02(){
    return List.of(formula("F02.grossProfit"),
      formula("F02.grossMargin"),formula("F02.operatingResult"));
  }
  private void noExecution(){
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_job",Integer.class));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));
  }

  private record Fixture(UUID organization,UUID factset,UUID sourceRevision,
      UUID formVersion,UUID project) {}
  private Fixture source(String storedRevenue,String observationRevenue,
      String observationSha,boolean dropThirdObservation){
    var month=YearMonth.of(2026,11);
    LocalDate start=month.atDay(1),end=month.plusMonths(1).atDay(1);
    partitions.ensureMonthlyRange(month,month,false);
    UUID org=UUID.randomUUID(),entity=UUID.randomUUID(),
      business=UUID.randomUUID(),project=UUID.randomUUID(),factset=UUID.randomUUID();
    var saved=drafts.save(new FormDraftService.DraftRequest(
      null,org,entity,business,project,"F02","M","2026-11","KZT",
      "PERIOD_ONLY","MANAGEMENT","SYNTHETIC",0,
      Map.of("netRevenue",storedRevenue,"cogs","100.00","operatingExpenses","50.00"),
      List.of(),"calc4d-uat","synthetic source for exact FactSet resolution"),
      "calc4d-"+UUID.randomUUID());
    UUID formVersion=jdbc.queryForObject("""
      SELECT form_version_id FROM fin_intake.lib_form_revision WHERE id=?
      """,UUID.class,saved.revisionId());
    String schema=jdbc.queryForObject("""
      SELECT schema_sha256 FROM fin_lib.lib_form_version WHERE id=?
      """,String.class,formVersion);
    String sourceManifest;
    try {
      sourceManifest=mapper.writeValueAsString(List.of(Map.of(
        "revisionId",saved.revisionId().toString(),
        "formVersionId",formVersion.toString(),
        "formCode","F02",
        "canonicalContentSha256",saved.contentSha256(),
        "schemaSha256",schema)));
    }catch(Exception e){throw new IllegalStateException(e);}
    String scope=Calc4DTypedDagCompiler.sha("scope|"+org);
    String factSha=Calc4DTypedDagCompiler.sha("factset|"+factset);
    String mapping=Calc4DTypedDagCompiler.sha("mapping|"+factset);
    String qualification=Calc4DTypedDagCompiler.sha("qualification|"+factset);
    var bind=new MapSqlParameterSource()
      .addValue("id",factset).addValue("org",org)
      .addValue("entity",entity).addValue("business",business)
      .addValue("project",project).addValue("scope",scope)
      .addValue("start",start).addValue("end",end).addValue("source",saved.revisionId())
      .addValue("version",formVersion).addValue("sourceSha",saved.contentSha256())
      .addValue("sourceJson",sourceManifest)
      .addValue("mapSha",mapping).addValue("factSha",factSha)
      .addValue("qualSha",qualification)
      .addValue("count",dropThirdObservation?2:3);
    var named=new NamedParameterJdbcTemplate(jdbc);
    named.update("""
      INSERT INTO fin_fact.fact_set(
        id,organization_id,economic_entity_id,business_scope_id,project_id,
        dataset_code,scope_key,data_space,coverage,reporting_basis,currency_code,
        target_period_type,target_period_code,target_period_start,
        target_period_end_exclusive,source_revisions_jsonb,source_set_sha256,
        mapping_bundle_sha256,canonicalization_version,observation_count,
        factset_sha256,qualification_binding_sha256,
        qualification_epoch,qualification_mode,issuer)
      VALUES(:id,:org,:entity,:business,:project,'CALC4D_SYNTHETIC',
        :scope,'SYNTHETIC','PERIOD_ONLY','MANAGEMENT','KZT',
        'M','2026-11',:start,:end,CAST(:sourceJson AS jsonb),:sourceSha,
        :mapSha,1,:count,:factSha,:qualSha,1,'TEST_QUALIFIED','CALC4D_UAT')
      """,bind);
    jdbc.update("""
      INSERT INTO fin_fact.factset_control(
        id,stable_scope_key,organization_id,active_factset_id,
        qualification_epoch,revoked,row_version)
      VALUES (?,?,?,?,1,false,0)
      """,UUID.randomUUID(),scope,org,factset);
    var values=dropThirdObservation?Map.of("cogs","100.00",
        "netRevenue",observationRevenue):
      Map.of("cogs","100.00","netRevenue",observationRevenue,
        "operatingExpenses","50.00");
    for(var entry:values.entrySet()){
      String field=entry.getKey();
      String content=observationSha==null?saved.contentSha256():observationSha;
      var p=new MapSqlParameterSource().addValues(bind.getValues())
        .addValue("field",field).addValue("metric","F02."+field)
        .addValue("value",new BigDecimal(entry.getValue()))
        .addValue("contentSha",content)
        .addValue("observation",UUID.randomUUID())
        .addValue("dim",Calc4DTypedDagCompiler.sha("DIM|"+factset))
        .addValue("keySha",Calc4DTypedDagCompiler.sha("KEY|"+factset+"|"+field))
        .addValue("valueSha",Calc4DTypedDagCompiler.sha(
          "VAL|"+factset+"|"+field+"|"+entry.getValue()));
      named.update("""
        INSERT INTO fin_fact.fact_observation(
          period_start,observation_id,factset_id,source_revision_id,
          source_form_code,source_form_version_id,source_row_key,source_field_code,
          source_content_sha256,mapping_sha256,metric_code,metric_contract_version,
          period_type,period_end_exclusive,period_semantics,dimension_digest,
          dimensions_jsonb,observation_key_sha256,value_kind,value_state,
          numeric_value,unit_code,currency_code,canonical_value_sha256)
        VALUES(:start,:observation,:id,:source,'F02',:version,'SINGLE',
          :field,:contentSha,:mapSha,:metric,'TEST_ONLY_V1','M',:end,
          'INTERVAL',:dim,'{}'::jsonb,:keySha,'NUMERIC','PRESENT',
          :value,'KZT','KZT',:valueSha)
        """,p);
    }
    return new Fixture(org,factset,saved.revisionId(),formVersion,project);
  }

  @Test void exactTypedF02GraphIsDeterministicAndTopologicallySorted(){
    var ids=f02();
    var one=dag.compile(ids);
    var two=dag.compile(List.of(ids.get(2),ids.get(0),ids.get(1)));
    assertEquals(one.dagSha256(),two.dagSha256());
    assertEquals(3,one.orderedNodes().size());
    assertEquals(3,one.physicalInputs().size());
    assertEquals("F02.grossProfit",one.orderedNodes().getFirst().outputMetricCode());
    assertTrue(one.orderedNodes().stream().allMatch(n->n.bindingSha256().length()==64));
    assertTrue(one.blockers().contains("FINANCIAL_S2_NOT_APPROVED"));
    assertFalse(one.executable());
    noExecution();
  }

  @Test void typedGraphSupportsOtherFormsAndDoesNotFabricateDependencies(){
    var graph=dag.compile(List.of(formula("C07.grossSalesGroupedCandidate"),
      formula("C09.expenseAmountGroupedCandidate"),
      formula("F01.balanceResidual")));
    assertEquals(3,graph.orderedNodes().size());
    assertEquals(5,graph.physicalInputs().size());
    assertEquals(3,graph.orderedNodes().stream()
      .map(Calc4DTypedDagCompiler.Node::formCode).distinct().count());
    assertFalse(graph.executable());
  }

  @Test void missingDerivedDependencyAndStaleFormulaAreRejected(){
    var ids=f02();
    assertThrows(IllegalStateException.class,()->
      dag.compile(List.of(ids.get(1))));
    assertThrows(IllegalStateException.class,()->
      dag.compile(List.of(UUID.randomUUID())));
    assertThrows(IllegalArgumentException.class,()->
      dag.compile(List.of(ids.getFirst(),ids.getFirst())));
  }

  @Test void sourceResolverRehashesF02AndBindsPlanPreviewWithoutWrites(){
    var fixture=source("200.00","200.00",null,false);
    var exact=resolver.resolve(fixture.organization(),fixture.factset());
    assertEquals(3,exact.observations().size());
    assertEquals(fixture.sourceRevision(),exact.observations().getFirst().sourceRevisionId());
    assertEquals(64,exact.observationSha256().length());
    assertFalse(exact.financiallyApproved());
    var first=plans.preview(fixture.organization(),f02(),List.of(fixture.factset()));
    var replay=plans.preview(fixture.organization(),
      List.of(f02().get(2),f02().get(1),f02().get(0)),List.of(fixture.factset()));
    assertEquals(first.planFingerprint(),replay.planFingerprint());
    assertEquals(3,first.nodeCount());
    assertEquals(3,first.numericSourceCount());
    assertEquals("UAT_PREVIEW_ONLY",first.state());
    assertFalse(first.executable());
    assertTrue(first.blockers().contains("EXECUTION_NOT_ADMITTED"));
    noExecution();
  }

  @Test void incorrectValueOrForgedSourceShaIsBlockedByIngest2(){
    var wrong=source("200.00","201.00",null,false);
    assertThrows(IllegalStateException.class,()->
      resolver.resolve(wrong.organization(),wrong.factset()));
    var wrongSha=source("200.00","200.00","a".repeat(64),false);
    assertThrows(IllegalStateException.class,()->
      resolver.resolve(wrongSha.organization(),wrongSha.factset()));
    noExecution();
  }

  @Test void missingPhysicalInputPreventsPlanCompilation(){
    var fixture=source("200.00","200.00",null,true);
    assertEquals(2,resolver.resolve(fixture.organization(),fixture.factset()).observations().size());
    assertThrows(IllegalStateException.class,()->
      plans.preview(fixture.organization(),f02(),List.of(fixture.factset())));
    noExecution();
  }

  @Test void revokedFactsetAndCrossTenantNeverYieldPlanPreview(){
    var fixture=source("200.00","200.00",null,false);
    assertThrows(IllegalStateException.class,()->
      resolver.resolve(UUID.randomUUID(),fixture.factset()));
    jdbc.update("""
      UPDATE fin_fact.factset_control
      SET revoked=true,qualification_epoch=2,row_version=row_version+1
      WHERE active_factset_id=?
      """,fixture.factset());
    assertThrows(IllegalStateException.class,()->
      plans.preview(fixture.organization(),f02(),List.of(fixture.factset())));
    noExecution();
  }

  @Test void duplicatedOrUnpinnedFactsetListsFailClosed(){
    var fixture=source("200.00","200.00",null,false);
    assertThrows(IllegalArgumentException.class,()->
      plans.preview(fixture.organization(),f02(),
        List.of(fixture.factset(),fixture.factset())));
    assertThrows(IllegalArgumentException.class,()->
      plans.preview(fixture.organization(),f02(),List.of()));
    assertThrows(IllegalStateException.class,()->
      plans.preview(fixture.organization(),f02(),List.of(UUID.randomUUID())));
  }
}
