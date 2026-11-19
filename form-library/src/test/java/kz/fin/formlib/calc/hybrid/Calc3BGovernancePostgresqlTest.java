package kz.fin.formlib.calc.hybrid;

import kz.fin.formlib.bootstrap.Calc154FormulaContractBootstrap;
import kz.fin.formlib.bootstrap.Calc3BModelBootstrap;
import kz.fin.formlib.bootstrap.Calc3BRequirementBootstrap;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc3BGovernancePostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("calc3b_governance").withUsername("fin").withPassword("test_only");

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired Calc3BRequirementBootstrap requirements;
  @Autowired Calc3BModelBootstrap models;
  @Autowired Calc154FormulaContractBootstrap formulas;

  @Test void everyNumericFormRequiresHumanReviewWithoutInventedMandatoryOutputs(){
    assertEquals(154,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.form_calculation_requirement_revision
        """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.form_calculation_requirement_revision
        WHERE mandatory_outputs_jsonb IS NOT NULL
           OR mandatory_output_state<>'REVIEW_REQUIRED'
           OR approval_state<>'NOT_S2_APPROVED'
           OR obligation_blockers NOT LIKE '%MANDATORY_OUTPUT_SET_NOT_AUTHORED%'
        """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.form_calculation_requirement_revision r
        JOIN fin_lib.lib_form_definition f ON f.form_code=r.form_code
        JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
        WHERE v.id<>r.form_version_id
           OR v.schema_sha256<>r.source_schema_sha256
        """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.form_calculation_requirement_revision r
        JOIN fin_lib.lib_form_version v ON v.id=r.form_version_id
        WHERE v.content_mode='EVIDENCE'
        """,Integer.class));
  }

  @Test void typedFormulaInputsPinNumericFieldOrExactDerivedContract(){
    assertEquals(21,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.formula_typed_input_binding
        """,Integer.class));
    assertEquals(19,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.formula_typed_input_binding
        WHERE source_kind='PHYSICAL_NUMERIC_FIELD'
        """,Integer.class));
    assertEquals(2,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.formula_typed_input_binding
        WHERE source_kind='FORMULA_CANDIDATE'
        """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.formula_typed_input_binding i
        JOIN fin_calc.formula_contract_revision c ON c.id=i.formula_revision_id
        JOIN fin_lib.lib_form_version v ON v.id=i.source_form_version_id
        WHERE i.source_form_version_id<>c.form_version_id
           OR i.source_schema_sha256<>v.schema_sha256
           OR i.requiredness_state<>'REVIEW_REQUIRED'
           OR i.period_policy<>c.period_semantics
        """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.formula_typed_input_binding i
        LEFT JOIN fin_calc.formula_contract_revision d
          ON d.id=i.source_formula_revision_id
        WHERE i.source_kind='FORMULA_CANDIDATE'
          AND (d.id IS NULL OR d.output_metric_code<>i.source_metric_code
               OR d.form_version_id<>i.source_form_version_id)
        """,Integer.class));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));
  }

  @Test void fortyTwoModelsPinNinetySevenInputsAndFortySixUnapprovedOutputs(){
    assertEquals(42,jdbc.queryForObject(
        "SELECT count(*) FROM fin_calc.model_candidate_revision",Integer.class));
    assertEquals(97,jdbc.queryForObject(
        "SELECT count(*) FROM fin_calc.model_candidate_input_form",Integer.class));
    assertEquals(46,jdbc.queryForObject(
        "SELECT count(*) FROM fin_calc.model_candidate_output_metric",Integer.class));
    assertEquals(1,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.model_candidate_input_form i
        JOIN fin_calc.model_candidate_revision m ON m.id=i.model_revision_id
        WHERE m.model_code='CST-07' AND i.source_form_code='D05'
          AND i.content_mode='EVIDENCE' AND i.numeric_eligible=false
        """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.model_candidate_input_form i
        JOIN fin_lib.lib_form_definition f ON f.form_code=i.source_form_code
        JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
        WHERE v.id<>i.source_form_version_id
           OR v.schema_sha256<>i.source_schema_sha256
           OR v.content_mode<>i.content_mode
           OR i.numeric_eligible<>(v.content_mode IN ('ROWSET','SINGLE_RECORD'))
           OR i.approval_state<>'NOT_S2_APPROVED'
        """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.model_candidate_revision
        WHERE formula_state<>'NOT_AUTHORED' OR requirement_state<>'REVIEW_REQUIRED'
           OR financial_approval_state<>'NOT_S2_APPROVED'
        """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.model_candidate_output_metric
        WHERE formula_state<>'NOT_AUTHORED' OR requiredness_state<>'REVIEW_REQUIRED'
           OR approval_state<>'NOT_S2_APPROVED'
        """,Integer.class));
  }

  @Test void immutableGovernanceRejectsUpdateDeleteTruncate(){
    assertThrows(DataAccessException.class,()->jdbc.update("""
        UPDATE fin_calc.form_calculation_requirement_revision
        SET mandatory_output_state='REVIEW_REQUIRED' WHERE form_code='F02'
        """));
    assertThrows(DataAccessException.class,()->jdbc.update("""
        DELETE FROM fin_calc.formula_typed_input_binding WHERE ordinal=0
        """));
    assertThrows(DataAccessException.class,()->jdbc.update("""
        UPDATE fin_calc.model_candidate_revision
        SET formula_state='NOT_AUTHORED' WHERE model_code='CFO-01'
        """));
    assertThrows(DataAccessException.class,()->jdbc.execute(
        "TRUNCATE fin_calc.model_candidate_input_form"));
    assertThrows(DataAccessException.class,()->jdbc.execute(
        "TRUNCATE fin_calc.model_candidate_output_metric"));
  }

  @Test void databaseRejectsFakeMandatorySetAndEvidenceAsNumeric(){
    assertThrows(DataAccessException.class,()->jdbc.execute("""
        INSERT INTO fin_calc.form_calculation_requirement_revision(
          id,form_code,form_version_id,source_schema_sha256,
          requirement_version,mandatory_output_state,mandatory_outputs_jsonb,
          obligation_blockers,source_definition_sha256,binding_sha256)
        SELECT gen_random_uuid(),form_code,form_version_id,source_schema_sha256,
          'FAKE_REVIEW','REVIEW_REQUIRED','[]'::jsonb,obligation_blockers,
          source_definition_sha256,binding_sha256
        FROM fin_calc.form_calculation_requirement_revision
        WHERE form_code='F02'
        """));
    assertThrows(DataAccessException.class,()->jdbc.execute("""
        INSERT INTO fin_calc.model_candidate_input_form(
          model_revision_id,ordinal,source_form_code,source_form_version_id,
          source_schema_sha256,content_mode,numeric_eligible,
          dependency_state,approval_state,binding_sha256)
        SELECT model_revision_id,777,source_form_code,source_form_version_id,
          source_schema_sha256,content_mode,true,dependency_state,approval_state,
          binding_sha256
        FROM fin_calc.model_candidate_input_form
        WHERE source_form_code='D05' LIMIT 1
        """));
  }

  @Test void replayPreservesAllImmutableHashes() throws Exception {
    String before=jdbc.queryForObject("""
        SELECT string_agg(binding_sha256,',' ORDER BY form_code)
        FROM fin_calc.form_calculation_requirement_revision
        """,String.class);
    String modelsBefore=jdbc.queryForObject("""
        SELECT string_agg(binding_sha256,',' ORDER BY model_code)
        FROM fin_calc.model_candidate_revision
        """,String.class);
    var args=new DefaultApplicationArguments(new String[0]);
    requirements.run(args);
    models.run(args);
    String after=jdbc.queryForObject("""
        SELECT string_agg(binding_sha256,',' ORDER BY form_code)
        FROM fin_calc.form_calculation_requirement_revision
        """,String.class);
    String modelsAfter=jdbc.queryForObject("""
        SELECT string_agg(binding_sha256,',' ORDER BY model_code)
        FROM fin_calc.model_candidate_revision
        """,String.class);
    assertEquals(before,after);
    assertEquals(modelsBefore,modelsAfter);
    assertEquals(154,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.form_calculation_requirement_revision",Integer.class));
    assertEquals(21,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.formula_typed_input_binding",Integer.class));
    assertEquals(97,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.model_candidate_input_form",Integer.class));
  }
  @Test void bootstrapReplaysBaselineAlongsideAdditionalImmutableRevisions() throws Exception {
    // Synthetic unqualified revisions prove the loaders no longer equate
    // global table cardinality with a fixed historical manifest.
    jdbc.update("""
      INSERT INTO fin_calc.formula_contract_revision(
        id,form_code,form_version_id,source_schema_sha256,
        output_metric_code,formula_version,operation_code,source_metrics_jsonb,
        period_semantics,dimension_policy,currency_policy,unit_policy,
        rounding_policy,missing_policy,zero_policy,review_state,blocker_codes,
        source_evidence,definition_sha256,binding_sha256)
      SELECT gen_random_uuid(),form_code,form_version_id,source_schema_sha256,
        output_metric_code,'CALC3C2_TEST_UNQUALIFIED_V2',operation_code,
        source_metrics_jsonb,period_semantics,dimension_policy,currency_policy,
        unit_policy,rounding_policy,missing_policy,zero_policy,
        'CANDIDATE_UNQUALIFIED',blocker_codes,'CALC3C2_SYNTHETIC_TEST_ONLY',
        repeat('a',64),repeat('b',64)
      FROM fin_calc.formula_contract_revision
      WHERE output_metric_code='F02.grossProfit'
      """);
    jdbc.update("""
      INSERT INTO fin_calc.form_calculation_requirement_revision(
        id,form_code,form_version_id,source_schema_sha256,requirement_version,
        mandatory_output_state,mandatory_outputs_jsonb,obligation_blockers,
        approval_state,source_definition_sha256,binding_sha256)
      SELECT gen_random_uuid(),form_code,form_version_id,source_schema_sha256,
        'CALC3C2_TEST_UNQUALIFIED_V2','REVIEW_REQUIRED',NULL,obligation_blockers,
        'NOT_S2_APPROVED',repeat('c',64),repeat('d',64)
      FROM fin_calc.form_calculation_requirement_revision
      WHERE form_code='F02'
      """);
    jdbc.update("""
      INSERT INTO fin_calc.model_candidate_revision(
        id,model_code,catalog_revision,model_pack,catalog_state,
        formula_state,requirement_state,financial_approval_state,
        definition_sha256,binding_sha256)
      SELECT gen_random_uuid(),model_code,'CALC3C2_TEST_UNQUALIFIED_V2',
        model_pack,catalog_state,'NOT_AUTHORED','REVIEW_REQUIRED',
        'NOT_S2_APPROVED',repeat('e',64),repeat('f',64)
      FROM fin_calc.model_candidate_revision
      WHERE model_code='ACC-07' AND catalog_revision='CATALOG_CANDIDATE_V1'
      """);
    var args=new DefaultApplicationArguments(new String[0]);
    formulas.run(args);
    requirements.run(args);
    models.run(args);
    assertEquals(11,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.formula_contract_revision",Integer.class));
    assertEquals(155,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.form_calculation_requirement_revision",Integer.class));
    assertEquals(43,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.model_candidate_revision",Integer.class));
    assertEquals(21,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.formula_typed_input_binding",Integer.class));
    assertEquals(97,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.model_candidate_input_form",Integer.class));
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
  }

}
