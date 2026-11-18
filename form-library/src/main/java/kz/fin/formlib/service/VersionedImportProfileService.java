package kz.fin.formlib.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.domain.FormField;
import kz.fin.formlib.domain.FormVersion;
import kz.fin.formlib.repository.FormFieldRepository;
import kz.fin.formlib.repository.FormVersionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * R19 LOCAL-TEST-ONLY profile registry: exact form version + typed field
 * descriptor is the immutable server-side import contract. XLSX PARAMETERS
 * is untrusted and cannot override any server-side validation.
 *
 * No user-controlled mapping expressions, code execution, SQL or external
 * profile activation. Financial writes are delegated to FormDraftService.
 */
@Service
public class VersionedImportProfileService {
  public static final String PROFILE_CODE="FIN_XLSX_V2";
  private static final int PREVIEW_MINUTES=15;

  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final FormVersionRepository versions;
  private final FormFieldRepository fields;
  private final FormXlsxExchangeService exchange;

  public VersionedImportProfileService(JdbcTemplate db,ObjectMapper json,
      FormVersionRepository versions,FormFieldRepository fields,
      FormXlsxExchangeService exchange){
    this.db=db;this.json=json;this.versions=versions;
    this.fields=fields;this.exchange=exchange;
  }
  public record Profile(UUID id,UUID formVersionId,String profileCode,
                        int profileVersion,String fileFormat,String bindingSha256,
                        String lifecycleState) {}
  public record PreviewTicket(UUID previewId,UUID profileId,UUID instanceId,
                              UUID organizationId,int expectedRevision,
                              String uploadSha256,String bindingSha256,
                              OffsetDateTime expiresAt,int inputCellCount,
                              Map<String,String> values) {}

