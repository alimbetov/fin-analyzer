package kz.fin.formlib;

import kz.fin.formlib.bootstrap.FieldMetricContractBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** R16.A direct DB proof: every active field is uniquely role-bound or evidence-only. */
@SpringBootTest
@AutoConfigureMockMvc
class FieldMetricContractIntegrityTest {
  @Autowired JdbcTemplate db;
  @Autowired FieldMetricContractBootstrap seeder;

  private long n(String sql,Object... a){
    return Objects.requireNonNull(db.queryForObject(sql,Long.class,a));
  }

  @Test void exactly877FieldsAnd793PhysicalMetricsPlus84EvidenceBindings(){
    assertEquals(166,n("SELECT COUNT(*) FROM fin_lib.lib_form_definition"));
    assertEquals(877,n("SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract"));
    assertEquals(793,n("""
      SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract
      WHERE semantic_role='SOURCE_FIELD' AND metric_code IS NOT NULL
      """));
    assertEquals(84,n("""
      SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract
      WHERE semantic_role='EVIDENCE_METADATA' AND metric_code IS NULL
      """));
    assertEquals(1,n("SELECT COUNT(*) FROM fin_lib.lib_metric_binding_exception"));
    assertEquals(1,n("SELECT COUNT(*) FROM fin_lib.lib_field_contract_seed_manifest"));
    assertEquals(0,n("""
      SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract c
      JOIN fin_lib.lib_metric_definition m ON m.metric_code=c.metric_code
      WHERE c.semantic_role='SOURCE_FIELD' AND
        (m.form_code<>c.form_code OR m.source_field_code<>c.field_code OR
         m.metric_kind<>'SOURCE_FIELD')
      """));
    assertEquals(0,n("""
      SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract c
      JOIN fin_lib.lib_form_definition f ON c.form_code=f.form_code
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      WHERE c.source_schema_sha256<>v.schema_sha256
      """));
    assertEquals(877,n("""
      SELECT COUNT(DISTINCT form_code || '.' || field_code)
      FROM fin_lib.lib_field_binding_contract
      """));
    assertEquals(46,n("""
      SELECT COUNT(*) FROM fin_lib.lib_metric_definition
      WHERE metric_kind='ANALYTICAL_OUTPUT'
      """));
    assertEquals(840,n("SELECT COUNT(*) FROM fin_lib.lib_metric_definition"));
  }

  @Test void f02NetProfitIsNotAnotherEditablePhysicalField(){
    assertEquals(3,n("""
      SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract
      WHERE form_code='F02' AND semantic_role='SOURCE_FIELD'
      """));
    assertEquals(0,n("""
      SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract
      WHERE metric_code='F02.netProfit'
      """));
    assertEquals("FORM_DERIVED_CANDIDATE",db.queryForObject("""
      SELECT semantic_role FROM fin_lib.lib_metric_binding_exception
      WHERE metric_code='F02.netProfit'
      """,String.class));
    assertEquals("netRevenue - cogs - operatingExpenses",db.queryForObject("""
      SELECT formula_expression FROM fin_lib.lib_metric_binding_exception
      WHERE metric_code='F02.netProfit'
      """,String.class));
    assertEquals("CANDIDATE_UNQUALIFIED",db.queryForObject("""
      SELECT review_state FROM fin_lib.lib_metric_binding_exception
      WHERE metric_code='F02.netProfit'
      """,String.class));
  }

  @Test void immutableSeedReplaysWithoutDuplicatingContracts(){
    var digest=db.queryForObject(
      "SELECT source_sha256 FROM fin_lib.lib_field_contract_seed_manifest",
      String.class);
    seeder.run(new DefaultApplicationArguments(new String[0]));
    assertEquals(877,n("SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract"));
    assertEquals(digest,db.queryForObject(
      "SELECT source_sha256 FROM fin_lib.lib_field_contract_seed_manifest",String.class));
  }

  @Test void databaseRejectsCrossMetricAndInvalidFieldBindings(){
    assertThrows(DataIntegrityViolationException.class,()->db.update("""
      INSERT INTO fin_lib.lib_field_binding_contract
      (form_code,field_code,source_schema_sha256,field_type,input_mode,
       required,unit_policy,semantic_role,metric_code,contract_sha256,review_state)
      VALUES(?,?,?,?,?,?,?,?,?,?,?)
      ""","F02","forgedField","a".repeat(64),"MONEY","MANUAL",false,
      "FORM_CURRENCY_AND_SCALE","SOURCE_FIELD","F02.doesNotExist",
      "b".repeat(64),"CANDIDATE_UNQUALIFIED"));
    assertThrows(DataIntegrityViolationException.class,()->db.update("""
      INSERT INTO fin_lib.lib_field_binding_contract
      (form_code,field_code,source_schema_sha256,field_type,input_mode,
       required,unit_policy,semantic_role,metric_code,contract_sha256,review_state)
      VALUES(?,?,?,?,?,?,?,?,?,?,?)
      ""","F02","forgedEvidence","a".repeat(64),"REF","MANUAL",false,
      "NOT_A_FINANCIAL_METRIC","EVIDENCE_METADATA","F02.netRevenue",
      "b".repeat(64),"CANDIDATE_UNQUALIFIED"));
    assertEquals(877,n("SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract"));
  }
}
