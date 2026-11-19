package kz.fin.formlib.calc.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.calc.hybrid.FinancialMethodologyReviewPacketService;
import kz.fin.formlib.calc.hybrid.RequiredOutputReviewDraftService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * CALC-3C.3-A/B. Authenticated, independent two-person PRE-S2 workflow.
 *
 * NO S2 approval, revocation authority, CalcPlan or CalcJob are created.
 * All actions originate from signed JWT SecurityContext, not client author ID.
 * Legacy drafts without immutable IAM author attribution are rejected.
 */
@Service
public class TrustedFinancialReviewIntakeService {
  public record AuthorDraft(UUID id,int revisionNumber,String bindingSha256) {}
  public record PreS2Request(UUID id,UUID draftId,String requestSha256,
      String packetSha256,String status,boolean approvedForExecution) {}
  private record Attribution(UUID formVersion,String issuer,String subject,
      String audience,String attributionSha) {}
  private record Stored(UUID id,String packetSha,String requestSha,
      String reviewerIssuer,String reviewerSubject) {}

  private final FinancialReviewIamIdentity iam;
  private final RequiredOutputReviewDraftService writer;
  private final FinancialMethodologyReviewPacketService packets;
  private final JdbcTemplate sql;
  private final ObjectMapper json;

  public TrustedFinancialReviewIntakeService(FinancialReviewIamIdentity iam,
      RequiredOutputReviewDraftService writer,
      FinancialMethodologyReviewPacketService packets,
      JdbcTemplate sql,ObjectMapper json) {
    this.iam=Objects.requireNonNull(iam);
    this.writer=Objects.requireNonNull(writer);
    this.packets=Objects.requireNonNull(packets);
    this.sql=Objects.requireNonNull(sql);
    this.json=Objects.requireNonNull(json);
  }

  @Transactional
  public AuthorDraft authorDraft(String formCode,UUID expectedFormVersionId,
      UUID expectedPredecessorId,
      List<RequiredOutputReviewDraftService.OutputDecision> decisions,
      String rationale) throws Exception {
    var author=iam.requireAuthor();
    if(author.subject().length()>128||author.issuer().length()>512)
      throw new IllegalArgumentException("CALC3C3_AUTHOR_ID_TOO_LONG");
    var draft=writer.appendUnqualified(new RequiredOutputReviewDraftService.DraftRequest(
      formCode,expectedFormVersionId,expectedPredecessorId,decisions,
      author.subject(),rationale));

    String binding=sha("FIN-CALC3C3-AUTHOR-V1|"+draft.id()+"|"+
      expectedFormVersionId+"|"+author.issuer()+"|"+author.subject()+"|"+
      author.audience()+"|"+draft.bindingSha256());
    sql.update("""
        INSERT INTO fin_calc.financial_review_draft_author_attribution(
          draft_id,form_version_id,author_issuer,author_subject,dedicated_audience,
          attribution_sha256)
        VALUES(?,?,?,?,?,?) ON CONFLICT (draft_id) DO NOTHING
        """,draft.id(),expectedFormVersionId,author.issuer(),author.subject(),
        author.audience(),binding);
    var source=loadAttribution(draft.id());
    if(!source.issuer().equals(author.issuer())
        ||!source.subject().equals(author.subject())
        ||!source.formVersion().equals(expectedFormVersionId)
        ||!source.audience().equals(author.audience())
        ||!source.attributionSha().equals(binding))
      throw new IllegalStateException("CALC3C3_REVIEW_AUTHOR_ATTRIBUTION_MISMATCH");
    return new AuthorDraft(draft.id(),draft.revisionNumber(),draft.bindingSha256());
  }

