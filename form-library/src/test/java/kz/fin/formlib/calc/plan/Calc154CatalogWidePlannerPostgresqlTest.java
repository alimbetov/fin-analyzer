package kz.fin.formlib.calc.plan;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Catalog-wide PostgreSQL qualification, not 154 imaginary financial E2Es. */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc154CatalogWidePlannerPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("calc154_catalog_plan")
        .withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired Calc154CatalogWidePlanner planner;
  @Autowired JdbcTemplate sql;

  @Test void inventoryCoversAll154With145DirectNumericAndNineReferenceOnly(){
    var inv=planner.inventory();
    assertEquals(154,inv.forms().size());
    assertEquals(145,inv.formsWithNumericSources());
    assertEquals(9,inv.referenceOnlyForms());
    assertEquals(5,inv.candidateFormCount());
    assertEquals(10,inv.candidateFormulaCount());
    assertEquals(0,inv.financiallyApprovedCount());
    assertEquals(64,inv.inventorySha256().length());
    assertEquals(154,new HashSet<>(
      inv.forms().stream().map(Calc154CatalogWidePlanner.FormPlan::formCode).toList()).size());
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));

    // Emit the exact live PostgreSQL contract state for all 154 forms.
    // This report is a readiness inventory, NEVER 154 completed calculations.
    try{
      Path folder=Path.of("target/fin-calc154");
      Files.createDirectories(folder);
      var out=new StringBuilder(
        "formCode\\tformVersionId\\tcontentMode\\tschemaSha256\\t"+
        "directNumericFields\\tformulaCandidates\\toperations\\t"+
        "mandatoryOutputStatus\\tfinanciallyApproved\\texecutable\\t"+
        "blockers\\tmanifestSha256\\n".replace("\\\\t","\\t"));
      for(var form:inv.forms()){
        String ops=form.candidates().stream()
          .map(Calc154CatalogWidePlanner.FormulaCandidate::operation)
          .distinct().sorted().collect(java.util.stream.Collectors.joining(";"));
        out.append(String.join("\\t",form.formCode(),form.formVersionId().toString(),
          form.contentMode(),form.schemaSha256(),
          Integer.toString(form.directNumericFields()),
          Integer.toString(form.candidates().size()),ops,
          "REVIEW_REQUIRED","false","false",
          String.join(";",form.blockers()),form.manifestSha256())).append('\\n');
      }
      Files.writeString(folder.resolve("154-form-calc4b-coverage.tsv"),
        out.toString(),StandardCharsets.UTF_8);
    }catch(java.io.IOException e){throw new IllegalStateException("CALC154_REPORT_WRITE_FAILED",e);}
  }

  @TestFactory Stream<DynamicTest> everyNumericFormHasTypedSourcesOrExplicitBlocker(){
    var snapshot=planner.inventory();
    return snapshot.forms().stream().map(form->DynamicTest.dynamicTest(
        "CALC4B_FORM_"+form.formCode(),()->{
      assertEquals(64,form.schemaSha256().length());
      assertEquals(64,form.manifestSha256().length());
      assertFalse(form.financiallyApproved());
      assertFalse(form.executable());
      assertTrue(form.blockers().contains("FINANCIAL_S2_NOT_APPROVED"));
      assertTrue(form.blockers().contains("MANDATORY_OUTPUT_SET_NOT_AUTHORED"));
      assertEquals(form.directNumericFields(),form.sources().size());
      assertEquals(form.sources().size(),new HashSet<>(
        form.sources().stream().map(Calc154CatalogWidePlanner.SourceMetric::metricCode)
          .toList()).size());
      assertTrue(form.sources().stream().allMatch(s->
        s.metricCode().startsWith(form.formCode()+".")
        && Set.of("MONEY","DECIMAL","INTEGER").contains(s.fieldType())));
      if(form.directNumericFields()==0) {
        assertTrue(form.blockers().contains("NO_DIRECT_NUMERIC_SOURCE"));
        assertTrue(form.candidates().isEmpty());
      }
      if(form.candidates().isEmpty()){
        assertEquals(Calc154CatalogWidePlanner.Status.BLOCKED_NO_FORMULA_CONTRACT,
          form.status());
        assertTrue(form.blockers().contains("FORMULA_CONTRACT_NOT_AUTHORED"));
      }else{
        assertEquals(Calc154CatalogWidePlanner.Status.BLOCKED_REQUIRED_OUTPUT_REVIEW,
          form.status());
        assertTrue(form.candidates().stream().allMatch(c->
          c.outputMetric().startsWith(form.formCode()+".")
          && c.bindingSha256().matches("[0-9a-f]{64}")
          && !c.sourceMetrics().isEmpty()));
      }
    }));
  }

  @Test void catalogHasC07AndC09OperationsBeyondF01F02F03(){
    var inv=planner.inventory();
    assertEquals(3,inv.require("C07").candidates().size());
    assertEquals(Set.of("GROUP_BY","RATIO_OF_SUMS"),new HashSet<>(
      inv.require("C07").candidates().stream()
        .map(Calc154CatalogWidePlanner.FormulaCandidate::operation).toList()));
    assertEquals(1,inv.require("C09").candidates().size());
    assertEquals("GROUP_BY",inv.require("C09").candidates().getFirst().operation());
    assertEquals("C09.amount",inv.require("C09").candidates().getFirst().sourceMetrics().getFirst());
    assertEquals(0,inv.require("C01").candidates().size());
    assertTrue(inv.require("C01").directNumericFields()>0);
    assertThrows(IllegalArgumentException.class,()->inv.require("FAKE999"));
  }

  @Test void snapshotIsDeterministicAndDoesNotCreateFinancialArtifacts(){
    var a=planner.inventory();
    var b=planner.inventory();
    assertEquals(a.inventorySha256(),b.inventorySha256());
    for(int i=0;i<154;i++){
      var left=a.forms().get(i);
      var right=b.forms().get(i);
      assertEquals(left.formCode(),right.formCode());
      assertEquals(left.manifestSha256(),right.manifestSha256());
    }
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_fact.fact_set",Integer.class));
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_calc.calc_job",Integer.class));
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));
  }

  @Test void missingFinancialMethodologyMustNotBeReportedComplete(){
    var inv=planner.inventory();
    assertEquals(149,inv.forms().stream().filter(x->x.candidates().isEmpty()).count());
    assertEquals(154,inv.forms().stream().filter(x->!x.executable()).count());
    assertEquals(154,inv.forms().stream().filter(x->!x.financiallyApproved()).count());
    assertEquals(0,sql.queryForObject("""
      SELECT count(*) FROM fin_calc.calc3c2_financial_review_readiness_v1
      WHERE calc_plan_execution_admitted=true
      """,Integer.class));
  }
}
