package kz.fin.formlib.calc.hybrid;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.bootstrap.Calc3BRequirementBootstrap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * CALC-3C.1: INTERNAL finance-editorial DRAFT workflow.
 *
 * This is not an HTTP endpoint and does not establish authorization or S2.
 * Caller identity must eventually come from IAM; current reviewerRef is only
 * traceability in a test-only workflow. Do not expose this service publicly
 * without an authenticated, role-checked moderation controller.
 *
 * A human may propose REQUIRED/OPTIONAL outputs; the proposal is appended
 * immutably and cannot become an approved methodology or a CalcPlan.
 */
@Service
public class RequiredOutputReviewDraftService {
  public record OutputDecision(String metricCode, String requiredness,
      String sourceKind, UUID formulaRevisionId) {}
  public record DraftRequest(String formCode, UUID expectedFormVersionId,
      UUID expectedPredecessorId, List<OutputDecision> decisions,
      String reviewerRef, String rationale) {}
  public record DraftReceipt(UUID id,int revisionNumber,
      String definitionSha256,String bindingSha256) {}
  private record FormBinding(UUID version,String schema,String mode) {}
  private record Prior(UUID id,int number,String definitionSha,String bindingSha,
      UUID predecessorId) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public RequiredOutputReviewDraftService(JdbcTemplate jdbc,ObjectMapper json){
    this.jdbc=Objects.requireNonNull(jdbc);
    this.json=Objects.requireNonNull(json);
  }

