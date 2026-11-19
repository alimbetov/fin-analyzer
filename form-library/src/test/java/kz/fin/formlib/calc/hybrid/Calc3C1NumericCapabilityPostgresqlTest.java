package kz.fin.formlib.calc.hybrid;

import kz.fin.formlib.bootstrap.Calc3C1NumericSourceBootstrap;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc3C1NumericCapabilityPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("calc3c1_numeric").withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void db(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired Calc3C1NumericSourceBootstrap bootstrap;

  @Test void currentFormCapabilitiesAre145NumericNineReferenceTwelveEvidence(){
    assertEquals(166,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.form_numeric_source_capability_revision
      WHERE capability_version='CALC3C1_NUMERIC_SOURCE_V1'
      """,Integer.class));
    assertEquals(145,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.form_numeric_source_capability_revision
      WHERE has_direct_numeric_source=true AND capability_version='CALC3C1_NUMERIC_SOURCE_V1'
      """,Integer.class));
    assertEquals(9,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.form_numeric_source_capability_revision
      WHERE reference_only=true AND capability_version='CALC3C1_NUMERIC_SOURCE_V1'
      """,Integer.class));
    assertEquals(12,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.form_numeric_source_capability_revision
      WHERE evidence_only=true AND capability_version='CALC3C1_NUMERIC_SOURCE_V1'
      """,Integer.class));
    assertEquals(0,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.form_numeric_source_capability_revision c
      JOIN fin_lib.lib_form_definition f ON f.form_code=c.form_code
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      WHERE c.capability_version='CALC3C1_NUMERIC_SOURCE_V1'
        AND (v.id<>c.form_version_id OR v.schema_sha256<>c.source_schema_sha256)
      """,Integer.class));
  }

  @Test void activeProjectionRejectsF08AsNumericEvenThoughLegacyFlagWasTrue(){
    var values=jdbc.queryForMap("""
      SELECT legacy_mode_numeric_eligible,
        source_has_direct_numeric_input,reference_only,
        legacy_numeric_claim_mismatch,direct_numeric_field_count
      FROM fin_calc.model_input_source_capability_v1 c
      JOIN fin_calc.model_candidate_revision m ON m.id=c.model_revision_id
      WHERE m.model_code='ACC-07' AND c.source_form_code='F08'
      """);
    assertEquals(true,values.get("legacy_mode_numeric_eligible"));
    assertEquals(false,values.get("source_has_direct_numeric_input"));
    assertEquals(true,values.get("reference_only"));
    assertEquals(true,values.get("legacy_numeric_claim_mismatch"));
    assertEquals(0,((Number)values.get("direct_numeric_field_count")).intValue());

    assertEquals(1,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.model_input_source_capability_v1 c
      JOIN fin_calc.model_candidate_revision m ON m.id=c.model_revision_id
      WHERE m.model_code='CST-07' AND c.source_form_code='D05'
        AND c.evidence_only=true AND c.source_has_direct_numeric_input=false
      """,Integer.class));
    assertEquals(97,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.model_input_source_capability_v1",Integer.class));
  }

  @Test void immutableSnapshotAndFailedApprovalCannotBeOverwritten(){
    assertThrows(DataAccessException.class,()->jdbc.update("""
      UPDATE fin_calc.form_numeric_source_capability_revision
      SET direct_numeric_field_count=1 WHERE form_code='F08'
      """));
    assertThrows(DataAccessException.class,()->jdbc.update("""
      DELETE FROM fin_calc.form_numeric_source_capability_revision WHERE form_code='F08'
      """));
    assertThrows(DataAccessException.class,()->jdbc.execute(
      "TRUNCATE fin_calc.form_numeric_source_capability_revision"));
    assertThrows(DataAccessException.class,()->jdbc.execute("""
      INSERT INTO fin_calc.form_numeric_source_capability_revision(
        id,form_code,form_version_id,source_schema_sha256,capability_version,
        content_mode,source_field_count,direct_numeric_field_count,
        has_direct_numeric_source,reference_only,evidence_only,
        approval_state,definition_sha256,binding_sha256)
      SELECT gen_random_uuid(),form_code,form_version_id,source_schema_sha256,
        'CALC3C1_FAKE_APPROVAL',content_mode,source_field_count,
        direct_numeric_field_count,has_direct_numeric_source,
        reference_only,evidence_only,'S2_APPROVED',
        definition_sha256,binding_sha256
      FROM fin_calc.form_numeric_source_capability_revision
      WHERE form_code='F08'
      """));
  }

  @Test void deterministicReplayDoesNotMutateOrDuplicateProvenance() throws Exception {
    String initial=jdbc.queryForObject("""
      SELECT string_agg(binding_sha256,',' ORDER BY form_code)
      FROM fin_calc.form_numeric_source_capability_revision
      WHERE capability_version='CALC3C1_NUMERIC_SOURCE_V1'
      """,String.class);
    bootstrap.run(new DefaultApplicationArguments(new String[0]));
    String repeated=jdbc.queryForObject("""
      SELECT string_agg(binding_sha256,',' ORDER BY form_code)
      FROM fin_calc.form_numeric_source_capability_revision
      WHERE capability_version='CALC3C1_NUMERIC_SOURCE_V1'
      """,String.class);
    assertEquals(initial,repeated);
    assertEquals(166,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.form_numeric_source_capability_revision",Integer.class));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));
  }
}