  @Transactional(isolation=Isolation.REPEATABLE_READ)
  public PreS2Request submitForIndependentS2(UUID draftId) throws Exception {
    var reviewer=iam.requireReviewer();
    if(draftId==null)throw new IllegalArgumentException("CALC3C3_DRAFT_ID_REQUIRED");
    var author=loadAttribution(draftId); // Legacy drafts without verified IAM are HOLD.
    if(author.issuer().equals(reviewer.issuer())
        &&author.subject().equals(reviewer.subject()))
      throw new IllegalStateException("CALC3C3_SEPARATION_OF_DUTIES_REQUIRED");

    // Serialize submission with draft mutations. New drafts lock the same form
    // row; a new revision makes the original review packet stale.
    var form=sql.query("""
      SELECT f.form_code,v.id FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      JOIN fin_calc.required_output_review_draft_revision d ON d.form_code=f.form_code
      WHERE d.id=? AND d.form_version_id=v.id
      FOR UPDATE OF f
      """,(r,i)->r.getObject(2,UUID.class),draftId);
    if(form.size()!=1||!form.getFirst().equals(author.formVersion()))
      throw new IllegalStateException("CALC3C3_REVIEW_SOURCE_VERSION_STALE");

    var packet=packets.preview(draftId);
    if(packet.qualifiedForExecution()||packet.requiredOutputs()<1
        ||!packet.blockers().contains("FINANCIAL_METHODOLOGY_S2_NOT_VERIFIED"))
      throw new IllegalStateException("CALC3C3_PRE_S2_PACKET_INVALID");
    String blockers=json.writeValueAsString(packet.blockers());
    String requestSha=sha("FIN-CALC3C3-PRE-S2-REQUEST-V1|"+
      draftId+"|"+packet.formVersionId()+"|"+packet.sourceSchemaSha256()+
      "|"+packet.draftBindingSha256()+"|"+packet.packetSha256()+
      "|"+author.issuer()+"|"+author.subject()+"|"+
      reviewer.issuer()+"|"+reviewer.subject()+"|"+blockers);

    var stored=sql.query("""
      SELECT id,review_packet_sha256,review_request_sha256,
        reviewer_issuer,reviewer_subject
      FROM fin_calc.financial_methodology_review_request
      WHERE draft_id=?
      """,(r,i)->new Stored(r.getObject(1,UUID.class),r.getString(2),
        r.getString(3),r.getString(4),r.getString(5)),draftId);
    if(!stored.isEmpty()){
      Stored previous=stored.getFirst();
      if(stored.size()!=1||!previous.packetSha().equals(packet.packetSha256())
          ||!previous.requestSha().equals(requestSha)
          ||!previous.reviewerIssuer().equals(reviewer.issuer())
          ||!previous.reviewerSubject().equals(reviewer.subject()))
        throw new IllegalStateException("CALC3C3_PRE_S2_REQUEST_CONFLICT");
      return new PreS2Request(previous.id(),draftId,previous.requestSha(),
        previous.packetSha(),"PRE_S2_REVIEW_ONLY",false);
    }
    UUID requestId=UUID.randomUUID();
    sql.update("""
      INSERT INTO fin_calc.financial_methodology_review_request(
        id,draft_id,form_version_id,form_schema_sha256,
        draft_binding_sha256,review_packet_sha256,author_issuer,author_subject,
        reviewer_issuer,reviewer_subject,required_output_count,
        derived_output_count,blocker_codes_jsonb,review_request_sha256)
      VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
      """,requestId,draftId,packet.formVersionId(),packet.sourceSchemaSha256(),
      packet.draftBindingSha256(),packet.packetSha256(),author.issuer(),author.subject(),
      reviewer.issuer(),reviewer.subject(),packet.requiredOutputs(),
      packet.derivedOutputs(),blockers,requestSha);
    return new PreS2Request(requestId,draftId,requestSha,
      packet.packetSha256(),"PRE_S2_REVIEW_ONLY",false);
  }

  private Attribution loadAttribution(UUID draftId){
    var matched=sql.query("""
      SELECT form_version_id,author_issuer,author_subject,
        dedicated_audience,attribution_sha256
      FROM fin_calc.financial_review_draft_author_attribution WHERE draft_id=?
      """,(r,i)->new Attribution(r.getObject(1,UUID.class),r.getString(2),
        r.getString(3),r.getString(4),r.getString(5)),draftId);
    if(matched.size()!=1||!FinancialReviewIamIdentity.AUDIENCE.equals(
          matched.getFirst().audience()))
      throw new IllegalStateException("CALC3C3_TRUSTED_AUTHOR_ATTRIBUTION_REQUIRED");
    return matched.getFirst();
  }

  private static String sha(String input){
    try{
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(input.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception exception){
      throw new IllegalStateException("CALC3C3_SHA_UNAVAILABLE",exception);
    }
  }
}
