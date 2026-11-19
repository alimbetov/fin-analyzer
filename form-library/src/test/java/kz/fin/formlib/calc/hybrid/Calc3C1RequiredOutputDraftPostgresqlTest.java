package kz.fin.formlib.calc.hybrid;

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

@SpringBootTest(properties={"fin.uat.editor.enabled=false"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc3C1RequiredOutputDraftPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("calc3c1_review").withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void db(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url",pg::getJdbcUrl);
    registry.add("spring.datasource.username",pg::getUsername);
    registry.add("spring.datasource.password",pg::getPassword);
    registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }

  @Autowired JdbcTemplate db;
  @Autowired RequiredOutputReviewDraftService review;

  private UUID version(String code){
    return db.queryForObject("""
      SELECT v.id FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      WHERE f.form_code=?
      """,UUID.class,code);
  }

  private UUID formula(String metric){
    return db.queryForObject("""
      SELECT id FROM fin_calc.formula_contract_revision
      WHERE output_metric_code=?
      """,UUID.class,metric);
  }

  @Test void reviewerDraftPinsTypedPhysicalAndDerivedMetricWithoutFinanceApproval() throws Exception {
    var decisions=List.of(
      new RequiredOutputReviewDraftService.OutputDecision(
        "F02.netRevenue","REQUIRED","SOURCE",null),
      new RequiredOutputReviewDraftService.OutputDecision(
        "F02.grossProfit","REQUIRED","DERIVED",formula("F02.grossProfit")));
    var req=new RequiredOutputReviewDraftService.DraftRequest(
      "F02",version("F02"),null,decisions,"finance-review-uat",
      "Proposed required output set: finance S2 still pending");
    var created=review.appendUnqualified(req);
    assertEquals(1,created.revisionNumber());
    assertEquals(64,created.definitionSha256().length());
    assertEquals(64,created.bindingSha256().length());
    assertEquals(created,review.appendUnqualified(req),"idempotent retry");

    assertEquals(1,db.queryForObject("""
      SELECT count(*) FROM fin_calc.required_output_review_draft_revision
      WHERE id=? AND review_state='DRAFT_UNQUALIFIED'
        AND finance_approval='NOT_S2_APPROVED'
        AND jsonb_array_length(output_decisions_jsonb)=2
      """,Integer.class,created.id()));
    assertEquals(0,db.queryForObject("""
      SELECT count(*) FROM fin_calc.form_calculation_requirement_revision
      WHERE mandatory_outputs_jsonb IS NOT NULL OR approval_state<>'NOT_S2_APPROVED'
      """,Integer.class));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));
  }

  @Test void successorIsAppendOnlyAndStalePredecessorFails() throws Exception {
    var first=review.appendUnqualified(new RequiredOutputReviewDraftService.DraftRequest(
      "C07",version("C07"),null,List.of(new RequiredOutputReviewDraftService.OutputDecision(
        "C07.grossSales","OPTIONAL","SOURCE",null)),
      "reviewer-uat","Initial review draft"));
    var second=review.appendUnqualified(new RequiredOutputReviewDraftService.DraftRequest(
      "C07",version("C07"),first.id(),List.of(new RequiredOutputReviewDraftService.OutputDecision(
        "C07.grossSales","REQUIRED","SOURCE",null)),
      "reviewer-uat","Revised nonqualified draft"));
    assertEquals(2,second.revisionNumber());
    assertNotEquals(first.id(),second.id());
    assertThrows(IllegalStateException.class,()->review.appendUnqualified(
      new RequiredOutputReviewDraftService.DraftRequest(
        "C07",version("C07"),first.id(),List.of(new RequiredOutputReviewDraftService.OutputDecision(
          "C07.returns","OPTIONAL","SOURCE",null)),"reviewer-uat","Stale attempt")));
    assertEquals(2,db.queryForObject("""
      SELECT count(*) FROM fin_calc.required_output_review_draft_revision
      WHERE form_code='C07'
      """,Integer.class));
  }

  @Test void incorrectVersionNonNumericAsSourceAndForeignFormulaFailClosed() {
    assertThrows(IllegalStateException.class,()->review.appendUnqualified(
      new RequiredOutputReviewDraftService.DraftRequest(
        "F02",UUID.randomUUID(),null,List.of(),"reviewer-uat","Stale schema")));
    assertThrows(IllegalStateException.class,()->review.appendUnqualified(
      new RequiredOutputReviewDraftService.DraftRequest(
        "F05",version("F05"),null,List.of(new RequiredOutputReviewDraftService.OutputDecision(
          "F05.sourceRevisionRef","REQUIRED","SOURCE",null)),
        "reviewer-uat","Reference cannot be numerically summed")));
    assertThrows(IllegalStateException.class,()->review.appendUnqualified(
      new RequiredOutputReviewDraftService.DraftRequest(
        "F02",version("F02"),null,List.of(new RequiredOutputReviewDraftService.OutputDecision(
          "F02.grossProfit","REQUIRED","DERIVED",formula("F01.balanceResidual"))),
        "reviewer-uat","Cross-formula contract forbidden")));
    assertThrows(IllegalArgumentException.class,()->review.appendUnqualified(
      new RequiredOutputReviewDraftService.DraftRequest(
        "F02",version("F02"),null,List.of(
          new RequiredOutputReviewDraftService.OutputDecision(
            "F02.netRevenue","REQUIRED","SOURCE",null),
          new RequiredOutputReviewDraftService.OutputDecision(
            "F02.netRevenue","OPTIONAL","SOURCE",null)),
        "reviewer-uat","Duplicate metrics forbidden")));
  }

  @Test void referenceOnlyFormCanBeReviewedWithoutPretendingItIsNumeric() throws Exception {
    var saved=review.appendUnqualified(new RequiredOutputReviewDraftService.DraftRequest(
      "F05",version("F05"),null,List.of(new RequiredOutputReviewDraftService.OutputDecision(
        "F05.sourceRevisionRef","REQUIRED","REFERENCE",null)),
      "reviewer-uat","Source revision reference required for disclosure provenance"));
    assertEquals(1,saved.revisionNumber());
    assertEquals(1,db.queryForObject("""
      SELECT count(*) FROM fin_calc.required_output_review_draft_revision
      WHERE form_code='F05'
        AND output_decisions_jsonb @> '[{"sourceKind":"REFERENCE"}]'::jsonb
      """,Integer.class));
  }

  @Test void postgresBlocksMutationDirectS2AndInvalidSource() throws Exception {
    var saved=review.appendUnqualified(new RequiredOutputReviewDraftService.DraftRequest(
      "F02",version("F02"),null,List.of(new RequiredOutputReviewDraftService.OutputDecision(
        "F02.netRevenue","REQUIRED","SOURCE",null)),
      "reviewer-uat","Nonapproved draft for mutation testing"));
    assertThrows(DataAccessException.class,()->db.update("""
      UPDATE fin_calc.required_output_review_draft_revision
      SET review_state='DRAFT_UNQUALIFIED' WHERE id=?
      """,saved.id()));
    assertThrows(DataAccessException.class,()->db.update("""
      DELETE FROM fin_calc.required_output_review_draft_revision WHERE id=?
      """,saved.id()));
    assertThrows(DataAccessException.class,()->db.execute(
      "TRUNCATE fin_calc.required_output_review_draft_revision"));
    assertThrows(DataAccessException.class,()->db.update("""
      INSERT INTO fin_calc.required_output_review_draft_revision(
        id,form_code,form_version_id,source_schema_sha256,revision_number,
        predecessor_id,output_decisions_jsonb,finance_approval,reviewer_ref,rationale,
        definition_sha256,binding_sha256)
      SELECT gen_random_uuid(),form_code,form_version_id,source_schema_sha256,
        revision_number+1,id,output_decisions_jsonb,'S2_APPROVED',
        reviewer_ref,rationale,definition_sha256,binding_sha256
      FROM fin_calc.required_output_review_draft_revision WHERE id=?
      """,saved.id()));
    assertThrows(DataAccessException.class,()->db.update("""
      INSERT INTO fin_calc.required_output_review_draft_revision(
        id,form_code,form_version_id,source_schema_sha256,revision_number,
        predecessor_id,output_decisions_jsonb,reviewer_ref,rationale,
        definition_sha256,binding_sha256)
      SELECT gen_random_uuid(),form_code,form_version_id,source_schema_sha256,
        revision_number+1,id,
        '[{"metricCode":"F02.fake","requiredness":"REQUIRED","sourceKind":"SOURCE"}]'::jsonb,
        reviewer_ref,rationale,definition_sha256,binding_sha256
      FROM fin_calc.required_output_review_draft_revision WHERE id=?
      """,saved.id()));
  }
}