  private static ResponseStatusException invalid(String code){
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }
  private static ResponseStatusException conflict(String code){
    return new ResponseStatusException(HttpStatus.CONFLICT,code);
  }
  private static String digest(byte[] data){
    try{
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }catch(Exception e){throw new IllegalStateException(e);}
  }
  private static String digest(String value){
    return digest(value.getBytes(StandardCharsets.UTF_8));
  }
  private static void fileSize(byte[] bytes){
    if(bytes==null||bytes.length==0||bytes.length>FormXlsxCodec.MAX_BYTES)
      throw invalid("XLSX_FILE_SIZE_INVALID");
  }
  private FormVersion formVersion(UUID versionId){
    if(versionId==null)throw invalid("FORM_VERSION_REQUIRED");
    return versions.findById(versionId)
      .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,
        "FORM_VERSION_NOT_FOUND"));
  }

  private String descriptor(UUID versionId){
    FormVersion version=formVersion(versionId);
    var all=fields.findByFormVersionIdOrderByDisplayOrderAsc(versionId);
    if(all.isEmpty())throw invalid("IMPORT_PROFILE_EMPTY_FORM");
    var attributes=new ArrayList<List<String>>();
    for(FormField field:all){
      attributes.add(List.of(
        field.getFieldCode(),field.getFieldType(),field.getInputMode(),
        Boolean.toString(Boolean.TRUE.equals(field.getRequired())),
        Objects.toString(field.getUnitCode(),""),
        Objects.toString(field.getFormulaCode(),""),
        Integer.toString(field.getDisplayOrder())));
    }
    try{
      return json.writeValueAsString(List.of(
        "FIN-IMPORT-PROFILE-BINDINGS-V1",
        versionId.toString(),Objects.toString(version.getSchemaSha256(),""),
        Objects.toString(version.getMappingSha256(),""),attributes));
    }catch(JsonProcessingException e){throw new IllegalStateException(e);}
  }

  private Profile queryProfile(UUID id){
    if(id==null)throw invalid("PROFILE_ID_REQUIRED");
    return db.query("""
      SELECT id,form_version_id,profile_code,profile_version,file_format,
             binding_sha256,lifecycle_state
      FROM fin_lib.lib_import_profile_version WHERE id=?
      """,rs->rs.next()?new Profile(
         rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
         rs.getString(3),rs.getInt(4),rs.getString(5),rs.getString(6),
         rs.getString(7)):null,id);
  }

  @Transactional
  public Profile register(UUID versionId){
    // Serialize version allocation per form contract. Concurrent profile
    // registrations must not race on the (form_version, code, version) key.
    if(versionId==null)throw invalid("FORM_VERSION_REQUIRED");
    List<UUID> locked=db.query("""
      SELECT id FROM fin_lib.lib_form_version WHERE id=? FOR UPDATE
      """,(rs,n)->rs.getObject(1,UUID.class),versionId);
    if(locked.isEmpty())throw new ResponseStatusException(
      HttpStatus.NOT_FOUND,"FORM_VERSION_NOT_FOUND");
    String descriptor=descriptor(versionId);
    String sha=digest(descriptor);
    Profile latest=db.query("""
      SELECT id,form_version_id,profile_code,profile_version,file_format,
             binding_sha256,lifecycle_state
      FROM fin_lib.lib_import_profile_version
      WHERE form_version_id=? AND profile_code=?
      ORDER BY profile_version DESC
      """,rs->rs.next()?new Profile(rs.getObject(1,UUID.class),
        rs.getObject(2,UUID.class),rs.getString(3),rs.getInt(4),
        rs.getString(5),rs.getString(6),rs.getString(7)):null,
      versionId,PROFILE_CODE);
    if(latest!=null&&latest.bindingSha256().equals(sha))return latest;
    UUID id=UUID.randomUUID();
    int number=latest==null?1:latest.profileVersion()+1;
    db.update("""
      INSERT INTO fin_lib.lib_import_profile_version
        (id,form_version_id,profile_code,profile_version,file_format,
         binding_sha256,binding_json,lifecycle_state,created_at)
      VALUES (?,?,?,?,?,?,?,?,?)
      """,id,versionId,PROFILE_CODE,number,"XLSX",sha,descriptor,
          "LOCAL_TEST_ONLY",OffsetDateTime.now(ZoneOffset.UTC));
    return new Profile(id,versionId,PROFILE_CODE,number,
      "XLSX",sha,"LOCAL_TEST_ONLY");
  }

  @Transactional(readOnly=true)
  public Profile get(UUID profileId){
    var profile=queryProfile(profileId);
    if(profile==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,
      "IMPORT_PROFILE_NOT_FOUND");
    String current=digest(descriptor(profile.formVersionId()));
    String persistedDescriptor=db.queryForObject("""
      SELECT binding_json FROM fin_lib.lib_import_profile_version WHERE id=?
      """,String.class,profileId);
    if(!current.equals(profile.bindingSha256())||
       !digest(persistedDescriptor).equals(profile.bindingSha256()))
      throw conflict("IMPORT_PROFILE_SCHEMA_STALE");
    if(!PROFILE_CODE.equals(profile.profileCode())||
       !"LOCAL_TEST_ONLY".equals(profile.lifecycleState()))
      throw conflict("IMPORT_PROFILE_NOT_AVAILABLE");
    return profile;
  }

  @Transactional
  public PreviewTicket preview(UUID profileId,UUID instanceId,
      UUID organizationId,byte[] upload,String actor,String reason){
    fileSize(upload);
    var profile=get(profileId);
    var candidate=exchange.preview(instanceId,organizationId,upload,actor,reason);
    if(!profile.formVersionId().equals(candidate.formVersionId()))
      throw conflict("IMPORT_PROFILE_VERSION_MISMATCH");
    OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime expires=now.plusMinutes(PREVIEW_MINUTES);
    UUID ticket=UUID.randomUUID();
    String uploadSha=digest(upload);
    db.update("""
      INSERT INTO fin_ui.lib_import_preview_ticket
        (id,profile_id,instance_id,organization_id,expected_revision,
         original_content_sha256,upload_sha256,binding_sha256,
         created_at,expires_at)
      VALUES (?,?,?,?,?,?,?,?,?,?)
      """,ticket,profile.id(),instanceId,organizationId,
      candidate.expectedRevision(),candidate.originalContentSha256(),
      uploadSha,profile.bindingSha256(),now,expires);
    return new PreviewTicket(ticket,profileId,instanceId,organizationId,
      candidate.expectedRevision(),uploadSha,profile.bindingSha256(),
      expires,candidate.inputCellCount(),candidate.values());
  }

  private record StoredTicket(UUID id,UUID profileId,UUID instanceId,UUID org,
                              int revision,String originalSha,String fileSha,
                              String profileSha,OffsetDateTime expires) {}

  @Transactional
  public FormDraftService.DraftReceipt commit(UUID previewId,UUID instanceId,
      UUID organizationId,byte[] upload,String idempotencyKey,String actor,String reason){
    fileSize(upload);
    if(previewId==null)throw invalid("PREVIEW_ID_REQUIRED");
    StoredTicket ticket=db.query("""
      SELECT id,profile_id,instance_id,organization_id,expected_revision,
             original_content_sha256,upload_sha256,binding_sha256,expires_at
      FROM fin_ui.lib_import_preview_ticket WHERE id=?
      FOR UPDATE
      """,rs->rs.next()?new StoredTicket(
         rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
         rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),
         rs.getInt(5),rs.getString(6),rs.getString(7),
         rs.getString(8),rs.getObject(9,OffsetDateTime.class)):null,
      previewId);
    if(ticket==null)throw conflict("IMPORT_PREVIEW_UNKNOWN_OR_CONSUMED");
    if(!Objects.equals(ticket.instanceId(),instanceId)||
       !Objects.equals(ticket.org(),organizationId))
      throw conflict("IMPORT_PREVIEW_CONTEXT_MISMATCH");
    if(!ticket.expires().isAfter(OffsetDateTime.now(ZoneOffset.UTC)))
      throw conflict("IMPORT_PREVIEW_EXPIRED");
    if(!digest(upload).equals(ticket.fileSha()))
      throw conflict("IMPORT_PREVIEW_FILE_CHANGED");
    Profile profile=get(ticket.profileId());
    if(!profile.bindingSha256().equals(ticket.profileSha()))
      throw conflict("IMPORT_PREVIEW_PROFILE_CHANGED");
    var candidate=exchange.preview(instanceId,organizationId,upload,actor,reason);
    if(candidate.expectedRevision()!=ticket.revision()||
       !candidate.originalContentSha256().equals(ticket.originalSha())||
       !profile.formVersionId().equals(candidate.formVersionId()))
      throw conflict("IMPORT_PREVIEW_STALE");
    var saved=exchange.importFile(instanceId,organizationId,upload,
      idempotencyKey,actor,reason);
    db.update("DELETE FROM fin_ui.lib_import_preview_ticket WHERE id=?",previewId);
    return saved;
  }
}
