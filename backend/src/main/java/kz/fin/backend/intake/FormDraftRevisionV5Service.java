package kz.fin.backend.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * FIN V5 R0/R1: tenant-bound, immutable draft revision ledger.
 * This service does NOT approve a source, publish a FactSet or run a diagnostic.
 * HTTP identity and permissions must be resolved by the existing FinanceAccessPolicy.
 */
@Service
public class FormDraftRevisionV5Service {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final TypeReference<List<FormSchemaV5.Input>> CELL_TYPE = new TypeReference<>() {};

    public FormDraftRevisionV5Service(JdbcTemplate jdbc,ObjectMapper json){
        this.jdbc=jdbc;this.json=json;
    }

    public record Project(UUID id,UUID organizationId,String projectCode,
                          String accountingCurrency,Instant createdAt) {}
    public record Delta(String fieldCode,String before,String after,String kind) {}
    public record Preview(UUID id,UUID projectId,String formCode,String periodCode,
                          int expectedRevision,String verdict,String fingerprint,
                          String confirmationDigest,Instant uploadedAt,String uploadedBy,
                          List<FormSchemaV5.Input> cells,List<Delta> changes) {
        public Preview {cells=List.copyOf(cells);changes=List.copyOf(changes);}
    }
    public record Confirmation(UUID previewId,UUID revisionId,int revisionNumber,
                               String result,Instant uploadedAt,Instant savedAt,
                               String fingerprint) {}
    public record DraftHistory(UUID revisionId,int revisionNumber,String formCode,
                               String periodCode,String sourceKind,String inputLocale,
                               String uploadedBy,Instant uploadedAt,String savedBy,
                               Instant savedAt,String reason,String fingerprint) {}
    public record UploadHistory(UUID previewId,Instant uploadedAt,String uploadedBy,
                                String sourceKind,String inputLocale,String fingerprint,
                                Integer resultingRevision) {}

    private record Context(UUID id,UUID org,String projectCode,String currency){}
    private record Slot(UUID id,UUID projectId,int currentRevision,String currency,int scale){}
    private record PreviewRow(UUID id,UUID slotId,UUID projectId,int expectedRevision,
                              String fingerprint,String digest,String cellsJson,
                              String uploadedBy,Instant uploadedAt,int currentRevision,
                              String periodCode,String sourceKind,String locale) {}
    private record RevisionRow(UUID id,int number,String fingerprint,String cellsJson,
                               Instant uploadedAt,Instant savedAt) {}

