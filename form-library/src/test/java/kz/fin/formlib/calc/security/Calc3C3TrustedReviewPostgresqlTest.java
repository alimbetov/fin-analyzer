package kz.fin.formlib.calc.security;

import kz.fin.formlib.calc.hybrid.RequiredOutputReviewDraftService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL 16 negative governance: JWT authentication assumed verified
 * upstream by servlet resource-server. This class exercises service/SQL
 * authorization, NOT RSA signature validation or real Keycloak availability.
 * No APPROVED decision or executable plan is produced.
 */
@SpringBootTest(properties={
    "fin.uat.editor.enabled=false",
    "fin.financial-review.api-enabled=false",
    "fin.financial-review.issuer=https://idp.example.org/realms/fin",
    "fin.financial-review.audience=fin-form-library/financial-methodology-review/v1"})
@Testcontainers
@EnabledIfSystemProperty(named="fin.postgres.uat.enabled",matches="true")
class Calc3C3TrustedReviewPostgresqlTest {
  @Container static final PostgreSQLContainer<?> pg=
      new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("calc3c3_trusted").withUsername("fin").withPassword("test_only");

  @DynamicPropertySource static void db(DynamicPropertyRegistry r){
    r.add("spring.datasource.url",pg::getJdbcUrl);
    r.add("spring.datasource.username",pg::getUsername);
    r.add("spring.datasource.password",pg::getPassword);
    r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired TrustedFinancialReviewIntakeService intake;
  @Autowired RequiredOutputReviewDraftService legacy;

  @AfterEach void clearSecurity(){SecurityContextHolder.clearContext();}

  private UUID version(String code){
    return jdbc.queryForObject("""
      SELECT v.id FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      WHERE f.form_code=?
      """,UUID.class,code);
  }
  private void principal(String subject,String role,String issuer,String audience,
      boolean expired){
    Instant now=Instant.now();
    Jwt jwt=Jwt.withTokenValue("test-jwt-verified-in-real-http-layer")
      .header("alg","RS256")
      .issuer(issuer).subject(subject).audience(List.of(audience))
      .issuedAt(now.minusSeconds(120))
      .expiresAt(expired?now.minusSeconds(1):now.plusSeconds(600))
      .claim("realm_access",Map.of("roles",List.of(role)))
      .build();
    SecurityContextHolder.getContext().setAuthentication(
      new JwtAuthenticationToken(jwt));
  }
  private void principal(String subject,String role){
    principal(subject,role,"https://idp.example.org/realms/fin",
      FinancialReviewIamIdentity.AUDIENCE,false);
  }
  private RequiredOutputReviewDraftService.OutputDecision source(String metric){
    return new RequiredOutputReviewDraftService.OutputDecision(
      metric,"REQUIRED","SOURCE",null);
  }
  private TrustedFinancialReviewIntakeService.AuthorDraft author(String form,String metric)
      throws Exception{
    principal("author-"+form,FinancialReviewIamIdentity.AUTHOR_ROLE);
    return intake.authorDraft(form,version(form),null,List.of(source(metric)),
      "UAT independent methodology review required");
  }
  private void noPlanOrApproval(){
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_plan",Integer.class));
    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM fin_calc.calc_result",Integer.class));
    assertEquals(0,jdbc.queryForObject("""
      SELECT count(*) FROM fin_calc.financial_methodology_review_request
      WHERE review_status<>'PRE_S2_REVIEW_ONLY'
        OR financial_approval_state<>'NOT_S2_APPROVED'
      """,Integer.class));
  }

  @Test void authorAndDifferentReviewerCanCreatePreS2OnlyWithIdempotentReplay() throws Exception{
    var draft=author("F02","F02.netRevenue");
    principal("reviewer-bob",FinancialReviewIamIdentity.REVIEWER_ROLE);
    var request=intake.submitForIndependentS2(draft.id());
    assertEquals("PRE_S2_REVIEW_ONLY",request.status());
    assertFalse(request.approvedForExecution());
    assertEquals(64,request.requestSha256().length());
    assertEquals(request,intake.submitForIndependentS2(draft.id()));
    assertEquals(1,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.financial_methodology_review_request",Integer.class));
    noPlanOrApproval();
  }

  @Test void selfReviewAndDifferentSecondReviewerMustFail() throws Exception{
    var draft=author("C07","C07.grossSales");
    principal("author-C07",FinancialReviewIamIdentity.REVIEWER_ROLE);
    assertThrows(IllegalStateException.class,()->intake.submitForIndependentS2(draft.id()));
    principal("reviewer-one",FinancialReviewIamIdentity.REVIEWER_ROLE);
    var receipt=intake.submitForIndependentS2(draft.id());
    assertNotNull(receipt.id());
    principal("reviewer-two",FinancialReviewIamIdentity.REVIEWER_ROLE);
    assertThrows(IllegalStateException.class,()->intake.submitForIndependentS2(draft.id()));
    noPlanOrApproval();
  }

  @Test void missingRoleWrongIssuerWrongAudienceAndExpiredJwtFailClosed() throws Exception{
    SecurityContextHolder.clearContext();
    assertThrows(AccessDeniedException.class,()->intake.authorDraft(
      "C09",version("C09"),null,List.of(source("C09.amount")),"not trusted"));
    principal("bad","FIN_OTHER_ROLE");
    assertThrows(AccessDeniedException.class,()->intake.authorDraft(
      "C09",version("C09"),null,List.of(source("C09.amount")),"wrong role"));
    principal("bad",FinancialReviewIamIdentity.AUTHOR_ROLE,
      "https://untrusted.example.org","fin-form-library/financial-methodology-review/v1",false);
    assertThrows(AccessDeniedException.class,()->intake.authorDraft(
      "C09",version("C09"),null,List.of(source("C09.amount")),"wrong issuer"));
    principal("bad",FinancialReviewIamIdentity.AUTHOR_ROLE,
      "https://idp.example.org/realms/fin","other/audience",false);
    assertThrows(AccessDeniedException.class,()->intake.authorDraft(
      "C09",version("C09"),null,List.of(source("C09.amount")),"wrong audience"));
    principal("bad",FinancialReviewIamIdentity.AUTHOR_ROLE,
      "https://idp.example.org/realms/fin",
      "fin-form-library/financial-methodology-review/v1",true);
    assertThrows(AccessDeniedException.class,()->intake.authorDraft(
      "C09",version("C09"),null,List.of(source("C09.amount")),"expired token"));
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.financial_review_draft_author_attribution",Integer.class));
    noPlanOrApproval();
  }

  @Test void legacyUnauthenticatedDraftCannotBecomeTrustedReviewRequest() throws Exception{
    var legacyDraft=legacy.appendUnqualified(new RequiredOutputReviewDraftService.DraftRequest(
      "F03",version("F03"),null,List.of(source("F03.openingCash")),
      "user-supplied-string-not-an-identity","old internal workflow"));
    principal("reviewer",FinancialReviewIamIdentity.REVIEWER_ROLE);
    assertThrows(IllegalStateException.class,()->intake.submitForIndependentS2(
      legacyDraft.id()));
    assertEquals(0,jdbc.queryForObject(
      "SELECT count(*) FROM fin_calc.financial_methodology_review_request",Integer.class));
  }

  @Test void supersededDraftNeverReceivesPreS2Request() throws Exception{
    principal("author-p09",FinancialReviewIamIdentity.AUTHOR_ROLE);
    var first=intake.authorDraft("F02",version("F02"),null,
      List.of(source("F02.cogs")),"First review candidate");
    var second=intake.authorDraft("F02",version("F02"),first.id(),
      List.of(source("F02.netRevenue")),"Superseding review candidate");
    principal("reviewer",FinancialReviewIamIdentity.REVIEWER_ROLE);
    assertThrows(IllegalStateException.class,()->intake.submitForIndependentS2(first.id()));
    assertEquals("PRE_S2_REVIEW_ONLY",intake.submitForIndependentS2(second.id()).status());
    noPlanOrApproval();
  }

  @Test void directPostgresqlMutationAndForgedApprovalAreDenied() throws Exception{
    var draft=author("C09","C09.amount");
    principal("reviewer",FinancialReviewIamIdentity.REVIEWER_ROLE);
    var packet=intake.submitForIndependentS2(draft.id());
    assertThrows(DataAccessException.class,()->jdbc.update("""
      UPDATE fin_calc.financial_methodology_review_request
      SET financial_approval_state='S2_APPROVED'
      WHERE id=?
      """,packet.id()));
    assertThrows(DataAccessException.class,()->jdbc.update("""
      DELETE FROM fin_calc.financial_review_draft_author_attribution WHERE draft_id=?
      """,draft.id()));
    assertThrows(DataAccessException.class,()->jdbc.execute(
      "TRUNCATE fin_calc.financial_methodology_review_request"));
    assertThrows(DataAccessException.class,()->jdbc.execute("""
      INSERT INTO fin_calc.financial_methodology_review_request(
        id,draft_id,form_version_id,form_schema_sha256,draft_binding_sha256,
        review_packet_sha256,author_issuer,author_subject,
        reviewer_issuer,reviewer_subject,required_output_count,
        derived_output_count,blocker_codes_jsonb,review_status,review_request_sha256)
      SELECT gen_random_uuid(),draft_id,form_version_id,form_schema_sha256,
        draft_binding_sha256,review_packet_sha256,author_issuer,author_subject,
        reviewer_issuer,reviewer_subject,required_output_count,
        derived_output_count,blocker_codes_jsonb,'S2_APPROVED',review_request_sha256
      FROM fin_calc.financial_methodology_review_request LIMIT 1
      """));
    noPlanOrApproval();
  }

  @Test void authorOnlyCannotSubmitAndReviewerOnlyCannotAuthor() throws Exception {
    var draft=author("C09","C09.amount");
    assertThrows(AccessDeniedException.class,()->intake.submitForIndependentS2(draft.id()));
    principal("reviewer",FinancialReviewIamIdentity.REVIEWER_ROLE);
    assertThrows(AccessDeniedException.class,()->intake.authorDraft(
      "F02",version("F02"),null,List.of(source("F02.netRevenue")),
      "reviewer not author"));
    noPlanOrApproval();
  }
}
