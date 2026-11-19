package kz.fin.formlib.calc.hybrid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * CALC-3C.2 internal pre-S2 review packet. This service does not authorize
 * users, accept signatures, or commit an approval; it is NOT an HTTP API.
 *
 * Production moderation requires Keycloak/IAM principal verification AND
 * independent methodology-specific S2 signature validation, which does not
 * exist in this module yet. FactSet S2 is not interchangeable with formula S2.
 */
@Service
public class FinancialMethodologyReviewPacketService {
  public record Packet(UUID draftId,String formCode,UUID formVersionId,
      String sourceSchemaSha256,String draftBindingSha256,
      String packetSha256,List<String> blockers,int requiredOutputs,
      int optionalOutputs,int derivedOutputs,int syntheticControlExamples,
      boolean qualifiedForExecution) {}

  private record Draft(UUID id,String code,UUID version,String schema,String binding,
      String decisions) {}
  private record Formula(UUID id,String output,String binding,String period,String
      dimension,String currency,String rounding,String approval) {}
  private record Control(String binding,String state,String approval) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  public FinancialMethodologyReviewPacketService(JdbcTemplate jdbc,ObjectMapper json){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.json=Objects.requireNonNull(json);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Packet preview(UUID draftId) throws Exception {
    if(draftId==null)throw new IllegalArgumentException("CALC3C2_DRAFT_REQUIRED");
    var found=jdbc.query("""
      SELECT d.id,d.form_code,d.form_version_id,d.source_schema_sha256,
        d.binding_sha256,d.output_decisions_jsonb::text
      FROM fin_calc.required_output_review_draft_revision d
      JOIN fin_lib.lib_form_definition f ON f.form_code=d.form_code
        AND f.current_version_id=d.form_version_id
      JOIN fin_lib.lib_form_version v ON v.id=d.form_version_id
        AND v.schema_sha256=d.source_schema_sha256
      WHERE d.id=? AND d.review_state='DRAFT_UNQUALIFIED'
        AND d.finance_approval='NOT_S2_APPROVED'
        AND d.revision_number=(
          SELECT max(x.revision_number)
          FROM fin_calc.required_output_review_draft_revision x
          WHERE x.form_version_id=d.form_version_id)
      """,(r,i)->new Draft(r.getObject(1,UUID.class),r.getString(2),
        r.getObject(3,UUID.class),r.getString(4),r.getString(5),
        r.getString(6)),draftId);
    if(found.size()!=1)throw new IllegalStateException(
      "CALC3C2_REVIEW_DRAFT_NOT_CURRENT_OR_NOT_UNQUALIFIED");
    Draft d=found.getFirst();
    JsonNode decisions=json.readTree(d.decisions());
    if(!decisions.isArray()||decisions.isEmpty())
      throw new IllegalStateException("CALC3C2_EMPTY_MANDATORY_REVIEW");
    int required=0,optional=0,derived=0,synthetic=0;
    var shaInput=new StringBuilder("FIN-CALC3C2-S2-REVIEW-PACKET-V1|")
      .append(d.id()).append('|').append(d.code()).append('|')
      .append(d.version()).append('|').append(d.schema())
      .append('|').append(d.binding()).append('|').append(d.decisions());
    var blockers=new ArrayList<String>();
    blockers.add("FINANCIAL_METHODOLOGY_S2_NOT_VERIFIED");
    blockers.add("MODERATOR_IAM_IDENTITY_NOT_VERIFIED");

    for(JsonNode decision:decisions){
      String metric=decision.path("metricCode").asText();
      String kind=decision.path("sourceKind").asText();
      String level=decision.path("requiredness").asText();
      if("REQUIRED".equals(level))required++;
      else if("OPTIONAL".equals(level))optional++;
      else throw new IllegalStateException("CALC3C2_DECISION_ROLE_INVALID");

      if(!"DERIVED".equals(kind))continue;
      derived++;
      UUID formulaId=UUID.fromString(decision.path("formulaRevisionId").asText());
      var formula=jdbc.query("""
        SELECT id,output_metric_code,binding_sha256,period_semantics,
          dimension_policy,currency_policy,rounding_policy,financial_approval_state
        FROM fin_calc.formula_contract_revision
        WHERE id=? AND form_version_id=? AND source_schema_sha256=?
          AND output_metric_code=?
        """,(r,i)->new Formula(r.getObject(1,UUID.class),r.getString(2),
          r.getString(3),r.getString(4),r.getString(5),r.getString(6),
          r.getString(7),r.getString(8)),
          formulaId,d.version(),d.schema(),metric);
      if(formula.size()!=1)
        throw new IllegalStateException("CALC3C2_FORMULA_REVISION_INVALID:"+metric);
      Formula f=formula.getFirst();
      if(!"NOT_S2_APPROVED".equals(f.approval()))
        throw new IllegalStateException("CALC3C2_FORGED_FORMULA_APPROVAL");
      shaInput.append('|').append(f.id()).append(':').append(f.binding());
      if("UNREVIEWED".equals(f.dimension())||"UNREVIEWED".equals(f.currency())
          ||"UNREVIEWED".equals(f.rounding()))
        blockers.add("FORMULA_POLICY_UNREVIEWED:"+metric);

      var controls=jdbc.query("""
        SELECT binding_sha256,review_state,financial_approval_state
        FROM fin_calc.financial_control_example_revision
        WHERE formula_revision_id=?
        ORDER BY example_code,binding_sha256
        """,(r,i)->new Control(r.getString(1),r.getString(2),r.getString(3)),
          f.id());
      if(controls.isEmpty())blockers.add("CONTROL_EXAMPLE_MISSING:"+metric);
      for(var c:controls){
        if(!"NOT_S2_APPROVED".equals(c.approval()))
          throw new IllegalStateException("CALC3C2_CONTROL_APPROVAL_INVALID");
        synthetic++;
        shaInput.append('|').append(c.binding());
        if(!"UNREVIEWED".equals(c.state()))
          throw new IllegalStateException("CALC3C2_CONTROL_REVIEW_STATE_INVALID");
      }
      if(!controls.isEmpty())blockers.add("INDEPENDENT_CONTROL_VALIDATION_REQUIRED:"+metric);
    }

    // A packet is never a qualification credential. Only a separately
    // authorized, cryptographically verified S2 receipt may advance status.
    return new Packet(d.id(),d.code(),d.version(),d.schema(),d.binding(),
      sha(shaInput.toString()),List.copyOf(blockers),
      required,optional,derived,synthetic,false);
  }

  private static String sha(String s) throws Exception{
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.UTF_8)));
  }
}