  @Transactional
  public DraftReceipt appendUnqualified(DraftRequest req) throws Exception {
    if(req==null||req.formCode()==null||req.formCode().isBlank()
       ||req.expectedFormVersionId()==null||req.decisions()==null
       ||req.decisions().size()>128||req.reviewerRef()==null
       ||req.reviewerRef().isBlank()||req.reviewerRef().length()>128
       ||req.rationale()==null||req.rationale().isBlank())
      throw new IllegalArgumentException("CALC3C1_REVIEW_DRAFT_ARGUMENTS_REQUIRED");

    // Serialize concurrent writers for this form; never SELECT "latest" from
    // data snapshots outside a transaction or assume a stable version.
    var forms=jdbc.query("""
        SELECT v.id,v.schema_sha256,v.content_mode
        FROM fin_lib.lib_form_definition f
        JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
        WHERE f.form_code=?
        FOR UPDATE OF f
        """,(r,i)->new FormBinding(r.getObject(1,UUID.class),
          r.getString(2),r.getString(3)),req.formCode());
    if(forms.size()!=1)throw new IllegalStateException("CALC3C1_REVIEW_FORM_NOT_FOUND");
    FormBinding form=forms.getFirst();
    if(!form.version().equals(req.expectedFormVersionId())
       ||!"SINGLE_RECORD".equals(form.mode())&&!"ROWSET".equals(form.mode()))
      throw new IllegalStateException("CALC3C1_REVIEW_FORM_VERSION_OR_MODE_STALE");

    List<OutputDecision> decisions=new ArrayList<>(req.decisions());
    decisions.sort(Comparator.comparing(OutputDecision::metricCode));
    var seen=new HashSet<String>();
    var items=new ArrayList<Map<String,Object>>();
    for(var decision:decisions){
      if(decision==null||decision.metricCode()==null
          ||!decision.metricCode().startsWith(req.formCode()+".")
          ||!seen.add(decision.metricCode())
          ||!List.of("REQUIRED","OPTIONAL").contains(decision.requiredness())
          ||!List.of("SOURCE","REFERENCE","DERIVED").contains(decision.sourceKind()))
        throw new IllegalArgumentException("CALC3C1_REVIEW_INVALID_DECISION");
      if("DERIVED".equals(decision.sourceKind())) {
        if(decision.formulaRevisionId()==null)
          throw new IllegalArgumentException("CALC3C1_REVIEW_DERIVED_REVISION_REQUIRED");
        Integer found=jdbc.queryForObject("""
            SELECT count(*) FROM fin_calc.formula_contract_revision c
            WHERE c.id=? AND c.form_version_id=? AND c.form_code=?
              AND c.source_schema_sha256=? AND c.output_metric_code=?
              AND c.financial_approval_state='NOT_S2_APPROVED'
            """,Integer.class,decision.formulaRevisionId(),form.version(),
            req.formCode(),form.schema(),decision.metricCode());
        if(found==null||found!=1)
          throw new IllegalStateException("CALC3C1_REVIEW_FORMULA_BINDING_INVALID");
      }else {
        if(decision.formulaRevisionId()!=null)
          throw new IllegalArgumentException("CALC3C1_REVIEW_UNEXPECTED_FORMULA");
        Integer found=jdbc.queryForObject("""
            SELECT count(*) FROM fin_lib.lib_metric_definition m
            JOIN fin_lib.lib_form_field f ON f.form_version_id=?
              AND f.field_code=m.source_field_code
            WHERE m.form_code=? AND m.metric_code=?
              AND m.metric_kind='SOURCE_FIELD'
              AND (?='REFERENCE' OR f.field_type IN ('MONEY','DECIMAL','INTEGER'))
            """,Integer.class,form.version(),req.formCode(),decision.metricCode(),
             decision.sourceKind());
        if(found==null||found!=1)
          throw new IllegalStateException("CALC3C1_REVIEW_SOURCE_METRIC_INVALID");
      }
      Map<String,Object> item=new LinkedHashMap<>();
      item.put("metricCode",decision.metricCode());
      item.put("requiredness",decision.requiredness());
      item.put("sourceKind",decision.sourceKind());
      if(decision.formulaRevisionId()!=null)
        item.put("formulaRevisionId",decision.formulaRevisionId().toString());
      items.add(item);
    }
    String canonical=json.writeValueAsString(items);
    String definition=sha("CALC3C1-REVIEW-DRAFT-V1|"+req.formCode()+"|"+
      canonical+"|"+req.reviewerRef()+"|"+req.rationale());

    var prior=jdbc.query("""
        SELECT id,revision_number,definition_sha256,binding_sha256,predecessor_id
        FROM fin_calc.required_output_review_draft_revision
        WHERE form_version_id=?
        ORDER BY revision_number DESC LIMIT 1
        """,(r,i)->new Prior(r.getObject(1,UUID.class),r.getInt(2),
          r.getString(3),r.getString(4),r.getObject(5,UUID.class)),
        form.version());
    if(prior.size()>1)throw new IllegalStateException("CALC3C1_REVIEW_DUPLICATE_LATEST");
    Prior latest=prior.isEmpty()?null:prior.getFirst();
    if(latest!=null && latest.definitionSha().equals(definition)
        &&Objects.equals(latest.predecessorId(),req.expectedPredecessorId()))
      return new DraftReceipt(latest.id(),latest.number(),
        latest.definitionSha(),latest.bindingSha());

    UUID actualHead=latest==null?null:latest.id();
    if(!Objects.equals(actualHead,req.expectedPredecessorId()))
      throw new IllegalStateException("CALC3C1_REVIEW_STALE_PREDECESSOR");
    int next=latest==null?1:latest.number()+1;
    UUID id=UUID.randomUUID();
    String binding=sha("CALC3C1-REVIEW-BIND-V1|"+req.formCode()+"|"+
        form.version()+"|"+form.schema()+"|"+next+"|"+
        (actualHead==null?"ROOT":actualHead)+"|"+definition);
    jdbc.update("""
        INSERT INTO fin_calc.required_output_review_draft_revision(
          id,form_code,form_version_id,source_schema_sha256,revision_number,
          predecessor_id,output_decisions_jsonb,review_state,finance_approval,
          reviewer_ref,rationale,definition_sha256,binding_sha256)
        VALUES(?,?,?,?,?,?,?::jsonb,'DRAFT_UNQUALIFIED','NOT_S2_APPROVED',?,?,?,?)
        """,id,req.formCode(),form.version(),form.schema(),next,actualHead,
        canonical,req.reviewerRef(),req.rationale(),definition,binding);
    return new DraftReceipt(id,next,definition,binding);
  }

  private static String sha(String input){
    try{
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(input.getBytes(StandardCharsets.UTF_8)));
    }catch(Exception ex){throw new IllegalStateException("CALC3C1_SHA_UNAVAILABLE",ex);}
  }
}
