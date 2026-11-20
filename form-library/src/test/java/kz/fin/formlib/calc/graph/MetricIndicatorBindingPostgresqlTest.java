package kz.fin.formlib.calc.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MG-R0.2: exact business-to-physical bindings, no alias inference. */
@SpringBootTest(properties="fin.uat.editor.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class MetricIndicatorBindingPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
      new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("fin_mg_r02").withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url",pg::getJdbcUrl);
    registry.add("spring.datasource.username",pg::getUsername);
    registry.add("spring.datasource.password",pg::getPassword);
    registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired MetricIndicatorBindingService bindings;
  @Autowired JdbcTemplate db;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  private void noFinancialWrites(){
    assertEquals(0,db.queryForObject(
      "SELECT count(*) FROM fin_intake.lib_form_revision",Integer.class));
    assertEquals(0,db.queryForObject(
      "SELECT count(*) FROM fin_fact.fact_set",Integer.class));
    assertEquals(0,db.queryForObject(
      "SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
  }

  @Test void netProfitBusinessIndicatorResolvesExactF02NetProfitNotOperatingProfit(){
    var choices=bindings.variants("BANK_FINANCING","NET_PROFIT");
    assertEquals(1,choices.size());
    var binding=choices.getFirst();
    assertEquals("F02.netProfit",binding.targetMetricCode());
    assertEquals("SOURCE_FIELD",binding.bindingKind());
    assertNull(binding.formulaRevisionId());
    assertEquals(64,binding.bindingSha256().length());
    var resolved=bindings.requireExact(binding.id());
    assertEquals("DIRECT_SOURCE_CANDIDATE_NOT_CALCULATED",resolved.state());
    assertEquals("PHYSICAL_SOURCE_ONLY",resolved.metricGraph().state());
    assertEquals(1,resolved.metricGraph().requiredPhysicalInputs().size());
    var input=resolved.metricGraph().requiredPhysicalInputs().getFirst();
    assertEquals("F02",input.formCode());
    assertEquals("netProfit",input.fieldCode());
    assertEquals("MONEY",input.fieldType());
    assertFalse(binding.financiallyApproved());
    assertFalse(binding.productionAdmitted());
    assertFalse(resolved.financiallyQualified());
    noFinancialWrites();
  }

  @Test void cashEndUsesExactF03ClosingCashSourceAndDoesNotCalculateForecast(){
    var choice=bindings.variants("BANK_FINANCING","CASH_END").getFirst();
    assertEquals("F03.closingCash",choice.targetMetricCode());
    var graph=bindings.requireExact(choice.id());
    assertEquals("DIRECT_SOURCE_CANDIDATE_NOT_CALCULATED",graph.state());
    assertEquals("F03",graph.metricGraph().requiredPhysicalInputs()
      .getFirst().formCode());
    assertEquals("closingCash",graph.metricGraph().requiredPhysicalInputs()
      .getFirst().fieldCode());
    assertFalse(graph.metricGraph().graphExecutable());
    noFinancialWrites();
  }

  @Test void unmappedBalanceCheckIsNotSilentlyConvertedToAnyFormula(){
    assertTrue(bindings.variants("BANK_FINANCING","BALANCE_CHECK").isEmpty());
    assertThrows(org.springframework.web.server.ResponseStatusException.class,
      ()->bindings.requireExact(UUID.randomUUID()));
    noFinancialWrites();
  }

  @Test void exactRestReadAndInvalidGoalIndicatorPairFailClosed() throws Exception {
    var response=mvc.perform(get("/api/v1/metric-graph/indicator-bindings")
        .param("goalCode","BANK_FINANCING").param("indicatorCode","NET_PROFIT"))
      .andExpect(status().isOk()).andReturn().getResponse();
    var list=json.readTree(response.getContentAsByteArray());
    assertEquals(1,list.size());
    String id=list.get(0).path("id").asText();
    mvc.perform(get("/api/v1/metric-graph/indicator-bindings/"+id))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.binding.targetMetricCode").value("F02.netProfit"))
      .andExpect(jsonPath("$.metricGraph.requiredPhysicalInputs[0].fieldCode")
        .value("netProfit"));
    mvc.perform(get("/api/v1/metric-graph/indicator-bindings")
        .param("goalCode","INVESTMENT_APPRAISAL")
        .param("indicatorCode","NET_PROFIT"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.code").value("MG_R02_GOAL_INDICATOR_LINK_NOT_AUTHORED"));
    noFinancialWrites();
  }

  @Test void immutableRegistryRejectsTamperingAndForgedSha(){
    var row=bindings.variants("BANK_FINANCING","NET_PROFIT").getFirst();
    assertThrows(DataAccessException.class,()->db.update("""
      UPDATE fin_calc.metric_indicator_binding_revision
      SET target_metric_code='F02.operatingResult' WHERE id=?
      """,row.id()));
    assertThrows(DataAccessException.class,()->db.update("""
      INSERT INTO fin_calc.metric_indicator_binding_revision(
        id,goal_code,indicator_code,revision_number,target_metric_code,
        binding_kind,evidence_ref,binding_sha256)
      VALUES(?,'BANK_FINANCING','NET_PROFIT',2,'F02.operatingResult',
        'SOURCE_FIELD','FAKE_SEMANTIC_EQUIVALENCE',?)
      """,UUID.randomUUID(),"a".repeat(64)));
    assertThrows(DataAccessException.class,()->db.update("""
      DELETE FROM fin_calc.metric_indicator_binding_revision WHERE id=?
      """,row.id()));
    noFinancialWrites();
  }
}
