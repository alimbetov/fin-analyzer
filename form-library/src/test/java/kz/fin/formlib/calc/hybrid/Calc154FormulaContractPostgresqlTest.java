package kz.fin.formlib.calc.hybrid;

import kz.fin.formlib.bootstrap.Calc154FormulaContractBootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** CALC-154: real PG16 contract registry and negative tamper checks.
 * Never production S2, never a proof that 154 financial formulas are complete.
 */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc154FormulaContractPostgresqlTest {
  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("calc154_formula_contracts")
      .withUsername("fin").withPassword("test_only");

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url",postgres::getJdbcUrl);
    properties.add("spring.datasource.username",postgres::getUsername);
    properties.add("spring.datasource.password",postgres::getPassword);
    properties.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired Calc154FormulaContractBootstrap bootstrap;

  @Test void tenRealFormVersionBoundCandidateContractsAndThreeUatReferences(){
    assertEquals(10,jdbc.queryForObject(
        "SELECT count(*) FROM fin_calc.formula_contract_revision",Integer.class));
    assertEquals(3,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.formula_contract_revision
        WHERE review_state='UAT_TEST_ONLY'
        """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.formula_contract_revision
        WHERE financial_approval_state<>'NOT_S2_APPROVED'
        """,Integer.class));
    var rows=jdbc.query("""
        SELECT c.output_metric_code,c.definition_sha256,c.binding_sha256,
          c.source_schema_sha256,v.schema_sha256,c.form_code,f.form_code,
          c.source_metrics_jsonb::text
        FROM fin_calc.formula_contract_revision c
        JOIN fin_lib.lib_form_version v ON v.id=c.form_version_id
        JOIN fin_lib.lib_form_definition f ON f.current_version_id=v.id
        ORDER BY c.output_metric_code
        """,(rs,i)->new String[]{
          rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
          rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)});
    assertEquals(10,rows.size());
    var hashes=new HashSet<String>();
    for(var row:rows){
      assertEquals(64,row[1].length());
      assertEquals(64,row[2].length());
      assertTrue(hashes.add(row[2]));
      assertEquals(row[3],row[4]);
      assertEquals(row[5],row[6]);
      assertTrue(row[7].startsWith("["));
    }
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));
  }

  @Test void revisionUpdateDeleteAndTruncateAreRejected(){
    assertThrows(DataAccessException.class,()->jdbc.update("""
        UPDATE fin_calc.formula_contract_revision
        SET review_state='CANDIDATE_UNQUALIFIED'
        WHERE output_metric_code='F02.grossProfit'
        """));
    assertThrows(DataAccessException.class,()->jdbc.update("""
        DELETE FROM fin_calc.formula_contract_revision
        WHERE output_metric_code='F02.grossProfit'
        """));
    assertThrows(DataAccessException.class,()->jdbc.execute(
        "TRUNCATE fin_calc.formula_contract_revision"));
  }

  @Test void databaseRejectsImplicitFinanceApprovalAndUnreviewedPolicyPromotion(){
    assertEquals(0,jdbc.queryForObject("""
        SELECT count(*) FROM fin_calc.formula_contract_revision
        WHERE financial_approval_state='S2_APPROVED'
        """,Integer.class));
    assertThrows(DataAccessException.class,()->jdbc.execute("""
        INSERT INTO fin_calc.formula_contract_revision(
          id,form_code,form_version_id,source_schema_sha256,output_metric_code,
          formula_version,operation_code,source_metrics_jsonb,period_semantics,
          dimension_policy,currency_policy,unit_policy,rounding_policy,missing_policy,
          zero_policy,review_state,financial_approval_state,blocker_codes,
          source_evidence,definition_sha256,binding_sha256)
        SELECT gen_random_uuid(),form_code,form_version_id,source_schema_sha256,
          'F02.invalidApproval','INTERNAL_TEST','SUBTRACT',source_metrics_jsonb,
          period_semantics,dimension_policy,currency_policy,unit_policy,
          rounding_policy,missing_policy,zero_policy,review_state,'S2_APPROVED',
          blocker_codes,source_evidence,definition_sha256,binding_sha256
        FROM fin_calc.formula_contract_revision
        WHERE output_metric_code='F02.grossProfit'
        """));
  }

  @Test void startupReplayDoesNotChangeSHAOrAddCandidates() throws Exception {
    var args=new DefaultApplicationArguments(new String[0]);
    String before=jdbc.queryForObject("""
        SELECT string_agg(definition_sha256||':'||binding_sha256,',' ORDER BY output_metric_code)
        FROM fin_calc.formula_contract_revision
        """,String.class);
    bootstrap.run(args);
    String after=jdbc.queryForObject("""
        SELECT string_agg(definition_sha256||':'||binding_sha256,',' ORDER BY output_metric_code)
        FROM fin_calc.formula_contract_revision
        """,String.class);
    assertEquals(before,after);
    assertEquals(10,jdbc.queryForObject(
        "SELECT count(*) FROM fin_calc.formula_contract_revision",Integer.class));
  }
}
