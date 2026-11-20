package kz.fin.formlib.calc.methodology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only policy decision catalog.
 * User editorial decision != verified IFRS clause != independent financial S2.
 * Configurations are NOT linked to executable CalcPlans in this release.
 */
@Service
public final class MethodologyPolicyDecisionRegistry {
  public record Decision(String code,int revision,String scope,String ruleCode,
      String status,List<String> affectedFormCodes,String statement,String guardrail,
      List<String> requiredEvidence,String sourceSha256,String decisionSha256,
      boolean financiallyApproved,boolean productionAdmitted) {
    public Decision {
      affectedFormCodes=List.copyOf(affectedFormCodes);
      requiredEvidence=List.copyOf(requiredEvidence);
    }
  }
  private record Row(
    String code,int revision,String scope,String ruleCode,String status,
    String formsJson,String statement,String guardrail,String evidenceJson,
    String sourceSha,String sha,boolean financial,boolean production) {}

  private static final TypeReference<List<String>> LIST=new TypeReference<>() {};
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public MethodologyPolicyDecisionRegistry(JdbcTemplate jdbc,ObjectMapper mapper){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.mapper=Objects.requireNonNull(mapper);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public List<Decision> all(){
    var rows=jdbc.query("""
      SELECT decision_code,revision_number,scope_level,selected_rule_code,
        decision_state,affected_form_codes_jsonb::text,decision_text,
        guardrail_text,required_evidence_jsonb::text,source_raw_sha256,
        decision_sha256,financial_s2_approved,production_admitted
      FROM fin_calc.methodology_policy_decision_revision
      WHERE revision_number=1 ORDER BY decision_code
      """,(r,i)->new Row(r.getString(1),r.getInt(2),r.getString(3),
        r.getString(4),r.getString(5),r.getString(6),r.getString(7),
        r.getString(8),r.getString(9),r.getString(10).trim(),
        r.getString(11).trim(),r.getBoolean(12),r.getBoolean(13)));
    if(rows.size()!=10)throw new IllegalStateException("METHOD_POLICY_TEN_DECISIONS_REQUIRED");
    var decisions=new ArrayList<Decision>();
    var distinct=new HashSet<String>();
    for(var r:rows) {
      if(!distinct.add(r.code())||!r.code().matches("(P0_[1-7]|P1_[1-3])")
          || r.financial() || r.production() || r.revision()!=1)
        throw new IllegalStateException("METHOD_POLICY_UNQUALIFIED_DECISION_DRIFT");
      String expected=sha("FIN-METHOD-POLICY-DECISION-V1|"+
        r.code()+"|"+r.revision()+"|"+r.scope()+"|"+r.ruleCode()+"|"+
        r.status()+"|"+r.formsJson()+"|"+r.statement()+"|"+
        r.guardrail()+"|"+r.evidenceJson()+"|"+r.sourceSha());
      if(!expected.equals(r.sha()))
        throw new IllegalStateException("METHOD_POLICY_SOURCE_CONTRACT_SHA_DRIFT");
      if(r.code().equals("P1_3") && !r.status().equals("NUMERIC_PARAMS_NOT_APPROVED"))
        throw new IllegalStateException("METHOD_POLICY_NUMERIC_DEFAULTS_UNAPPROVED");
      if(!r.code().equals("P1_3") &&
          !r.status().equals("EDITORIAL_ACCEPTED_WITH_GATES"))
        throw new IllegalStateException("METHOD_POLICY_REVIEW_STATE_CHANGED");
      var forms=parse(r.formsJson());
      var evidence=parse(r.evidenceJson());
      if(forms.isEmpty()||evidence.isEmpty()||forms.size()!=Set.copyOf(forms).size())
        throw new IllegalStateException("METHOD_POLICY_EVIDENCE_OR_FORMS_INVALID");
      decisions.add(new Decision(r.code(),r.revision(),r.scope(),r.ruleCode(),
        r.status(),forms,r.statement(),r.guardrail(),evidence,
        r.sourceSha(),r.sha(),false,false));
    }
    return List.copyOf(decisions);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public void requireAllSourceFormsRegistered(){
    all();
    var missing=jdbc.query("""
      SELECT DISTINCT d.decision_code,f.code
      FROM fin_calc.methodology_policy_decision_revision d
      CROSS JOIN LATERAL jsonb_array_elements_text(d.affected_form_codes_jsonb)
        AS f(code)
      LEFT JOIN fin_lib.lib_form_definition v ON v.form_code=f.code
      WHERE v.form_code IS NULL
      ORDER BY d.decision_code,f.code
      """,(r,i)->r.getString(1)+":"+r.getString(2));
    if(!missing.isEmpty())
      throw new IllegalStateException("METHOD_POLICY_SOURCE_FORM_UNRESOLVED:"+missing);
  }

  private List<String> parse(String json) {
    try {return mapper.readValue(json,LIST);}
    catch(JsonProcessingException ex){
      throw new IllegalStateException("METHOD_POLICY_JSON_CONTRACT_INVALID",ex);
    }
  }
  private static String sha(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch(Exception e) {
      throw new IllegalStateException("METHOD_POLICY_SHA256_UNAVAILABLE",e);
    }
  }
}
