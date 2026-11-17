package kz.fin.formlib.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Fail-closed equality check for the exact pinned financial schema. Never
 * uses display-only translations or cached permission state as input evidence.
 */
@Service
public class FieldMetricContractGuard {
  private final JdbcTemplate db;
  public FieldMetricContractGuard(JdbcTemplate db){this.db=db;}

  public boolean matches(String formCode,UUID versionId){
    if(formCode==null||formCode.isBlank()||versionId==null)return false;
    Integer belongs=db.queryForObject("""
      SELECT COUNT(*) FROM fin_lib.lib_form_version v
      JOIN fin_lib.lib_form_definition f ON f.id=v.form_id
      WHERE f.form_code=? AND v.id=?
      """,Integer.class,formCode,versionId);
    if(belongs==null||belongs!=1)return false;
    Integer actual=db.queryForObject("""
      SELECT COUNT(*) FROM fin_lib.lib_form_field WHERE form_version_id=?
      """,Integer.class,versionId);
    Integer expected=db.queryForObject("""
      SELECT COUNT(*) FROM fin_lib.lib_field_binding_contract WHERE form_code=?
      """,Integer.class,formCode);
    if(actual==null||expected==null||actual<=0||!actual.equals(expected))
      return false;
    Integer bad=db.queryForObject("""
      SELECT COUNT(*)
      FROM fin_lib.lib_form_field field
      JOIN fin_lib.lib_form_version v ON v.id=field.form_version_id
      LEFT JOIN fin_lib.lib_field_binding_contract c
        ON c.form_code=? AND c.field_code=field.field_code
      LEFT JOIN fin_lib.lib_metric_definition m ON m.metric_code=c.metric_code
      WHERE field.form_version_id=?
        AND (c.field_code IS NULL
             OR c.source_schema_sha256<>v.schema_sha256
             OR c.field_type<>field.field_type
             OR c.input_mode<>field.input_mode
             OR c.required<>field.required
             OR (c.semantic_role='SOURCE_FIELD'
                 AND (c.metric_code IS NULL OR m.metric_code IS NULL
                      OR m.metric_kind<>'SOURCE_FIELD'
                      OR m.form_code<>?
                      OR m.source_field_code<>field.field_code
                      OR m.unit_policy<>c.unit_policy))
             OR (c.semantic_role='EVIDENCE_METADATA' AND c.metric_code IS NOT NULL))
      """,Integer.class,formCode,versionId,formCode);
    return bad!=null&&bad==0;
  }
}