    private static String actor(String value) {
        if(value==null||value.isBlank()||value.length()>200)
            throw new IllegalArgumentException("FIN_V5_ACTOR_REQUIRED");
        return value;
    }
    private static String projectCode(String value) {
        if(value==null||value.isBlank()||value.length()>50 ||
            !value.codePoints().allMatch(c -> Character.isLetterOrDigit(c)||c=='_'||c=='-'))
            throw new IllegalArgumentException("FIN_V5_INVALID_PROJECT_CODE");
        return value;
    }
    private static String digest(String value) {
        try{
            byte[] bytes=MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        }catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private String encode(List<FormSchemaV5.Input> cells) {
        try{return json.writeValueAsString(cells);}
        catch(JsonProcessingException ex){throw new IllegalStateException("FIN_V5_JSON_ERROR",ex);}
    }
    private List<FormSchemaV5.Input> decode(String cellsJson) {
        try{return json.readValue(cellsJson,CELL_TYPE);}
        catch(JsonProcessingException ex){throw new IllegalStateException("FIN_V5_STORED_JSON_INVALID",ex);}
    }

    @Transactional
    public Project createProject(UUID organizationId,UUID economicEntityId,UUID businessScopeId,
                                 String code,String accountingCurrency,String createdBy){
        Objects.requireNonNull(organizationId);Objects.requireNonNull(economicEntityId);
        Objects.requireNonNull(businessScopeId);
        code=projectCode(code);actor(createdBy);
        if(accountingCurrency==null||!accountingCurrency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("FIN_V5_CURRENCY_INVALID");
        UUID id=UUID.randomUUID();
        String finalCode=code;
        Instant createdAt=jdbc.queryForObject("""
          INSERT INTO fin_intake.project_v5
            (id,organization_id,economic_entity_id,business_scope_id,project_code,accounting_currency,created_by)
          VALUES(?,?,?,?,?,?,?) RETURNING created_at
          """,(rs,row)->rs.getTimestamp(1).toInstant(),
          id,organizationId,economicEntityId,businessScopeId,finalCode,accountingCurrency,createdBy);
        return new Project(id,organizationId,code,accountingCurrency,createdAt);
    }

    private Context requireProject(UUID organizationId,UUID projectId) {
        Objects.requireNonNull(organizationId);Objects.requireNonNull(projectId);
        var found=jdbc.query("""
          SELECT id,organization_id,project_code,accounting_currency
            FROM fin_intake.project_v5 WHERE id=? AND organization_id=?
          """,(rs,n)->new Context((UUID)rs.getObject(1),(UUID)rs.getObject(2),
                rs.getString(3),rs.getString(4)),projectId,organizationId);
        if(found.size()!=1)throw new IllegalArgumentException("FIN_V5_PROJECT_NOT_FOUND");
        return found.getFirst();
    }

    private Slot requireSlot(UUID org,UUID projectId,String periodCode,int scale,
                             String currency){
        UUID newSlotId=UUID.randomUUID();
        jdbc.update("""
          INSERT INTO fin_intake.form_slot_v5
            (id,organization_id,project_id,form_code,reporting_basis,period_type,
             period_code,value_coverage,report_currency,amount_scale)
          VALUES(?,?,?,'F02','MANAGEMENT','MONTH',?,'PERIOD_ONLY',?,?)
          ON CONFLICT ON CONSTRAINT uq_fin_v5_slot_identity DO NOTHING
          """,newSlotId,org,projectId,periodCode,currency,scale);
        var slots=jdbc.query("""
          SELECT id,project_id,latest_revision,report_currency,amount_scale
            FROM fin_intake.form_slot_v5
           WHERE organization_id=? AND project_id=? AND form_code='F02'
             AND reporting_basis='MANAGEMENT' AND period_type='MONTH'
             AND period_code=? AND value_coverage='PERIOD_ONLY'
          """,(rs,n)->new Slot((UUID)rs.getObject(1),(UUID)rs.getObject(2),
                rs.getInt(3),rs.getString(4),rs.getInt(5)),org,projectId,periodCode);
        if(slots.size()!=1)throw new IllegalStateException("FIN_V5_SLOT_NOT_FOUND");
        Slot slot=slots.getFirst();
        if(!slot.currency().equals(currency) || slot.scale()!=scale)
            throw new IllegalArgumentException("FIN_V5_REPORT_MONEY_CONTEXT_MISMATCH");
        return slot;
    }

    private RevisionRow latest(UUID slotId,int number) {
        if(number==0)return null;
        var rows=jdbc.query("""
          SELECT id,revision_number,normalized_fingerprint,cells::text,uploaded_at,saved_at
          FROM fin_intake.draft_revision_v5 WHERE slot_id=? AND revision_number=?
          """,(rs,n)->new RevisionRow((UUID)rs.getObject(1),rs.getInt(2),
                 rs.getString(3),rs.getString(4),rs.getTimestamp(5).toInstant(),
                 rs.getTimestamp(6).toInstant()),slotId,number);
        if(rows.size()!=1)throw new IllegalStateException("FIN_V5_REVISION_CHAIN_BROKEN");
        return rows.getFirst();
    }

    private static List<Delta> compare(List<FormSchemaV5.Input> before,
                                       List<FormSchemaV5.Input> after) {
        Map<String,String> prior=new HashMap<>();
        for(var item:before)prior.put(item.fieldCode(),item.decimalText());
        List<Delta> changes=new ArrayList<>();
        for(var item:after) {
            String old=prior.get(item.fieldCode());
            String value=item.decimalText();
            String kind=old==null?"ADDED":old.equals(value)?"UNCHANGED":"CHANGED";
            changes.add(new Delta(item.fieldCode(),old,value,kind));
        }
        return List.copyOf(changes);
    }

    /** Validates a MANUAL F02 source using the same Java registry as React.
     *  An XLSX source is NOT accepted here; it requires the independent safe POI parser.
     */
    @Transactional
    public Preview previewManual(UUID organizationId,UUID projectId,String periodCode,
                                 String inputLocale,int amountScale,
                                 List<FormSchemaV5.Input> values,String uploadedBy) {
        actor(uploadedBy);FormSchemaV5.requireLocale(inputLocale);
        var period=ReportingPeriodV5.resolve(ReportingPeriodV5.Type.MONTH,periodCode,
                ReportingPeriodV5.Coverage.PERIOD_ONLY);
        Context project=requireProject(organizationId,projectId);
        if(!"KZT".equals(project.currency()))
            throw new IllegalArgumentException("FIN_V5_F02_KZT_ONLY");
        if(amountScale!=1 && amountScale!=1000 && amountScale!=1000000)
            throw new IllegalArgumentException("FIN_V5_AMOUNT_SCALE_INVALID");
        var normalized=FormSchemaV5.normalizeF02(values,inputLocale);
        if(!normalized.valid())
            throw new IllegalArgumentException("FIN_V5_VALIDATION_FAILED:"+String.join(",",normalized.errors()));
        Slot slot=requireSlot(organizationId,project.id(),period.code(),amountScale,project.currency());
        List<FormSchemaV5.Input> cells=normalized.cells();
        String canonical=String.join("|",projectId.toString(),"F02",FormSchemaV5.VERSION,
                "MANAGEMENT",period.type().name(),period.code(),period.coverage().name(),
                project.currency(),String.valueOf(amountScale));
        for(var cell:cells)canonical+="|"+cell.rowId()+"|"+cell.fieldCode()+"|"+cell.decimalText();
        String fingerprint=digest(canonical);
        RevisionRow prior=latest(slot.id(),slot.currentRevision());
        List<Delta> changes=compare(prior==null?List.of():decode(prior.cellsJson()),cells);
        String verdict=prior==null?"NEW":prior.fingerprint().equals(fingerprint)?"DUPLICATE":"CHANGED";
        UUID previewId=UUID.randomUUID();
        String confirmationDigest=digest(previewId+"|"+slot.id()+"|"+slot.currentRevision()+"|"+fingerprint);
        Instant uploadedAt=jdbc.queryForObject("""
          INSERT INTO fin_intake.preview_v5
            (id,organization_id,slot_id,expected_revision,source_kind,input_locale,
             form_version,uploaded_by,normalized_fingerprint,confirmation_digest,cells)
          VALUES(?,?,?,?,'MANUAL',?,'FIN-FORM-V2',?,?,?,?::jsonb)
          RETURNING uploaded_at
          """,(rs,n)->rs.getTimestamp(1).toInstant(),
          previewId,organizationId,slot.id(),slot.currentRevision(),inputLocale,uploadedBy,
          fingerprint,confirmationDigest,encode(cells));
        return new Preview(previewId,projectId,"F02",period.code(),slot.currentRevision(),
                verdict,fingerprint,confirmationDigest,uploadedAt,uploadedBy,cells,changes);
    }

    private PreviewRow lockedPreview(UUID org,UUID previewId){
        var rows=jdbc.query("""
          SELECT p.id,p.slot_id,s.project_id,p.expected_revision,p.normalized_fingerprint,
                 p.confirmation_digest,p.cells::text,p.uploaded_by,p.uploaded_at,
                 s.latest_revision,s.period_code,p.source_kind,p.input_locale
          FROM fin_intake.preview_v5 p
          JOIN fin_intake.form_slot_v5 s ON s.id=p.slot_id AND s.organization_id=p.organization_id
          WHERE p.id=? AND p.organization_id=?
          FOR UPDATE OF s
          """,(rs,n)->new PreviewRow((UUID)rs.getObject(1),(UUID)rs.getObject(2),
                 (UUID)rs.getObject(3),rs.getInt(4),rs.getString(5),rs.getString(6),
                 rs.getString(7),rs.getString(8),rs.getTimestamp(9).toInstant(),
                 rs.getInt(10),rs.getString(11),rs.getString(12),rs.getString(13)),
          previewId,org);
        if(rows.size()!=1)throw new IllegalArgumentException("FIN_V5_PREVIEW_NOT_FOUND");
        return rows.getFirst();
    }

    /**
     * Serialized by PostgreSQL row-level locking of the logical form slot.
     * Exact same preview confirmation is idempotent; same-current values yield
     * DUPLICATE without advancing the revision. A stale snapshot cannot overwrite.
     */
    @Transactional
    public Confirmation confirm(UUID organizationId,UUID previewId,String digest,
                                int expectedRevision,String confirmedBy,String changeReason){
        actor(confirmedBy);Objects.requireNonNull(organizationId);Objects.requireNonNull(previewId);
        if(digest==null||!digest.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("FIN_V5_CONFIRMATION_DIGEST_INVALID");
        PreviewRow p=lockedPreview(organizationId,previewId);
        if(!p.digest().equals(digest))
            throw new IllegalArgumentException("FIN_V5_CONFIRMATION_DIGEST_MISMATCH");
        if(!p.uploadedBy().equals(confirmedBy))
            throw new IllegalArgumentException("FIN_V5_PREVIEW_OWNER_MISMATCH");
        var already=jdbc.query("""
          SELECT id,revision_number,uploaded_at,saved_at,normalized_fingerprint
          FROM fin_intake.draft_revision_v5
          WHERE organization_id=? AND preview_id=?
          """,(rs,n)->new Confirmation(previewId,(UUID)rs.getObject(1),rs.getInt(2),
                  "ALREADY_APPLIED",rs.getTimestamp(3).toInstant(),
                  rs.getTimestamp(4).toInstant(),rs.getString(5)),
          organizationId,previewId);
        if(!already.isEmpty())return already.getFirst();
        if(expectedRevision!=p.expectedRevision() || p.currentRevision()!=p.expectedRevision())
            throw new IllegalArgumentException("FIN_V5_REVISION_CONFLICT");
        RevisionRow prior=latest(p.slotId(),p.currentRevision());
        if(prior!=null && prior.fingerprint().equals(p.fingerprint()))
            return new Confirmation(previewId,prior.id(),prior.number(),
                    "DUPLICATE",p.uploadedAt(),prior.savedAt(),p.fingerprint());
        String reason=changeReason==null?"":changeReason.trim();
        if(prior!=null && reason.isBlank())
            throw new IllegalArgumentException("FIN_V5_CHANGE_REASON_REQUIRED");
        if(reason.length()>1000)
            throw new IllegalArgumentException("FIN_V5_CHANGE_REASON_TOO_LONG");
        UUID revisionId=UUID.randomUUID();
        int newRevision=p.currentRevision()+1;
        Instant savedAt=jdbc.queryForObject("""
          INSERT INTO fin_intake.draft_revision_v5
            (id,organization_id,slot_id,revision_number,preview_id,parent_revision_id,
             normalized_fingerprint,cells,uploaded_by,uploaded_at,saved_by,change_reason)
          VALUES(?,?,?,?,?,?,?,?::jsonb,?,?,?,?) RETURNING saved_at
          """,(rs,n)->rs.getTimestamp(1).toInstant(),
          revisionId,organizationId,p.slotId(),newRevision,previewId,prior==null?null:prior.id(),
          p.fingerprint(),p.cellsJson(),p.uploadedBy(),Timestamp.from(p.uploadedAt()),
          confirmedBy,reason);
        int updated=jdbc.update("""
          UPDATE fin_intake.form_slot_v5 SET latest_revision=?,changed_at=now()
          WHERE id=? AND organization_id=? AND latest_revision=?
          """,newRevision,p.slotId(),organizationId,p.currentRevision());
        if(updated!=1)throw new IllegalStateException("FIN_V5_REVISION_CONFLICT");
        return new Confirmation(previewId,revisionId,newRevision,"CREATED",
                p.uploadedAt(),savedAt,p.fingerprint());
    }

    /** Newest-first, tenant-scoped history; all timestamps are stored in UTC. */
    @Transactional(readOnly=true)
    public List<DraftHistory> history(UUID organizationId,UUID projectId,String periodCode) {
        requireProject(organizationId,projectId);
        ReportingPeriodV5.resolve(ReportingPeriodV5.Type.MONTH,periodCode,
                ReportingPeriodV5.Coverage.PERIOD_ONLY);
        return jdbc.query("""
          SELECT d.id,d.revision_number,s.form_code,s.period_code,
                 p.source_kind,p.input_locale,d.uploaded_by,d.uploaded_at,d.saved_by,
                 d.saved_at,d.change_reason,d.normalized_fingerprint
          FROM fin_intake.form_slot_v5 s
          JOIN fin_intake.draft_revision_v5 d ON d.slot_id=s.id
          JOIN fin_intake.preview_v5 p ON p.id=d.preview_id
          WHERE s.organization_id=? AND s.project_id=?
            AND s.form_code='F02' AND s.period_type='MONTH' AND s.period_code=?
          ORDER BY d.revision_number DESC
          """,(rs,n)->new DraftHistory((UUID)rs.getObject(1),rs.getInt(2),
                 rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),
                 rs.getString(7),rs.getTimestamp(8).toInstant(),rs.getString(9),
                 rs.getTimestamp(10).toInstant(),rs.getString(11),rs.getString(12)),
          organizationId,projectId,periodCode);
    }

    /** Every validated submission is retained even when duplicate/no-change. */
    @Transactional(readOnly=true)
    public List<UploadHistory> uploads(UUID organizationId,UUID projectId,String periodCode) {
        requireProject(organizationId,projectId);
        ReportingPeriodV5.resolve(ReportingPeriodV5.Type.MONTH,periodCode,
                ReportingPeriodV5.Coverage.PERIOD_ONLY);
        return jdbc.query("""
          SELECT p.id,p.uploaded_at,p.uploaded_by,p.source_kind,p.input_locale,
                 p.normalized_fingerprint,d.revision_number
          FROM fin_intake.preview_v5 p
          JOIN fin_intake.form_slot_v5 s ON s.id=p.slot_id AND s.organization_id=p.organization_id
          LEFT JOIN fin_intake.draft_revision_v5 d ON d.preview_id=p.id
          WHERE s.organization_id=? AND s.project_id=? AND s.form_code='F02'
            AND s.period_type='MONTH' AND s.period_code=?
          ORDER BY p.uploaded_at DESC,p.id DESC
          """,(rs,n)->new UploadHistory((UUID)rs.getObject(1),rs.getTimestamp(2).toInstant(),
                 rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),
                 (Integer)rs.getObject(7)),
          organizationId,projectId,periodCode);
    }
}
