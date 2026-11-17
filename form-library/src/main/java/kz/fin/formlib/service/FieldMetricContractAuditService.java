package kz.fin.formlib.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * R16.A — SQL-traceable draft contract read model, never financial S2 approval.
 * Unlike catalog cards, physical current-version bindings are checked live.
 */
@Service
public class FieldMetricContractAuditService {
  public record Coverage(int forms,int physicalFields,int sourceBindings,
      int evidenceMetadataFields,int unboundDerivedCandidates,int analyticalOutputs,
      int financialQualifiedBindings,int staleSchemaBindings) {}
  public record FieldView(String formCode,UUID formVersionId,String fieldCode,
      String metricCode,String semanticRole,String fieldType,String inputMode,
      boolean required,String unitPolicy,String contractSha256,
      String bindingState,String reviewState,String locale,String title,
      String translationStatus) {}
  public record FormView(String formCode,UUID formVersionId,String schemaSha256,
      String captureMode,String qualificationState,String locale,
      List<FieldView> fields) {}
  private final JdbcTemplate db;
  private final FieldMetricLabelCandidateLookup labelCandidates;
  public FieldMetricContractAuditService(JdbcTemplate db,FieldMetricLabelCandidateLookup labelCandidates){
    this.db=db;this.labelCandidates=labelCandidates;
  }
  private static String locale(String l){
    if(!Set.of("ru","kk","en","zh-Hans").contains(l))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UNSUPPORTED_LOCALE");
    return l;
  }
  private int number(String query,Object...args){
    return Objects.requireNonNull(db.queryForObject(query,Integer.class,args));
  }

  @Transactional(readOnly=true)
  public Coverage coverage(){
    int formCount=number("SELECT COUNT(*) FROM fin_lib.lib_form_definition");
    int all=number("SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract");
    int source=number("""
      SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract
      WHERE semantic_role='SOURCE_FIELD'
      """);
    int evidence=number("""
      SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract
      WHERE semantic_role='EVIDENCE_METADATA'
      """);
    int derived=number("SELECT COUNT(*) FROM fin_lib.lib_metric_binding_exception");
    int outputs=number("""
      SELECT COUNT(*) FROM fin_lib.lib_metric_definition
      WHERE metric_kind='ANALYTICAL_OUTPUT'
      """);
    int stale=number("""
      SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract c
      JOIN fin_lib.lib_form_definition f ON f.form_code=c.form_code
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      WHERE v.schema_sha256<>c.source_schema_sha256
      """);
    int approved=number("""
      SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract
      WHERE review_state <> 'CANDIDATE_UNQUALIFIED'
      """);
    return new Coverage(formCount,all,source,evidence,derived,outputs,
      approved,stale);
  }

  @Transactional(readOnly=true)
  public FormView form(String formCode,String language){
    String l=locale(language);
    if(formCode==null||formCode.isBlank())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"FORM_NOT_FOUND");
    List<FormView> forms=db.query("""
      SELECT f.form_code,f.current_version_id,f.legacy_capture_mode,
             v.schema_sha256,v.qualification_state
      FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      WHERE f.form_code=?
      """,(rs,i)->new FormView(
        rs.getString("form_code"),(UUID)rs.getObject("current_version_id"),
        rs.getString("schema_sha256"),rs.getString("legacy_capture_mode"),
        rs.getString("qualification_state"),l,List.of()),formCode);
    if(forms.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"FORM_NOT_FOUND");
    FormView current=forms.getFirst();
    var localizedCandidates=labelCandidates.fields(formCode,l);
    List<FieldView> fields=db.query("""
      SELECT f.form_code,f.current_version_id,v.schema_sha256,
             ff.field_code,ff.field_type,ff.input_mode,ff.required,
             c.metric_code,c.semantic_role,c.unit_policy,c.contract_sha256,
             c.source_schema_sha256,c.review_state,
             t.title,t.review_status
      FROM fin_lib.lib_form_definition f
      JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
      JOIN fin_lib.lib_form_field ff ON ff.form_version_id=v.id
      LEFT JOIN fin_lib.lib_field_binding_contract c
        ON c.form_code=f.form_code AND c.field_code=ff.field_code
      LEFT JOIN fin_lib.lib_form_field_translation t
        ON t.form_version_id=v.id AND t.subject_type='FIELD'
           AND t.subject_code=ff.field_code AND t.locale=?
      WHERE f.form_code=?
      ORDER BY ff.display_order,ff.field_code
      """,(rs,i)->{
        String contractSha=rs.getString("contract_sha256");
        String sourceSchema=rs.getString("source_schema_sha256");
        String title=rs.getString("title");
        String status=rs.getString("review_status");
        String binding=contractSha==null?"MISSING_CONTRACT":
          !Objects.equals(sourceSchema,rs.getString("schema_sha256"))?
          "STALE_SCHEMA_REVIEW_REQUIRED":"MATCHED_UNQUALIFIED";
        if("PENDING_TRANSLATION".equals(status)||title==null||title.isBlank()
            ||title.equals(rs.getString("field_code")))title=null;
        if(title==null){
          var candidate=localizedCandidates.get(rs.getString("field_code"));
          if(candidate==null)throw new IllegalStateException("MISSING_FIELD_LABEL");
          title=candidate.title();status=candidate.reviewStatus();
        }
        return new FieldView(rs.getString("form_code"),
          (UUID)rs.getObject("current_version_id"),rs.getString("field_code"),
          rs.getString("metric_code"),rs.getString("semantic_role"),
          rs.getString("field_type"),rs.getString("input_mode"),
          rs.getBoolean("required"),rs.getString("unit_policy"),
          contractSha,binding,rs.getString("review_state"),l,
          title,status);
      },l,formCode);
    if(fields.size()>1000)throw new IllegalStateException("TOO_MANY_FIELDS_IN_ONE_FORM");
    return new FormView(current.formCode(),current.formVersionId(),
      current.schemaSha256(),current.captureMode(),current.qualificationState(),
      l,List.copyOf(fields));
  }
}
