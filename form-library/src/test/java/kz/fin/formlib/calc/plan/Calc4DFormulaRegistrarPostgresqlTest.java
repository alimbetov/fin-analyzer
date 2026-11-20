package kz.fin.formlib.calc.plan;

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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Tests registration of synthetic authored candidates, never finance approval. */
@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc4DFormulaRegistrarPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
      new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("calc4d_formula_authoring")
        .withUsername("fin").withPassword("test_only");
  @DynamicPropertySource static void db(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired JdbcTemplate sql;
  @Autowired Calc4DFormulaCandidateRegistrar registrar;
  @Autowired Calc4DTypedDagCompiler compiler;
  @Autowired Calc154CatalogWidePlanner catalog;

  private UUID version(String form){
    return sql.queryForObject("""
      SELECT v.id FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      WHERE f.form_code=?
      """,UUID.class,form);
  }
  private Calc4DFormulaCandidateRegistrar.Definition definition(String version){
    UUID v=version("C08");
    return new Calc4DFormulaCandidateRegistrar.Definition(
      "C08",v,"C08.syntheticUnqualifiedDifference",version,
      "SUBTRACT","INTERVAL_REQUIRED",List.of(
        new Calc4DFormulaCandidateRegistrar.InputSource("C08.fixedCost",v,null),
        new Calc4DFormulaCandidateRegistrar.InputSource("C08.variableCost",v,null)),
      "CALC4D_SYNTHETIC_TEST_METHOD_NOT_FINANCIALLY_REVIEWED");
  }
  private void assertNotExecutable(){
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_calc.calc_job",Integer.class));
    assertEquals(0,sql.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));
  }

  @Test void newC08CandidateRegistersOnceAndCompilesWithoutNewJavaHandler() throws Exception{
    var r=registrar.register(definition("CALC4D_C08_V1"));
    assertFalse(r.replayed());
    assertFalse(r.financiallyApproved());
    assertEquals(64,r.definitionSha256().length());
    var graph=compiler.compile(List.of(r.revisionId()));
    assertEquals("C08",graph.orderedNodes().getFirst().formCode());
    assertEquals("SUBTRACT",graph.orderedNodes().getFirst().operation());
    assertEquals(2,graph.physicalInputs().size());
    assertFalse(graph.executable());
    assertEquals(1,catalog.inventory().require("C08").candidates().size());
    assertNotExecutable();
  }

  @Test void identicalReplayKeepsSameUUIDAndDifferentContractFailsClosed() throws Exception{
    var request=definition("CALC4D_C08_V2");
    var first=registrar.register(request);
    var again=registrar.register(request);
    assertTrue(again.replayed());
    assertEquals(first.revisionId(),again.revisionId());
    assertEquals(first.bindingSha256(),again.bindingSha256());
    var conflicting=new Calc4DFormulaCandidateRegistrar.Definition(
      request.targetFormCode(),request.exactTargetFormVersionId(),
      request.outputMetricCode(),request.formulaVersion(),request.operation(),
      request.periodSemantics(),request.inputs(),"DIFFERENT_EVIDENCE");
    assertThrows(IllegalStateException.class,()->registrar.register(conflicting));
    assertEquals(1,sql.queryForObject("""
      SELECT count(*) FROM fin_calc.formula_contract_revision
      WHERE output_metric_code='C08.syntheticUnqualifiedDifference'
      """,Integer.class));
  }

  @Test void newImmutableVersionCanCoexistAndDependentDagIsTopological() throws Exception {
    var one=registrar.register(definition("CALC4D_C08_V3"));
    var two=registrar.register(definition("CALC4D_C08_V4"));
    assertNotEquals(one.revisionId(),two.revisionId());
    var dependent=registrar.register(new Calc4DFormulaCandidateRegistrar.Definition(
      "C08",version("C08"),"C08.syntheticDependentDifference",
      "CALC4D_C08_V5","SUBTRACT","INTERVAL_REQUIRED",List.of(
        new Calc4DFormulaCandidateRegistrar.InputSource(
          "C08.syntheticUnqualifiedDifference",version("C08"),one.revisionId()),
        new Calc4DFormulaCandidateRegistrar.InputSource(
          "C08.fixedCost",version("C08"),null)),
      "CALC4D_TEST_PINNED_DEPENDENCY"));
    assertThrows(IllegalStateException.class,()->
      compiler.compile(List.of(dependent.revisionId())));
    var graph=compiler.compile(List.of(dependent.revisionId(),one.revisionId()));
    assertEquals(2,graph.orderedNodes().size());
    assertEquals(one.revisionId(),graph.orderedNodes().getFirst().formulaRevisionId());
    assertEquals(dependent.revisionId(),graph.orderedNodes().get(1).formulaRevisionId());
    assertNotExecutable();
  }

  @Test void candidateInputCanReferenceAnotherExactFormWithoutGuessingExchangeRate()
      throws Exception {
    UUID v08=version("C08"),v09=version("C09");
    var receipt=registrar.register(new Calc4DFormulaCandidateRegistrar.Definition(
      "C09",v09,"C09.syntheticCrossFormCandidate","CALC4D_CROSS_FORM_V1",
      "SUBTRACT","INTERVAL_REQUIRED",List.of(
        new Calc4DFormulaCandidateRegistrar.InputSource("C09.amount",v09,null),
        new Calc4DFormulaCandidateRegistrar.InputSource("C08.fixedCost",v08,null)),
      "CALC4D_SYNTHETIC_CROSS_FORM_NO_FINANCIAL_REVIEW"));
    var graph=compiler.compile(List.of(receipt.revisionId()));
    assertEquals(2,graph.physicalInputs().size());
    assertEquals(2,graph.physicalInputs().stream()
      .map(Calc4DTypedDagCompiler.Input::formVersionId).distinct().count());
    assertTrue(graph.blockers().contains("FORMULA_POLICIES_UNREVIEWED"));
    assertFalse(graph.executable());
  }

  @Test void wrongSourceArityStaleFormAndSQLInjectionLikeOperatorRejected() throws Exception{
    var request=definition("CALC4D_REJECT_V1");
    var missing=new Calc4DFormulaCandidateRegistrar.Definition(
      request.targetFormCode(),request.exactTargetFormVersionId(),
      request.outputMetricCode(),request.formulaVersion(),
      request.operation(),request.periodSemantics(),
      request.inputs().subList(0,1),request.authoredEvidence());
    assertThrows(IllegalArgumentException.class,()->registrar.register(missing));
    var injected=new Calc4DFormulaCandidateRegistrar.Definition(
      request.targetFormCode(),request.exactTargetFormVersionId(),
      request.outputMetricCode(),request.formulaVersion(),
      "SUBTRACT; DROP TABLE fin_calc.calc_plan",request.periodSemantics(),
      request.inputs(),request.authoredEvidence());
    assertThrows(IllegalArgumentException.class,()->registrar.register(injected));
    var stale=new Calc4DFormulaCandidateRegistrar.Definition(
      request.targetFormCode(),UUID.randomUUID(),
      request.outputMetricCode(),request.formulaVersion(),
      request.operation(),request.periodSemantics(),request.inputs(),
      request.authoredEvidence());
    assertThrows(IllegalStateException.class,()->registrar.register(stale));
    var invalid=new Calc4DFormulaCandidateRegistrar.Definition(
      request.targetFormCode(),request.exactTargetFormVersionId(),
      request.outputMetricCode(),request.formulaVersion(),
      request.operation(),request.periodSemantics(),List.of(
        new Calc4DFormulaCandidateRegistrar.InputSource(
          "C08.missingSourceMetric",version("C08"),null),
        request.inputs().get(1)),request.authoredEvidence());
    assertThrows(IllegalStateException.class,()->registrar.register(invalid));
    assertNotExecutable();
  }

  @Test void immutableCandidateCannotBeEditedOrFinancePromotedViaDirectSql()
      throws Exception {
    var r=registrar.register(definition("CALC4D_C08_IMMUTABLE"));
    assertThrows(DataAccessException.class,()->sql.update("""
      UPDATE fin_calc.formula_contract_revision
      SET review_state='UAT_TEST_ONLY' WHERE id=?
      """,r.revisionId()));
    assertThrows(DataAccessException.class,()->sql.update("""
      DELETE FROM fin_calc.formula_typed_input_binding WHERE formula_revision_id=?
      """,r.revisionId()));
    assertThrows(DataAccessException.class,()->sql.update("""
      UPDATE fin_calc.formula_contract_revision
      SET financial_approval_state='S2_APPROVED' WHERE id=?
      """,r.revisionId()));
    assertNotExecutable();
  }
}
