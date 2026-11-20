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

/**
 * CALC-APPS-R0 technical gate only; no financial S2, no formula execution.
 */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class CalcApplicationContractPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("fin_calc_apps_r0")
          .withUsername("fin").withPassword("test_only");
  @DynamicPropertySource static void dataSource(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired CalcApplicationContractRegistry registry;

  @Test void sixVersionPinnedApplicationInputContractsAreReadableAndUatOnly(){
    var apps=registry.all();
    assertEquals(6,apps.size());
    assertEquals(6,apps.stream().map(CalcApplicationContractRegistry.Contract::code)
        .distinct().count());
    assertTrue(apps.stream().allMatch(a->a.version()==1
        && a.contractSha256().length()==64
        && !a.financiallyApproved() && !a.productionAdmitted()
        && !a.typedInputs().isEmpty() && !a.sourceForms().isEmpty()));
    assertEquals("CALC-A01",apps.getFirst().code());
    assertEquals("CALC-A06",apps.getLast().code());
    assertTrue(apps.getLast().variants().contains("FCFF_WACC"));
    assertTrue(apps.getLast().variants().contains("FCFE_KE"));
  }

  @Test void everyApplicationSourceIsAnExistingFormAfterJavaBootstrap(){
    assertDoesNotThrow(()->registry.requireSourceCatalogIntegrity());
    var sources=jdbc.query("""
      SELECT distinct s.form_code
      FROM fin_calc.calc_application_contract_revision c
      CROSS JOIN LATERAL jsonb_array_elements_text(c.source_form_codes_jsonb) s(form_code)
      """,(r,i)->r.getString(1));
    assertTrue(sources.contains("C01"));
    assertTrue(sources.contains("P07"));
    assertTrue(sources.contains("DF-STK-03"));
  }

  @Test void appendOnlyContractsRejectUpdateDeleteAndPromotion(){
    UUID id=jdbc.queryForObject("""
      SELECT id FROM fin_calc.calc_application_contract_revision
      WHERE app_code='CALC-A05' AND revision_number=1
      """,UUID.class);
    assertNotNull(id);
    assertThrows(DataAccessException.class,()->jdbc.update("""
      UPDATE fin_calc.calc_application_contract_revision
      SET production_admitted=true WHERE id=?
      """,id));
    assertThrows(DataAccessException.class,()->jdbc.update("""
      DELETE FROM fin_calc.calc_application_contract_revision WHERE id=?
      """,id));
  }

  @Test void forgedDigestAndMalformedInputAreRejected(){
    assertThrows(DataAccessException.class,()->jdbc.update("""
      INSERT INTO fin_calc.calc_application_contract_revision(
        id,app_code,revision_number,source_form_codes_jsonb,
        candidate_input_fields_jsonb,proposed_outputs_jsonb,proposed_variants_jsonb,
        blocker_description,contract_sha256)
      VALUES(?,'CALC-A01',2,'["C01"]'::jsonb,
        '["obligationBusinessKey:TEXT"]'::jsonb,
        '["balance"]'::jsonb,'["FIFO_DUE_DATE"]'::jsonb,
        'unauthorized test contract',?)
      """,UUID.randomUUID(),"a".repeat(64)));
    assertThrows(DataAccessException.class,()->jdbc.update("""
      INSERT INTO fin_calc.calc_application_contract_revision(
        id,app_code,revision_number,source_form_codes_jsonb,
        candidate_input_fields_jsonb,proposed_outputs_jsonb,proposed_variants_jsonb,
        blocker_description,contract_sha256)
      VALUES(?,'CALC-A03',2,'["C03"]'::jsonb,
        '["unitCost:USER_SQL"]'::jsonb,
        '["closingCost"]'::jsonb,'["FIFO"]'::jsonb,
        'non typed test contract',?)
      """,UUID.randomUUID(),"a".repeat(64)));
  }
}
