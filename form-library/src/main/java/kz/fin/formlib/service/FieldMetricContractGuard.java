package kz.fin.formlib.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Read-only semantic gate: contract hash, field types and roles must match the
 * EXACT pinned version. A presentation-only version clone is still compatible;
 * a structural edit must be reviewed and explicitly rebound before input.
 */
@Service
public class FieldMetricContractGuard {
  private final JdbcTemplate db;
  public FieldMetricContractGuard(JdbcTemplate db){this.db=db;}

  public boolean matches(String formCode,UUID versionId){
    if(formCode==null||formCode.isBlank()||versionId==null)return false;
    Integer total=db.queryForObject("""
      SELECT COUNT(*) FROM fin_lib.lib_form_field WHERE form_version_id=?
      """,Integer.class,versionId);
    if(total==null||total<=0)return false;
    Integer mismatched=db.queryForObject("""
      SELECT COUNT(*)
      FROM fin_lib.lib_form_field f
      JOIN fin_lib.lib_form_version v ON v.id=f.form_version_id
      LEFT JOIN fin_lib.lib_field_binding_contract c
        ON c.form_code=? AND c.field_code=f.field_code
      WHERE f.form_version_id=?
        AND (c.field_code IS NULL
             OR c.source_schema_sha256<>v.schema_sha256
             OR c.field_type<>f.field_type
             OR c.input_mode<>f.input_mode
             OR c.required<>f.required
             OR (c.semantic_role='SOURCE_FIELD' AND c.metric_code IS NULL)
             OR (c.semantic_role='EVIDENCE_METADATA' AND c.metric_code IS NOT NULL))
      """,Integer.class,formCode,versionId);
    return mismatched!=null&&mismatched==0;
  }
}
