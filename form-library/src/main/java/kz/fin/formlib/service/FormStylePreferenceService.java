package kz.fin.formlib.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * UI preference storage. Style is intentionally excluded from form revisions,
 * content SHA and financial qualification state.
 *
 * LOCAL DEVELOPMENT ONLY: userId is caller-provided until trusted IAM is wired.
 */
@Service
public class FormStylePreferenceService {
  private static final Set<String> STYLES=Set.of("CLASSIC","EXCEL","DASHBOARD");
  private final JdbcTemplate jdbc;

  public FormStylePreferenceService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

  public record StyleSelection(String formCode, UUID userId, String styleCode, String source) {}
  public record StyleUpdate(UUID userId, String styleCode) {}

  private static UUID requireUser(UUID userId) {
    if(userId==null) throw new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_ENTITY,"USER_ID_REQUIRED");
    return userId;
  }

  private static String validatedStyle(String code) {
    if(code==null||!STYLES.contains(code.trim().toUpperCase(Locale.ROOT)))
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_STYLE_CODE");
    return code.trim().toUpperCase(Locale.ROOT);
  }

  private void requireForm(String formCode) {
    if(formCode==null||formCode.isBlank())
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"FORM_CODE_REQUIRED");
    Integer count=jdbc.queryForObject(
      "SELECT COUNT(*) FROM fin_lib.lib_form_definition WHERE form_code=?", Integer.class, formCode);
    if(count==null||count!=1)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"FORM_NOT_FOUND");
  }

  private String defaultCode(UUID userId) {
    return jdbc.query(
      "SELECT style_code FROM fin_ui.lib_user_style_default WHERE user_id=?",
      rs->rs.next()?rs.getString(1):null,userId);
  }

  @Transactional(readOnly=true)
  public StyleSelection getDefault(UUID userId) {
    requireUser(userId);
    String style=defaultCode(userId);
    return new StyleSelection(null,userId,style==null?"CLASSIC":style,
        style==null?"SYSTEM_DEFAULT":"USER_DEFAULT");
  }

  @Transactional
  public StyleSelection setDefault(StyleUpdate request) {
    if(request==null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"REQUEST_REQUIRED");
    UUID user=requireUser(request.userId());
    String style=validatedStyle(request.styleCode());
    OffsetDateTime now=OffsetDateTime.now();
    int changed=jdbc.update("""
      UPDATE fin_ui.lib_user_style_default SET style_code=?,updated_at=? WHERE user_id=?
      """,style,now,user);
    if(changed==0) jdbc.update("""
      INSERT INTO fin_ui.lib_user_style_default(user_id,style_code,updated_at) VALUES (?,?,?)
      """,user,style,now);
    return new StyleSelection(null,user,style,"USER_DEFAULT");
  }

  @Transactional(readOnly=true)
  public StyleSelection getForForm(String formCode,UUID userId) {
    requireUser(userId);
    requireForm(formCode);
    String style=jdbc.query("""
      SELECT style_code FROM fin_ui.lib_user_form_style_preference
      WHERE user_id=? AND form_code=?
      """,rs->rs.next()?rs.getString(1):null,userId,formCode);
    if(style!=null) return new StyleSelection(formCode,userId,style,"USER_FORM");
    var fallback=getDefault(userId);
    return new StyleSelection(formCode,userId,fallback.styleCode(),fallback.source());
  }

  @Transactional
  public StyleSelection setForForm(String formCode,StyleUpdate request) {
    if(request==null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"REQUEST_REQUIRED");
    UUID user=requireUser(request.userId());
    requireForm(formCode);
    String style=validatedStyle(request.styleCode());
    OffsetDateTime now=OffsetDateTime.now();
    int changed=jdbc.update("""
      UPDATE fin_ui.lib_user_form_style_preference SET style_code=?,updated_at=?
      WHERE user_id=? AND form_code=?
      """,style,now,user,formCode);
    if(changed==0) jdbc.update("""
      INSERT INTO fin_ui.lib_user_form_style_preference
      (user_id,form_code,style_code,updated_at) VALUES (?,?,?,?)
      """,user,formCode,style,now);
    return new StyleSelection(formCode,user,style,"USER_FORM");
  }

  @Transactional
  public StyleSelection resetForForm(String formCode,UUID userId) {
    requireUser(userId);
    requireForm(formCode);
    jdbc.update("""
      DELETE FROM fin_ui.lib_user_form_style_preference WHERE user_id=? AND form_code=?
      """,userId,formCode);
    return getForForm(formCode,userId);
  }
}
