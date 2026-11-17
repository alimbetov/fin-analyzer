package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(schema="fin_lib",name="lib_form_edit_session")
public class LibFormEditSession {
 @Id private UUID id;
 @Column(name="form_id",nullable=false) private UUID formId;
 @Column(name="base_version_id",nullable=false) private UUID baseVersionId;
 @Column(name="draft_version_id",nullable=false) private UUID draftVersionId;
 @Column(name="status",nullable=false) private String status;
 @Column(name="edit_revision",nullable=false) private Integer editRevision;
 @Version @Column(name="row_version",nullable=false) private Long rowVersion;
 @Column(name="created_by",nullable=false) private String createdBy;
 @Column(name="updated_by",nullable=false) private String updatedBy;
 @Column(name="created_at",nullable=false) private Instant createdAt;
 @Column(name="updated_at",nullable=false) private Instant updatedAt;
 @Column(name="published_at") private Instant publishedAt;
 public UUID getId(){return id;} public void setId(UUID x){id=x;}
 public UUID getFormId(){return formId;} public void setFormId(UUID x){formId=x;}
 public UUID getBaseVersionId(){return baseVersionId;} public void setBaseVersionId(UUID x){baseVersionId=x;}
 public UUID getDraftVersionId(){return draftVersionId;} public void setDraftVersionId(UUID x){draftVersionId=x;}
 public String getStatus(){return status;} public void setStatus(String x){status=x;}
 public Integer getEditRevision(){return editRevision;} public void setEditRevision(Integer x){editRevision=x;}
 public Long getRowVersion(){return rowVersion;}
 public String getCreatedBy(){return createdBy;} public void setCreatedBy(String x){createdBy=x;}
 public String getUpdatedBy(){return updatedBy;} public void setUpdatedBy(String x){updatedBy=x;}
 public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant x){createdAt=x;}
 public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant x){updatedAt=x;}
 public Instant getPublishedAt(){return publishedAt;} public void setPublishedAt(Instant x){publishedAt=x;}
}
