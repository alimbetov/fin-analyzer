package kz.fin.backend.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only projection of the latest CONFIRMED draft. An immutable PreviewSnapshot
 * never mutates when another user confirms a new form revision.
 */
@Service
public class CurrentFormV5QueryService {
    private static final TypeReference<List<FormSchemaV5.Input>> CELLS=new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public CurrentFormV5QueryService(JdbcTemplate jdbc,ObjectMapper json){
        this.jdbc=jdbc;this.json=json;
    }

    public record CurrentFormView(
        UUID projectId,String formCode,String periodCode,
        int latestRevision,UUID revisionId,
        String fingerprint,Instant uploadedAt,Instant savedAt,
        String uploadedBy,String savedBy,String changeReason,
        List<FormSchemaV5.Input> cells
    ) {
        public CurrentFormView {cells=List.copyOf(cells);}
    }
    public record PreviewFreshness(
        UUID previewId,UUID projectId,String periodCode,
        int expectedRevision,int currentRevision,boolean stale,
        UUID currentRevisionId,Instant currentSavedAt,String currentSavedBy
    ) {}

    private List<FormSchemaV5.Input> decode(String value) {
        try {return json.readValue(value,CELLS);}
        catch(JsonProcessingException e){
            throw new IllegalStateException("FIN_V5_STORED_JSON_INVALID",e);
        }
    }
    private static void validMonth(String code){
        ReportingPeriodV5.resolve(ReportingPeriodV5.Type.MONTH,code,
                ReportingPeriodV5.Coverage.PERIOD_ONLY);
    }
    private void requireProject(UUID organizationId,UUID projectId) {
        Objects.requireNonNull(organizationId);
        Objects.requireNonNull(projectId);
        Integer count=jdbc.queryForObject("""
          SELECT COUNT(*) FROM fin_intake.project_v5
          WHERE organization_id=? AND id=?
          """,Integer.class,organizationId,projectId);
        if(count==null||count!=1)throw new IllegalArgumentException("FIN_V5_PROJECT_NOT_FOUND");
    }

    /**
     * No snapshot ID: this is the moving, organization-scoped CURRENT pointer.
     * v0 is returned if project exists but its F02 monthly slot has no saved draft.
     */
    @Transactional(readOnly=true)
    public CurrentFormView current(UUID organizationId,UUID projectId,String periodCode) {
        validMonth(periodCode);
        requireProject(organizationId,projectId);
        var rows=jdbc.query("""
          SELECT s.latest_revision,d.id,d.normalized_fingerprint,d.uploaded_at,
                 d.saved_at,d.uploaded_by,d.saved_by,d.change_reason,d.cells::text
          FROM fin_intake.form_slot_v5 s
          LEFT JOIN fin_intake.draft_revision_v5 d
             ON d.slot_id=s.id AND d.revision_number=s.latest_revision
          WHERE s.organization_id=? AND s.project_id=?
             AND s.form_code='F02' AND s.reporting_basis='MANAGEMENT'
             AND s.period_type='MONTH' AND s.period_code=?
             AND s.value_coverage='PERIOD_ONLY'
          """,(rs,n)->new CurrentFormView(projectId,"F02",periodCode,
                rs.getInt(1),(UUID)rs.getObject(2),rs.getString(3),
                rs.getTimestamp(4)==null?null:rs.getTimestamp(4).toInstant(),
                rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant(),
                rs.getString(6),rs.getString(7),rs.getString(8),
                rs.getString(9)==null?List.of():decode(rs.getString(9))),
            organizationId,projectId,periodCode);
        if(rows.size()>1)throw new IllegalStateException("FIN_V5_FORM_SLOT_NOT_UNIQUE");
        return rows.isEmpty()
            ? new CurrentFormView(projectId,"F02",periodCode,0,null,null,null,null,
                                  null,null,null,List.of())
            : rows.getFirst();
    }

    /** Only the author of a private, unconfirmed preview can inspect freshness. */
    @Transactional(readOnly=true)
    public PreviewFreshness freshness(UUID organizationId,UUID projectId,
                                    UUID previewId,String requestingSubject) {
        requireProject(organizationId,projectId);
        if(previewId==null||requestingSubject==null||requestingSubject.isBlank())
            throw new IllegalArgumentException("FIN_V5_PREVIEW_NOT_FOUND");
        var rows=jdbc.query("""
          SELECT p.expected_revision,s.latest_revision,p.uploaded_by,s.period_code,
                 d.id,d.saved_at,d.saved_by
          FROM fin_intake.preview_v5 p
          JOIN fin_intake.form_slot_v5 s
            ON s.id=p.slot_id AND s.organization_id=p.organization_id
          LEFT JOIN fin_intake.draft_revision_v5 d
            ON d.slot_id=s.id AND d.revision_number=s.latest_revision
          WHERE p.id=? AND p.organization_id=? AND s.project_id=?
          """,(rs,n)->new Object[]{
              rs.getInt(1),rs.getInt(2),rs.getString(3),rs.getString(4),
              (UUID)rs.getObject(5),
              rs.getTimestamp(6)==null?null:rs.getTimestamp(6).toInstant(),
              rs.getString(7)
          },previewId,organizationId,projectId);
        if(rows.size()!=1||!requestingSubject.equals(rows.getFirst()[2]))
            throw new IllegalArgumentException("FIN_V5_PREVIEW_NOT_FOUND");
        Object[] row=rows.getFirst();
        int expected=(Integer)row[0],current=(Integer)row[1];
        return new PreviewFreshness(previewId,projectId,(String)row[3],expected,current,
                expected!=current,(UUID)row[4],(Instant)row[5],(String)row[6]);
    }
}
