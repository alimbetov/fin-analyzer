package kz.fin.formlib.calc.methodology;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
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
class MethodologyPolicyDecisionPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg =
    new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("fin_calc_policy_p0p1")
      .withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void db(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired MethodologyPolicyDecisionRegistry registry;

  @Test void allTenPoliciesAreVersionPinnedEditoralNotS2(){
    var entries=registry.all();
    assertEquals(10,entries.size());
    assertEquals(9,entries.stream().filter(x->
      x.status().equals("EDITORIAL_ACCEPTED_WITH_GATES")).count());
    assertEquals(1,entries.stream().filter(x->
      x.status().equals("NUMERIC_PARAMS_NOT_APPROVED")).count());
    assertTrue(entries.stream().allMatch(x->x.revision()==1 &&
      x.decisionSha256().matches("[0-9a-f]{64}") &&
      !x.financiallyApproved()&&!x.productionAdmitted()));
  }

  @Test void crossFormReferencesExistAfterBootstrap(){
    assertDoesNotThrow(()->registry.requireAllSourceFormsRegistered());
    var all=registry.all();
    var dcf=all.stream().filter(x->x.code().equals("P0_6"))
      .findFirst().orElseThrow();
    assertEquals("DCF_VARIANT_PER_SCENARIO_WITH_RATE_PAIRING",dcf.ruleCode());
    assertTrue(dcf.affectedFormCodes().contains("P07"));
    var dscr=all.stream().filter(x->x.code().equals("P0_7"))
      .findFirst().orElseThrow();
    assertTrue(dscr.guardrail().contains("НЕ подтверждает"));
    assertTrue(dscr.affectedFormCodes().contains("P09"));
  }

  @Test void updatesDeletesAndPromotionsAreForbidden(){
    var id=jdbc.queryForObject("""
      SELECT id FROM fin_calc.methodology_policy_decision_revision
      WHERE decision_code='P1_3' AND revision_number=1
      """,UUID.class);
    assertNotNull(id);
    assertThrows(DataAccessException.class,()->jdbc.update("""
      UPDATE fin_calc.methodology_policy_decision_revision
      SET financial_s2_approved=true WHERE id=?
      """,id));
    assertThrows(DataAccessException.class,()->jdbc.update("""
      DELETE FROM fin_calc.methodology_policy_decision_revision WHERE id=?
      """,id));
  }

  @Test void forgedPolicyAndDecisionShaIsNotAdmitted(){
    assertThrows(DataAccessException.class,()->jdbc.update("""
      INSERT INTO fin_calc.methodology_policy_decision_revision(
        id,decision_code,revision_number,scope_level,selected_rule_code,
        decision_state,affected_form_codes_jsonb,decision_text,guardrail_text,
        required_evidence_jsonb,source_repository_path,source_raw_sha256,
        decision_sha256)
      VALUES (?, 'P0_6', 2, 'PROJECT_SCENARIO',
        'UNVERIFIED_RULE','EDITORIAL_ACCEPTED_WITH_GATES',
        '["P07"]'::jsonb,'invalid','invalid',
        '["unverified"]'::jsonb,'unverified',?,?)
      """,UUID.randomUUID(),"a".repeat(64),"b".repeat(64)));
    assertThrows(DataAccessException.class,()->jdbc.update("""
      INSERT INTO fin_calc.methodology_policy_decision_revision(
        id,decision_code,revision_number,scope_level,selected_rule_code,
        decision_state,affected_form_codes_jsonb,decision_text,guardrail_text,
        required_evidence_jsonb,source_repository_path,source_raw_sha256,
        decision_sha256)
      VALUES (?, 'P1_3', 2, 'SYSTEM_AND_FORM',
        'VERSIONED_THRESHOLDS_WITH_TIGHTEN_ONLY_OVERRIDES',
        'NUMERIC_PARAMS_NOT_APPROVED',
        '["P07","P07"]'::jsonb,'invalid','invalid',
        '["unverified"]'::jsonb,'unverified',?,?)
      """,UUID.randomUUID(),"a".repeat(64),"b".repeat(64)));
  }
}
