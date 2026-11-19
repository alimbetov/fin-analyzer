package kz.fin.formlib.calc.hybrid;

import kz.fin.formlib.bootstrap.Calc3C2ControlExampleBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc3C2ControlExamplePostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("calc3c2_control").withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void db(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired Calc3C2ControlExampleBootstrap bootstrap;

  @Test void threePinnedExamplesAreSyntheticAndNotS2Approved(){
    assertEquals(3,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.financial_control_example_revision
      """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.financial_control_example_revision e
      JOIN fin_calc.formula_contract_revision c ON c.id=e.formula_revision_id
      WHERE e.form_version_id<>c.form_version_id
        OR e.source_schema_sha256<>c.source_schema_sha256
        OR e.review_state<>'UNREVIEWED'
        OR e.financial_approval_state<>'NOT_S2_APPROVED'
        OR e.reference_type<>'SYNTHETIC_UAT'
        OR c.formula_version<>'F02_TEST_ONLY_V1'
      """,Integer.class));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));
  }

  @Test void syntheticExpectedValuesAgreeWithIndependentPinnedNativeSql(){
    var expected=jdbc.queryForMap("""
      SELECT max(expected_value) FILTER(WHERE c.output_metric_code='F02.grossProfit')
          AS gross_profit,
        max(expected_value) FILTER(WHERE c.output_metric_code='F02.grossMargin')
          AS gross_margin,
        max(expected_value) FILTER(WHERE c.output_metric_code='F02.operatingResult')
          AS operating_result
      FROM fin_calc.financial_control_example_revision e
      JOIN fin_calc.formula_contract_revision c ON c.id=e.formula_revision_id
      """);
    var actual=jdbc.queryForMap("""
      SELECT gross_profit,gross_margin,operating_result,margin_state
      FROM fin_calc.f02_native_calculate(200.00,100.00,50.00)
      """);
    assertEquals(0,((BigDecimal)actual.get("gross_profit"))
        .compareTo((BigDecimal)expected.get("gross_profit")));
    assertEquals(0,((BigDecimal)actual.get("gross_margin"))
        .compareTo((BigDecimal)expected.get("gross_margin")));
    assertEquals(0,((BigDecimal)actual.get("operating_result"))
        .compareTo((BigDecimal)expected.get("operating_result")));
    assertEquals("PRESENT",actual.get("margin_state"));
  }

  @Test void zeroRevenueMarginCannotSilentlyTurnIntoPresentZero(){
    var actual=jdbc.queryForMap("""
      SELECT gross_profit,gross_margin,operating_result,margin_state
      FROM fin_calc.f02_native_calculate(0,0,0)
      """);
    assertNull(actual.get("gross_margin"));
    assertEquals("INVALID",actual.get("margin_state"));
  }

  @Test void immutableExamplesAndDirectFinancePromotionAreRejected(){
    assertThrows(DataAccessException.class,()->jdbc.update("""
      UPDATE fin_calc.financial_control_example_revision
      SET expected_value=999 WHERE example_code='F02_UAT_200_100_50'
      """));
    assertThrows(DataAccessException.class,()->jdbc.update("""
      DELETE FROM fin_calc.financial_control_example_revision
      WHERE example_code='F02_UAT_200_100_50'
      """));
    assertThrows(DataAccessException.class,()->jdbc.execute(
      "TRUNCATE fin_calc.financial_control_example_revision"));
    assertThrows(DataAccessException.class,()->jdbc.execute("""
      INSERT INTO fin_calc.financial_control_example_revision(
        id,formula_revision_id,example_code,form_version_id,source_schema_sha256,
        input_values_jsonb,expected_value,expected_state,expected_scale,
        rounding_mode,reference_type,review_state,
        financial_approval_state,definition_sha256,binding_sha256)
      SELECT gen_random_uuid(),formula_revision_id,'FORGED_APPROVAL',
        form_version_id,source_schema_sha256,input_values_jsonb,
        expected_value,expected_state,expected_scale,rounding_mode,
        reference_type,review_state,'S2_APPROVED',
        definition_sha256,binding_sha256
      FROM fin_calc.financial_control_example_revision LIMIT 1
      """));
  }

  @Test void bootstrapReplayIsDeterministicAndNotAnExecution() throws Exception {
    String before=jdbc.queryForObject("""
      SELECT string_agg(definition_sha256||':'||binding_sha256,','
        ORDER BY formula_revision_id)
      FROM fin_calc.financial_control_example_revision
      """,String.class);
    bootstrap.run(new DefaultApplicationArguments(new String[0]));
    String after=jdbc.queryForObject("""
      SELECT string_agg(definition_sha256||':'||binding_sha256,','
        ORDER BY formula_revision_id)
      FROM fin_calc.financial_control_example_revision
      """,String.class);
    assertEquals(before,after);
    assertEquals(3,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.financial_control_example_revision",Integer.class));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
  }
}
