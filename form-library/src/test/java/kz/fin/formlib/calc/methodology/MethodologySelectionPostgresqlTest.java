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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class MethodologySelectionPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg =
    new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("calc154_methodology_v1")
      .withUsername("fin").withPassword("test_only");
  @DynamicPropertySource static void dataSource(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired MethodologySelectionService selector;
  @Autowired MethodologyResearchReadService research;
  @Autowired JdbcTemplate sql;

  private static Map<String,Object> params() {
    return Map.of(
      "discountRate",new BigDecimal("0.12"),
      "cashflowRevisionId",UUID.randomUUID().toString(),
      "rateSourceRevisionId",UUID.randomUUID().toString(),
      "valuationDate","2026-11-20",
      "terminalMethod","NONE");
  }

  @Test void variantsArePinnedAndCorrectlyPairCashflowRateAndOutput(){
    var variants=selector.dcfVariants();
    assertEquals(2,variants.size());
    assertEquals(8,selector.dcfParameters().size());
    for(var v:variants){
      assertFalse(v.financiallyApproved());
      if(v.code().equals("FCFF_WACC")){
        assertEquals("FCFF",v.inputBasis());
        assertEquals("WACC",v.rateBasis());
        assertEquals("ENTERPRISE_VALUE",v.resultBasis());
      }else{
        assertEquals("FCFE_KE",v.code());
        assertEquals("COST_OF_EQUITY",v.rateBasis());
        assertEquals("EQUITY_VALUE",v.resultBasis());
      }
    }
  }

  @Test void immutableSelectionReplayAndDistinctVariants() {
    UUID org=UUID.randomUUID(),project=UUID.randomUUID(),scenario=UUID.randomUUID();
    var p=params();
    var f=selector.selectDcfUat(org,project,scenario,"FCFF_WACC",p,"uat-researcher");
    var replay=selector.selectDcfUat(org,project,scenario,"FCFF_WACC",p,"uat-researcher");
    assertFalse(f.replayed());
    assertTrue(replay.replayed());
    assertEquals(f.id(),replay.id());
    assertEquals(f.selectionSha256(),replay.selectionSha256());
    assertEquals("ENTERPRISE_VALUE",f.resultBasis());
    assertFalse(f.financiallyApproved());
    assertFalse(f.productionAdmitted());

    var equity=selector.selectDcfUat(org,project,scenario,"FCFE_KE",p,"uat-researcher");
    assertEquals("EQUITY_VALUE",equity.resultBasis());
    assertNotEquals(equity.id(),f.id());
    assertNotEquals(equity.selectionSha256(),f.selectionSha256());
    var changed=new HashMap<String,Object>(p);
    changed.put("discountRate",new BigDecimal("0.14"));
    var version2=selector.selectDcfUat(org,project,scenario,
      "FCFF_WACC",changed,"uat-researcher");
    assertNotEquals(f.id(),version2.id());
    assertNotEquals(f.paramsSha256(),version2.paramsSha256());
    assertThrows(DataAccessException.class,()->sql.update("""
      UPDATE fin_calc.methodology_selection_revision
      SET production_admitted=true WHERE id=?
      """,f.id()));
    assertThrows(DataAccessException.class,()->sql.update("""
      DELETE FROM fin_calc.methodology_selection_revision WHERE id=?
      """,f.id()));
  }

  @Test void typedPolicyRejectsMissingUnknownAndInvalidGrowth(){
    UUID org=UUID.randomUUID(),project=UUID.randomUUID(),scenario=UUID.randomUUID();
    var missing=new HashMap<String,Object>(params());
    missing.remove("cashflowRevisionId");
    assertThrows(DataAccessException.class,()->
      selector.selectDcfUat(org,project,scenario,"FCFF_WACC",missing,"researcher"));
    var unknown=new HashMap<String,Object>(params());
    unknown.put("sqlExpression","DROP TABLE foo");
    assertThrows(DataAccessException.class,()->
      selector.selectDcfUat(org,project,scenario,"FCFF_WACC",unknown,"researcher"));
    var growth=new HashMap<String,Object>(params());
    growth.put("terminalMethod","GORDON_GROWTH");
    growth.put("terminalGrowthRate",new BigDecimal("0.12"));
    assertThrows(DataAccessException.class,()->
      selector.selectDcfUat(org,project,scenario,"FCFF_WACC",growth,"researcher"));
  }

  @Test void directDbCannotForgeVariantOrSelectionDigest(){
    String sha="a".repeat(64);
    assertThrows(DataAccessException.class,()->sql.update("""
      INSERT INTO fin_calc.methodology_variant_revision(
        id,family_code,variant_code,revision_number,input_basis,rate_basis,
        result_basis,variant_contract_sha256)
      VALUES(?, 'DCF_VALUATION','MIXED_VARIANT',1,'FCFF','COST_OF_EQUITY',
        'ENTERPRISE_VALUE',?)
      """,UUID.randomUUID(),sha));
    // Valid FCFF/WACC labels but fake SHA are rejected by the SHA trigger.
    assertThrows(DataAccessException.class,()->sql.update("""
      INSERT INTO fin_calc.methodology_variant_revision(
        id,family_code,variant_code,revision_number,input_basis,rate_basis,
        result_basis,variant_contract_sha256)
      VALUES(?, 'DCF_VALUATION','DUMMY_VARIANT',1,'FCFF','WACC',
        'ENTERPRISE_VALUE',?)
      """,UUID.randomUUID(),sha));
  }

  @Test void all44SourceFormulasAreNonExecutableCandidates(){
    Integer count=sql.queryForObject(
      "SELECT count(*) FROM fin_calc.methodology_research_candidate",
      Integer.class);
    assertEquals(44,count);
    Integer blocked=sql.queryForObject("""
      SELECT count(*) FROM fin_calc.methodology_research_candidate
      WHERE financially_approved=false AND production_admitted=false
      """,Integer.class);
    assertEquals(count,blocked);
    var corrected=sql.queryForObject("""
      SELECT review_status FROM fin_calc.methodology_research_candidate
      WHERE candidate_code='C01_PAST_DUE_DAYS'
      """,String.class);
    assertEquals("CORRECTED_FROM_SOURCE",corrected);
    assertThrows(DataAccessException.class,()->sql.update("""
      DELETE FROM fin_calc.methodology_research_candidate
      WHERE candidate_code='P07_FCFF'
      """));
  }
  @Test void researchReadinessIsVisibleForAll154ButNotExecutable(){
    Integer forms=sql.queryForObject(
      "SELECT count(*) FROM fin_calc.calc154_methodology_research_readiness_v1",
      Integer.class);
    assertEquals(154,forms);
    var p07=research.requireForm("P07");
    assertTrue(p07.researchCandidateCount()>=7);
    assertEquals(p07.researchCandidateCount(),p07.candidates().size());
    assertFalse(p07.executable());
    assertFalse(p07.financiallyApproved());
    assertTrue(p07.candidates().stream().anyMatch(
      c->c.candidateCode().equals("P07_FCFF")));
    var c01=research.requireForm("C01");
    assertTrue(c01.candidates().stream().anyMatch(
      v->v.candidateCode().equals("C01_PAST_DUE_DAYS")));
    assertThrows(IllegalStateException.class,()->
      research.requireForm("D01"));
  }

}
