package kz.fin.formlib.calc.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MG-R0.1 uses the real 166-form catalogue, formula contracts and DAG on PG16. */
@SpringBootTest(properties="fin.uat.editor.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class MetricGraphResolverPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("fin_mg_r01")
        .withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired MetricGraphResolver resolver;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private UUID method(String code) {
    return jdbc.queryForObject("""
      SELECT id FROM fin_calc.formula_contract_revision
      WHERE output_metric_code=? AND formula_version='F02_TEST_ONLY_V1'
      """,UUID.class,code);
  }

  private void noCalculationWrites() {
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.calc4d_uat_plan",Integer.class));
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_fact.fact_set",Integer.class));
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_intake.lib_form_revision",Integer.class));
  }

  @Test void grossMarginResolvesRealMethodAndDependencyClosureWithTypedInputs(){
    var resolved=resolver.resolve("F02.grossMargin",null,null);
    assertEquals("DAG_COMPILED_PREVIEW_ONLY",resolved.state());
    assertEquals(method("F02.grossMargin"),resolved.selectedMethodRevisionId());
    assertEquals(2,resolved.orderedNodes().size());
    assertEquals(List.of("F02.grossProfit","F02.grossMargin"),
      resolved.orderedNodes().stream().map(n->n.outputMetricCode()).toList());
    assertEquals(Set.of("netRevenue","cogs"),
      resolved.requiredPhysicalInputs().stream().map(x->x.fieldCode())
        .collect(java.util.stream.Collectors.toSet()));
    for(var input:resolved.requiredPhysicalInputs()){
      assertEquals("F02",input.formCode());
      assertEquals("MONEY",input.fieldType());
      assertEquals("MANUAL",input.inputMode());
      assertEquals("REVIEW_REQUIRED",input.requirednessState());
      assertEquals(64,input.schemaSha256().length());
      assertEquals(64,input.inputBindingSha256().length());
    }
    assertEquals(64,resolved.graphSha256().length());
    assertEquals(resolved.graphSha256(),
      resolver.resolve("F02.grossMargin",null,method("F02.grossMargin")).graphSha256());
    assertFalse(resolved.graphExecutable());
    assertFalse(resolved.financiallyQualified());
    assertTrue(resolved.blockers().contains("MG_FINANCIAL_S2_NOT_APPROVED"));
    noCalculationWrites();
  }

  @Test void operatingResultIncludesTransitiveInputsWithoutDuplicateSources(){
    var r=resolver.resolve("F02.operatingResult",null,null);
    assertEquals("DAG_COMPILED_PREVIEW_ONLY",r.state());
    assertEquals(2,r.orderedNodes().size());
    assertEquals(Set.of("netRevenue","cogs","operatingExpenses"),
      r.requiredPhysicalInputs().stream().map(x->x.fieldCode())
        .collect(java.util.stream.Collectors.toSet()));
    assertEquals(3,r.requiredPhysicalInputs().size());
    noCalculationWrites();
  }

  @Test void editorialGoalIndicatorDoesNotInventFormulaBindings() throws Exception {
    var response=mvc.perform(get("/api/v1/metric-graph/metrics/NET_PROFIT")
        .param("goalCode","BANK_FINANCING"))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    JsonNode result=json.readTree(response);
    assertEquals("NET_PROFIT",result.path("metricCode").asText());
    assertEquals("CATALOG_ONLY",result.path("state").asText());
    assertTrue(result.path("goalCodes").toString().contains("BANK_FINANCING"));
    assertTrue(result.path("candidateInputForms").toString().contains("F02"));
    assertEquals(0,result.path("orderedNodes").size());
    assertEquals(0,result.path("requiredPhysicalInputs").size());
    assertTrue(result.path("blockers").toString()
      .contains("MG_EDITORIAL_INDICATOR_FORMULA_LINK_NOT_AUTHORED"));
    assertFalse(result.path("graphExecutable").asBoolean());
    noCalculationWrites();
  }

  @Test void exactRevisionAndGoalMappingMustMatchTheRequestedMetric() throws Exception {
    mvc.perform(get("/api/v1/metric-graph/metrics/F02.grossMargin")
        .param("exactMethodRevisionId",method("F02.grossProfit").toString()))
      .andExpect(status().isConflict())
      .andExpect(jsonPath("$.code").value("MG_METHOD_REVISION_NOT_FOR_METRIC"));
    mvc.perform(get("/api/v1/metric-graph/metrics/NET_PROFIT")
        .param("goalCode","INVESTMENT_APPRAISAL"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("MG_GOAL_INDICATOR_MAPPING_NOT_AUTHORED"));
    mvc.perform(get("/api/v1/metric-graph/metrics/NOT_A_METRIC"))
      .andExpect(status().isNotFound())
      .andExpect(jsonPath("$.code").value("MG_METRIC_NOT_FOUND"));
    mvc.perform(get("/api/v1/metric-graph/metrics/F02.grossMargin")
        .param("goalCode","BANK_FINANCING"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("MG_GOAL_INDICATOR_MAPPING_NOT_AUTHORED"));
    noCalculationWrites();
  }

  @Test void directPhysicalMetricResolvesExactFormFieldNotSyntheticFormula(){
    var r=resolver.resolve("F02.netRevenue",null,null);
    assertEquals("PHYSICAL_SOURCE_ONLY",r.state());
    assertEquals(1,r.requiredPhysicalInputs().size());
    assertEquals("F02",r.requiredPhysicalInputs().getFirst().formCode());
    assertEquals("netRevenue",r.requiredPhysicalInputs().getFirst().fieldCode());
    assertNull(r.graphSha256());
    assertTrue(r.methodRevisions().isEmpty());
    noCalculationWrites();
  }
}
