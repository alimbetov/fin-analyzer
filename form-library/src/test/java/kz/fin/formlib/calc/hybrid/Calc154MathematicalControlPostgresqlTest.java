package kz.fin.formlib.calc.hybrid;

import kz.fin.formlib.bootstrap.Calc154MathematicalControlBootstrap;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generic 10-formula candidate mathematical control vectors, independent of
 * the F02-only 040 migration. Synthetic oracle != approved finance S2.
 */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc154MathematicalControlPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
      new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("calc154_math_control")
        .withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void db(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired Calc154MathematicalControlBootstrap bootstrap;

  @Test void allTenExistingFormulaCandidatesHaveTwelveExactSyntheticMathCases(){
    assertEquals(12,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc154_synthetic_control_case
      """,Integer.class));
    assertEquals(10,jdbc.queryForObject("""
      SELECT count(DISTINCT formula_revision_id)
      FROM fin_calc.calc154_synthetic_control_case
      """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.formula_contract_revision c
      WHERE NOT EXISTS (
        SELECT 1 FROM fin_calc.calc154_synthetic_control_case e
        WHERE e.formula_revision_id=c.id
      )
      """,Integer.class));
    assertEquals(2,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc154_synthetic_control_case
      WHERE expected_state='INVALID_ZERO_DENOMINATOR'
        AND expected_numeric IS NULL
      """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc154_synthetic_control_case
      WHERE review_state<>'UNREVIEWED'
        OR financial_approval_state<>'NOT_S2_APPROVED'
        OR reference_type<>'SYNTHETIC_OPERATOR_MATH_ONLY'
      """,Integer.class));
    assertEquals(3,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.financial_control_example_revision
      """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc_result
      """,Integer.class));
  }

  @Test void formulaRevisionsSchemaAndBindingShaAreExactlyPinned(){
    assertEquals(0,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc154_synthetic_control_case e
      JOIN fin_calc.formula_contract_revision f ON f.id=e.formula_revision_id
      WHERE e.form_version_id<>f.form_version_id
        OR e.source_schema_sha256<>f.source_schema_sha256
        OR e.formula_binding_sha256<>f.binding_sha256
        OR e.binding_sha256 !~ '^[0-9a-f]{64}$'
        OR e.definition_sha256 !~ '^[0-9a-f]{64}$'
      """,Integer.class));
    assertEquals(5,jdbc.queryForObject("""
      SELECT count(DISTINCT f.form_code)
      FROM fin_calc.calc154_synthetic_control_case e
      JOIN fin_calc.formula_contract_revision f ON f.id=e.formula_revision_id
      """,Integer.class));
  }

  @Test void startupReplayNeverChangesImmutableCaseHashOrCount() throws Exception{
    String before=jdbc.queryForObject("""
      SELECT string_agg(e.case_code || ':' || e.binding_sha256, '|'
        ORDER BY e.case_code)
      FROM fin_calc.calc154_synthetic_control_case e
      """,String.class);
    bootstrap.run(new DefaultApplicationArguments(new String[0]));
    String after=jdbc.queryForObject("""
      SELECT string_agg(e.case_code || ':' || e.binding_sha256, '|'
        ORDER BY e.case_code)
      FROM fin_calc.calc154_synthetic_control_case e
      """,String.class);
    assertEquals(before,after);
    assertEquals(12,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc154_synthetic_control_case
      """,Integer.class));
  }

  @Test void mathematicalCasesAreNotInterpretedAsFinancialApproval(){
    assertEquals(0,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc3c2_financial_review_readiness_v1
      WHERE calc_plan_execution_admitted
        OR financial_approval_state<>'NOT_S2_APPROVED'
      """,Integer.class));
    assertEquals(154,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc4d_methodology_workqueue_v1
      WHERE NOT executable
      """,Integer.class));
  }

  @Test void controlsAreImmutableAndDirectFinancialPromotionIsForbidden(){
    assertThrows(DataAccessException.class,()->jdbc.execute("""
      UPDATE fin_calc.calc154_synthetic_control_case
      SET expected_numeric=999
      WHERE expected_state='PRESENT'
      """));
    assertThrows(DataAccessException.class,()->jdbc.execute("""
      DELETE FROM fin_calc.calc154_synthetic_control_case
      """));
    assertThrows(DataAccessException.class,()->jdbc.execute(
      "TRUNCATE fin_calc.calc154_synthetic_control_case"));
    assertThrows(DataAccessException.class,()->jdbc.execute("""
      INSERT INTO fin_calc.calc154_synthetic_control_case(
        id,formula_revision_id,form_version_id,source_schema_sha256,
        formula_binding_sha256,case_code,source_inputs_jsonb,
        expected_state,expected_numeric,source_evidence,
        definition_sha256,binding_sha256,financial_approval_state)
      SELECT gen_random_uuid(),formula_revision_id,form_version_id,
        source_schema_sha256,formula_binding_sha256,'FORGED_S2',
        source_inputs_jsonb,'PRESENT',1,source_evidence,
        definition_sha256,binding_sha256,'S2_APPROVED'
      FROM fin_calc.calc154_synthetic_control_case LIMIT 1
      """));
  }

  @Test void forgedFormulaBindingAndZeroValueShapeFailAtDatabase(){
    assertThrows(DataAccessException.class,()->jdbc.execute("""
      INSERT INTO fin_calc.calc154_synthetic_control_case(
        id,formula_revision_id,form_version_id,source_schema_sha256,
        formula_binding_sha256,case_code,source_inputs_jsonb,
        expected_state,expected_numeric,source_evidence,
        definition_sha256,binding_sha256)
      SELECT gen_random_uuid(),formula_revision_id,form_version_id,
        source_schema_sha256,
        repeat('a',64),'FORGED_FORMULA_SHA',source_inputs_jsonb,
        'PRESENT',1,source_evidence,
        definition_sha256,binding_sha256
      FROM fin_calc.calc154_synthetic_control_case LIMIT 1
      """));
    assertThrows(DataAccessException.class,()->jdbc.execute("""
      INSERT INTO fin_calc.calc154_synthetic_control_case(
        id,formula_revision_id,form_version_id,source_schema_sha256,
        formula_binding_sha256,case_code,source_inputs_jsonb,
        expected_state,expected_numeric,source_evidence,
        definition_sha256,binding_sha256)
      SELECT gen_random_uuid(),formula_revision_id,form_version_id,
        source_schema_sha256,
        formula_binding_sha256,'FORGED_INVALID_STATE',source_inputs_jsonb,
        'INVALID_ZERO_DENOMINATOR',1,source_evidence,
        definition_sha256,binding_sha256
      FROM fin_calc.calc154_synthetic_control_case LIMIT 1
      """));
  }
  @Test void all154MethodologyRowsShowActualInputsAndFinancialBlockers(){
    assertEquals(154,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc154_methodology_completion_v1
      """,Integer.class));
    assertEquals(145,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc154_methodology_completion_v1
      WHERE direct_numeric_field_count>0
      """,Integer.class));
    assertEquals(9,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc154_methodology_completion_v1
      WHERE reference_only
      """,Integer.class));
    assertEquals(10,jdbc.queryForObject("""
      SELECT sum(formula_candidate_count)
      FROM fin_calc.calc154_methodology_completion_v1
      """,Integer.class));
    assertEquals(12,jdbc.queryForObject("""
      SELECT sum(mathematical_control_cases)
      FROM fin_calc.calc154_methodology_completion_v1
      """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
      SELECT sum(formula_candidates_missing_math_control)
      FROM fin_calc.calc154_methodology_completion_v1
      """,Integer.class));
    assertEquals(2,jdbc.queryForObject("""
      SELECT sum(zero_division_negative_cases)
      FROM fin_calc.calc154_methodology_completion_v1
      """,Integer.class));
    assertEquals(1,jdbc.queryForObject("""
      SELECT sum(unbound_derived_metrics)
      FROM fin_calc.calc154_methodology_completion_v1
      """,Integer.class));
    assertEquals(3,jdbc.queryForObject("""
      SELECT legacy_f02_control_examples
      FROM fin_calc.calc154_methodology_completion_v1 WHERE form_code='F02'
      """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc154_methodology_completion_v1
      WHERE qualified_for_production OR financial_approval_state<>'NOT_S2_APPROVED'
         OR policy_review_state<>'PERIOD_CURRENCY_DIMENSION_ROUNDING_NOT_REVIEWED'
      """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.calc154_methodology_completion_v1
      WHERE required_numeric_input_fields+optional_numeric_input_fields
          <> direct_numeric_field_count
      """,Integer.class));
  }

}
