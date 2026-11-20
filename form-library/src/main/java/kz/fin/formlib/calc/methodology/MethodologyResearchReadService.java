package kz.fin.formlib.calc.methodology;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Backend bridge from immutable research evidence to the 154-form catalog.
 * No formula strings are executed; they are only displayed as editorial text.
 */
@Service
public class MethodologyResearchReadService {
  private static final Pattern FORM=Pattern.compile("[A-Z0-9-]{2,40}");
  public record Candidate(String candidateCode,String operationFamily,
      String expression,String reviewStatus,String blockers,String sourceSha256) {}
  public record Readiness(String formCode,String formVersionId,String schemaSha256,
      String contentMode,int authoredFormulaCount,int researchCandidateCount,
      String researchState,List<Candidate> candidates,
      boolean executable,boolean financiallyApproved) {
    public Readiness { candidates=List.copyOf(candidates); }
  }
  private record Header(String formCode,String versionId,String schema,
      String contentMode,int authoredCount,int researchCount,String state) {}
  private final JdbcTemplate jdbc;
  public MethodologyResearchReadService(JdbcTemplate jdbc) {
    this.jdbc=Objects.requireNonNull(jdbc);
  }

  @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
  public Readiness requireForm(String formCode) {
    if(formCode==null||!FORM.matcher(formCode).matches())
      throw new IllegalArgumentException("METHODOLOGY_FORM_CODE_INVALID");
    var headers=jdbc.query("""
      SELECT form_code,form_version_id,schema_sha256,content_mode,
        previously_authored_formula_count,source_research_candidate_count,
        research_state
      FROM fin_calc.calc154_methodology_research_readiness_v1
      WHERE form_code=?
      """,(r,i)->new Header(r.getString(1),r.getObject(2).toString(),
        r.getString(3).trim(),r.getString(4),r.getInt(5),r.getInt(6),
        r.getString(7)),formCode);
    if(headers.size()!=1)
      throw new IllegalStateException("METHODOLOGY_FORM_NOT_IN_154_CATALOG");
    var h=headers.getFirst();
    var candidates=jdbc.query("""
      SELECT candidate_code,operation_family,formula_text,review_status,
        blockers_text,uploaded_source_sha256
      FROM fin_calc.methodology_research_candidate
      WHERE owner_form_code=? ORDER BY candidate_code
      """,(r,i)->new Candidate(r.getString(1),r.getString(2),r.getString(3),
        r.getString(4),r.getString(5),r.getString(6).trim()),formCode);
    if(candidates.size()!=h.researchCount())
      throw new IllegalStateException("METHODOLOGY_RESEARCH_CANDIDATE_COUNT_DRIFT");
    return new Readiness(h.formCode(),h.versionId(),h.schema(),h.contentMode(),
      h.authoredCount(),h.researchCount(),h.state(),candidates,false,false);
  }
}
